package com.prismai.llmhost.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards the activation-time verification cache. A match requires the same digest, size, mtime and
 * content fingerprint, so any change to size, mtime, digest, location, or the head/tail bytes
 * invalidates it. The fingerprint is what stops a same-size, same-mtime substitution of a different
 * artifact from reusing a previously computed digest; it is not a substitute for the app-private
 * writer boundary the whole hash check relies on.
 */
class ModelArtifactVerificationCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sha = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"

    @Test
    fun remembersExactArtifactBytes() {
        val file = temp.newFile("model.gguf").apply { writeBytes(ByteArray(64) { it.toByte() }) }

        assertFalse("unseen artifact must not match", ModelArtifactVerificationCache.matches(file, sha))
        ModelArtifactVerificationCache.remember(file, sha)
        assertTrue(ModelArtifactVerificationCache.matches(file, sha))
    }

    @Test
    fun digestComparisonIsCaseInsensitive() {
        val file = temp.newFile("model.gguf").apply { writeBytes(ByteArray(16)) }
        ModelArtifactVerificationCache.remember(file, sha)

        assertTrue(ModelArtifactVerificationCache.matches(file, sha.uppercase()))
    }

    @Test
    fun differentExpectedDigestDoesNotMatch() {
        val file = temp.newFile("model.gguf").apply { writeBytes(ByteArray(16)) }
        ModelArtifactVerificationCache.remember(file, sha)

        assertFalse(ModelArtifactVerificationCache.matches(file, "0".repeat(64)))
    }

    @Test
    fun modificationTimeChangeInvalidatesTheRecord() {
        val file = temp.newFile("model.gguf").apply { writeBytes(ByteArray(16)) }
        ModelArtifactVerificationCache.remember(file, sha)
        file.setLastModified(file.lastModified() + 5_000)

        assertFalse(ModelArtifactVerificationCache.matches(file, sha))
    }

    @Test
    fun sizeChangeInvalidatesTheRecord() {
        val file = temp.newFile("model.gguf").apply { writeBytes(ByteArray(16)) }
        ModelArtifactVerificationCache.remember(file, sha)
        file.writeBytes(ByteArray(32))

        assertFalse(ModelArtifactVerificationCache.matches(file, sha))
    }

    @Test
    fun sameSizeSameMtimeSubstitutionIsDetectedByFingerprint() {
        val file = temp.newFile("model.gguf")
        val original = ByteArray(4096) { it.toByte() }
        file.writeBytes(original)
        val mtime = 1_000_000_000_000L
        file.setLastModified(mtime)
        ModelArtifactVerificationCache.remember(file, sha)

        // Same length, same mtime, different content: the fingerprint window differs.
        val substituted = original.copyOf().also { it[0] = (it[0] + 1).toByte() }
        file.writeBytes(substituted)
        file.setLastModified(mtime)

        assertFalse(
            "a same-size, same-mtime substitution must not reuse the cached digest",
            ModelArtifactVerificationCache.matches(file, sha),
        )
    }

    @Test
    fun identicalBytesWithSameMetadataStillMatch() {
        val file = temp.newFile("model.gguf")
        val bytes = ByteArray(4096) { it.toByte() }
        file.writeBytes(bytes)
        file.setLastModified(1_000_000_000_000L)
        ModelArtifactVerificationCache.remember(file, sha)

        // Rewrite identical content and the same mtime: still the bytes we hashed.
        file.writeBytes(bytes)
        file.setLastModified(1_000_000_000_000L)

        assertTrue(ModelArtifactVerificationCache.matches(file, sha))
    }

    @Test
    fun forgetUnderDirectoryDropsOnlyThatSubtree() {
        val dir = temp.newFolder("models")
        val nested = File(dir, "model-a/versions/v1").apply { mkdirs() }
        val inTree = File(nested, "model.gguf").apply { writeBytes(ByteArray(8)) }
        val outside = temp.newFile("outside.gguf").apply { writeBytes(ByteArray(8)) }
        ModelArtifactVerificationCache.remember(inTree, sha)
        ModelArtifactVerificationCache.remember(outside, sha)

        ModelArtifactVerificationCache.forgetUnder(dir)

        assertFalse(ModelArtifactVerificationCache.matches(inTree, sha))
        assertTrue("artifacts outside the deleted subtree must be retained", ModelArtifactVerificationCache.matches(outside, sha))
    }
}
