package com.prismai.llmhost.engine.runtime
import com.prismai.llmhost.GenerationSettings

/**
 * Versioned requested/applied/observed model for the native engine load configuration.
 *
 * Truthfulness rule: a configuration value is only "applied" or "observed" when it was
 * actually read back from the native runtime. Anything the runtime cannot report stays
 * `null` (unknown) on [AppliedPlan] rather than echoing a value from [RequestedPlan].
 *
 * This file is intentionally dependency-free (no Android imports) so it can be unit
 * tested on the JVM and reused by both persistence and model-lifecycle code.
 */
object InferencePlan {

    /**
     * Bump when the shape or interpretation of [RequestedPlan]/[AppliedPlan] changes so
     * persisted or cached plans from an older schema are not treated as equivalent.
     */
    const val SCHEMA_VERSION: Int = 1

    /** Canonical adapter key when no LoRA adapter is part of the load. */
    const val NO_ADAPTERS: String = "none"

    /**
     * Canonical key for the LoRA adapters that are part of the load. Native applies
     * adapters at load time, so a change here must force a reload.
     */
    fun adapterKeyFor(settings: GenerationSettings): String =
        if (settings.loraAdapters.isEmpty()) {
            NO_ADAPTERS
        } else {
            settings.loraAdapters.joinToString(",") { (path, scale) -> "$path@$scale" }
        }

    /**
     * Builds the requested plan from user-facing [GenerationSettings].
     *
     * [modelId]/[modelSha256]/[adapterKey] identify what is being loaded; the remaining
     * fields are the load-affecting configuration the user asked for.
     */
    fun fromSettings(
        settings: GenerationSettings,
        modelId: String? = null,
        modelSha256: String? = null,
        adapterKey: String = adapterKeyFor(settings),
    ): RequestedPlan = RequestedPlan(
        version = SCHEMA_VERSION,
        modelId = modelId,
        modelSha256 = modelSha256,
        adapterKey = adapterKey,
        backend = if (settings.useVulkan) BackendPreference.VULKAN else BackendPreference.CPU,
        useVulkan = settings.useVulkan,
        threadCount = settings.threadCount,
        contextLength = settings.contextLength,
        batchSize = settings.batchSize,
        gpuLayers = settings.gpuLayers,
        kvCacheTypeK = settings.kvCacheTypeK,
        kvCacheTypeV = settings.kvCacheTypeV,
        enableFlashAttn = settings.enableFlashAttn,
        maxTokens = settings.maxTokens,
    )

    /**
     * Load key for settings alone (no model identity). Used to decide whether a reload is
     * required; two settings that differ in any load-affecting field produce different keys.
     */
    fun settingsLoadKey(settings: GenerationSettings): String = loadKey(fromSettings(settings))

    /**
     * Complete load key over every load-affecting field:
     * schema version + model id + sha256 + adapters + backend + context length + batch size
     * + gpu layers + KV cache types + flash attention.
     *
     * Sampling-only knobs (temperature/topK/topP/repeatPenalty), generation-time caps
     * (maxTokens), and thread count (adjustable without reload) are deliberately excluded.
     */
    fun loadKey(requested: RequestedPlan): String = buildString(192) {
        append("v").append(requested.version)
        append("|model=").append(requested.modelId ?: "")
        append("|sha256=").append(requested.modelSha256 ?: "")
        append("|adapters=").append(requested.adapterKey)
        append("|backend=").append(requested.backend.name)
        append("|ctx=").append(requested.contextLength)
        append("|batch=").append(requested.batchSize)
        append("|gpu=").append(requested.gpuLayers)
        append("|kvK=").append(requested.kvCacheTypeK)
        append("|kvV=").append(requested.kvCacheTypeV)
        append("|fa=").append(requested.enableFlashAttn)
    }
}

/** Backend preference expressed in the request; the applied backend is observed separately. */
enum class BackendPreference { VULKAN, CPU }

/**
 * What the user asked the engine to load. `threadCount` and `maxTokens` are carried for
 * diagnostics but are not load-affecting (see [InferencePlan.loadKey]).
 */
data class RequestedPlan(
    val version: Int,
    val modelId: String?,
    val modelSha256: String?,
    val adapterKey: String,
    val backend: BackendPreference,
    val useVulkan: Boolean,
    val threadCount: Int,
    val contextLength: Int,
    val batchSize: Int,
    val gpuLayers: Int,
    val kvCacheTypeK: String,
    val kvCacheTypeV: String,
    val enableFlashAttn: Boolean,
    val maxTokens: Int,
) {
    val loadKey: String get() = InferencePlan.loadKey(this)
}

/**
 * What the native runtime actually applied, as observed via readback. Fields the runtime
 * does not report remain `null` — never a copy of the requested value.
 */
data class AppliedPlan(
    val version: Int,
    val modelId: String? = null,
    val modelSha256: String? = null,
    val backendName: String? = null,
    val useVulkan: Boolean? = null,
    val gpuLayersOffloaded: Int? = null,
    val kleidiAiEnabled: Boolean? = null,
    val threadCount: Int? = null,
    val contextLength: Int? = null,
    val batchSize: Int? = null,
    val gpuLayers: Int? = null,
    val kvCacheTypeK: String? = null,
    val kvCacheTypeV: String? = null,
    val enableFlashAttn: Boolean? = null,
    val maxTokens: Int? = null,
) {
    companion object {
        /**
         * Placeholder for "not yet verified". Every configuration field is unknown; nothing
         * from the request is promoted to an applied value.
         */
        fun fromRequested(requested: RequestedPlan): AppliedPlan =
            AppliedPlan(version = requested.version)

        /**
         * Applies the values the native runtime can actually report. Context length, batch
         * size, KV cache types, flash attention, threads, and token caps are not observable
         * through the bridge, so they stay `null` instead of echoing the request.
         */
        fun fromNativeReadback(
            version: Int,
            modelId: String?,
            modelSha256: String?,
            backendName: String?,
            useVulkan: Boolean?,
            gpuLayersOffloaded: Int?,
            kleidiAiEnabled: Boolean?,
        ): AppliedPlan = AppliedPlan(
            version = version,
            modelId = modelId,
            modelSha256 = modelSha256,
            backendName = backendName,
            useVulkan = useVulkan,
            gpuLayersOffloaded = gpuLayersOffloaded,
            kleidiAiEnabled = kleidiAiEnabled,
        )
    }
}
