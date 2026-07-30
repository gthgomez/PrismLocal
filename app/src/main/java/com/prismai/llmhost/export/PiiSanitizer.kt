package com.prismai.llmhost.export

object PiiSanitizer {

    private val EMAIL_REGEX = Regex("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}")
    private val IP_REGEX = Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b")
    private val PHONE_REGEX = Regex("\\b(?:\\+?\\d{1,3}[- .]?)?\\(?\\d{3}\\)?[- .]?\\d{3}[- .]?\\d{4}\\b")
    private val API_KEY_REGEX = Regex("(?i)\\b(?:sk-[a-zA-Z0-9]{20,}|bearer\\s+[a-zA-Z0-9._-]+|key-[a-zA-Z0-9]{16,})\\b")
    private val FILE_PATH_REGEX = Regex("(?i)(?:[a-z]:\\\\[^\\s:]+|/data/user/0/[^\\s:]+|/storage/emulated/0/[^\\s:]+)")
    private val CREDIT_CARD_REGEX = Regex("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\\b")
    private val SSN_REGEX = Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")

    /**
     * Sanitizes raw text before export to protect user privacy while preserving code syntax.
     */
    fun sanitize(text: String): String {
        var clean = text
        clean = EMAIL_REGEX.replace(clean, "[REDACTED_EMAIL]")
        clean = PHONE_REGEX.replace(clean, "[REDACTED_PHONE]")
        clean = API_KEY_REGEX.replace(clean, "[REDACTED_API_KEY]")
        clean = FILE_PATH_REGEX.replace(clean, "[REDACTED_PATH]")
        clean = CREDIT_CARD_REGEX.replace(clean, "[REDACTED_CC]")
        clean = SSN_REGEX.replace(clean, "[REDACTED_SSN]")

        // Code-safe IP sanitization: ignore loopback (127.0.0.1, 0.0.0.0)
        clean = IP_REGEX.replace(clean) { match ->
            val ip = match.value
            if (ip == "127.0.0.1" || ip == "0.0.0.0") ip else "[REDACTED_IP]"
        }

        return clean
    }
}
