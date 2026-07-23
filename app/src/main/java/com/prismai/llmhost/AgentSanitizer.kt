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
}
