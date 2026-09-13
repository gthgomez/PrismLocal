package com.prismai.llmhost.cloud.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.nio.charset.StandardCharsets

interface TokenStorage {
    fun saveSession(session: SupabaseAuthSession)
    fun loadSession(): SupabaseAuthSession?
    fun clearSession()
}

class InMemoryTokenStorage : TokenStorage {
    @Volatile
    private var session: SupabaseAuthSession? = null

    override fun saveSession(session: SupabaseAuthSession) {
        this.session = session
    }

    override fun loadSession(): SupabaseAuthSession? = session

    override fun clearSession() {
        this.session = null
    }
}

/**
 * Android Keystore AES-256 GCM backed encrypted token storage.
 *
 * Invariant P1:
 * - `access_token`, `refresh_token`, and user metadata are NEVER stored in plaintext.
 * - Ciphertext + random 12-byte IV are stored in private SharedPreferences.
 * - Corrupted ciphertext or bad authentication tags fail closed and clear the stored session.
 * - Logout completely purges the ciphertext and IV from storage.
 */
class EncryptedTokenStorage(
    context: Context,
    private val crypto: KeystoreCrypto = KeystoreCrypto(AndroidKeystoreKeyProvider()),
    prefsName: String = PREFS_NAME,
) : TokenStorage {

    companion object {
        const val PREFS_NAME = "prism_auth_secure_prefs"
        private const val KEY_IV_B64 = "enc_iv_b64"
        private const val KEY_CIPHERTEXT_B64 = "enc_payload_b64"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun saveSession(session: SupabaseAuthSession) {
        val plaintextJson = session.toJsonString()
        val plaintextBytes = plaintextJson.toByteArray(StandardCharsets.UTF_8)
        val encrypted = crypto.encrypt(plaintextBytes)

        val ivB64 = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP)
        val ciphertextB64 = Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP)

        prefs.edit()
            .putString(KEY_IV_B64, ivB64)
            .putString(KEY_CIPHERTEXT_B64, ciphertextB64)
            .commit()
    }

    override fun loadSession(): SupabaseAuthSession? {
        val ivB64 = prefs.getString(KEY_IV_B64, null) ?: return null
        val ciphertextB64 = prefs.getString(KEY_CIPHERTEXT_B64, null) ?: return null

        try {
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP)
            val decryptedBytes = crypto.decrypt(iv, ciphertext)
            val jsonString = String(decryptedBytes, StandardCharsets.UTF_8)
            return SupabaseAuthSession.fromJsonString(jsonString)
        } catch (_: Exception) {
            // Decryption failure (corrupted ciphertext, invalid key, tampered tag):
            // Fail closed and purge unreadable session
            clearSession()
            return null
        }
    }

    override fun clearSession() {
        prefs.edit()
            .remove(KEY_IV_B64)
            .remove(KEY_CIPHERTEXT_B64)
            .commit()
    }
}
