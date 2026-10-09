package com.prismai.llmhost.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes the v1 -> v2 migration and the revision-filtered reads against a real
 * SQLite database.
 *
 * The JVM unit tests can only assert the schema constants: Robolectric is not on
 * the unit-test classpath, so a JVM test cannot open SQLite. This instrumentation
 * test closes that gap. It is compiled by `assembleDevDebugAndroidTest` but only
 * executed on a device/emulator, which is the device gate for the sprint.
 */
@RunWith(AndroidJUnit4::class)
class VectorStoreRevisionMigrationTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun cleanBefore() {
        context.deleteDatabase(VectorStore.DB_NAME)
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(VectorStore.DB_NAME)
    }

    @Test
    fun upgradeStampsLegacyRowsRevision1_andRevisionFilteredReadsExcludeThem() {
        seedVersion1Database()

        // Opening through VectorStore runs onUpgrade(1 -> 2), adding
        // embedding_revision with DEFAULT 1 so the legacy row is stamped stale.
        val store = VectorStore(context)
        store.insert(
            VectorChunk(
                id = "current-1",
                documentId = "current-doc",
                chunkIndex = 0,
                text = "fresh chunk",
                embedding = floatArrayOf(1f, 0f, 0f),
            )
        )

        // getAllChunks is the maintenance read: it must still surface the stale
        // row so deletion can remove it.
        val all = store.getAllChunks().map { it.id }.toSet()
        assertTrue(
            "getAllChunks must still return the revision-1 row, got $all",
            "legacy-1" in all,
        )
        assertTrue("getAllChunks must return the revision-2 row, got $all", "current-1" in all)
        assertEquals(2, all.size)

        // getCurrentChunks is the retrieval read: it must hide the stale row.
        val current = store.getCurrentChunks().map { it.id }
        assertEquals(
            "getCurrentChunks must exclude revision-1 rows",
            listOf("current-1"),
            current,
        )

        // search must not score a stale row even though it shares the document.
        val results = store.search(floatArrayOf(1f, 0f, 0f), topK = 10, minScore = -1f)
        assertEquals(
            "search must exclude revision-1 rows",
            listOf("current-1"),
            results.map { it.first.id },
        )

        // The revision-1 row is obsolete under every encoder and may be reclaimed.
        assertEquals("only the revision-1 row is obsolete", 1, store.countObsoleteChunks())
        assertEquals(1, store.deleteObsoleteChunks())
        assertEquals(0, store.countObsoleteChunks())
        assertTrue(
            "cleanup must not remove the current-revision row",
            store.getAllChunks().any { it.id == "current-1" },
        )
    }

    @Test
    fun encoderSwitch_makesRowsUnsearchableButNeverObsoleteOrDeletable() {
        val store = VectorStore(context)
        var encoderId = "sha-a"
        store.setEmbeddingIdentityProvider { EmbeddingIdentity(encoderId, VectorStore.EMBEDDING_REVISION) }
        store.insert(
            VectorChunk(
                id = "a-1",
                documentId = "doc-a",
                chunkIndex = 0,
                text = "chunk from encoder a",
                embedding = floatArrayOf(1f, 0f, 0f),
            )
        )

        // Same encoder: the row is searchable and current.
        assertEquals(
            listOf("a-1"),
            store.search(floatArrayOf(1f, 0f, 0f), topK = 10, minScore = -1f).map { it.first.id },
        )
        assertEquals(listOf("a-1"), store.getCurrentChunks().map { it.id })

        // Switching model/encoder must not silently score the old vectors.
        encoderId = "sha-b"
        assertTrue(
            "rows from another encoder must not be scored",
            store.search(floatArrayOf(1f, 0f, 0f), topK = 10, minScore = -1f).isEmpty(),
        )
        assertTrue("rows from another encoder must not be reported current", store.getCurrentChunks().isEmpty())

        // Critically, another encoder's rows are NOT obsolete: deleting them would destroy the only
        // stored copy of the document text and force a re-index just because a different model is
        // loaded for a moment.
        assertEquals("another encoder's rows must not be obsolete", 0, store.countObsoleteChunks())
        assertEquals(0, store.deleteObsoleteChunks())
        assertEquals("the row must still be stored", 1, store.countStoredChunks())
        assertEquals(0, store.countSearchableChunks())
        assertTrue(store.getAllChunks().any { it.id == "a-1" })

        // Switching back re-enables retrieval without a re-index.
        encoderId = "sha-a"
        assertEquals(
            "model A -> B -> A must preserve A's index",
            listOf("a-1"),
            store.search(floatArrayOf(1f, 0f, 0f), topK = 10, minScore = -1f).map { it.first.id },
        )
        assertEquals(1, store.countSearchableChunks())
    }

    @Test
    fun search_doesNotMatchRowsOfADifferentDimension() {
        val store = VectorStore(context)
        store.setEmbeddingIdentityProvider { EmbeddingIdentity("sha-a", VectorStore.EMBEDDING_REVISION) }
        store.insert(
            VectorChunk(
                id = "dim-3",
                documentId = "doc-a",
                chunkIndex = 0,
                text = "3-wide",
                embedding = floatArrayOf(1f, 0f, 0f),
            )
        )

        assertTrue(
            "a query of another width must not match stored rows",
            store.search(floatArrayOf(1f, 0f), topK = 10, minScore = -1f).isEmpty(),
        )
    }

    @Test
    fun rowsFromAnotherOrUnloadedEncoderAreNeverOfferedForDestructiveCleanup() {
        val store = VectorStore(context)
        // No provider set: identity is UNKNOWN, as when no model is loaded yet.
        store.insert(
            VectorChunk(
                id = "pre-model",
                documentId = "doc-a",
                chunkIndex = 0,
                text = "indexed while a model was loaded earlier",
                embedding = floatArrayOf(1f, 0f, 0f),
            )
        )

        // The row is stored, and is never obsolete merely because no model is loaded.
        assertEquals(1, store.countStoredChunks())
        assertEquals(0, store.countObsoleteChunks())
        assertEquals("nothing may be offered for destructive cleanup", 0, store.deleteObsoleteChunks())

        // Once a different real encoder is known, the row is not searchable, but it is still stored
        // and still NOT obsolete: switching back to its encoder must recover it.
        store.setEmbeddingIdentityProvider { EmbeddingIdentity("sha-a", VectorStore.EMBEDDING_REVISION) }
        assertTrue(store.getCurrentChunks().isEmpty())
        assertEquals("a different encoder's row is not obsolete", 0, store.countObsoleteChunks())
        assertEquals(1, store.countStoredChunks())
        assertTrue(store.getAllChunks().any { it.id == "pre-model" })
    }

    @Test
    fun upgradeFromVersion2ToVersion3_marksMigratedRowsAsObsoleteAndCleansThem() {
        seedVersion2Database()

        // Opening through VectorStore runs onUpgrade(2 -> 3), adding
        // encoder_identity with DEFAULT '' and embedding_dim with DEFAULT 0.
        val store = VectorStore(context)
        store.setEmbeddingIdentityProvider { EmbeddingIdentity("sha-a", VectorStore.EMBEDDING_REVISION) }
        store.insert(
            VectorChunk(
                id = "v3-current",
                documentId = "current-doc",
                chunkIndex = 0,
                text = "fresh chunk on v3",
                embedding = floatArrayOf(1f, 0f, 0f),
            )
        )

        // The v2 row cannot be searched under any encoder because its encoder_identity is empty and dim is 0
        val searchResults = store.search(floatArrayOf(1f, 0f, 0f), topK = 10, minScore = -1f)
        assertEquals(listOf("v3-current"), searchResults.map { it.first.id })

        // The v2 migrated row must be reported as obsolete/legacy and offered for cleanup
        assertEquals("the migrated v2 row must be identified as obsolete", 1, store.countObsoleteChunks())
        assertEquals("countLegacyUnknownChunks must also count it", 1, store.countLegacyUnknownChunks())

        // deleteObsoleteChunks must reclaim it without affecting the v3 row
        assertEquals(1, store.deleteObsoleteChunks())
        assertEquals(0, store.countObsoleteChunks())
        assertTrue("v3 row must remain", store.getAllChunks().any { it.id == "v3-current" })
    }

    /**
     * Recreate the pre-revision schema at user_version 1, exactly as an upgraded
     * install would have it, and insert one row.
     */
    private fun seedVersion1Database() {
        val dbFile = context.getDatabasePath(VectorStore.DB_NAME)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ${VectorStore.TABLE} (
                    ${VectorStore.COL_ID} TEXT PRIMARY KEY,
                    ${VectorStore.COL_DOCUMENT_ID} TEXT NOT NULL,
                    ${VectorStore.COL_CHUNK_INDEX} INTEGER NOT NULL,
                    ${VectorStore.COL_TEXT} TEXT NOT NULL,
                    ${VectorStore.COL_EMBEDDING} BLOB NOT NULL,
                    ${VectorStore.COL_CREATED} INTEGER NOT NULL
                )
                """.trimIndent()
            )
            val values = ContentValues().apply {
                put(VectorStore.COL_ID, "legacy-1")
                put(VectorStore.COL_DOCUMENT_ID, "legacy-doc")
                put(VectorStore.COL_CHUNK_INDEX, 0)
                put(VectorStore.COL_TEXT, "stale chunk")
                put(VectorStore.COL_EMBEDDING, VectorStore.floatArrayToBytes(floatArrayOf(1f, 0f, 0f)))
                put(VectorStore.COL_CREATED, 1L)
            }
            db.insert(VectorStore.TABLE, null, values)
            db.version = 1
        } finally {
            db.close()
        }
    }

    /**
     * Recreate the version 2 schema (embedding_revision present, but no encoder_identity or embedding_dim)
     * and insert a row with embedding_revision = 2.
     */
    private fun seedVersion2Database() {
        val dbFile = context.getDatabasePath(VectorStore.DB_NAME)
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ${VectorStore.TABLE} (
                    ${VectorStore.COL_ID} TEXT PRIMARY KEY,
                    ${VectorStore.COL_DOCUMENT_ID} TEXT NOT NULL,
                    ${VectorStore.COL_CHUNK_INDEX} INTEGER NOT NULL,
                    ${VectorStore.COL_TEXT} TEXT NOT NULL,
                    ${VectorStore.COL_EMBEDDING} BLOB NOT NULL,
                    ${VectorStore.COL_CREATED} INTEGER NOT NULL,
                    ${VectorStore.COL_EMBEDDING_REVISION} INTEGER NOT NULL DEFAULT 2
                )
                """.trimIndent()
            )
            val values = ContentValues().apply {
                put(VectorStore.COL_ID, "v2-legacy-1")
                put(VectorStore.COL_DOCUMENT_ID, "v2-legacy-doc")
                put(VectorStore.COL_CHUNK_INDEX, 0)
                put(VectorStore.COL_TEXT, "migrated v2 chunk without encoder")
                put(VectorStore.COL_EMBEDDING, VectorStore.floatArrayToBytes(floatArrayOf(1f, 0f, 0f)))
                put(VectorStore.COL_CREATED, 2L)
                put(VectorStore.COL_EMBEDDING_REVISION, 2)
            }
            db.insert(VectorStore.TABLE, null, values)
            db.version = 2
        } finally {
            db.close()
        }
    }
}
