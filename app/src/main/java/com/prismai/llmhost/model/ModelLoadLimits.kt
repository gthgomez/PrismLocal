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

    /**
     * Models above this size are treated as "large" for scoring only: they use a
     * tighter SAFE headroom fraction, so they are more likely to be flagged RISKY.
     * This never blocks a load by itself — RISKY models still load.
     */
    const val LARGE_MODEL_BYTES = 3_000_000_000L
}
