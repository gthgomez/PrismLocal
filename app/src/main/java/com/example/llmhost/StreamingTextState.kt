package com.example.llmhost

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StreamingTextState {
    private val lock = Any()
    private val builder = StringBuilder(64 * 1024)
    private val mutableSnapshot = MutableStateFlow("")
    val snapshot: StateFlow<String> = mutableSnapshot.asStateFlow()
    private var lastPublish = 0L
    private var activeGenerationId: Int? = null
    private val throttleMs = 33L

    fun beginGeneration(expectedGenerationId: Int? = null) {
        val text = synchronized(lock) {
            builder.clear()
            activeGenerationId = expectedGenerationId
            lastPublish = 0L
            builder.toString()
        }
        mutableSnapshot.value = text
    }

    fun append(chunk: GenerationChunk) {
        val publishText = synchronized(lock) {
            val currentGeneration = activeGenerationId
            if (currentGeneration != null && currentGeneration != chunk.generationId) {
                return
            }
            if (currentGeneration == null) {
                activeGenerationId = chunk.generationId
            }
            if (chunk.text.isNotEmpty()) {
                builder.append(chunk.text)
            }

            val now = SystemClock.uptimeMillis()
            if (now - lastPublish >= throttleMs || chunk.isTerminal) {
                lastPublish = now
                builder.toString()
            } else {
                null
            }
        }
        if (publishText != null) {
            mutableSnapshot.value = publishText
        }
    }

    fun clear() {
        val text = synchronized(lock) {
            builder.clear()
            activeGenerationId = null
            lastPublish = 0L
            builder.toString()
        }
        mutableSnapshot.value = text
    }

    fun snapshotText(): String {
        return synchronized(lock) {
            builder.toString()
        }
    }
}
