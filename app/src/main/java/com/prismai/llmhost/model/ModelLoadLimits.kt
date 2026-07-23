package com.prismai.llmhost.model
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

/**
 * Shared model load / fitness limits for [ModelManager] and [ModelReadinessAssessor].
 * Keep a single source of truth — do not duplicate hard-cap constants elsewhere.
 */
object ModelLoadLimits {
    /** Preflight hard cap: large enough for Bonsai-27B Q1_0 weights (~3.54 GiB). */
    const val HARD_CAP_BYTES = 4200L * 1024L * 1024L

    /** Dynamic reserve subtracted from available RAM when computing load budget. */
    const val MEMORY_RESERVE_BYTES = 768L * 1024L * 1024L

    /** Models above this size use flagship available-RAM tier gates. */
    const val LARGE_MODEL_BYTES = 3_000_000_000L

    /**
     * Large models need at least this much available RAM after unload or they are TOO_LARGE.
     * Uses available (not total) device RAM — Android never exposes full RAM to one app.
     */
    const val LARGE_MODEL_AVAILABLE_TOO_LARGE = 6_000_000_000L

    /**
     * Large models with available RAM below this (but above [LARGE_MODEL_AVAILABLE_TOO_LARGE])
     * are at most RISKY, never SAFE.
     */
    const val LARGE_MODEL_AVAILABLE_RISKY = 10_000_000_000L
}
