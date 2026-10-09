package com.prismai.llmhost.ui

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * App-private durable storage for large draft attachment payloads.
 *
 * Keeps Android's saved instance state Bundle strictly bounded across process death
 * and activity recreation, eliminating the risk of TransactionTooLargeException
 * when drafts across multiple chats contain large attachments (each up to 16,000 characters).
 */
object DraftPayloadStore {
    private var baseDir: File? = null
    private val memoryFallback = ConcurrentHashMap<String, String>()

    fun init(filesDir: File) {
        val dir = File(filesDir, "draft_payloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        baseDir = dir
    }

    fun resetForTesting(testDir: File? = null) {
        baseDir = testDir
        memoryFallback.clear()
    }

    private fun fileForKey(key: String): File? {
        val dir = baseDir ?: return null
        val hash = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(key.toByteArray(Charsets.UTF_8))
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault(key.hashCode().toString())
        return File(dir, "$hash.txt")
    }

    fun put(key: String, text: String) {
        val file = fileForKey(key)
        if (file != null) {
            runCatching { file.writeText(text, Charsets.UTF_8) }
        } else {
            memoryFallback[key] = text
        }
    }

    fun get(key: String): String? {
        val file = fileForKey(key)
        if (file != null && file.exists()) {
            val read = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            if (read != null) return read
        }
        return memoryFallback[key]
    }

    fun remove(key: String) {
        fileForKey(key)?.delete()
        memoryFallback.remove(key)
    }

    fun clear() {
        baseDir?.listFiles()?.forEach { it.delete() }
        memoryFallback.clear()
    }
}
