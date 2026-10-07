package com.prismai.llmhost

import org.junit.Assert.assertEquals
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
    fun restoredEntry_isNeverCurated() {
        val entry = HuggingFaceModelCatalog.buildCustomEntry("user/repo", "m.gguf")
        assertTrue("custom entries must stay unverified-verifiable", !entry.curated)
    }

    @Test
    fun restoredEntry_keepsExpectedBytesAndMetadata() {
        val entry = HuggingFaceModelCatalog.buildCustomEntry("user/repo", "m.gguf")
        assertEquals(-1L, entry.expectedBytes)
        assertEquals("Custom", entry.parameters)
        assertNull(HuggingFaceModelCatalog.find("custom_does_not_exist"))
    }

    /**
     * Locks in the worker's real dependency: `HuggingFaceDownloadWorker.doWork` calls
     * `HuggingFaceModelCatalog.find(entryId)` after a restart, with only the durable store
     * (not the curated in-memory list) able to resolve a custom id.
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
