package com.prismai.llmhost.work

/**
 * Play Store distribution configuration.
 * Hard-disables developer work mode and local/remote process execution.
 */
object DistributionConfigProvider : DistributionConfig {
    override val developerWorkMode: Boolean = false
    override val localProcessExecutionAvailable: Boolean = false
    override val babelHostExecutionAvailable: Boolean = false
}
