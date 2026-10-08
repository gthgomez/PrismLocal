package com.prismai.llmhost

/**
 * How a Hugging Face model download should be integrity-checked before import.
 *
 * Kept as a pure, Android-free decision so the policy can be unit-tested directly.
 */
enum class DownloadIntegrityDecision {
    /** A trusted local pin is available: verify the file against it and fail on mismatch. */
    VERIFY_TRUSTED_PIN,

    /**
     * Dynamic import with only a provider-announced digest: verify against it, but treat it as
     * provider metadata rather than an independently established trust anchor.
     */
    VERIFY_PROVIDER_METADATA,

    /** Dynamic import with no digest at all: import but surface the artifact as unverified. */
    UNVERIFIED_DYNAMIC,

    /** Curated/pinned import with no usable trusted digest: refuse to import at all. */
    FAIL_CLOSED,

    /**
     * The provider's announced digest disagrees with the trusted local pin. Refuse: a remote value
     * must never be preferred over an independently pinned one.
     */
    TRUST_CONFLICT,
}

/**
 * Integrity a completed import can honestly be reported with.
 *
 * [VERIFIED_PINNED] is the only outcome backed by a digest this app independently trusts;
 * [VERIFIED_PROVIDER_METADATA] means the bytes matched what the provider announced, which is
 * useful but not an independent guarantee; [UNVERIFIED] had no digest to check against.
 */
enum class DownloadIntegrity {
    VERIFIED_PINNED,
    VERIFIED_PROVIDER_METADATA,
    UNVERIFIED,
}

/** Maps a successful integrity decision to the outcome to report; null for refusing decisions. */
fun DownloadIntegrityDecision.outcome(): DownloadIntegrity? = when (this) {
    DownloadIntegrityDecision.VERIFY_TRUSTED_PIN -> DownloadIntegrity.VERIFIED_PINNED
    DownloadIntegrityDecision.VERIFY_PROVIDER_METADATA -> DownloadIntegrity.VERIFIED_PROVIDER_METADATA
    DownloadIntegrityDecision.UNVERIFIED_DYNAMIC -> DownloadIntegrity.UNVERIFIED
    DownloadIntegrityDecision.FAIL_CLOSED,
    DownloadIntegrityDecision.TRUST_CONFLICT -> null
}

/**
 * Decides how a download should be verified.
 *
 * [trustedPin] is a digest this app independently trusts: a curated catalog pin or a user-supplied
 * pin. [providerHash] is a digest announced by the remote provider, which is NOT a trust anchor. A
 * valid trusted pin always wins; a provider digest that disagrees with it is rejected rather than
 * silently preferred. Any value that is not a valid 64-hex SHA-256 is treated as absent, so a
 * malformed pin fails closed for curated entries instead of weakening the check.
 */
fun decideDownloadIntegrity(
    trustedPin: String?,
    providerHash: String?,
    curated: Boolean,
): DownloadIntegrityDecision {
    val pin = normalizeSha256(trustedPin)
    val provider = normalizeSha256(providerHash)
    return when {
        pin != null && provider != null && pin != provider -> DownloadIntegrityDecision.TRUST_CONFLICT
        pin != null -> DownloadIntegrityDecision.VERIFY_TRUSTED_PIN
        curated -> DownloadIntegrityDecision.FAIL_CLOSED
        provider != null -> DownloadIntegrityDecision.VERIFY_PROVIDER_METADATA
        else -> DownloadIntegrityDecision.UNVERIFIED_DYNAMIC
    }
}

/** Returns the trimmed lowercase digest when [value] is a 64-character hex SHA-256, else null. */
fun normalizeSha256(value: String?): String? {
    val trimmed = value?.trim() ?: return null
    if (trimmed.length != 64) return null
    if (!trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return trimmed.lowercase()
}
