package com.example.llmhost

/**
 * Holder for a combined drain+decode+state operation.
 * Populated by native code via JNI field access.
 */
class NativeDrainResult {
    @JvmField var tokens: IntArray = IntArray(0)
    @JvmField var text: String = ""
    @JvmField var state: Int = 0
}
