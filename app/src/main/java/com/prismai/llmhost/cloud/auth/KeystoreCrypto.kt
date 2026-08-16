package com.prismai.llmhost.cloud.auth

import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedPayload(
    val iv: ByteArray,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedPayload
        return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = iv.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}

enum class KeystoreSecurityLevel {
    STRONGBOX,
    TRUSTED_ENVIRONMENT,
    UNKNOWN_SECURE,
    SOFTWARE,
    UNKNOWN,
}

interface KeyProvider {
    fun getOrCreateSecretKey(): SecretKey
    fun deleteKey()
    fun getSecurityLevel(): KeystoreSecurityLevel = KeystoreSecurityLevel.UNKNOWN
}

/**
 * Standard JVM / test-compatible software key provider for deterministic unit testing.
 */
class JvmSecretKeyProvider(
    private val keyAlgorithm: String = "AES",
    private val keySize: Int = 256,
) : KeyProvider {
    @Volatile
    private var secretKey: SecretKey? = null

    override fun getOrCreateSecretKey(): SecretKey {
        return secretKey ?: synchronized(this) {
            secretKey ?: KeyGenerator.getInstance(keyAlgorithm).apply {
                init(keySize, SecureRandom())
            }.generateKey().also { secretKey = it }
        }
    }

    override fun deleteKey() {
        secretKey = null
    }

    override fun getSecurityLevel(): KeystoreSecurityLevel = KeystoreSecurityLevel.SOFTWARE
}

/**
 * AndroidKeyStore backed SecretKey provider.
 * Interrogates KeyInfo at runtime on device to determine whether the key resides in
 * hardware (StrongBox / TEE) or software Keystore.
 */
class AndroidKeystoreKeyProvider(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : KeyProvider {
    companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "prism_auth_session_key"
    }

    override fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        // Generate new key in Android KeyStore
        val keyGenerator = KeyGenerator.getInstance("AES", ANDROID_KEY_STORE)
        val specClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        val builder = specClass.getConstructor(String::class.java, Int::class.java).newInstance(
            keyAlias,
            3, // PURPOSE_ENCRYPT (1) or PURPOSE_DECRYPT (2)
        )

        val setBlockModes = specClass.getMethod("setBlockModes", Array<String>::class.java)
        setBlockModes.invoke(builder, arrayOf("GCM"))

        val setEncryptionPaddings = specClass.getMethod("setEncryptionPaddings", Array<String>::class.java)
        setEncryptionPaddings.invoke(builder, arrayOf("NoPadding"))

        val setKeySize = specClass.getMethod("setKeySize", Int::class.java)
        setKeySize.invoke(builder, 256)

        val buildMethod = specClass.getMethod("build")
        val spec = buildMethod.invoke(builder)

        val initMethod = KeyGenerator::class.java.getMethod("init", java.security.spec.AlgorithmParameterSpec::class.java)
        initMethod.invoke(keyGenerator, spec)

        return keyGenerator.generateKey()
    }

    override fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        } catch (_: Exception) {
            // Fail safe on key cleanup
        }
    }

    override fun getSecurityLevel(): KeystoreSecurityLevel {
        return try {
            val secretKey = getOrCreateSecretKey()
            val factoryClass = Class.forName("javax.crypto.SecretKeyFactory")
            val keyInfoClass = Class.forName("android.security.keystore.KeyInfo")
            val getInstanceMethod = factoryClass.getMethod("getInstance", String::class.java, String::class.java)
            val factory = getInstanceMethod.invoke(null, secretKey.algorithm, ANDROID_KEY_STORE)
            val getKeySpecMethod = factoryClass.getMethod("getKeySpec", SecretKey::class.java, Class::class.java)
            val keyInfo = getKeySpecMethod.invoke(factory, secretKey, keyInfoClass)
            val getSecurityLevelMethod = keyInfoClass.getMethod("getSecurityLevel")
            val level = getSecurityLevelMethod.invoke(keyInfo) as Int
            when (level) {
                1 -> KeystoreSecurityLevel.TRUSTED_ENVIRONMENT // KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT (1)
                2 -> KeystoreSecurityLevel.STRONGBOX // KeyProperties.SECURITY_LEVEL_STRONGBOX (2)
                -1 -> KeystoreSecurityLevel.UNKNOWN_SECURE // KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE (-1)
                0 -> KeystoreSecurityLevel.SOFTWARE // KeyProperties.SECURITY_LEVEL_SOFTWARE (0)
                -2 -> KeystoreSecurityLevel.UNKNOWN // KeyProperties.SECURITY_LEVEL_UNKNOWN (-2)
                else -> KeystoreSecurityLevel.UNKNOWN
            }
        } catch (_: Exception) {
            KeystoreSecurityLevel.UNKNOWN
        }
    }
}

/**
 * AndroidKeyStore-backed AES-256 GCM cryptographic engine for encrypting sensitive tokens and sessions.
 * Guarantees zero plaintext token persistence at rest.
 */
class KeystoreCrypto(
    private val keyProvider: KeyProvider,
) {
    companion object {
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
    }

    fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val secretKey = keyProvider.getOrCreateSecretKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).apply {
            SecureRandom().nextBytes(this)
        }
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPayload(iv = iv, ciphertext = ciphertext)
    }

    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        if (iv.size != GCM_IV_LENGTH_BYTES) {
            throw IllegalArgumentException("Invalid IV length: ${iv.size} bytes (expected $GCM_IV_LENGTH_BYTES)")
        }
        val secretKey = keyProvider.getOrCreateSecretKey()
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    fun deleteKey() {
        keyProvider.deleteKey()
    }

    fun getSecurityLevel(): KeystoreSecurityLevel = keyProvider.getSecurityLevel()
}
