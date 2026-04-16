package com.whatsappguard

import android.content.Context
import androidx.preference.PreferenceManager
import java.security.MessageDigest
import java.security.SecureRandom

object PasswordManager {

    private const val PREF_PASSWORD_HASH = "password_hash"
    private const val PREF_PASSWORD_SALT = "password_salt"

    private fun getPrefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun isPasswordSet(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getString(PREF_PASSWORD_HASH, null) != null
    }

    fun createPassword(context: Context, password: String) {
        val salt = generateSalt()
        val hash = hashPassword(password, salt)
        getPrefs(context).edit()
            .putString(PREF_PASSWORD_SALT, salt.toHex())
            .putString(PREF_PASSWORD_HASH, hash)
            .apply()
    }

    fun verifyPassword(context: Context, password: String): Boolean {
        val prefs = getPrefs(context)
        val storedHash = prefs.getString(PREF_PASSWORD_HASH, null) ?: return false
        val saltHex = prefs.getString(PREF_PASSWORD_SALT, null) ?: return false
        val salt = saltHex.hexToBytes()
        val inputHash = hashPassword(password, salt)
        return storedHash == inputHash
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun hashPassword(password: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hash.toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
