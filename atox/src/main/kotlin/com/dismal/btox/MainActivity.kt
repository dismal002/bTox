// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox

import android.content.Intent
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.navigation.fragment.findNavController
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.Fragment
import androidx.appcompat.widget.Toolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import javax.inject.Inject
import com.dismal.btox.di.ViewModelFactory
import com.dismal.btox.settings.AppLockMode
import com.dismal.btox.settings.AppColorResolver
import com.dismal.btox.settings.Settings
import com.dismal.btox.ui.contactlist.ARG_ADD_CONTACT
import com.dismal.btox.ui.contactlist.ARG_SHARE

private const val TAG = "MainActivity"
private const val SCHEME = "tox:"
private const val TOX_ID_LENGTH = 76

class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var vmFactory: ViewModelFactory

    @Inject
    lateinit var autoAway: AutoAway

    @Inject
    lateinit var settings: Settings

    private var appUnlocked = false
    private var isAuthenticating = false
    private lateinit var lockCoverView: View

    private val screenLockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            isAuthenticating = false
            if (result.resultCode == RESULT_OK) {
                onUnlockSucceeded()
                return@registerForActivityResult
            }

            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as App).component.inject(this)

        AppCompatDelegate.setDefaultNightMode(settings.theme)
        setTheme(settings.appThemeRes())

        super.onCreate(savedInstanceState)

        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                super.onFragmentViewCreated(fm, f, v, savedInstanceState)
                v.findViewById<Toolbar>(R.id.toolbar)?.let {
                    AppColorResolver.applyToToolbar(it)
                }
                // Handle FABs if found (both classic and M3)
                v.findViewById<View>(R.id.startNewConversationButton)?.let {
                    AppColorResolver.applyToFab(it)
                }
                v.findViewById<View>(R.id.startNewConversationButtonM3)?.let {
                    AppColorResolver.applyToFab(it)
                }
            }
        }, true)

        window.statusBarColor = AppColorResolver.primaryDark(this, R.color.colorPrimaryDark)

        if (settings.disableScreenshots) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)
        setupLockCover()

        // Only handle intent the first time it triggers the app.
        if (savedInstanceState != null) return
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        autoAway.onBackground()
    }

    override fun onStart() {
        super.onStart()
        ensureUnlocked()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            appUnlocked = false
            if (settings.appLockMode != AppLockMode.None) {
                showLockCover()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        autoAway.onForeground()
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> handleToxLinkIntent(intent)
            Intent.ACTION_SEND -> handleShareIntent(intent)
        }
    }

    private fun handleToxLinkIntent(intent: Intent) {
        val data = intent.dataString ?: ""
        Log.i(TAG, "Got uri with data: $data")
        if (!data.startsWith(SCHEME) || data.length != SCHEME.length + TOX_ID_LENGTH) {
            Log.e(TAG, "Got malformed uri: $data")
            return
        }

        supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController()?.navigate(
            R.id.contactListFragment,
            bundleOf(ARG_ADD_CONTACT to data.drop(SCHEME.length)),
        )
    }

    private fun handleShareIntent(intent: Intent) {
        if (intent.type != "text/plain") {
            Log.e(TAG, "Got unsupported share type ${intent.type}")
            return
        }

        val data = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (data.isNullOrEmpty()) {
            Log.e(TAG, "Got share intent with no data")
            return
        }

        Log.i(TAG, "Got text share: $data")
        val navController =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.findNavController() ?: return
        navController.navigate(R.id.contactListFragment, bundleOf(ARG_SHARE to data))
    }

    private fun ensureUnlocked() {
        if (settings.appLockMode != AppLockMode.None) {
            showLockCover()
        } else {
            hideLockCover()
        }

        if (appUnlocked || isAuthenticating) {
            if (appUnlocked) {
                hideLockCover()
            }
            return
        }

        when (settings.appLockMode) {
            AppLockMode.None -> {
                appUnlocked = true
                hideLockCover()
            }
            AppLockMode.ScreenLock -> promptScreenLock()
            AppLockMode.Biometric -> promptBiometricOrScreenLock()
            AppLockMode.AppPassword -> promptAppPassword()
        }
    }

    private fun promptScreenLock() {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard == null || !keyguard.isDeviceSecure) {
            Toast.makeText(this, R.string.screenlock_not_available, Toast.LENGTH_LONG).show()
            onUnlockSucceeded()
            return
        }

        val intent = keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.unlock_app),
            getString(R.string.unlock_with_screenlock),
        )
        if (intent == null) {
            Toast.makeText(this, R.string.screenlock_not_available, Toast.LENGTH_LONG).show()
            onUnlockSucceeded()
            return
        }

        isAuthenticating = true
        screenLockLauncher.launch(intent)
    }

    private fun promptBiometricOrScreenLock() {
        val biometricManager = BiometricManager.from(this)
        if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            promptScreenLock()
            return
        }

        isAuthenticating = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlockSucceeded()
                    isAuthenticating = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    isAuthenticating = false
                    finish()
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_app))
            .setSubtitle(getString(R.string.unlock_with_biometric))
            .setNegativeButtonText(getString(android.R.string.cancel))
            .build()

        prompt.authenticate(info)
    }

    private fun promptAppPassword() {
        if (!settings.hasAppPassword()) {
            Toast.makeText(this, R.string.app_password_not_set, Toast.LENGTH_LONG).show()
            onUnlockSucceeded()
            return
        }

        isAuthenticating = true
        val passwordInput = EditText(this).apply {
            hint = getString(R.string.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.unlock_app)
            .setMessage(R.string.unlock_with_app_password)
            .setView(passwordInput)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                isAuthenticating = false
                finish()
            }
            .setPositiveButton(R.string.unlock_profile, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordInput.text?.toString().orEmpty()
                if (!settings.verifyAppPassword(password)) {
                    passwordInput.error = getString(R.string.incorrect_password)
                    return@setOnClickListener
                }

                onUnlockSucceeded()
                isAuthenticating = false
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun onUnlockSucceeded() {
        appUnlocked = true
        hideLockCover()
    }

    private fun setupLockCover() {
        lockCoverView = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.mg_surface))
            isClickable = true
            isFocusable = true
            visibility = if (settings.appLockMode == AppLockMode.None) View.GONE else View.VISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        findViewById<ViewGroup>(android.R.id.content).addView(
            lockCoverView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun showLockCover() {
        if (!::lockCoverView.isInitialized) return
        lockCoverView.visibility = View.VISIBLE
        lockCoverView.bringToFront()
    }

    private fun hideLockCover() {
        if (!::lockCoverView.isInitialized) return
        lockCoverView.visibility = View.GONE
    }
}
