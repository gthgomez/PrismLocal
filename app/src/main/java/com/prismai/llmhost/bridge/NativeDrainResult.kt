package com.prismai.llmhost.bridge
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

/**
 * Holder for a combined drain+decode+state operation.
 * Populated by native code via JNI field access.
 *
 * Pre-allocated buffers let JNI write via SetIntArrayRegion / SetByteArrayRegion
 * without NewIntArray / NewStringUTF on the fast path.
 */
class NativeDrainResult {
    // Pre-allocated buffers sized to ring buffer capacity and worst-case UTF-8 expansion.
    // JNI writes directly into these via SetIntArrayRegion / SetByteArrayRegion.
    @JvmField val tokensBuffer: IntArray = IntArray(TOKENS_CAPACITY)
    @JvmField var tokensCount: Int = 0

    @JvmField val textBuffer: ByteArray = ByteArray(TEXT_CAPACITY)
    @JvmField var textCount: Int = 0

    // Overflow fallback: JNI sets this when text exceeds textBuffer capacity.
    // Kotlin checks this field; if non-empty, it takes priority over textBuffer.
    @JvmField var textOverflow: String = ""

    @JvmField var state: Int = 0
    @JvmField var promptTokens: Int = 0

    @JvmField var ttftMs: Long = 0L
    @JvmField var tokensPerSec: Float = 0.0f
    @JvmField var activeThreads: Int = 0

    companion object {
        // Matches kTokenCapacity (2048) in Engine.cpp.
        // Drain call passes maxTokens=128, but this buffer must accommodate
        // any future increase without silent truncation.
        const val TOKENS_CAPACITY = 2048

        // Worst-case: 128 tokens × 20 bytes/token UTF-8 = 2560.
        // Rounded to 4096 for power-of-2 alignment and headroom for larger drains.
        const val TEXT_CAPACITY = 4096
    }
}
