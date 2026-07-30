package com.prismai.llmhost.export

import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import org.json.JSONArray
import org.json.JSONObject

object DpoDatasetExporter {

    /**
     * Extracts DPO preference pairs (prompt, chosen, rejected) from multi-turn chat sessions
     * where user clicked 'Regenerate' to produce multiple candidate assistant responses.
     */
    fun exportDpoPairs(
        session: ChatSession,
        messages: List<TranscriptMessage>,
        sanitizePii: Boolean = true
    ): String {
        val records = JSONArray()
        var lastUserText = ""

        messages.forEach { msg ->
            when (msg.role) {
                TranscriptRole.USER -> {
                    lastUserText = msg.text
                }
                TranscriptRole.ASSISTANT -> {
                    if (lastUserText.isNotBlank() && msg.regenerationHistory.isNotEmpty()) {
                        val chosenText = if (sanitizePii) PiiSanitizer.sanitize(msg.text) else msg.text
                        val rejectedArray = JSONArray()

                        msg.regenerationHistory.forEach { rejected ->
                            val cleanRejected = if (sanitizePii) PiiSanitizer.sanitize(rejected) else rejected
                            if (cleanRejected != chosenText && cleanRejected.isNotBlank()) {
                                rejectedArray.put(cleanRejected)
                            }
                        }

                        if (rejectedArray.length() > 0) {
                            val record = JSONObject()
                                .put("id", session.id)
                                .put("model", session.modelId ?: "unknown")
                                .put("prompt", if (sanitizePii) PiiSanitizer.sanitize(lastUserText) else lastUserText)
                                .put("chosen", chosenText)
                                .put("rejected", rejectedArray)
                            records.put(record)
                        }
                    }
                }
                TranscriptRole.TOOL -> {}
            }
        }
        return records.toString(2)
    }
}
