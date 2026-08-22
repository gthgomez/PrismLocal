package com.prismai.llmhost.work

/**
 * Developer distribution configuration (Prism Dev).
 * Enables developer work mode and Babel host bridge.
 * Local process execution remains disabled in PR 1 foundation phase.
 */
object DistributionConfigProvider : DistributionConfig {
    override val developerWorkMode: Boolean = true
    override val localProcessExecutionAvailable: Boolean = false // Gated: no local shell until PR 2
    override val babelHostExecutionAvailable: Boolean = true
}
