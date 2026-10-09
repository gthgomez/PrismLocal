package com.prismai.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure integrity policy for Hugging Face downloads (no Android device required).
 *
 * Guards that a curated/pinned model must never be imported without a verified SHA-256, that a
 * provider-announced digest can never override an independently pinned one, and that a conflict
 * between the two is refused rather than silently resolved.
 */
class DownloadIntegrityPolicyTest {

    private val validSha = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
    private val otherSha = "0000000000000000000000000000000000000000000000000000000000000000"

    @Test
    fun trustedPinIsVerifiedForCuratedEntry() {
        assertEquals(
            DownloadIntegrityDecision.VERIFY_TRUSTED_PIN,
            decideDownloadIntegrity(validSha, providerHash = null, curated = true),
        )
    }

    @Test
    fun userPinIsVerifiedForDynamicEntry() {
        assertEquals(
            DownloadIntegrityDecision.VERIFY_TRUSTED_PIN,
            decideDownloadIntegrity(validSha, providerHash = null, curated = false),
        )
    }

    @Test
    fun matchingProviderHashDoesNotDowngradeATrustedPin() {
        assertEquals(
            DownloadIntegrityDecision.VERIFY_TRUSTED_PIN,
            decideDownloadIntegrity(validSha, providerHash = validSha.uppercase(), curated = true),
        )
    }

    @Test
    fun providerHashThatDisagreesWithThePinIsAConflict() {
        assertEquals(
            DownloadIntegrityDecision.TRUST_CONFLICT,
            decideDownloadIntegrity(validSha, providerHash = otherSha, curated = true),
        )
    }

    @Test
    fun providerHashConflictAlsoAppliesToUserPinnedDynamicEntries() {
        assertEquals(
            DownloadIntegrityDecision.TRUST_CONFLICT,
            decideDownloadIntegrity(validSha, providerHash = otherSha, curated = false),
        )
    }

    @Test
    fun curatedEntryWithoutPinFailsClosedEvenWithAProviderHash() {
        assertEquals(
            DownloadIntegrityDecision.FAIL_CLOSED,
            decideDownloadIntegrity(null, providerHash = validSha, curated = true),
        )
    }

    @Test
    fun dynamicEntryWithOnlyAProviderHashIsProviderVerified() {
        assertEquals(
            DownloadIntegrityDecision.VERIFY_PROVIDER_METADATA,
            decideDownloadIntegrity(null, providerHash = validSha, curated = false),
        )
    }

    @Test
    fun dynamicEntryWithNoHashIsExplicitlyUnverified() {
        assertEquals(
            DownloadIntegrityDecision.UNVERIFIED_DYNAMIC,
            decideDownloadIntegrity(null, providerHash = null, curated = false),
        )
    }

    @Test
    fun malformedPinIsTreatedAsAbsentAndNeverConflicts() {
        val tooShort = validSha.dropLast(1)
        assertEquals(
            DownloadIntegrityDecision.FAIL_CLOSED,
            decideDownloadIntegrity(tooShort, providerHash = validSha, curated = true),
        )
        assertEquals(
            DownloadIntegrityDecision.VERIFY_PROVIDER_METADATA,
            decideDownloadIntegrity(tooShort, providerHash = validSha, curated = false),
        )
    }

    @Test
    fun nonHexPinIsTreatedAsAbsent() {
        val nonHex = "z".repeat(64)
        assertEquals(
            DownloadIntegrityDecision.FAIL_CLOSED,
            decideDownloadIntegrity(nonHex, providerHash = null, curated = true),
        )
    }

    @Test
    fun blankPinIsTreatedAsAbsent() {
        assertEquals(
            DownloadIntegrityDecision.FAIL_CLOSED,
            decideDownloadIntegrity("   ", providerHash = null, curated = true),
        )
    }

    @Test
    fun failingDecisionsHaveNoReportableOutcome() {
        assertNull(DownloadIntegrityDecision.FAIL_CLOSED.outcome())
        assertNull(DownloadIntegrityDecision.TRUST_CONFLICT.outcome())
        assertEquals(
            DownloadIntegrity.VERIFIED_PINNED,
            DownloadIntegrityDecision.VERIFY_TRUSTED_PIN.outcome(),
        )
        assertEquals(
            DownloadIntegrity.VERIFIED_PROVIDER_METADATA,
            DownloadIntegrityDecision.VERIFY_PROVIDER_METADATA.outcome(),
        )
        assertEquals(
            DownloadIntegrity.UNVERIFIED,
            DownloadIntegrityDecision.UNVERIFIED_DYNAMIC.outcome(),
        )
    }

    @Test
    fun normalizeSha256AcceptsOnlyCanonicalDigests() {
        assertEquals(validSha, normalizeSha256("  ${validSha.uppercase()}  "))
        assertNull(normalizeSha256(null))
        assertNull(normalizeSha256(""))
        assertNull(normalizeSha256(validSha.dropLast(1)))
        assertNull(normalizeSha256("z".repeat(64)))
    }

    @Test
    fun everyCuratedCatalogEntryPinsAValidUniqueDigest() {
        val entries = HuggingFaceModelCatalog.entries
        assertTrue("catalog must not be empty", entries.isNotEmpty())
        val seenIds = mutableSetOf<String>()
        val seenDigests = mutableMapOf<String, String>()
        for (entry in entries) {
            assertTrue("catalog entry ${entry.id} must be curated", entry.curated)
            assertTrue("catalog entry id must be unique: ${entry.id}", seenIds.add(entry.id))
            val digest = normalizeSha256(entry.expectedSha256)
            assertTrue(
                "curated catalog entry ${entry.id} must pin a 64-hex SHA-256",
                digest != null,
            )
            val previous = seenDigests.put(digest!!, entry.id)
            assertNull(
                "catalog entries ${entry.id} and $previous must not share a digest",
                previous,
            )
        }
    }

    @Test
    fun curatedCatalogEntriesWouldNeverFailClosedAtDownloadTime() {
        // A curated entry that fails closed can never be downloaded; the audit keeps the catalog
        // free of entries that are shown to users but cannot be verified.
        for (entry in HuggingFaceModelCatalog.entries) {
            assertEquals(
                "curated entry ${entry.id} must have a usable trusted pin",
                DownloadIntegrityDecision.VERIFY_TRUSTED_PIN,
                decideDownloadIntegrity(entry.expectedSha256, providerHash = null, curated = entry.curated),
            )
        }
    }

    @Test
    fun createCustomEntryIsDynamic() {
        val entry = HuggingFaceModelCatalog.createCustomEntry("some-org/some-repo", "model.gguf")
        assertFalse("user-supplied entry must not be curated", entry.curated)
    }
}
