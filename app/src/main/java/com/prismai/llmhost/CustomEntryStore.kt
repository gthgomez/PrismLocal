package com.prismai.llmhost

import android.content.Context
import org.json.JSONObject

/**
 * Durable storage for user-supplied download specs.
 *
 * A WorkManager request persists only the entry id, but WorkManager's whole
 * purpose is surviving process death. With specs held in a process-local map, a
 * queued custom download failed in doWork with "Model catalog entry not found"
 * after any restart. Backend is injected so this is unit-testable without a
 * Context; the production instance uses SharedPreferences.
 */
class CustomEntryStore(private val backend: Backend) {

    interface Backend {
        fun readAll(): List<String>
        fun writeAll(rows: List<String>)
    }

    fun put(entry: HuggingFaceModelEntry) {
        val rows = findAll().map { serialize(it) }.filterNot { idOf(it) == entry.id }
        backend.writeAll(rows + serialize(entry))
    }

    fun find(id: String): HuggingFaceModelEntry? =
        findAll().firstOrNull { it.id == id }

    fun findAll(): List<HuggingFaceModelEntry> =
        backend.readAll().mapNotNull { deserialize(it) }

    companion object {
        private const val PREFS_NAME = "prism_custom_downloads"

        fun forContext(context: Context): CustomEntryStore {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return CustomEntryStore(object : Backend {
                override fun readAll(): List<String> =
                    prefs.all.values.filterIsInstance<String>()

                override fun writeAll(rows: List<String>) {
                    val editor = prefs.edit().clear()
                    rows.forEachIndexed { i, row -> editor.putString("entry_$i", row) }
                    editor.apply()
                }
            })
        }

        /**
         * Non-durable fallback used before a Context is wired in and by unit tests.
         * Production always calls [forContext].
         */
        fun inMemoryBackend(): Backend = object : Backend {
            private val rows = mutableListOf<String>()
            override fun readAll(): List<String> = rows.toList()
            override fun writeAll(rows: List<String>) {
                this.rows.clear()
                this.rows.addAll(rows)
            }
        }

        fun serialize(entry: HuggingFaceModelEntry): String = JSONObject().apply {
            put("id", entry.id)
            put("name", entry.name)
            put("repoId", entry.repoId)
            put("fileName", entry.fileName)
            put("expectedBytes", entry.expectedBytes)
            put("license", entry.license)
            put("parameters", entry.parameters)
            put("quantization", entry.quantization)
            put("notes", entry.notes)
            // Never restore curated=true: an unverified custom download must not
            // become integrity-gated, nor bypass the fail-closed policy.
            put("curated", false)
        }.toString()

        fun idOf(json: String): String =
            runCatching { JSONObject(json).optString("id") }.getOrDefault("")

        fun deserialize(json: String): HuggingFaceModelEntry? = runCatching {
            val o = JSONObject(json)
            HuggingFaceModelEntry(
                id = o.optString("id"),
                name = o.optString("name"),
                repoId = o.optString("repoId"),
                fileName = o.optString("fileName"),
                expectedBytes = o.optLong("expectedBytes", -1L),
                license = o.optString("license", "Community / Unspecified"),
                parameters = o.optString("parameters", "Custom"),
                quantization = o.optString("quantization", "Auto"),
                notes = o.optString("notes", ""),
                curated = false,
            ).takeIf { it.id.isNotBlank() && it.repoId.isNotBlank() }
        }.getOrNull()
    }
}
