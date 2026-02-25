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
import com.dismal.btox.R
import com.dismal.btox.BootReceiver
import ltd.evilcorp.core.vo.PublicKey
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

enum class UiStyleMode {
    Classic,
    Material3,
}

class Settings @Inject constructor(private val ctx: Context) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    var theme: Int
        get() = preferences.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(theme) {
            preferences.edit { putInt("theme", theme) }
            AppCompatDelegate.setDefaultNightMode(theme)
        }

    var appColorIndex: Int
        get() = preferences.getInt("app_color_index", 0).coerceIn(0, APP_THEME_STYLES.lastIndex)
        set(value) = preferences.edit { putInt("app_color_index", value.coerceIn(0, APP_THEME_STYLES.lastIndex)) }

    var customAppColor: Int
        get() = preferences.getInt("custom_app_color", 0)
        set(value) = preferences.edit { putInt("custom_app_color", value) }

    fun appColorValue(): Int {
        val custom = customAppColor
        if (custom != 0) return custom
        return APP_THEME_COLORS[appColorIndex]
    }

    var uiStyleMode: UiStyleMode
        get() = UiStyleMode.entries.getOrElse(preferences.getInt("ui_style_mode", UiStyleMode.Classic.ordinal)) {
            UiStyleMode.Classic
        }
        set(mode) = preferences.edit { putInt("ui_style_mode", mode.ordinal) }

    fun availableAppColors(): IntArray = APP_THEME_COLORS

    fun setAppColorValue(color: Int) {
        var bestIndex = 0
        var bestDistance = Long.MAX_VALUE
        val targetR = (color shr 16) and 0xFF
        val targetG = (color shr 8) and 0xFF
        val targetB = color and 0xFF
        var exactMatch = false
        APP_THEME_COLORS.forEachIndexed { index, c ->
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val dr = (targetR - r).toLong()
            val dg = (targetG - g).toLong()
            val db = (targetB - b).toLong()
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDistance) {
                bestDistance = dist
                bestIndex = index
            }
            if (color == c) exactMatch = true
        }
        appColorIndex = bestIndex
        customAppColor = if (exactMatch) 0 else color
    }

    fun appThemeRes(): Int = APP_THEME_STYLES[appColorIndex]

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

    var outgoingMessageSoundsEnabled: Boolean
        get() = preferences.getBoolean("outgoing_message_sounds_enabled", true)
        set(enabled) = preferences.edit { putBoolean("outgoing_message_sounds_enabled", enabled) }

    var nfcFriendAddEnabled: Boolean
        get() = preferences.getBoolean("nfc_friend_add_enabled", false)
        set(enabled) = preferences.edit { putBoolean("nfc_friend_add_enabled", enabled) }

    var appLockMode: AppLockMode
        get() = AppLockMode.entries.getOrElse(preferences.getInt("app_lock_mode", AppLockMode.None.ordinal)) {
            AppLockMode.None
        }
        set(mode) = preferences.edit { putInt("app_lock_mode", mode.ordinal) }

    fun isContactMuted(publicKey: PublicKey): Boolean = mutedContacts.contains(publicKey.string())

    fun setContactMuted(publicKey: PublicKey, muted: Boolean) {
        val current = mutedContacts
        val updated = if (muted) {
            current + publicKey.string()
        } else {
            current - publicKey.string()
        }
        preferences.edit { putStringSet("muted_contacts", updated) }
    }

    private val mutedContacts: Set<String>
        get() = preferences.getStringSet("muted_contacts", emptySet())?.toSet() ?: emptySet()

    fun isContactBlocked(publicKey: PublicKey): Boolean = blockedContacts.contains(publicKey.string())

    fun setContactBlocked(publicKey: PublicKey, blocked: Boolean) {
        val current = blockedContacts
        val updated = if (blocked) {
            current + publicKey.string()
        } else {
            current - publicKey.string()
        }
        preferences.edit { putStringSet("blocked_contacts", updated) }
    }

    fun getBlockedContactKeys(): Set<String> = blockedContacts

    private val blockedContacts: Set<String>
        get() = preferences.getStringSet("blocked_contacts", emptySet())?.toSet() ?: emptySet()

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
        private val APP_THEME_COLORS = intArrayOf(
            0xFF689F38.toInt(), // Green
            0xFF1E88E5.toInt(), // Blue
            0xFF00897B.toInt(), // Teal
            0xFFEF6C00.toInt(), // Orange
            0xFFE53935.toInt(), // Red
            0xFFD81B60.toInt(), // Pink
            0xFF8E24AA.toInt(), // Purple
            0xFF3949AB.toInt(), // Indigo
            0xFF00ACC1.toInt(), // Cyan
            0xFF7CB342.toInt(), // Light Green
            0xFFFFB300.toInt(), // Amber
            0xFFF4511E.toInt(), // Deep Orange
            0xFF6D4C41.toInt(), // Brown
            0xFF546E7A.toInt(), // Blue Grey
            0xFF2E7D32.toInt(), // Forest
            0xFF0277BD.toInt(), // Ocean
            0xFF43A047.toInt(), // Green 2
            0xFF66BB6A.toInt(), // Green 3
            0xFF26A69A.toInt(), // Teal 2
            0xFF42A5F5.toInt(), // Blue 2
            0xFF5C6BC0.toInt(), // Indigo 2
            0xFF7E57C2.toInt(), // Deep Purple
            0xFFAB47BC.toInt(), // Purple 2
            0xFFEC407A.toInt(), // Pink 2
            0xFFEF5350.toInt(), // Red 2
            0xFFFF7043.toInt(), // Deep Orange 2
            0xFFFFA726.toInt(), // Orange 2
            0xFFFFCA28.toInt(), // Amber 2
            0xFF9CCC65.toInt(), // Light Green 2
            0xFF26C6DA.toInt(), // Cyan 2
            0xFF8D6E63.toInt(), // Brown 2
            0xFF78909C.toInt(), // Blue Grey 2
        )

        private val APP_THEME_STYLES = intArrayOf(
            R.style.AppTheme_C0,
            R.style.AppTheme_C1,
            R.style.AppTheme_C2,
            R.style.AppTheme_C3,
            R.style.AppTheme_C4,
            R.style.AppTheme_C5,
            R.style.AppTheme_C6,
            R.style.AppTheme_C7,
            R.style.AppTheme_C8,
            R.style.AppTheme_C9,
            R.style.AppTheme_C10,
            R.style.AppTheme_C11,
            R.style.AppTheme_C12,
            R.style.AppTheme_C13,
            R.style.AppTheme_C14,
            R.style.AppTheme_C15,
            R.style.AppTheme_C16,
            R.style.AppTheme_C17,
            R.style.AppTheme_C18,
            R.style.AppTheme_C19,
            R.style.AppTheme_C20,
            R.style.AppTheme_C21,
            R.style.AppTheme_C22,
            R.style.AppTheme_C23,
            R.style.AppTheme_C24,
            R.style.AppTheme_C25,
            R.style.AppTheme_C26,
            R.style.AppTheme_C27,
            R.style.AppTheme_C28,
            R.style.AppTheme_C29,
            R.style.AppTheme_C30,
            R.style.AppTheme_C31,
        )

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
