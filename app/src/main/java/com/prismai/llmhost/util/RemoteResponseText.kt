package com.prismai.llmhost.util

import java.io.Reader

/**
 * Upper bound for a response body that is parsed (JSON/HTML) after reading.
 * Generous enough for catalog pages, but prevents a hostile or runaway server
 * from forcing an unbounded heap allocation via `readText()`.
 */
const val MAX_REMOTE_RESPONSE_CHARS: Int = 8 * 1024 * 1024

/** Upper bound for diagnostic/error bodies that are only surfaced in messages. */
const val MAX_REMOTE_ERROR_BODY_CHARS: Int = 64 * 1024

/** Thrown when a remote body exceeds its configured character budget. */
class ResponseTooLargeException(maxChars: Int) :
    IllegalStateException("Remote response exceeded $maxChars characters")

private const val READ_CHUNK_CHARS = 8192

/**
 * Reads a response body, failing closed with [ResponseTooLargeException] instead
 * of allocating an unbounded string. Use before parsing JSON/HTML so a truncated
 * body is never silently accepted.
 */
fun Reader.readTextBounded(maxChars: Int = MAX_REMOTE_RESPONSE_CHARS): String {
    val out = StringBuilder(minOf(maxChars, READ_CHUNK_CHARS))
    val buffer = CharArray(READ_CHUNK_CHARS)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > maxChars) throw ResponseTooLargeException(maxChars)
        out.append(buffer, 0, read)
    }
    return out.toString()
}

/**
 * Reads at most [maxChars] characters, silently truncating anything longer.
 * Intended for error/diagnostic text that is only interpolated into a message,
 * where a valid full body is not required.
 */
fun Reader.readTextTruncated(maxChars: Int = MAX_REMOTE_ERROR_BODY_CHARS): String {
    val out = StringBuilder(minOf(maxChars, READ_CHUNK_CHARS))
    val buffer = CharArray(READ_CHUNK_CHARS)
    var total = 0
    while (total < maxChars) {
        val read = read(buffer, 0, minOf(buffer.size, maxChars - total))
        if (read == -1) break
        total += read
        out.append(buffer, 0, read)
    }
    return out.toString()
}
