package com.example.llmhost

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite-backed MemoryStore implementation.
 * Thread-safe via ReentrantLock. All public methods acquire the lock.
 */
class SqlMemoryStore(context: Context) : MemoryStore {

    private val dbHelper = MemoryDbHelper(context)
    private val lock = ReentrantLock()

    // ---- MemoryStore implementation ----

    override fun insert(fact: MemoryFact): MemoryFact {
        lock.withLock {
            dbHelper.writableDatabase.insertWithOnConflict(
                TABLE, null, toValues(fact), SQLiteDatabase.CONFLICT_REPLACE
            )
            return fact
        }
    }

    override fun update(id: String, fact: String, confidence: Float): Boolean {
        lock.withLock {
            val values = ContentValues(2).apply {
                put(COL_FACT, fact)
                put(COL_CONFIDENCE, confidence.toDouble())
            }
            return dbHelper.writableDatabase.update(
                TABLE, values, "$COL_ID = ?", arrayOf(id)
            ) > 0
        }
    }

    override fun markDecayed(id: String): Boolean {
        lock.withLock {
            val values = ContentValues(1).apply { put(COL_DECAYED, 1) }
            return dbHelper.writableDatabase.update(
                TABLE, values, "$COL_ID = ?", arrayOf(id)
            ) > 0
        }
    }

    override fun delete(id: String): Boolean {
        lock.withLock {
            return dbHelper.writableDatabase.delete(
                TABLE, "$COL_ID = ?", arrayOf(id)
            ) > 0
        }
    }

