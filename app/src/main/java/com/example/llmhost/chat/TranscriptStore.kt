package com.example.llmhost.chat

import android.content.Context
import android.util.Log
import com.example.llmhost.AgentSanitizer
import com.example.llmhost.ChatTitles
import com.example.llmhost.TranscriptMessage
import com.example.llmhost.TranscriptRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * File I/O for chat transcripts and the chat index.
 *
 * Owns no mutable state — every call reads or writes from the filesystem directly.
 */
class TranscriptStore(private val context: Context) {

    companion object {
        private const val TAG = "TranscriptStore"
        private const val LEGACY_TRANSCRIPT_FILE_NAME = "chat_transcript.json"
        private const val CHAT_INDEX_FILE_NAME = "chat_index.json"
        private const val CHAT_DIR_NAME = "chats"
    }

    // ── File path helpers ────────────────────────────────────────────────

    fun chatIndexFile(): File = File(context.filesDir, CHAT_INDEX_FILE_NAME)

    fun chatDirectory(): File =
        File(context.filesDir, CHAT_DIR_NAME).apply {
            if (!isDirectory) {
                mkdirs()
            }
        }

    fun transcriptFile(chatId: String): File =
        File(chatDirectory(), "${AgentSanitizer.sanitizeChatId(chatId)}.json")

    fun legacyTranscriptFile(): File = File(context.filesDir, LEGACY_TRANSCRIPT_FILE_NAME)

    // ── Transcript file read / write ─────────────────────────────────────

    fun readTranscriptFile(file: File): List<TranscriptMessage> =
        runCatching {
            if (!file.isFile) {
                return@runCatching emptyList<TranscriptMessage>()
            }
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val role = runCatching {
                        TranscriptRole.valueOf(item.getString("role"))
                    }.getOrDefault(TranscriptRole.ASSISTANT)
                    add(
                        TranscriptMessage(
                            id = item.getLong("id"),
                            role = role,
                            text = item.optString("text", ""),
                            summary = item.optString("summary").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "failed to load transcript file=${file.absolutePath}", error)
        }.getOrDefault(emptyList())

    fun writeTranscriptFile(file: File, messages: List<TranscriptMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            val obj = JSONObject()
                .put("id", message.id)
                .put("role", message.role.name)
                .put("text", message.text)
            if (message.summary != null) {
                obj.put("summary", message.summary)
            }
            array.put(obj)
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: context.filesDir, "${file.name}.tmp")
        temp.writeText(array.toString())
        promoteTempFile(temp, file)
    }

    // ── Atomic temp-file promotion ──────────────────────────────────────

    fun promoteTempFile(temp: File, target: File) {
        runCatching {
            Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        }.getOrElse {
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "Failed to promote temp file ${temp.absolutePath}" }
            }
        }
    }

    // ── Recovery transcript (in-memory stream snapshot → filesystem) ────

    /**
     * Writes the current stream snapshot to a recovery file if non-empty.
     * Returns the absolute path of the recovery file, or null if nothing was written.
     */
    fun saveRecoveryTranscript(snapshotText: String): String? {
        if (snapshotText.isEmpty()) return null
        val transcript = File(context.filesDir, "recovery_transcript.txt")
        transcript.writeText(snapshotText)
        return transcript.absolutePath
    }

    // ── Title helpers ────────────────────────────────────────────────────

    fun firstUserTitle(messages: List<TranscriptMessage>): String? =
        messages.firstOrNull { it.role == TranscriptRole.USER }
            ?.text
            ?.let(ChatTitles::fromPrompt)
}
