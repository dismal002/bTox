// SPDX-FileCopyrightText: 2019-2024 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.settings

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import java.lang.NumberFormatException
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dismal.btox.BuildConfig
import com.dismal.btox.R
import com.dismal.btox.databinding.FragmentSettingsBinding
import com.dismal.btox.settings.AppLockMode
import com.dismal.btox.settings.BootstrapNodeSource
import com.dismal.btox.settings.FtAutoAccept
import com.dismal.btox.ui.BaseFragment
import com.dismal.btox.ui.contactlist.ARG_CONTACT_LIST_MODE
import com.dismal.btox.ui.contactlist.CONTACT_LIST_MODE_BLOCKED
import com.dismal.btox.vmFactory
import ltd.evilcorp.domain.tox.ProxyType

private fun Spinner.onItemSelectedListener(callback: (Int) -> Unit) {
    this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {}
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            callback(position)
        }
    }
}

class SettingsFragment : BaseFragment<FragmentSettingsBinding>(FragmentSettingsBinding::inflate) {
    private companion object {
        const val ARG_SETTINGS_MODE = "settings_mode"
        const val SETTINGS_MODE_ADVANCED = "advanced"
    }

    private val vm: SettingsViewModel by viewModels { vmFactory }
    private val scope = CoroutineScope(Dispatchers.Default)
    private var suppressAppLockSelection = false
    private val blockBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            Toast.makeText(requireContext(), getString(R.string.warn_proxy_broken), Toast.LENGTH_LONG).show()
        }
    }

    private val applySettingsCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            vm.commit()
        }
    }

    private val importNodesLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        scope.launch {
            if (uri != null && vm.validateNodeJson(uri)) {
                if (vm.importNodeJson(uri)) {
                    vm.setBootstrapNodeSource(BootstrapNodeSource.UserProvided)
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    binding.settingBootstrapNodes.setSelection(BootstrapNodeSource.BuiltIn.ordinal)

                    Toast.makeText(
                        requireContext(),
                        getString(R.string.warn_node_json_import_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private val exportToxSaveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) {
            vm.saveToxBackupTo(uri)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        requireActivity().onBackPressedDispatcher.addCallback(this, applySettingsCallback)
        requireActivity().onBackPressedDispatcher.addCallback(this, blockBackCallback)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        val advancedMode = arguments?.getString(ARG_SETTINGS_MODE) == SETTINGS_MODE_ADVANCED

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, compat ->
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            val ime = compat.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(bars.bottom, max(gestures.bottom, ime.bottom))
            toolbar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            v.updatePadding(left = bars.left, right = bars.right, bottom = bottomInset)
            compat
        }

        toolbar.apply {
            setNavigationIcon(R.drawable.ic_back)
            inflateMenu(R.menu.settings_overflow_menu)
            title = getString(if (advancedMode) R.string.pref_heading_advanced else R.string.settings)
            setNavigationOnClickListener {
                WindowInsetsControllerCompat(requireActivity().window, view)
                    .hide(WindowInsetsCompat.Type.ime())
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_settings_info -> {
                        showAboutDialog()
                        true
                    }
                    else -> false
                }
            }
        }

        settingsGeneralGroup.isVisible = !advancedMode
        settingsNetworkGroup.isVisible = advancedMode
        settingsProxyGroup.isVisible = advancedMode
        settingsPasswordGroup.isVisible = advancedMode
        settingsAdvancedGroup.isVisible = advancedMode
        version.isVisible = !advancedMode

        if (advancedMode) {
            advancedToggleRow.isVisible = false
        } else {
            blockedConversationsRow.setOnClickListener {
                findNavController().navigate(
                    R.id.contactListFragment,
                    bundleOf(ARG_CONTACT_LIST_MODE to CONTACT_LIST_MODE_BLOCKED),
                    navOptions {
                        popUpTo(R.id.contactListFragment) { inclusive = false }
                        launchSingleTop = true
                    },
                )
            }
            advancedToggleRow.setOnClickListener {
                findNavController().navigate(R.id.action_settingsFragment_to_advancedSettingsFragment)
            }
        }

        theme.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.pref_theme_options,
            R.layout.settings_spinner_item,
        ).apply { setDropDownViewResource(R.layout.settings_spinner_dropdown_item) }
        theme.setSelection(vm.getTheme())
        theme.onItemSelectedListener {
            vm.setTheme(it)
        }

        fun updateAppPasswordUi() {
            val enabled = vm.getAppLockMode() == AppLockMode.AppPassword
            appPasswordRow.isVisible = enabled
            appPasswordDivider.isVisible = enabled
            settingAppPassword.text = getString(
                if (vm.hasAppPassword()) R.string.pref_change_app_password else R.string.pref_set_app_password,
            )
        }

        settingAppLock.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.pref_app_lock_options,
            R.layout.settings_spinner_item,
        ).apply { setDropDownViewResource(R.layout.settings_spinner_dropdown_item) }
        settingAppLock.setSelection(vm.getAppLockMode().ordinal)
        settingAppLock.onItemSelectedListener { position ->
            if (suppressAppLockSelection) return@onItemSelectedListener

            val selectedMode = AppLockMode.entries[position]
            if (selectedMode == AppLockMode.AppPassword && !vm.hasAppPassword()) {
                showSetAppPasswordDialog {
                    vm.setAppLockMode(AppLockMode.AppPassword)
                    suppressAppLockSelection = true
                    settingAppLock.setSelection(AppLockMode.AppPassword.ordinal)
                    suppressAppLockSelection = false
                    updateAppPasswordUi()
                }
                suppressAppLockSelection = true
                settingAppLock.setSelection(vm.getAppLockMode().ordinal)
                suppressAppLockSelection = false
                return@onItemSelectedListener
            }

            vm.setAppLockMode(selectedMode)
            updateAppPasswordUi()
        }
        updateAppPasswordUi()
        settingAppPassword.setOnClickListener {
            showSetAppPasswordDialog {
                if (vm.getAppLockMode() == AppLockMode.AppPassword) {
                    updateAppPasswordUi()
                }
            }
        }

        settingRunAtStartup.isChecked = vm.getRunAtStartup()
        settingRunAtStartup.setOnCheckedChangeListener { _, isChecked -> vm.setRunAtStartup(isChecked) }

        settingAutoAwayEnabled.isChecked = vm.getAutoAwayEnabled()
        settingAutoAwayEnabled.setOnCheckedChangeListener { _, isChecked -> vm.setAutoAwayEnabled(isChecked) }

        settingAutoAwaySeconds.setText(vm.getAutoAwaySeconds().toString())
        settingAutoAwaySeconds.doAfterTextChanged {
            val str = it?.toString() ?: ""
            val seconds = try {
                str.toLong()
            } catch (_: NumberFormatException) {
                settingAutoAwaySeconds.error = getString(R.string.bad_positive_number)
                return@doAfterTextChanged
            }

            vm.setAutoAwaySeconds(seconds)
        }

        settingFtAutoAccept.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.pref_ft_auto_accept_options,
            R.layout.settings_spinner_item,
        ).apply { setDropDownViewResource(R.layout.settings_spinner_dropdown_item) }

        settingFtAutoAccept.setSelection(vm.getFtAutoAccept().ordinal)

        settingFtAutoAccept.onItemSelectedListener {
            vm.setFtAutoAccept(FtAutoAccept.entries[it])
        }

        settingConfirmQuitting.isChecked = vm.getConfirmQuitting()
        settingConfirmQuitting.setOnCheckedChangeListener { _, isChecked -> vm.setConfirmQuitting(isChecked) }

        settingConfirmCalling.isChecked = vm.getConfirmCalling()
        settingConfirmCalling.setOnCheckedChangeListener { _, isChecked -> vm.setConfirmCalling(isChecked) }

        settingOutgoingMessageSounds.isChecked = vm.getOutgoingMessageSoundsEnabled()
        settingOutgoingMessageSounds.setOnCheckedChangeListener { _, isChecked ->
            vm.setOutgoingMessageSoundsEnabled(isChecked)
        }

        settingNfcFriendAdd.isChecked = vm.getNfcFriendAddEnabled()
        settingNfcFriendAdd.setOnCheckedChangeListener { _, isChecked ->
            vm.setNfcFriendAddEnabled(isChecked)
        }

        if (vm.getProxyType() != ProxyType.None) {
            vm.setUdpEnabled(false)
        }

        settingsUdpEnabled.isChecked = vm.getUdpEnabled()
        settingsUdpEnabled.isEnabled = vm.getProxyType() == ProxyType.None
        settingsUdpEnabled.setOnCheckedChangeListener { _, isChecked -> vm.setUdpEnabled(isChecked) }

        proxyType.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.pref_proxy_type_options,
            R.layout.settings_spinner_item,
        ).apply { setDropDownViewResource(R.layout.settings_spinner_dropdown_item) }

        proxyType.setSelection(vm.getProxyType().ordinal)

        proxyType.onItemSelectedListener {
            val selected = ProxyType.entries[it]
            vm.setProxyType(selected)

            // Disable UDP if a proxy is selected to ensure all traffic goes through the proxy.
            settingsUdpEnabled.isEnabled = selected == ProxyType.None
            settingsUdpEnabled.isChecked = settingsUdpEnabled.isChecked && selected == ProxyType.None
            vm.setUdpEnabled(settingsUdpEnabled.isChecked)
        }

        proxyAddress.setText(vm.getProxyAddress())
        proxyAddress.doAfterTextChanged { vm.setProxyAddress(it?.toString() ?: "") }

        proxyPort.setText(vm.getProxyPort().toString())
        proxyPort.doAfterTextChanged {
            val str = it?.toString() ?: ""
            val port = try {
                Integer.parseInt(str)
            } catch (_: NumberFormatException) {
                proxyPort.error = getString(R.string.bad_port)
                return@doAfterTextChanged
            }

            if (port < 1 || port > 65535) {
                proxyPort.error = getString(R.string.bad_port)
                return@doAfterTextChanged
            }

            vm.setProxyPort(port)
        }

        vm.proxyStatus.observe(viewLifecycleOwner) { status: ProxyStatus ->
            proxyStatus.text = when (status) {
                ProxyStatus.Good -> ""
                ProxyStatus.BadPort -> getString(R.string.bad_port)
                ProxyStatus.BadHost -> getString(R.string.bad_host)
                ProxyStatus.BadType -> getString(R.string.bad_type)
                ProxyStatus.NotFound -> getString(R.string.proxy_not_found)
            }
            blockBackCallback.isEnabled = proxyStatus.text.isNotEmpty()
        }
        vm.checkProxy()

        vm.committed.observe(viewLifecycleOwner) { committed ->
            if (committed) {
                findNavController().popBackStack()
            }
        }

        fun onPasswordEdit() {
            passwordCurrent.error = if (vm.isCurrentPassword(passwordCurrent.text.toString())) {
                null
            } else {
                getString(R.string.incorrect_password)
            }

            passwordNewConfirm.error = if (passwordNew.text.toString() == passwordNewConfirm.text.toString()) {
                null
            } else {
                getString(R.string.passwords_must_match)
            }

            passwordConfirm.isEnabled = passwordCurrent.error == null && passwordNewConfirm.error == null
        }
        onPasswordEdit()

        passwordCurrent.doAfterTextChanged { onPasswordEdit() }
        passwordNew.doAfterTextChanged { onPasswordEdit() }
        passwordNewConfirm.doAfterTextChanged { onPasswordEdit() }
        passwordConfirm.setOnClickListener {
            passwordConfirm.isEnabled = false
            vm.setPassword(passwordNewConfirm.text.toString())
            Toast.makeText(requireContext(), getString(R.string.password_updated), Toast.LENGTH_LONG).show()
        }

        if (vm.nospamAvailable()) {
            @Suppress("SetTextI18n") // This should be displayed the way Tox likes it.
            nospam.setText("%08X".format(vm.getNospam()))
            nospam.doAfterTextChanged {
                saveNospam.isEnabled =
                    nospam.text.length == 8 &&
                    nospam.text.toString().toUInt(16).toInt() != vm.getNospam()
            }
            saveNospam.isEnabled = false
            saveNospam.setOnClickListener {
                vm.setNospam(nospam.text.toString().toUInt(16).toInt())
                saveNospam.isEnabled = false
                Toast.makeText(requireContext(), R.string.saved, Toast.LENGTH_LONG).show()
            }
        } else {
            nospam.isEnabled = false
            saveNospam.isEnabled = false
            nospamExtraText.text = getString(R.string.pref_disabled_tox_error)
        }

        settingBootstrapNodes.adapter = ArrayAdapter.createFromResource(
            requireContext(),
            R.array.pref_bootstrap_node_options,
            R.layout.settings_spinner_item,
        ).apply { setDropDownViewResource(R.layout.settings_spinner_dropdown_item) }

        settingBootstrapNodes.setSelection(vm.getBootstrapNodeSource().ordinal)

        settingBootstrapNodes.onItemSelectedListener {
            val source = BootstrapNodeSource.entries[it]

            // Hack to avoid triggering the document chooser again if the user has set it to UserProvided.
            if (source == vm.getBootstrapNodeSource()) return@onItemSelectedListener

            if (source == BootstrapNodeSource.BuiltIn) {
                vm.setBootstrapNodeSource(source)
            } else {
                importNodesLauncher.launch(arrayOf("application/json"))
            }
        }

        settingDisableScreenshots.isChecked = vm.getDisableScreenshots()
        settingDisableScreenshots.setOnCheckedChangeListener { _, isChecked ->
            vm.setDisableScreenshots(isChecked)
            if (isChecked) {
                requireActivity().window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            } else {
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        exportToxSaveRow.setOnClickListener {
            exportToxSaveLauncher.launch("btox-profile.tox")
        }

        version.text = getString(R.string.version_display, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    private fun showSetAppPasswordDialog(onSuccess: () -> Unit) {
        val password = EditText(requireContext()).apply {
            hint = getString(R.string.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirm = EditText(requireContext()).apply {
            hint = getString(R.string.pref_confirm_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val spacing = (16 * resources.displayMetrics.density).toInt()
            setPadding(spacing, spacing, spacing, spacing)
            addView(password)
            addView(confirm)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (vm.hasAppPassword()) R.string.pref_change_app_password else R.string.pref_set_app_password)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pwd = password.text?.toString()?.trim().orEmpty()
                val confirmPwd = confirm.text?.toString()?.trim().orEmpty()
                if (pwd.isEmpty()) {
                    password.error = getString(R.string.password)
                    return@setOnClickListener
                }
                if (pwd != confirmPwd) {
                    confirm.error = getString(R.string.passwords_must_match)
                    return@setOnClickListener
                }

                vm.setAppPassword(pwd)
                Toast.makeText(requireContext(), R.string.password_updated, Toast.LENGTH_LONG).show()
                dialog.dismiss()
                onSuccess()
            }
        }
        dialog.show()
    }

    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        dialogView.findViewById<android.widget.TextView>(R.id.about_version).text =
            getString(R.string.version_display, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        dialogView.findViewById<android.widget.TextView>(R.id.about_link).movementMethod =
            LinkMovementMethod.getInstance()

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
