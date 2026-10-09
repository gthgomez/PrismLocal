package com.prismai.llmhost.storage
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * Streams [scored] once and retains only the [topK] highest-scoring entries at or
 * above [minScore], sorted by descending score.
 *
 * A bounded min-heap is used so the caller never has to materialize the whole
 * candidate set; the heap head is the weakest current survivor.
 */
internal fun <T> selectTopK(
    scored: Iterator<Pair<T, Float>>,
    topK: Int,
    minScore: Float,
): List<Pair<T, Float>> {
    if (topK <= 0) return emptyList()
    val best = PriorityQueue<Pair<T, Float>>(compareBy { it.second })
    while (scored.hasNext()) {
        val candidate = scored.next()
        if (candidate.second < minScore) continue
        if (best.size >= topK && candidate.second <= best.peek()!!.second) continue
        if (best.size >= topK) best.poll()
        best.add(candidate)
    }
    return best.sortedByDescending { it.second }
}

/**
 * A stored embedding chunk with its text and metadata.
 */
data class VectorChunk(
    val id: String,
    val documentId: String,
    val chunkIndex: Int,
    val text: String,
    val embedding: FloatArray,
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorChunk) return false
        return id == other.id &&
                documentId == other.documentId &&
                chunkIndex == other.chunkIndex &&
                text == other.text &&
                embedding.contentEquals(other.embedding) &&
                createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + text.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

/**
 * Bounded, per-document projection for the document browser: counts plus one short preview. It
 * never loads the whole vector table, so browsing a large knowledge base retains O(documents),
 * not O(chunks), and never decodes an embedding BLOB.
 *
 * The two counts separate what is *stored* from what is *searchable*:
 *  - [storedChunkCount] counts rows written at the current embedding revision, regardless of which
 *    encoder produced them. These rows survive on disk across model switches.
 *  - [searchableChunkCount] counts the subset [VectorStore.search] can return for the *currently
 *    loaded* encoder. It is 0 when no encoder is loaded or when the stored rows belong to another
 *    model.
 *
 * A document with fewer searchable chunks than stored ones is not stale and must not be offered for
 * deletion: switching back to its encoder makes it searchable again.
 */
data class VectorDocumentSummary(
    val documentId: String,
    val storedChunkCount: Int,
    val searchableChunkCount: Int,
    val previewText: String,
    val createdAt: Long,
    val legacyChunkCount: Int = 0,
) {
    /** True when the currently loaded encoder can score every stored chunk of this document. */
    val fullySearchable: Boolean get() = storedChunkCount > 0 && searchableChunkCount == storedChunkCount && legacyChunkCount == 0
    val hasLegacyChunks: Boolean get() = legacyChunkCount > 0
}

/**
 * Compatibility identity for stored embeddings.
 *
 * Two embeddings may only be compared when their identity matches. Vectors from different encoder
 * artifacts (different models), a different embedding algorithm revision, or the same algorithm
 * with incompatible dimensions are numerically incomparable even when their width matches, so a
 * global revision alone is not a sufficient contract.
 *
 * The token deliberately excludes the dimension: the dimension is implied by [encoderId] plus
 * [algorithmRevision] and is additionally validated against the query width at score time
 * (`cosineSimilarity` returns 0 on a length mismatch). Keeping it out of the token lets listing
 * paths reason about compatibility without having to run an encoder.
 */
data class EmbeddingIdentity(
    /** Identity of the encoder artifact, e.g. the loaded model's SHA-256. */
    val encoderId: String,
    /** Revision of the embedding computation (pooling/normalization included). */
    val algorithmRevision: Int,
    /** Monotonic model/config epoch incremented on every model change/reload. */
    val epoch: Long = 0L,
) {
    val token: String get() = "rev=$algorithmRevision;enc=$encoderId"

    companion object {
        const val UNKNOWN_ENCODER = "unknown"

        /** Identity for when no encoder/model is loaded: matches nothing that was ever stamped. */
        val UNKNOWN = EmbeddingIdentity(UNKNOWN_ENCODER, 0, 0L)

        fun of(encoderId: String?, algorithmRevision: Int, epoch: Long = 0L): EmbeddingIdentity =
            EmbeddingIdentity(encoderId?.takeIf { it.isNotBlank() } ?: UNKNOWN_ENCODER, algorithmRevision, epoch)
    }
}

