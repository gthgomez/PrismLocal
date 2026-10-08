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
    }

    @Test
    fun encoderIdentity_filteringExcludesRowsFromAnotherEncoder() {
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

        // Switching model/encoder must not silently reuse the old vectors.
        encoderId = "sha-b"
        assertTrue(
            "rows from another encoder must not be scored",
            store.search(floatArrayOf(1f, 0f, 0f), topK = 10, minScore = -1f).isEmpty(),
        )
        assertTrue("rows from another encoder must not be reported current", store.getCurrentChunks().isEmpty())
        assertEquals("the incompatible row must be surfaced as stale", 1, store.countStaleChunks())

        // Deleting stale rows reclaims it.
        assertEquals(1, store.deleteStaleChunks())
        assertEquals(0, store.countStaleChunks())
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
    fun unknownEncoderFallsBackToRevisionInsteadOfReportingEverythingStale() {
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

        assertEquals(
            "rows must not be hidden (or offered for destructive cleanup) merely because no model is loaded",
            listOf("pre-model"),
            store.getCurrentChunks().map { it.id },
        )
        assertEquals(0, store.countStaleChunks())

        // Once a real encoder is known, the same row is judged against it.
        store.setEmbeddingIdentityProvider { EmbeddingIdentity("sha-a", VectorStore.EMBEDDING_REVISION) }
        assertTrue(store.getCurrentChunks().isEmpty())
        assertEquals(1, store.countStaleChunks())
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
}
