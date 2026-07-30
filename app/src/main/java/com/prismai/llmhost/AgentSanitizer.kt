package com.prismai.llmhost
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

object AgentSanitizer {
    /**
     * Sanitizes and validates a Chat ID.
     * Throws IllegalArgumentException if the ID is blank or contains path traversal segments.
     */
    fun sanitizeChatId(chatId: String): String {
        require(chatId.isNotBlank()) { "Chat ID cannot be blank" }
        require(!chatId.contains("..") && !chatId.contains("/") && !chatId.contains("\\")) {
            "Invalid traversal characters in chat ID: $chatId"
        }
        return chatId
    }

    /**
     * Sanitizes a string for use as a safe file name.
     */
    fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        require(clean.isNotBlank()) { "Sanitized filename cannot be blank" }
        require(!clean.contains("..") && !clean.contains("/") && !clean.contains("\\")) {
            "Filename contains invalid characters: $clean"
        }
        return clean
    }
    /**
     * Validates that [file] resides strictly within [contextFilesDir] or [contextExternalFilesDir].
     * Uses canonicalPath and trailing file separators to prevent directory traversal and symlink escape.
     */
    fun isPathWithinSandbox(file: java.io.File, contextFilesDir: java.io.File, contextExternalFilesDir: java.io.File?): Boolean {
        val target = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val internalRoot = runCatching { contextFilesDir.canonicalPath }.getOrNull() ?: return false
        val internalPrefix = if (internalRoot.endsWith(java.io.File.separator)) internalRoot else internalRoot + java.io.File.separator
        if (target.startsWith(internalPrefix) || target == internalRoot) {
            return true
        }
        if (contextExternalFilesDir != null) {
            val externalRoot = runCatching { contextExternalFilesDir.canonicalPath }.getOrNull() ?: return false
            val externalPrefix = if (externalRoot.endsWith(java.io.File.separator)) externalRoot else externalRoot + java.io.File.separator
            if (target.startsWith(externalPrefix) || target == externalRoot) {
                return true
            }
        }
        return false
    }
}