/**
 * The subset of [VectorStore] that [RagManager] depends on. Extracted so the
 * manager can be exercised in JVM unit tests without a SQLite database or an
 * Android [Context]; [VectorStore] is the production implementation.
 */
interface VectorIndex {
    fun insertBatch(chunks: List<VectorChunk>): List<VectorChunk>

    /**
     * The encoder identity that would stamp a row written right now. Callers snapshot this once
     * before encoding a multi-chunk operation and pass the same snapshot to [replaceDocument], so
     * every row is stamped with the encoder that actually produced its vector even if the loaded
     * model changes while the operation is in flight.
     */
    fun currentEmbeddingIdentity(): EmbeddingIdentity

    /**
     * Atomically replace a document's rows with [chunks], stamping every row with [identity]:
     * delete the existing rows and insert the new batch in a single transaction, so a concurrent
     * [search] never observes the document missing and a failed insert cannot leave the previous
     * index destroyed.
     *
     * [identity] must be the identity that produced the chunk vectors; the store does not re-read
     * the live encoder here.
     */
    fun replaceDocument(
        documentId: String,
        chunks: List<VectorChunk>,
        identity: EmbeddingIdentity,
    ): List<VectorChunk>
    fun search(queryEmbedding: FloatArray, topK: Int = 5, minScore: Float = 0.0f): List<Pair<VectorChunk, Float>>
    fun search(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        minScore: Float = 0.0f,
        expectedIdentity: EmbeddingIdentity? = null,
    ): List<Pair<VectorChunk, Float>> = search(queryEmbedding, topK, minScore)
    fun deleteByDocument(documentId: String): Int
    fun documentCount(): Int
    fun chunkCount(): Int
    fun clear()

    /**
     * Sets how the current encoder identity is resolved. It is consulted when stamping new rows and
     * when filtering retrieval, so rows produced by an incompatible encoder are never scored.
     */
    fun setEmbeddingIdentityProvider(provider: () -> EmbeddingIdentity)
}

/**
 * The subset of [VectorStore] that [KnowledgePackManager] depends on.
 *
 * Both operations are bounded and avoid materializing embeddings or the full
 * chunk table: [getDocumentSummaries] returns one row per stored document, and
 * [deleteByDocumentPrefix] removes a pack's rows in a single targeted statement.
 */
interface KnowledgePackChunkStore {
    /** One bounded summary per stored document at the current embedding revision. */
    fun getDocumentSummaries(): List<VectorDocumentSummary>

    /** Delete every row whose document id starts with [prefix]; returns rows removed. */
    fun deleteByDocumentPrefix(prefix: String): Int
}

/**
 * SQLite-backed vector store for embedding chunks.
 * Thread-safe via ReentrantLock.
 *
 * Stores embeddings as BLOBs using the FloatArray <-> ByteArray conversion
 * in the companion object. Search performs brute-force cosine similarity
 * (suitable for on-device use with up to thousands of chunks).
 */
class VectorStore(context: Context) : VectorIndex, KnowledgePackChunkStore {

    private val dbHelper = VectorDbHelper(context)
    private val lock = ReentrantLock()

    /**
     * Resolves the encoder identity used to stamp new rows and filter retrieval. Rows stamped with
     * a different identity are never scored, so switching models cannot silently reuse vectors
     * produced by an incompatible encoder.
     */
    @Volatile
    private var identityProvider: () -> EmbeddingIdentity = { EmbeddingIdentity.UNKNOWN }

    override fun setEmbeddingIdentityProvider(provider: () -> EmbeddingIdentity) {
        identityProvider = provider
    }

    private fun currentIdentity(): EmbeddingIdentity = identityProvider()

    override fun currentEmbeddingIdentity(): EmbeddingIdentity = currentIdentity()

    /**
     * Selection for the retrieval view: rows [search] can score for the currently loaded encoder.
     * Requires both the current revision and the current encoder token, so it never returns a row
     * [search] would refuse; with no encoder loaded the token is [EmbeddingIdentity.UNKNOWN] and no
     * real row matches.
     */
    private fun currentChunkSelection(): Pair<String, Array<String>> {
        val identity = currentIdentity()
        val revisionArg = EMBEDDING_REVISION.toString()
        return "$COL_EMBEDDING_REVISION = ? AND $COL_ENCODER_IDENTITY = ?" to
            arrayOf(revisionArg, identity.token)
    }

