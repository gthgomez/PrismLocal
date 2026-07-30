package com.prismai.llmhost.util

import java.io.File
import java.util.Locale

/**
 * Security and sanitization utilities to prevent UI prompt injection,
 * deceptive BiDi character rendering, and sandbox path traversal attacks.
 */
object SanitizerUtils {

    // Matches zero-width spaces, BiDi directional overrides, and ANSI control sequences
    private val CONTROL_AND_BIDI_REGEX = Regex(
        "[\\u200B-\\u200D\\uFEFF" + // Zero-width spaces & BOM
        "\\u202A-\\u202E\\u2066-\\u2069" + // BiDi directional overrides & embeddings
        "\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F" + // Control characters
        "\\u001B\\[[0-9;]*[a-zA-Z]]", // ANSI escape sequences
    )

    /**
     * Strips control characters, zero-width spaces, BiDi overrides, and ANSI escape sequences
     * from raw text before rendering into UI confirmation dialogs.
     */
    fun stripControlCharacters(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        return CONTROL_AND_BIDI_REGEX.replace(input, "")
    }

    /**
     * Verifies that a target file path resolves within a canonical sandbox root directory.
     * Prevents symlink escape and `..` path traversal attacks.
     */
    fun isPathWithinSandbox(target: File, sandboxRoot: File): Boolean {
        return try {
            val canonicalTarget = target.canonicalFile.toPath()
            val canonicalSandbox = sandboxRoot.canonicalFile.toPath()
            canonicalTarget.startsWith(canonicalSandbox)
        } catch (_: Exception) {
            false
        }
    }
}
