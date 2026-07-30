package com.prismai.llmhost.generation

import com.prismai.llmhost.tools.VoiceIoManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sentence-level token streaming TTS engine.
 * Buffers streaming LLM token chunks and queues speech output sentence-by-sentence.
 */
class TtsStreamer(
    private val voiceIoManager: VoiceIoManager,
) {
    private val buffer = StringBuilder()
    private val active = AtomicBoolean(false)

    fun start() {
        buffer.clear()
        active.set(true)
        voiceIoManager.initTts()
    }

    fun onToken(token: String) {
        if (!active.get()) return

        synchronized(buffer) {
            buffer.append(token)
            val currentText = buffer.toString()

            // Detect sentence boundaries: ".", "!", "?", "\n" followed by space or end
            var lastSentenceEnd = -1
            var index = 0
            while (index < currentText.length) {
                val ch = currentText[index]
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                    if (index == currentText.length - 1 || currentText[index + 1].isWhitespace()) {
                        lastSentenceEnd = index + 1
                    }
                }
                index++
            }

            if (lastSentenceEnd > 0) {
                val sentence = currentText.substring(0, lastSentenceEnd).trim()
                buffer.delete(0, lastSentenceEnd)

                if (sentence.isNotBlank() && sentence.length >= 3) {
                    voiceIoManager.speakQueueAdd(sentence)
                }
            }
        }
    }

    fun finish() {
        if (!active.getAndSet(false)) return

        val remaining = synchronized(buffer) {
            val text = buffer.toString().trim()
            buffer.clear()
            text
        }

        if (remaining.isNotBlank() && remaining.length >= 2) {
            voiceIoManager.speakQueueAdd(remaining)
        }
    }

    fun stop() {
        active.set(false)
        synchronized(buffer) { buffer.clear() }
        voiceIoManager.stopSpeaking()
    }
}
