package com.prismai.llmhost.chat
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import java.util.Locale

/**
 * In-memory chat search index.
 *
 * Maps [ChatSession.id] → lowercased haystack string (title + first ~300 chars
 * of each message).  Avoids reading all transcript files on every search.
 */
class ChatSearchIndex {

    private val index = mutableMapOf<String, String>()

    // ── Index maintenance ────────────────────────────────────────────────

    /**
     * Rebuild the entire index from a list of sessions, reading each chat's
     * transcript file through [transcriptStore].
     */
    fun refresh(sessions: List<ChatSession>, transcriptStore: TranscriptStore) {
        index.clear()
        sessions.forEach { session ->
            val haystack = buildString {
                append(session.title)
                append(' ')
                val messages = transcriptStore.readTranscriptFile(transcriptStore.transcriptFile(session.id))
                // Store first ~3000 chars per chat — enough for search matching
                messages.forEach { msg ->
                    append(msg.text.take(300)).append(' ')
                }
            }
            index[session.id] = haystack.lowercase(Locale.US)
        }
    }

    /**
     * Update (or insert) a single chat's entry in the index.
     */
    fun update(chatId: String, messages: List<TranscriptMessage>, sessionTitle: String?) {
        val haystack = buildString {
            append(sessionTitle ?: "").append(' ')
            messages.forEach { msg ->
                append(msg.text.take(300)).append(' ')
            }
        }
        index[chatId] = haystack.lowercase(Locale.US)
    }

    // ── Accessors ────────────────────────────────────────────────────────

    /** Returns the raw lowercased haystack for a chat, or null if not indexed. */
    fun get(chatId: String): String? = index[chatId]

    /** Scorched-earth search — returns (session, score) pairs sorted by score descending. */
    fun search(
        query: String,
        sessions: List<ChatSession>,
        transcriptStore: TranscriptStore,
    ): List<Pair<ChatSession, Int>> {
        val terms = query.lowercase(Locale.US).split(' ').filter { it.length > 1 }
        if (terms.isEmpty()) return emptyList()

        return sessions.mapNotNull { session ->
            val haystack = index[session.id]
                ?: buildString {
                    append(session.title)
                    append(' ')
                    transcriptStore.readTranscriptFile(transcriptStore.transcriptFile(session.id))
                        .forEach { append(it.text).append(' ') }
                }.lowercase(Locale.US)

            val score = terms.count { it in haystack } +
                if (query.lowercase(Locale.US) in session.title.lowercase(Locale.US)) 2 else 0

            if (score <= 0) null else session to score
        }.sortedByDescending { it.second }
    }
}
