package com.prismai.llmhost.util

/**
 * Canonical byte-oriented helpers shared by UI and non-UI call sites.
 *
 * These exist to keep a single implementation of the small byte conversions that
 * were previously copied between packages. Kotlin visibility is [internal] so the
 * helpers stay app-private while remaining unit-testable.
 */

/**
 * Formats a byte count for compact display.
 *
 * Values of at least 1 MiB render as megabytes with one decimal (for example
 * "1.5 MB"); smaller values render as an exact byte count (for example "512 B").
 * The decimal separator follows the default locale, matching the previous
 * per-call-site implementations.
 */
internal fun formatByteSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        "%.1f MB".format(mb)
    } else {
        "$bytes B"
    }
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

/** Lowercase hexadecimal representation of these bytes. */
internal fun ByteArray.toHex(): String {
    val result = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xFF
        result[i * 2] = HEX_CHARS[b ushr 4]
        result[i * 2 + 1] = HEX_CHARS[b and 0x0F]
    }
    return String(result)
}
