package com.prismai.llmhost

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomDownloadSpecPersistenceTest {

    @Test
    fun specRoundTrips_throughTheStore() {
        val store = FakeCustomEntryStore()
        val original = HuggingFaceModelCatalog.buildCustomEntry("user/repo", "model.gguf")
        store.put(original)

        // Simulate process death: a fresh store instance reads the persisted row.
        val restored = FakeCustomEntryStore(rehydratedFrom = store)
        val found = restored.find(original.id)

        assertNotNull("a queued download must survive process death", found)
        assertEquals("user/repo", found!!.repoId)
        assertEquals("model.gguf", found.fileName)
        assertEquals(original.id, found.id)
    }

    @Test
    fun restoredEntry_keepsExpectedBytesAndMetadataThroughRoundTrip() {
        val entry = HuggingFaceModelCatalog.buildCustomEntry("user/repo", "m.gguf")

        val restored = CustomEntryStore.deserialize(CustomEntryStore.serialize(entry))

        assertNotNull(restored)
        assertEquals(-1L, restored!!.expectedBytes)
        assertEquals("Custom", restored.parameters)
        assertEquals("user/repo", restored.repoId)
        assertEquals("m.gguf", restored.fileName)
    }

    /**
     * Covers the durable store's own round-trip: a custom entry's id, repo and file
     * survive the serialize/deserialize the persisted row actually performs. The
     * worker's `HuggingFaceModelCatalog.find(id)` lookup that consumes such a row is
     * covered separately by [find_resolvesPersistedCustomEntryThatIsNotInTheCuratedList].
     */
    @Test
    fun aRoundTrippedCustomEntryResolvesByItsPersistedId() {
        val entry = HuggingFaceModelCatalog.buildCustomEntry("user/persisted", "persisted.gguf")

        val restored = CustomEntryStore.deserialize(CustomEntryStore.serialize(entry))

        assertNotNull("a persisted custom entry must survive the round-trip", restored)
        assertEquals(entry.id, restored!!.id)
        assertEquals("user/persisted", restored.repoId)
        assertTrue(
            "sanity: the custom id must not be a curated entry",
            HuggingFaceModelCatalog.entries.none { it.id == entry.id },
        )
    }

    /**
     * Locks in the worker's real dependency: `HuggingFaceDownloadWorker.doWork` calls
     * `HuggingFaceModelCatalog.find(entryId)` after a restart. A custom id is not in the
     * curated in-memory list, so only the custom-store fallback can resolve it.
     */
    @Test
    fun find_resolvesPersistedCustomEntryThatIsNotInTheCuratedList() {
        val entry = HuggingFaceModelCatalog.createCustomEntry("user/persisted", "persisted.gguf")

        assertNull(
            "sanity: the custom id must not be a curated entry",
            HuggingFaceModelCatalog.entries.firstOrNull { it.id == entry.id },
        )

        val found = HuggingFaceModelCatalog.find(entry.id)
        assertNotNull("worker must resolve a persisted custom entry by id", found)
        assertEquals(entry.id, found!!.id)
        assertEquals("user/persisted", found.repoId)
    }

    // --- D2: expectedSha256 must survive the round-trip ---

    @Test
    fun expectedSha256_roundTripsThroughSerializeDeserialize() {
        val sha = "a".repeat(64)
        val entry = HuggingFaceModelCatalog.buildCustomEntry("user/repo", "m.gguf")
            .copy(expectedSha256 = sha)

        val restored = CustomEntryStore.deserialize(CustomEntryStore.serialize(entry))

        assertNotNull("entry must deserialize", restored)
        assertEquals("integrity pin must survive process death", sha, restored!!.expectedSha256)
    }

    @Test
    fun invalidExpectedSha256_isRestoredAsNull() {
        val entry = HuggingFaceModelCatalog.buildCustomEntry("user/repo", "m.gguf")
            .copy(expectedSha256 = "not-a-valid-sha")

        val restored = CustomEntryStore.deserialize(CustomEntryStore.serialize(entry))

        assertNotNull(restored)
        assertNull("a malformed pin must fail closed, not be trusted", restored!!.expectedSha256)
    }

    // --- D3: restored id / fileName must be re-sanitized ---

    @Test
    fun restoredEntry_rejectsBlankFileName() {
        val row = JSONObject()
            .put("id", "custom_user_repo_m.gguf")
            .put("repoId", "user/repo")
            .put("fileName", "")
            .toString()

        assertNull(CustomEntryStore.deserialize(row))
    }

    @Test
    fun restoredEntry_rejectsIdWithPathSeparator() {
        val row = JSONObject()
            .put("id", "custom/../escape")
            .put("repoId", "user/repo")
            .put("fileName", "m.gguf")
            .toString()

        assertNull("an id that escapes its filename slot must be rejected", CustomEntryStore.deserialize(row))
    }

    @Test
    fun restoredEntry_rejectsDotDotId() {
        val row = JSONObject()
            .put("id", "..")
            .put("repoId", "user/repo")
            .put("fileName", "m.gguf")
            .toString()

        assertNull("a bare '..' id must be rejected", CustomEntryStore.deserialize(row))
    }

    @Test
    fun restoredEntry_isNeverCurated_evenWhenRowClaimsOtherwise() {
        val entry = HuggingFaceModelCatalog.buildCustomEntry("user/repo", "m.gguf")
        val row = JSONObject(CustomEntryStore.serialize(entry)).put("curated", true).toString()

        val restored = CustomEntryStore.deserialize(row)

        assertNotNull(restored)
        assertFalse("a restored custom entry must never become curated", restored!!.curated)
    }

    // --- PL-F14: Truncated custom-download IDs collide ---

    @Test
    fun customEntriesWithSameFortyCharPrefixDoNotCollideAndRoundTrip() {
        val longRepo = "TheBloke/Long-Repository-Name-Exceeding-Normal-Limits-GGUF"
        val file1 = "model-variant-q4_k_m.gguf"
        val file2 = "model-variant-q5_k_m.gguf"

        val entry1 = HuggingFaceModelCatalog.buildCustomEntry(longRepo, file1)
        val entry2 = HuggingFaceModelCatalog.buildCustomEntry(longRepo, file2)

        // The first 40 chars of the old scheme would have collided:
        val legacy1 = HuggingFaceModelCatalog.legacyCustomEntryId(longRepo, file1)
        val legacy2 = HuggingFaceModelCatalog.legacyCustomEntryId(longRepo, file2)
        assertEquals("legacy IDs collided due to 40 char truncation", legacy1, legacy2)

        // But new collision-resistant IDs MUST differ:
        org.junit.Assert.assertNotEquals("new collision-resistant IDs must not collide", entry1.id, entry2.id)

        val store = FakeCustomEntryStore()
        store.put(entry1)
        store.put(entry2)

        // Both survive in store without replacing each other:
        val restored = FakeCustomEntryStore(rehydratedFrom = store)
        val found1 = restored.find(entry1.id)
        val found2 = restored.find(entry2.id)

        assertNotNull(found1)
        assertNotNull(found2)
        assertEquals(file1, found1!!.fileName)
        assertEquals(file2, found2!!.fileName)

        // Also test legacy fallback resolution: an enqueued request holding the legacy truncated ID resolves
        val foundLegacy = restored.find(legacy1)
        assertNotNull("queued legacy ID must resolve without orphaning", foundLegacy)
    }
}

/**
 * In-memory [CustomEntryStore.Backend] that models process death: passing the previous
 * fake as [rehydratedFrom] copies the rows the previous process had persisted.
 */
private class FakeCustomEntryStore(
    rehydratedFrom: FakeCustomEntryStore? = null,
) {
    private val backend = FakeBackend()
    private val store = CustomEntryStore(backend)

    init {
        if (rehydratedFrom != null) {
            backend.rows.addAll(rehydratedFrom.backend.rows)
        }
    }

    fun put(entry: HuggingFaceModelEntry) = store.put(entry)

    fun find(id: String): HuggingFaceModelEntry? = store.find(id)

    private class FakeBackend : CustomEntryStore.Backend {
        val rows = mutableListOf<String>()

        override fun readAll(): List<String> = rows.toList()

        override fun writeAll(rows: List<String>) {
            this.rows.clear()
            this.rows.addAll(rows)
        }
    }
}
