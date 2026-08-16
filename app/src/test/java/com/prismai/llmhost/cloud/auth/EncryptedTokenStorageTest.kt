package com.prismai.llmhost.cloud.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class EncryptedTokenStorageTest {

    private val session = SupabaseAuthSession(
        accessToken = "secret-access-jwt-token-12345",
        refreshToken = "secret-refresh-token-67890",
        expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
        userId = "user-uuid-1111",
        email = "user@example.com",
    )

    @Test
    fun keystoreCryptoEncryptsAndDecryptsRoundTrip() {
        val keyProvider = JvmSecretKeyProvider()
        val crypto = KeystoreCrypto(keyProvider)

        val plaintext = "sensitive token payload".toByteArray(StandardCharsets.UTF_8)
        val encrypted = crypto.encrypt(plaintext)

        assertEquals(12, encrypted.iv.size)
        assertTrue(encrypted.ciphertext.isNotEmpty())
        assertFalse(String(encrypted.ciphertext, StandardCharsets.ISO_8859_1).contains("sensitive token payload"))

        val decrypted = crypto.decrypt(encrypted.iv, encrypted.ciphertext)
        assertEquals("sensitive token payload", String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun ciphertextTamperingFailsClosed() {
        val keyProvider = JvmSecretKeyProvider()
        val crypto = KeystoreCrypto(keyProvider)

        val plaintext = session.toJsonString().toByteArray(StandardCharsets.UTF_8)
        val encrypted = crypto.encrypt(plaintext)

        // Tamper with one byte in the ciphertext
        val tamperedCiphertext = encrypted.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0xFF).toByte()

        var caughtException = false
        try {
            crypto.decrypt(encrypted.iv, tamperedCiphertext)
        } catch (_: Exception) {
            caughtException = true
        }

        assertTrue("Tampered ciphertext MUST throw an authentication/decryption exception", caughtException)
    }

    @Test
    fun truncatedIvFailsClosed() {
        val keyProvider = JvmSecretKeyProvider()
        val crypto = KeystoreCrypto(keyProvider)

        val plaintext = "test".toByteArray(StandardCharsets.UTF_8)
        val encrypted = crypto.encrypt(plaintext)

        // Truncate IV to 8 bytes instead of 12
        val truncatedIv = encrypted.iv.copyOfRange(0, 8)

        var caughtException = false
        try {
            crypto.decrypt(truncatedIv, encrypted.ciphertext)
        } catch (_: Exception) {
            caughtException = true
        }

        assertTrue("Truncated IV MUST throw an exception", caughtException)
    }

    @Test
    fun keyRotationOrDeletionFailsClosed() {
        val keyProvider = JvmSecretKeyProvider()
        val crypto = KeystoreCrypto(keyProvider)

        val plaintext = "test token".toByteArray(StandardCharsets.UTF_8)
        val encrypted = crypto.encrypt(plaintext)

        // Delete key and recreate a new one (simulating key invalidation)
        keyProvider.deleteKey()
        val newCrypto = KeystoreCrypto(keyProvider)

        var caughtException = false
        try {
            newCrypto.decrypt(encrypted.iv, encrypted.ciphertext)
        } catch (_: Exception) {
            caughtException = true
        }

        assertTrue("Decryption with different key MUST fail closed", caughtException)
    }

    @Test
    fun securityLevelInspectionReportsAccurately() {
        val keyProvider = JvmSecretKeyProvider()
        val crypto = KeystoreCrypto(keyProvider)
        assertEquals(KeystoreSecurityLevel.SOFTWARE, crypto.getSecurityLevel())
    }
}
