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

    fun put(entry: HuggingFaceModelEntry) = synchronized(this) {
        val rows = findAll().map { serialize(it) }.filterNot { idOf(it) == entry.id }
        backend.writeAll(rows + serialize(entry))
    }

    fun find(id: String): HuggingFaceModelEntry? = synchronized(this) {
        findAll().firstOrNull { entry ->
            entry.id == id ||
                HuggingFaceModelCatalog.legacyCustomEntryId(entry.repoId, entry.fileName) == id
        }
    }

    fun findAll(): List<HuggingFaceModelEntry> = synchronized(this) {
        backend.readAll().mapNotNull { deserialize(it) }
    }

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
                    // commit(), not apply(): the caller enqueues WorkManager right
                    // after, and a queued download must outlive an immediate process
                    // kill -- the exact scenario this store exists to survive.
                    editor.commit()
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
            entry.expectedSha256?.let { put("expectedSha256", it) }
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

        /** Restored ids become filename components, so only safe, non-traversing ids survive. */
        private val SAFE_ID = Regex("[A-Za-z0-9._-]+")

        fun deserialize(json: String): HuggingFaceModelEntry? = runCatching {
            val o = JSONObject(json)
            val id = o.optString("id")
            val fileName = o.optString("fileName")
            // Integrity pin: re-validate on the way in, treating a malformed value
            // as absent so it fails closed instead of being trusted verbatim.
            val expectedSha256 = o.optString("expectedSha256", "").ifBlank { null }
                .takeIf { decideDownloadIntegrity(it, providerHash = null, curated = false) == DownloadIntegrityDecision.VERIFY_TRUSTED_PIN }
            HuggingFaceModelEntry(
                id = id,
                name = o.optString("name"),
                repoId = o.optString("repoId"),
                fileName = fileName,
                expectedBytes = o.optLong("expectedBytes", -1L),
                expectedSha256 = expectedSha256,
                license = o.optString("license", "Community / Unspecified"),
                parameters = o.optString("parameters", "Custom"),
                quantization = o.optString("quantization", "Auto"),
                notes = o.optString("notes", ""),
                curated = false,
            ).takeIf {
                it.id.isNotBlank() &&
                    !it.id.contains("..") &&
                    it.id.matches(SAFE_ID) &&
                    it.fileName.isNotBlank() &&
                    it.repoId.isNotBlank()
            }
        }.getOrNull()
    }
}
