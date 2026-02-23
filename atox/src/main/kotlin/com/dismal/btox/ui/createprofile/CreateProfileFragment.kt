// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.ui.createprofile

import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import com.dismal.btox.R
import com.dismal.btox.databinding.FragmentProfileBinding
import com.dismal.btox.ui.BaseFragment
import com.dismal.btox.vmFactory
import ltd.evilcorp.core.vo.User
import ltd.evilcorp.domain.tox.ToxSaveStatus

class CreateProfileFragment : BaseFragment<FragmentProfileBinding>(FragmentProfileBinding::inflate) {
    private val viewModel: CreateProfileViewModel by viewModels { vmFactory }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult

        Log.i("ProfileFragment", "Importing file $uri")
        viewModel.tryImportToxSave(uri)?.also { save ->
            when (val startStatus = viewModel.startTox(save)) {
                ToxSaveStatus.Ok -> {
                    viewModel.verifyUserExists(viewModel.publicKey)
                    findNavController().popBackStack()
                }
                ToxSaveStatus.Encrypted -> {
                    val passwordEdit = EditText(requireContext()).apply {
                        hint = getString(R.string.password)
                        inputType = EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                        setSingleLine()
                        transformationMethod = PasswordTransformationMethod()
                    }
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.unlock_profile)
                        .setView(passwordEdit)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val password = passwordEdit.text.toString()
                            if (viewModel.startTox(save, password) == ToxSaveStatus.Ok) {
                                viewModel.verifyUserExists(viewModel.publicKey)
                                findNavController().popBackStack()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.incorrect_password),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                else -> Toast.makeText(
                    requireContext(),
                    resources.getString(R.string.import_tox_save_failed, startStatus.name),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, compat ->
            val bars = compat.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = compat.getInsets(WindowInsetsCompat.Type.systemGestures())
            val ime = compat.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(bars.bottom, max(gestures.bottom, ime.bottom))
            content.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bottomInset)

            // Keep onboarding actions visible when the keyboard is shown even with edge-to-edge.
            val imeOffset = ime.bottom.toFloat()
            onboardingActions.translationY = -imeOffset
            bottomDivider.translationY = -imeOffset
            compat
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (!findNavController().popBackStack()) {
                activity?.finish()
            }
        }

        btnCreate.setOnClickListener {
            btnCreate.isEnabled = false

            viewModel.startTox()
            val user = User(
                publicKey = viewModel.publicKey.string(),
                name = if (username.text.isNotEmpty()) username.text.toString() else getString(R.string.name_default),
                statusMessage = getString(R.string.status_message_default),
            )

            runBlocking {
                viewModel.create(user).join()
            }

            findNavController().popBackStack()
        }

        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
    }
}