    /**
     * Selection for rows that are obsolete under EVERY encoder: an older embedding revision
     * (algorithm/schema), or unrecoverable legacy rows without valid encoder identity/dimension,
     * unreachable by [search] no matter which model is loaded. These are the
     * only rows the reclamation cleanup may delete.
     *
     * Rows that merely belong to a *different* valid encoder are deliberately NOT included. They are
     * still valid; the currently loaded model simply cannot score them. Deleting them would destroy
     * the only stored copy of a document's text and force a re-index just because the user switched
     * models temporarily. Switching back to their encoder makes them searchable again.
     */
    private fun obsoleteChunkSelection(): Pair<String, Array<String>> =
        "($COL_EMBEDDING_REVISION != ? OR $COL_ENCODER_IDENTITY = '' OR $COL_ENCODER_IDENTITY IS NULL OR $COL_EMBEDDING_DIM <= 0)" to
            arrayOf(EMBEDDING_REVISION.toString())

    /**
     * Insert a single chunk. Returns the chunk with its assigned id.
     */
    fun insert(chunk: VectorChunk): VectorChunk {
        lock.withLock {
            dbHelper.writableDatabase.insertWithOnConflict(
                TABLE, null, toValues(chunk, currentIdentity()), SQLiteDatabase.CONFLICT_REPLACE
            )
            return chunk
        }
    }

