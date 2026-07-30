package com.prismai.llmhost.export

import org.junit.Assert.*
import org.junit.Test

class PiiSanitizerTest {

    @Test
    fun testRedactsPiiPattern() {
        val rawInput = "Contact user@example.com at 192.168.1.1 or phone 555-123-4567 using key sk-abcdef1234567890123456"
        val sanitized = PiiSanitizer.sanitize(rawInput)

        assertTrue(sanitized.contains("[REDACTED_EMAIL]"))
        assertTrue(sanitized.contains("[REDACTED_IP]"))
        assertTrue(sanitized.contains("[REDACTED_PHONE]"))
        assertTrue(sanitized.contains("[REDACTED_API_KEY]"))
        assertFalse(sanitized.contains("user@example.com"))
        assertFalse(sanitized.contains("192.168.1.1"))
    }
}
