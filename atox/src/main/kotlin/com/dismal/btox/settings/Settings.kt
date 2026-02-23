// SPDX-FileCopyrightText: 2020-2025 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.dismal.btox.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import com.dismal.btox.BootReceiver
import ltd.evilcorp.domain.tox.ProxyType

enum class FtAutoAccept {
    None,
    Images,
    All,
}

enum class BootstrapNodeSource {
    BuiltIn,
    UserProvided,
}

enum class AppLockMode {
    None,
    ScreenLock,
    Biometric,
    AppPassword,
}

class Settings @Inject constructor(private val ctx: Context) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    var theme: Int
        get() = preferences.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(theme) {
            preferences.edit { putInt("theme", theme) }
            AppCompatDelegate.setDefaultNightMode(theme)
        }

    var udpEnabled: Boolean
        get() = preferences.getBoolean("udp_enabled", false)
        set(enabled) = preferences.edit { putBoolean("udp_enabled", enabled) }

    var runAtStartup: Boolean
        get() = ctx.packageManager.getComponentEnabledSetting(
            ComponentName(ctx, BootReceiver::class.java),
        ) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        set(runAtStartup) {
            val state = if (runAtStartup) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, BootReceiver::class.java),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }

    var autoAwayEnabled: Boolean
        get() = preferences.getBoolean("auto_away_enabled", false)
        set(enabled) = preferences.edit { putBoolean("auto_away_enabled", enabled) }

    var autoAwaySeconds: Long
        get() = preferences.getLong("auto_away_seconds", 180)
        set(seconds) = preferences.edit { putLong("auto_away_seconds", seconds) }

    var proxyType: ProxyType
        get() = ProxyType.entries[preferences.getInt("proxy_type", 0)]
        set(type) = preferences.edit { putInt("proxy_type", type.ordinal) }

    var proxyAddress: String
        get() = preferences.getString("proxy_address", null) ?: ""
        set(address) = preferences.edit { putString("proxy_address", address) }

    var proxyPort: Int
        get() = preferences.getInt("proxy_port", 0)
        set(port) = preferences.edit { putInt("proxy_port", port) }

    var ftAutoAccept: FtAutoAccept
        get() = FtAutoAccept.entries[preferences.getInt("ft_auto_accept", 0)]
        set(autoAccept) = preferences.edit { putInt("ft_auto_accept", autoAccept.ordinal) }

    var bootstrapNodeSource: BootstrapNodeSource
        get() = BootstrapNodeSource.entries[preferences.getInt("bootstrap_node_source", 0)]
        set(source) = preferences.edit { putInt("bootstrap_node_source", source.ordinal) }

    var disableScreenshots: Boolean
        get() = preferences.getBoolean("disable_screenshots", false)
        set(disable) = preferences.edit { putBoolean("disable_screenshots", disable) }

    var confirmQuitting: Boolean
        get() = preferences.getBoolean("confirm_quitting", true)
        set(confirm) = preferences.edit { putBoolean("confirm_quitting", confirm) }

    var confirmCalling: Boolean
        get() = preferences.getBoolean("confirm_calling", true)
        set(confirm) = preferences.edit { putBoolean("confirm_calling", confirm) }

    var appLockMode: AppLockMode
        get() = AppLockMode.entries.getOrElse(preferences.getInt("app_lock_mode", AppLockMode.None.ordinal)) {
            AppLockMode.None
        }
        set(mode) = preferences.edit { putInt("app_lock_mode", mode.ordinal) }

    fun hasAppPassword(): Boolean =
        preferences.contains("app_password_hash") && preferences.contains("app_password_salt")

    fun setAppPassword(password: String) {
        val salt = ByteArray(PASSWORD_SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val hash = hashPassword(password, salt)
        preferences.edit {
            putString("app_password_salt", encode(salt))
            putString("app_password_hash", encode(hash))
        }
    }

    fun verifyAppPassword(password: String): Boolean {
        val encodedSalt = preferences.getString("app_password_salt", null) ?: return false
        val encodedHash = preferences.getString("app_password_hash", null) ?: return false
        val salt = decode(encodedSalt) ?: return false
        val expectedHash = decode(encodedHash) ?: return false
        val actualHash = hashPassword(password, salt)
        return MessageDigest.isEqual(expectedHash, actualHash)
    }

    private fun hashPassword(password: String, salt: ByteArray): ByteArray {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH_BITS)
        val algorithm = if (hasPbkdf2Sha256) {
            PBKDF2_SHA256
        } else {
            PBKDF2_SHA1
        }

        return SecretKeyFactory.getInstance(algorithm).generateSecret(keySpec).encoded
    }

    private fun encode(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
    private fun decode(data: String): ByteArray? = try {
        Base64.decode(data, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        private const val PASSWORD_ITERATIONS = 120_000
        private const val PASSWORD_KEY_LENGTH_BITS = 256
        private const val PASSWORD_SALT_SIZE_BYTES = 16
        private const val PBKDF2_SHA256 = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_SHA1 = "PBKDF2WithHmacSHA1"
        private val secureRandom = SecureRandom()
        private val hasPbkdf2Sha256: Boolean = try {
            SecretKeyFactory.getInstance(PBKDF2_SHA256)
            true
        } catch (_: Exception) {
            false
        }
    }
}
