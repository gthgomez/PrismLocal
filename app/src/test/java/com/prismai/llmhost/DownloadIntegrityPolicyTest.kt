package com.prismai.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure integrity policy for Hugging Face downloads (no Android device required).
 *
 * Guards the D1 fix: a curated/pinned model must never be imported without a verified SHA-256,
 * and a dynamic import with no published hash must be surfaced as unverified instead of silently
 * treated as verified.
 */
class DownloadIntegrityPolicyTest {

    private val validSha = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"

    @Test
    fun hashPresentIsVerifiedForCuratedEntry() {
        assertEquals(
            DownloadIntegrityDecision.VERIFY_SHA256,
            decideDownloadIntegrity(validSha, curated = true),
        )
    }

    @Test
    fun hashPresentIsVerifiedForDynamicEntry() {
        assertEquals(
            DownloadIntegrityDecision.VERIFY_SHA256,
            decideDownloadIntegrity(validSha, curated = false),
        )
    }

    @Test
    fun uppercaseAndWhitespacePaddedHashIsAccepted() {
        assertEquals(
            DownloadIntegrityDecision.VERIFY_SHA256,
            decideDownloadIntegrity("  ${validSha.uppercase()}  ", curated = true),
        )
    }

    @Test
    fun curatedWithoutHashFailsClosed() {
        assertEquals(
            DownloadIntegrityDecision.FAIL_CLOSED,
            decideDownloadIntegrity(null, curated = true),
        )
    }

    @Test
    fun dynamicWithoutHashIsExplicitlyUnverified() {
        assertEquals(
            DownloadIntegrityDecision.UNVERIFIED_DYNAMIC,
            decideDownloadIntegrity(null, curated = false),
        )
    }

    @Test
    fun blankHashIsTreatedAsAbsent() {
        assertEquals(
            DownloadIntegrityDecision.FAIL_CLOSED,
            decideDownloadIntegrity("   ", curated = true),
        )
        assertEquals(
            DownloadIntegrityDecision.UNVERIFIED_DYNAMIC,
            decideDownloadIntegrity("", curated = false),
        )
    }

    @Test
    fun malformedLengthFailsClosedForCuratedOnly() {
        val tooShort = validSha.dropLast(1)
        assertEquals(
            DownloadIntegrityDecision.FAIL_CLOSED,
            decideDownloadIntegrity(tooShort, curated = true),
        )
        assertEquals(
            DownloadIntegrityDecision.UNVERIFIED_DYNAMIC,
            decideDownloadIntegrity(tooShort, curated = false),
        )
    }

    @Test
    fun nonHexHashFailsClosedForCuratedOnly() {
        val nonHex = "z".repeat(64)
        assertEquals(
            DownloadIntegrityDecision.FAIL_CLOSED,
            decideDownloadIntegrity(nonHex, curated = true),
        )
        assertEquals(
            DownloadIntegrityDecision.UNVERIFIED_DYNAMIC,
            decideDownloadIntegrity(nonHex, curated = false),
        )
    }

    @Test
    fun curatedCatalogEntriesAreMarkedCuratedAndPinOnlyValidHashes() {
        val entries = HuggingFaceModelCatalog.entries
        assertTrue("catalog must not be empty", entries.isNotEmpty())
        for (entry in entries) {
            assertTrue("catalog entry ${entry.id} must be curated", entry.curated)
            entry.expectedSha256?.let { sha ->
                assertTrue(
                    "catalog entry ${entry.id} must pin a 64-hex SHA-256",
                    sha.trim().length == 64,
                )
            }
        }
    }

    @Test
    fun createCustomEntryIsDynamic() {
        val entry = HuggingFaceModelCatalog.createCustomEntry("some-org/some-repo", "model.gguf")
        assertFalse("user-supplied entry must not be curated", entry.curated)
    }
}
