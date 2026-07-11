package com.example.llmhost

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

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
 * SQLite-backed vector store for embedding chunks.
 * Thread-safe via ReentrantLock.
 *
 * Stores embeddings as BLOBs using the FloatArray <-> ByteArray conversion
 * in the companion object. Search performs brute-force cosine similarity
 * (suitable for on-device use with up to thousands of chunks).
 */
class VectorStore(context: Context) {

    private val dbHelper = VectorDbHelper(context)
    private val lock = ReentrantLock()

    /**
     * Insert a single chunk. Returns the chunk with its assigned id.
     */
    fun insert(chunk: VectorChunk): VectorChunk {
        lock.withLock {
            dbHelper.writableDatabase.insertWithOnConflict(
                TABLE, null, toValues(chunk), SQLiteDatabase.CONFLICT_REPLACE
            )
            return chunk
        }
    }

    /**
     * Insert multiple chunks in a single transaction.
     */
    fun insertBatch(chunks: List<VectorChunk>): List<VectorChunk> {
        lock.withLock {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                for (chunk in chunks) {
                    db.insertWithOnConflict(
                        TABLE, null, toValues(chunk), SQLiteDatabase.CONFLICT_REPLACE
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
     * Delete all chunks for a given document. Returns number of rows deleted.
     */
    fun deleteByDocument(documentId: String): Int {
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
     */
    fun search(queryEmbedding: FloatArray, topK: Int = 5, minScore: Float = 0.0f): List<Pair<VectorChunk, Float>> {
        lock.withLock {
            val all = getAllChunks()
            if (all.isEmpty() || queryEmbedding.isEmpty()) return emptyList()

            val scored = all.map { chunk ->
                chunk to cosineSimilarity(queryEmbedding, chunk.embedding)
            }
                .filter { it.second >= minScore }
                .sortedByDescending { it.second }
                .take(topK)

            return scored
        }
    }

    /**
     * Number of unique documents stored.
     */
    fun documentCount(): Int {
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
    fun chunkCount(): Int {
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
    fun clear() {
        lock.withLock {
            dbHelper.writableDatabase.delete(TABLE, null, null)
        }
    }

    /**
     * Return every stored chunk (used internally by search, also exposed for debugging).
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

    // ---- Column / table constants ----

    companion object {
        const val DB_NAME = "prism_vector_store.db"
        const val DB_VERSION = 1
        const val TABLE = "vector_chunks"

        const val COL_ID = "id"
        const val COL_DOCUMENT_ID = "document_id"
        const val COL_CHUNK_INDEX = "chunk_index"
        const val COL_TEXT = "text"
        const val COL_EMBEDDING = "embedding"
        const val COL_CREATED = "created_at"

        val CREATE_TABLE_SQL: String = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_ID TEXT PRIMARY KEY,
                $COL_DOCUMENT_ID TEXT NOT NULL,
                $COL_CHUNK_INDEX INTEGER NOT NULL,
                $COL_TEXT TEXT NOT NULL,
                $COL_EMBEDDING BLOB NOT NULL,
                $COL_CREATED INTEGER NOT NULL
            )
        """.trimIndent()

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

    private fun toValues(chunk: VectorChunk): ContentValues = ContentValues(6).apply {
        put(COL_ID, chunk.id)
        put(COL_DOCUMENT_ID, chunk.documentId)
        put(COL_CHUNK_INDEX, chunk.chunkIndex)
        put(COL_TEXT, chunk.text)
        put(COL_EMBEDDING, floatArrayToBytes(chunk.embedding))
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
            // No schema upgrades yet — database version 1
        }
    }

}

