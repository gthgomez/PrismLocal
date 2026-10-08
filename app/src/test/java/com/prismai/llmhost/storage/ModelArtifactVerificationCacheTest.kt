package com.prismai.llmhost.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards the activation-time verification cache: it must only ever report a match for the exact
 * bytes that were hashed, and any change to size, mtime, digest, or location must invalidate it.
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