    /**
     * Insert multiple chunks in a single transaction, stamped with the live encoder identity at the
     * time of the call. Multi-chunk ingest goes through [replaceDocument] with a caller-supplied
     * identity snapshot instead, so this single-shot helper is only for tests/simple writes.
     */
    override fun insertBatch(chunks: List<VectorChunk>): List<VectorChunk> {
        lock.withLock {
            val identity = currentIdentity()
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                for (chunk in chunks) {
                    db.insertWithOnConflict(
                        TABLE, null, toValues(chunk, identity), SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return chunks
        }
    }

    /**
     * Atomically replace a document's rows with [chunks].
     *
     * The delete and the inserts share one DB transaction under [lock], so a
     * concurrent search cannot observe the document's rows missing, and a
     * failure before the transaction commits rolls back instead of destroying
     * the previously indexed rows.
     */
    override fun replaceDocument(
        documentId: String,
        chunks: List<VectorChunk>,
        identity: EmbeddingIdentity,
    ): List<VectorChunk> {
        lock.withLock {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete(TABLE, "$COL_DOCUMENT_ID = ?", arrayOf(documentId))
                for (chunk in chunks) {
                    db.insertWithOnConflict(
                        TABLE, null, toValues(chunk, identity), SQLiteDatabase.CONFLICT_REPLACE
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return chunks
        }
    }

    /**
     * Delete every row whose document id starts with [prefix] in a single statement. Used to remove
     * a knowledge pack without materializing its rows; the prefix is matched by `substr` so `%`/`_`
     * characters in a document id are treated literally.
     */
    override fun deleteByDocumentPrefix(prefix: String): Int {
        lock.withLock {
            return dbHelper.writableDatabase.delete(
                TABLE,
                "substr($COL_DOCUMENT_ID, 1, ?) = ?",
                arrayOf(prefix.length.toString(), prefix),
            )
        }
    }

    /**
     * Delete all chunks for a given document. Returns number of rows deleted.
     */
    override fun deleteByDocument(documentId: String): Int {
        lock.withLock {
            return dbHelper.writableDatabase.delete(
                TABLE, "$COL_DOCUMENT_ID = ?", arrayOf(documentId)
            )
        }
    }

    /**
     * Search for the top-K chunks most similar to [queryEmbedding].
     * Returns a list of (chunk, cosineSimilarity) pairs, sorted by descending similarity.
     * Results below [minScore] are filtered out.
     *
     * Rows are streamed from the cursor one at a time and only the current best
     * [topK] chunks are retained, so the full table (text + embedding BLOBs) is
     * never materialized on the heap.
     */
    override fun search(queryEmbedding: FloatArray, topK: Int, minScore: Float): List<Pair<VectorChunk, Float>> =
        search(queryEmbedding, topK, minScore, null)

    override fun search(
        queryEmbedding: FloatArray,
        topK: Int,
        minScore: Float,
        expectedIdentity: EmbeddingIdentity?,
    ): List<Pair<VectorChunk, Float>> {
        if (topK <= 0 || queryEmbedding.isEmpty()) return emptyList()
        lock.withLock {
            val current = currentIdentity()
            if (expectedIdentity != null && expectedIdentity != current) {
                return emptyList()
            }
            val activeToken = expectedIdentity?.token ?: current.token
            val cursor = dbHelper.readableDatabase.query(
                TABLE, null,
                "$COL_EMBEDDING_REVISION = ? AND $COL_ENCODER_IDENTITY = ? AND $COL_EMBEDDING_DIM = ?",
                arrayOf(EMBEDDING_REVISION.toString(), activeToken, queryEmbedding.size.toString()),
                null, null, "$COL_CREATED ASC"
            )
            try {
                return selectTopK(chunkScoreIterator(cursor, queryEmbedding), topK, minScore)
            } finally {
                cursor.close()
            }
        }
    }

    /**
     * Lazily yields `(chunk, cosineSimilarity)` for each row of [cursor], decoding
     * one embedding/chunk at a time. The cursor is already filtered to the current
     * encoder identity. Callers must keep [cursor] open until the iterator is exhausted.
     */
    private fun chunkScoreIterator(
        cursor: Cursor,
        queryEmbedding: FloatArray,
    ): Iterator<Pair<VectorChunk, Float>> {
        val idIndex = cursor.getColumnIndexOrThrow(COL_ID)
        val documentIndex = cursor.getColumnIndexOrThrow(COL_DOCUMENT_ID)
        val chunkIndex = cursor.getColumnIndexOrThrow(COL_CHUNK_INDEX)
        val textIndex = cursor.getColumnIndexOrThrow(COL_TEXT)
        val embeddingIndex = cursor.getColumnIndexOrThrow(COL_EMBEDDING)
        val createdIndex = cursor.getColumnIndexOrThrow(COL_CREATED)

        return object : Iterator<Pair<VectorChunk, Float>> {
            private var hasNextRow = cursor.moveToNext()

            override fun hasNext(): Boolean = hasNextRow

            override fun next(): Pair<VectorChunk, Float> {
                check(hasNextRow) { "No more rows in vector store" }
                val embedding = bytesToFloatArray(cursor.getBlob(embeddingIndex))
                val score = cosineSimilarity(queryEmbedding, embedding)
                val chunk = VectorChunk(
                    id = cursor.getString(idIndex),
                    documentId = cursor.getString(documentIndex),
                    chunkIndex = cursor.getInt(chunkIndex),
                    text = cursor.getString(textIndex),
                    embedding = embedding,
                    createdAt = cursor.getLong(createdIndex),
                )
                hasNextRow = cursor.moveToNext()
                return chunk to score
            }
        }
    }

    /**
     * Number of unique documents stored.
     */
    override fun documentCount(): Int {
        lock.withLock {
            val sql = "SELECT COUNT(DISTINCT $COL_DOCUMENT_ID) FROM $TABLE"
            val cursor = dbHelper.readableDatabase.rawQuery(sql, null)
            try {
                cursor.moveToFirst()
                return cursor.getInt(0)
            } finally {
                cursor.close()
            }
        }
    }

    /**
     * Total number of chunks stored.
     */
    override fun chunkCount(): Int {
        lock.withLock {
            val sql = "SELECT COUNT(*) FROM $TABLE"
            val cursor = dbHelper.readableDatabase.rawQuery(sql, null)
            try {
                cursor.moveToFirst()
                return cursor.getInt(0)
            } finally {
                cursor.close()
            }
        }
    }

    /**
     * Remove all chunks from the store.
     */
    override fun clear() {
        lock.withLock {
            dbHelper.writableDatabase.delete(TABLE, null, null)
        }
    }

    /**
     * Return every stored chunk, including rows written at an older
     * [EMBEDDING_REVISION]. This is the maintenance/deletion read: it lets
     * callers find and remove stale rows that [search] can no longer reach.
     * Retrieval and status paths must use [getCurrentChunks] instead.
     *
     * Materializes the whole table, so prefer targeted queries.
     */
    fun getAllChunks(): List<VectorChunk> {
        lock.withLock {
            val cursor = dbHelper.readableDatabase.query(
                TABLE, null, null, null, null, null, "$COL_CREATED ASC"
            )
            return cursorToList(cursor)
        }
    }

    /**
     * Chunks at [EMBEDDING_REVISION] only. Rows written by an older build are
     * unreachable by [search] and must not be counted as indexed knowledge.
     * Unlike [getAllChunks], this is safe to use for retrieval or status decisions.
     */
    fun getCurrentChunks(): List<VectorChunk> {
        lock.withLock {
            val (selection, args) = currentChunkSelection()
            val cursor = dbHelper.readableDatabase.query(
                TABLE, null, selection, args, null, null, "$COL_CREATED ASC"
            )
            return cursorToList(cursor)
        }
    }

    /**
     * One bounded summary per stored document: the current-revision chunk count, how many of those
     * the currently loaded encoder can score, and a short preview. A single grouped query with a
     * correlated preview subquery keeps memory O(documents) rather than O(chunks) and never decodes
     * an embedding BLOB, so browsing a large knowledge base cannot exhaust the heap.
     */
    override fun getDocumentSummaries(): List<VectorDocumentSummary> {
        lock.withLock {
            val identity = currentIdentity()
            val revisionArg = EMBEDDING_REVISION.toString()
            val sql = """
                SELECT o.$COL_DOCUMENT_ID AS document_id,
                       COUNT(*) AS stored_count,
                       SUM(CASE WHEN o.$COL_EMBEDDING_REVISION = ? AND o.$COL_ENCODER_IDENTITY = ? AND o.$COL_EMBEDDING_DIM > 0 THEN 1 ELSE 0 END) AS searchable_count,
                       MIN(o.$COL_CREATED) AS first_created,
                       (SELECT i.$COL_TEXT FROM $TABLE i
                          WHERE i.$COL_DOCUMENT_ID = o.$COL_DOCUMENT_ID
                            AND i.$COL_EMBEDDING_REVISION = ?
                          ORDER BY i.$COL_CHUNK_INDEX ASC, i.$COL_CREATED ASC
                          LIMIT 1) AS preview,
                       SUM(CASE WHEN o.$COL_EMBEDDING_REVISION != ? OR o.$COL_ENCODER_IDENTITY = '' OR o.$COL_ENCODER_IDENTITY IS NULL OR o.$COL_EMBEDDING_DIM <= 0 THEN 1 ELSE 0 END) AS legacy_count
                FROM $TABLE o
                GROUP BY o.$COL_DOCUMENT_ID
                ORDER BY first_created ASC
            """.trimIndent()
            val cursor = dbHelper.readableDatabase.rawQuery(
                sql, arrayOf(revisionArg, identity.token, revisionArg, revisionArg),
            )
            try {
                val summaries = mutableListOf<VectorDocumentSummary>()
                while (cursor.moveToNext()) {
                    summaries.add(
                        VectorDocumentSummary(
                            documentId = cursor.getString(0),
                            storedChunkCount = cursor.getInt(1),
                            searchableChunkCount = cursor.getInt(2),
                            previewText = (cursor.getString(4) ?: "").take(PREVIEW_MAX_CHARS),
                            createdAt = cursor.getLong(3),
                            legacyChunkCount = cursor.getInt(5),
                        )
                    )
                }
                return summaries
            } finally {
                cursor.close()
            }
        }
    }

    /**
     * Return all chunks for a specific document.
     */
    fun getDocumentChunks(documentId: String): List<VectorChunk> {
        lock.withLock {
            val cursor = dbHelper.readableDatabase.query(
                TABLE, null,
                "$COL_DOCUMENT_ID = ?", arrayOf(documentId),
                null, null, "$COL_CHUNK_INDEX ASC"
            )
            return cursorToList(cursor)
        }
    }

    /**
     * Number of rows written by an older [EMBEDDING_REVISION]: obsolete under every encoder and
     * reclaimable with [deleteObsoleteChunks]. Rows belonging to a different but still valid encoder
     * are NOT counted here.
     */
    fun countObsoleteChunks(): Int {
        val (selection, args) = obsoleteChunkSelection()
        return countRows(selection, args)
    }

    /**
     * Delete rows written at an older [EMBEDDING_REVISION], which no encoder can reach. Rows that
     * merely belong to a different (still valid) encoder are never touched, so this can never
     * destroy a document's only stored text because a different model is loaded. Returns rows
     * deleted.
     */
    fun deleteObsoleteChunks(): Int {
        lock.withLock {
            val (selection, args) = obsoleteChunkSelection()
            return dbHelper.writableDatabase.delete(TABLE, selection, args)
        }
    }

    /**
     * Count rows that have current [EMBEDDING_REVISION] but empty/invalid encoder identity or zero dimension.
     */
    fun countLegacyUnknownChunks(): Int {
        val selection = "$COL_EMBEDDING_REVISION = ? AND ($COL_ENCODER_IDENTITY = '' OR $COL_ENCODER_IDENTITY IS NULL OR $COL_EMBEDDING_DIM <= 0)"
        return countRows(selection, arrayOf(EMBEDDING_REVISION.toString()))
    }

    /**
     * Reclaim unrecoverable legacy rows that have current revision but empty/invalid encoder identity or zero dimension.
     */
    fun deleteLegacyUnknownChunks(): Int {
        lock.withLock {
            val selection = "$COL_EMBEDDING_REVISION = ? AND ($COL_ENCODER_IDENTITY = '' OR $COL_ENCODER_IDENTITY IS NULL OR $COL_EMBEDDING_DIM <= 0)"
            return dbHelper.writableDatabase.delete(TABLE, selection, arrayOf(EMBEDDING_REVISION.toString()))
        }
    }

    /** Rows at [EMBEDDING_REVISION], regardless of encoder: everything currently stored on disk. */
    fun countStoredChunks(): Int =
        countRows("$COL_EMBEDDING_REVISION = ?", arrayOf(EMBEDDING_REVISION.toString()))

    /** Rows [search] can score for the currently loaded encoder. */
    fun countSearchableChunks(): Int {
        val (selection, args) = currentChunkSelection()
        return countRows(selection, args)
    }

    private fun countRows(selection: String, args: Array<String>): Int {
        lock.withLock {
            val cursor = dbHelper.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM $TABLE WHERE $selection",
                args,
            )
            try {
                cursor.moveToFirst()
                return cursor.getInt(0)
            } finally {
                cursor.close()
            }
        }
    }

    // ---- Column / table constants ----

    companion object {
        const val DB_NAME = "prism_vector_store.db"
        const val DB_VERSION = 3
        const val TABLE = "vector_chunks"

        /**
         * Revision of the embedding values stored by this build. Bump this
         * whenever the embedding computation (including pooling/normalization)
         * changes, even if the vector width stays the same: a dimension check
         * would miss a same-width re-embedding. Combined with [COL_ENCODER_IDENTITY],
         * which names the encoder artifact, rows written by a different encoder
         * are never returned by [search].
         */
        const val EMBEDDING_REVISION = 2

        const val COL_ID = "id"
        const val COL_DOCUMENT_ID = "document_id"
        const val COL_CHUNK_INDEX = "chunk_index"
        const val COL_TEXT = "text"
        const val COL_EMBEDDING = "embedding"
        const val COL_EMBEDDING_REVISION = "embedding_revision"
        /** Encoder artifact + algorithm identity token; see [EmbeddingIdentity.token]. */
        const val COL_ENCODER_IDENTITY = "encoder_identity"
        /** Width of the stored embedding, validated against the query width at score time. */
        const val COL_EMBEDDING_DIM = "embedding_dim"
        const val COL_CREATED = "created_at"

        /** Max characters of a document's first chunk retained as a browser preview. */
        const val PREVIEW_MAX_CHARS = 160

        /** True when a row written at [rowRevision] is comparable with this build. */
        fun isCurrentRevision(rowRevision: Int): Boolean = rowRevision == EMBEDDING_REVISION

        /** True when a row stamped with [rowIdentity] is comparable with [current]. */
        fun isCompatibleIdentity(rowIdentity: String?, current: EmbeddingIdentity): Boolean =
            rowIdentity != null && rowIdentity == current.token

        /** True when a row with [rowRevision], [encoderIdentity], and [dimension] is unrecoverable legacy. */
        fun isUnrecoverableLegacy(rowRevision: Int, encoderIdentity: String?, dimension: Int): Boolean =
            rowRevision != EMBEDDING_REVISION || encoderIdentity.isNullOrBlank() || dimension <= 0

        val CREATE_TABLE_SQL: String = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_ID TEXT PRIMARY KEY,
                $COL_DOCUMENT_ID TEXT NOT NULL,
                $COL_CHUNK_INDEX INTEGER NOT NULL,
                $COL_TEXT TEXT NOT NULL,
                $COL_EMBEDDING BLOB NOT NULL,
                $COL_EMBEDDING_REVISION INTEGER NOT NULL DEFAULT 1,
                $COL_ENCODER_IDENTITY TEXT NOT NULL DEFAULT '',
                $COL_EMBEDDING_DIM INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED INTEGER NOT NULL
            )
        """.trimIndent()

        val ADD_EMBEDDING_REVISION_SQL: String =
            "ALTER TABLE $TABLE ADD COLUMN $COL_EMBEDDING_REVISION INTEGER NOT NULL DEFAULT 1"

        val ADD_ENCODER_IDENTITY_SQL: String =
            "ALTER TABLE $TABLE ADD COLUMN $COL_ENCODER_IDENTITY TEXT NOT NULL DEFAULT ''"

        val ADD_EMBEDDING_DIM_SQL: String =
            "ALTER TABLE $TABLE ADD COLUMN $COL_EMBEDDING_DIM INTEGER NOT NULL DEFAULT 0"

        val CREATE_DOCUMENT_INDEX_SQL: String = """
            CREATE INDEX IF NOT EXISTS idx_vector_chunks_document_id
            ON $TABLE ($COL_DOCUMENT_ID)
        """.trimIndent()

        /**
         * Compute cosine similarity between two float arrays.
         * Returns 0 if either vector is zero-magnitude.
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = sqrt(normA) * sqrt(normB)
            return if (denom < 1e-10f) 0f else (dot / denom).coerceIn(-1f, 1f)
        }

        /**
         * Convert a FloatArray to a ByteArray for BLOB storage.
         * Each float is 4 bytes (little-endian).
         */
        fun floatArrayToBytes(arr: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(arr.size * 4)
            for (v in arr) {
                buffer.putFloat(v)
            }
            return buffer.array()
        }

        /**
         * Convert a ByteArray back to a FloatArray.
         */
        fun bytesToFloatArray(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes)
            val count = bytes.size / 4
            val result = FloatArray(count)
            for (i in 0 until count) {
                result[i] = buffer.getFloat()
            }
            return result
        }
    }

    // ---- Cursor helpers ----

    private fun cursorToList(cursor: Cursor): List<VectorChunk> {
        try {
            val list = mutableListOf<VectorChunk>()
            while (cursor.moveToNext()) {
                list.add(cursorToChunk(cursor))
            }
            return list
        } finally {
            cursor.close()
        }
    }

    private fun cursorToChunk(c: Cursor): VectorChunk = VectorChunk(
        id = c.getString(c.getColumnIndexOrThrow(COL_ID)),
        documentId = c.getString(c.getColumnIndexOrThrow(COL_DOCUMENT_ID)),
        chunkIndex = c.getInt(c.getColumnIndexOrThrow(COL_CHUNK_INDEX)),
        text = c.getString(c.getColumnIndexOrThrow(COL_TEXT)),
        embedding = bytesToFloatArray(c.getBlob(c.getColumnIndexOrThrow(COL_EMBEDDING))),
        createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED)),
    )

    // ---- Value mapping ----

    private fun toValues(chunk: VectorChunk, identity: EmbeddingIdentity): ContentValues = ContentValues(9).apply {
        put(COL_ID, chunk.id)
        put(COL_DOCUMENT_ID, chunk.documentId)
        put(COL_CHUNK_INDEX, chunk.chunkIndex)
        put(COL_TEXT, chunk.text)
        put(COL_EMBEDDING, floatArrayToBytes(chunk.embedding))
        put(COL_EMBEDDING_REVISION, EMBEDDING_REVISION)
        put(COL_ENCODER_IDENTITY, identity.token)
        put(COL_EMBEDDING_DIM, chunk.embedding.size)
        put(COL_CREATED, chunk.createdAt)
    }

    // ---- SQLiteOpenHelper ----

    private class VectorDbHelper(context: Context) : SQLiteOpenHelper(
        context, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE_SQL)
            db.execSQL(CREATE_DOCUMENT_INDEX_SQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // Rows that predate embedding revisioning become revision 1 and
                // are therefore never returned by search().
                db.execSQL(ADD_EMBEDDING_REVISION_SQL)
            }
            if (oldVersion < 3) {
                // Rows that predate the encoder-identity contract carry an empty
                // identity and are treated as incompatible with every encoder.
                db.execSQL(ADD_ENCODER_IDENTITY_SQL)
                db.execSQL(ADD_EMBEDDING_DIM_SQL)
            }
        }
    }

}

