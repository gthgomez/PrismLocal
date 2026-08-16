package com.prismai.llmhost.work

/**
 * Contract describing distribution-specific capability flags.
 * Concrete implementations are provided per product flavor (play / dev).
 */
interface DistributionConfig {
    /** Whether developer work mode features are enabled in this distribution. */
    val developerWorkMode: Boolean

    /** Whether local process/shell execution is enabled in this distribution. */
    val localProcessExecutionAvailable: Boolean

    /** Whether remote Babel host integration is supported in this distribution. */
    val babelHostExecutionAvailable: Boolean
}