    override fun getAllActive(): List<MemoryFact> {
        lock.withLock {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                TABLE, null, "$COL_DECAYED = 0",
                null, null, null, "$COL_LAST_ACCESSED DESC"
            )
            return cursorToList(cursor)
        }
    }

    override fun queryRelevant(query: String, limit: Int): List<MemoryMatch> {
        lock.withLock {
            return getAllActive()
                .map { MemoryMatch(it, MemoryRetriever.score(query, it.fact)) }
                .filter { it.score > 0f }
                .sortedByDescending { it.score }
                .take(limit)
        }
    }

    override fun decayOldMemories(olderThanMillis: Long): Int {
        lock.withLock {
            val cutoff = System.currentTimeMillis() - olderThanMillis
            val values = ContentValues(1).apply { put(COL_DECAYED, 1) }
            return dbHelper.writableDatabase.update(
                TABLE, values,
                "$COL_CREATED < ? AND $COL_LAST_ACCESSED < ? AND $COL_DECAYED = 0",
                arrayOf(cutoff.toString(), cutoff.toString())
            )
        }
    }

    override fun findSimilar(fact: String, threshold: Float): List<MemoryFact> {
        lock.withLock {
            val queryTokens = tokenize(fact)
            if (queryTokens.isEmpty()) return emptyList()
            return getAllActive().filter { memory ->
                val memoryTokens = tokenize(memory.fact)
                if (memoryTokens.isEmpty()) return@filter false
                val overlap = queryTokens.count { it in memoryTokens }
                val jaccard = overlap.toFloat() /
                    (queryTokens.size + memoryTokens.size - overlap).coerceAtLeast(1)
                jaccard > threshold
            }
        }
    }

    override fun activeCount(): Int = countWhere("$COL_DECAYED = 0")
    override fun totalCount(): Int = countWhere(null)

    override fun touch(id: String): MemoryFact? {
        lock.withLock {
            val db = dbHelper.writableDatabase
            // read current row
            val cursor = db.query(
                TABLE, null, "$COL_ID = ?", arrayOf(id),
                null, null, null
            )
            val existing = cursorToSingle(cursor) ?: return null

            // update last_accessed_at and increment access_count
            val now = System.currentTimeMillis()
            val values = ContentValues(2).apply {
                put(COL_LAST_ACCESSED, now)
                put(COL_ACCESS_COUNT, existing.accessCount + 1)
            }
            db.update(TABLE, values, "$COL_ID = ?", arrayOf(id))
            return existing.copy(
                lastAccessedAt = now,
                accessCount = existing.accessCount + 1
            )
        }
    }

    // ---- Cursor helpers ----

    private fun cursorToList(cursor: Cursor): List<MemoryFact> {
        try {
            val list = mutableListOf<MemoryFact>()
            while (cursor.moveToNext()) {
                list.add(cursorToFact(cursor))
            }
            return list
        } finally {
            cursor.close()
        }
    }

    private fun cursorToSingle(cursor: Cursor): MemoryFact? {
        try {
            return if (cursor.moveToFirst()) cursorToFact(cursor) else null
        } finally {
            cursor.close()
        }
    }

    private fun cursorToFact(c: Cursor): MemoryFact = MemoryFact(
        id = c.getString(c.getColumnIndexOrThrow(COL_ID)),
        fact = c.getString(c.getColumnIndexOrThrow(COL_FACT)),
        category = MemoryCategory.valueOf(
            c.getString(c.getColumnIndexOrThrow(COL_CATEGORY))
        ),
        confidence = c.getFloat(c.getColumnIndexOrThrow(COL_CONFIDENCE)),
        sourceChatId = c.getString(c.getColumnIndexOrThrow(COL_SOURCE_CHAT_ID)),
        createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED)),
        lastAccessedAt = c.getLong(c.getColumnIndexOrThrow(COL_LAST_ACCESSED)),
        accessCount = c.getInt(c.getColumnIndexOrThrow(COL_ACCESS_COUNT)),
        decayed = c.getInt(c.getColumnIndexOrThrow(COL_DECAYED)) == 1,
    )

    // ---- Value mapping ----

    private fun toValues(fact: MemoryFact): ContentValues = ContentValues(9).apply {
        put(COL_ID, fact.id)
        put(COL_FACT, fact.fact)
        put(COL_CATEGORY, fact.category.name)
        put(COL_CONFIDENCE, fact.confidence.toDouble())
        put(COL_SOURCE_CHAT_ID, fact.sourceChatId)
        put(COL_CREATED, fact.createdAt)
        put(COL_LAST_ACCESSED, fact.lastAccessedAt)
        put(COL_ACCESS_COUNT, fact.accessCount)
        put(COL_DECAYED, if (fact.decayed) 1 else 0)
    }

    // ---- Utilities ----

    private fun countWhere(where: String?): Int {
        lock.withLock {
            val sql = "SELECT COUNT(*) FROM $TABLE" +
                (where?.let { " WHERE $it" } ?: "")
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
     * Tokenizes text for similarity matching.
     * Mirrors the tokenize logic used by [MemoryRetriever].
     */
    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .toSet()

    // ---- SQLiteOpenHelper ----

    private class MemoryDbHelper(context: Context) : SQLiteOpenHelper(
        context, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE_SQL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // No schema upgrades yet — database version 1
        }
    }

    // ---- Column / table constants ----

    private companion object {
        const val DB_NAME = "prism_memory.db"
        const val DB_VERSION = 1
        const val TABLE = "memories"

        const val COL_ID = "id"
        const val COL_FACT = "fact"
        const val COL_CATEGORY = "category"
        const val COL_CONFIDENCE = "confidence"
        const val COL_SOURCE_CHAT_ID = "source_chat_id"
        const val COL_CREATED = "created_at"
        const val COL_LAST_ACCESSED = "last_accessed_at"
        const val COL_ACCESS_COUNT = "access_count"
        const val COL_DECAYED = "decayed"

        const val CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_ID TEXT PRIMARY KEY,
                $COL_FACT TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL DEFAULT 'GENERAL',
                $COL_CONFIDENCE REAL NOT NULL DEFAULT 0.5,
                $COL_SOURCE_CHAT_ID TEXT,
                $COL_CREATED INTEGER NOT NULL,
                $COL_LAST_ACCESSED INTEGER NOT NULL,
                $COL_ACCESS_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_DECAYED INTEGER NOT NULL DEFAULT 0
            )
        """
    }
}
