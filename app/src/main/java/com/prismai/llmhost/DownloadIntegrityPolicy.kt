package com.prismai.llmhost

/**
 * How a Hugging Face model download should be integrity-checked before import.
 *
 * Kept as a pure, Android-free decision so the policy can be unit-tested directly.
 */
enum class DownloadIntegrityDecision {
    /** A trusted 64-hex SHA-256 is available: verify the file and fail on mismatch. */
    VERIFY_SHA256,

    /** No hash is available for a user-supplied/dynamic import: import as explicitly unverified. */
    UNVERIFIED_DYNAMIC,

    /** No hash is available for a curated/pinned import: refuse to import at all. */
    FAIL_CLOSED,
}

/**
 * Decides the download integrity policy for [expectedSha256] and whether the entry is curated.
 *
 * A curated entry must never be imported without a verified SHA-256; a dynamic import without a
 * published hash is allowed but must be surfaced as unverified. Any value that is not a valid
 * 64-hex SHA-256 is treated as absent, so a malformed pin fails closed for curated entries rather
 * than silently weakening the check.
 */
fun decideDownloadIntegrity(expectedSha256: String?, curated: Boolean): DownloadIntegrityDecision =
    when {
        isValidSha256(expectedSha256) -> DownloadIntegrityDecision.VERIFY_SHA256
        curated -> DownloadIntegrityDecision.FAIL_CLOSED
        else -> DownloadIntegrityDecision.UNVERIFIED_DYNAMIC
    }

/** Returns true only for a 64-character hexadecimal SHA-256 (case-insensitive). */
private fun isValidSha256(value: String?): Boolean {
    val trimmed = value?.trim() ?: return false
    if (trimmed.length != 64) return false
    return trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
