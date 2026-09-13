package com.prismai.llmhost.cloud.prismatix

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object PrismatixSseParser {

    /**
     * Parses an input stream formatted as Prismatix normalized Server-Sent Events (SSE)
     * and invokes [onEvent] for each extracted delta, done, or error.
     *
     * Invariants:
     * - Heartbeats and comment lines (starting with ':') are safely ignored.
     * - Blank lines are ignored.
     * - Valid `content_block_delta` frames emit [PrismatixStreamEvent.Delta].
     * - `[DONE]` frame emits [PrismatixStreamEvent.Done] and closes the stream.
     * - Malformed JSON data frames MUST emit [PrismatixStreamEvent.Error] and abort,
     *   preventing silent truncation or corrupted message persistence.
     *
     * Declared `inline` so a caller already inside a suspending context (for example a
     * `Flow` builder) may perform suspending work in [onEvent] while events are still
     * emitted incrementally as each frame is read. Non-suspending callers are unaffected.
     */
    inline fun parseStream(
        inputStream: InputStream,
        onEvent: (PrismatixStreamEvent) -> Unit,
    ) {
        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
        var line: String?

        try {
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: continue
                if (currentLine.isEmpty() || currentLine.startsWith(":")) {
                    // Ignore empty lines or SSE comments/heartbeats
                    continue
                }

                if (currentLine.startsWith("data:")) {
                    val dataContent = currentLine.substring(5).trim()
                    if (dataContent == "[DONE]") {
                        onEvent(PrismatixStreamEvent.Done)
                        break
                    }

                    val json = try {
                        JSONObject(dataContent)
                    } catch (e: Exception) {
                        onEvent(
                            PrismatixStreamEvent.Error(
                                statusCode = -2,
                                message = "Protocol Error: Malformed SSE data payload '$dataContent'",
                            )
                        )
                        break
                    }

                    val type = json.optString("type")
                    if (type == "content_block_delta") {
                        val deltaObj = json.optJSONObject("delta")
                        val text = deltaObj?.optString("text")
                        if (!text.isNullOrEmpty()) {
                            onEvent(PrismatixStreamEvent.Delta(text))
                        }
                    } else if (json.has("error")) {
                        onEvent(
                            PrismatixStreamEvent.Error(
                                statusCode = 500,
                                message = json.optString("error", "Unknown server error"),
                            )
                        )
                        break
                    }
                }
            }
        } finally {
            try {
                reader.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }
}
