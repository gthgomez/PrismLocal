package com.prismai.llmhost.model
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.prismai.llmhost.*
import com.prismai.llmhost.chat.ChatManager
import com.prismai.llmhost.engine.runtime.InferencePlan
import com.prismai.llmhost.util.FormatUtils
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.ui.UiEventBus

/**
 * Manages model lifecycle: switching, listing, load-rejection validation,
 * and memory snapshots.
 */
class ModelManager(
    private val context: Context,
    private val engine: NativeLlmBridge,
    private val modelStorageManager: ModelStorageManager,
    private val uiState: ServiceUiState,
    private val eventBus: UiEventBus,
    private val chatManager: ChatManager,
    private val deviceProfiler: DeviceProfiler,
    private val modelReadinessAssessor: ModelReadinessAssessor,
    private val onRefreshReadiness: () -> Unit,
) {
    private val TAG = "ModelManager"

    /**
     * Load key of the configuration that was last successfully applied to the native engine.
     * Null whenever no model is loaded. Used to avoid silently skipping a settings change.
     */
    private var lastLoadedPlanKey: String? = null

    companion object {
        private const val PREFS_NAME = "llm_host_prefs"
        private const val KEY_ACTIVE_MODEL = "active_model"
    }

    private data class NativeLoadRejection(
        val message: String,
    )

    fun listModels(): List<String> = modelStorageManager.listInstalledModels()

    suspend fun deleteModel(modelId: String, confirmedIdentity: ModelIdentity): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Log.d(TAG, "deleteModel requested modelId=$modelId")
            val deleted = modelStorageManager.deleteModel(modelId, confirmedIdentity)
            if (!deleted) {
                eventBus.publish("Failed to delete model $modelId")
                return@withContext false
            }
            if (uiState._currentModel.value == modelId) {
                engine.unloadModel()
                lastLoadedPlanKey = null
                uiState.streamState.clear()
                uiState._currentModel.value = null
                uiState._activeModelInfo.value = null
                uiState._runtimeStatus.value = RuntimeStatus.IDLE
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_ACTIVE_MODEL)
                    .apply()
            }
            eventBus.publish("Deleted model $modelId")
            onRefreshReadiness()
            true
        }

    suspend fun switchModel(modelId: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        Log.d(TAG, "switchModel requested modelId=$modelId")
        uiState._runtimeStatus.value = RuntimeStatus.LOADING_MODEL
        val loadStartedAt = SystemClock.elapsedRealtime()
        val initialMemory = deviceProfiler.deviceMemorySnapshot()
        uiState._modelLoadDiagnostics.value = ModelLoadDiagnostics(
            modelId = modelId,
            state = "loading",
            loadMs = 0L,
            modelBytes = null,
            availableMemoryMb = initialMemory.availableMb,
            lowMemory = initialMemory.lowMemory,
            message = "Loading model",
        )

        when (val resolved = modelStorageManager.resolveActiveModel(modelId, verifyHash = false)) {
            is ModelStorageManager.ModelResolveResult.Failure -> {
                if (uiState._currentModel.value == modelId) {
                    engine.unloadModel()
                    lastLoadedPlanKey = null
                    uiState.streamState.clear()
                    uiState._currentModel.value = null
                    uiState._activeModelInfo.value = null
                }
                uiState._runtimeStatus.value = RuntimeStatus.ERROR
                uiState._modelLoadDiagnostics.value = ModelLoadDiagnostics(
                    modelId = modelId,
                    state = "failed",
                    loadMs = SystemClock.elapsedRealtime() - loadStartedAt,
                    modelBytes = null,
                    availableMemoryMb = deviceProfiler.deviceMemorySnapshot().availableMb,
                    lowMemory = deviceProfiler.deviceMemorySnapshot().lowMemory,
                    message = resolved.error.userMessage,
                )
                Log.w(TAG, "switchModel failed modelId=$modelId error=${resolved.error}")
                eventBus.publish(resolved.error.userMessage)
                false
            }
            is ModelStorageManager.ModelResolveResult.Success -> {
                val activeModel = resolved.model
                val requestedSettings = uiState._generationSettings.value.clamped()
                val requestedPlanKey = InferencePlan.loadKey(
                    InferencePlan.fromSettings(
                        requestedSettings,
                        modelId = modelId,
                        modelSha256 = activeModel.sha256,
                    ),
                )
                val sameModelAlreadyLoaded =
                    uiState._currentModel.value == modelId &&
                        uiState._activeModelInfo.value?.sha256 == activeModel.sha256
                // Only short-circuit when the *requested load configuration* also matches what was
                // applied last. Otherwise fall through and reload so a settings change is never
                // silently ignored.
                if (sameModelAlreadyLoaded && lastLoadedPlanKey == requestedPlanKey) {
                    uiState._activeModelInfo.value = activeModel
                    uiState._runtimeStatus.value = RuntimeStatus.IDLE
                    val memory = deviceProfiler.deviceMemorySnapshot()
                    // Report the engine's actual backend, not a default: a loaded
                    // Vulkan model must not be displayed as CPU.
                    val backendName = engine.getBackendName()
                    val gpuLayersOffloaded = engine.getGpuLayersOffloaded()
                    val isKleidiAiEnabled = engine.isKleidiAiEnabled()
                    uiState._modelLoadDiagnostics.value = ModelLoadDiagnostics(
                        modelId = modelId,
                        state = "current",
                        loadMs = 0L,
                        modelBytes = activeModel.bytes,
                        availableMemoryMb = memory.availableMb,
                        lowMemory = memory.lowMemory,
                        message = "Model already loaded ($backendName)",
                        gpuLayersOffloaded = gpuLayersOffloaded,
                        backendName = backendName,
                        isKleidiAiEnabled = isKleidiAiEnabled,
                    )
                    return@withContext true
                }
                val loadRejection = nativeLoadRejection(activeModel)
                if (loadRejection != null) {
                    val memory = deviceProfiler.deviceMemorySnapshot()
                    uiState._runtimeStatus.value = RuntimeStatus.ERROR
                    uiState._modelLoadDiagnostics.value = ModelLoadDiagnostics(
                        modelId = modelId,
                        state = "rejected",
                        loadMs = SystemClock.elapsedRealtime() - loadStartedAt,
                        modelBytes = activeModel.bytes,
                        availableMemoryMb = memory.availableMb,
                        lowMemory = memory.lowMemory,
                        message = loadRejection.message,
                    )
                    eventBus.publish(loadRejection.message)
                    return@withContext false
                }
                Log.d(TAG, "unloadModel before switch modelId=$modelId current=${uiState._currentModel.value}")
                engine.unloadModel()
                // Invalidate the applied-plan key immediately: from here until a
                // successful load there is no model whose configuration this key
                // describes, so a throw or failure can never leave a stale key
                // that makes a later same-model switch short-circuit to
                // "Model already loaded" against an unloaded engine.
                lastLoadedPlanKey = null
                Log.d(TAG, "unloadModel complete modelId=$modelId")
                uiState.streamState.clear()
                val loaded = try {
                    engine.loadModel(activeModel.file.absolutePath, requestedSettings)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (t: Exception) {
                    Log.e(TAG, "model_load_threw modelId=$modelId", t)
                    false
                }
                Log.d(TAG, "switchModel path=${activeModel.file.absolutePath} result=$loaded")
                if (loaded) {
                    lastLoadedPlanKey = requestedPlanKey
                    val memory = deviceProfiler.deviceMemorySnapshot()
                    val backendName = engine.getBackendName()
                    val gpuLayersOffloaded = engine.getGpuLayersOffloaded()
                    val isKleidiAiEnabled = engine.isKleidiAiEnabled()
                    uiState._currentModel.value = modelId
                    uiState._activeModelInfo.value = activeModel
                    chatManager.touchCurrentChat(uiState._transcript.value, updateTitle = false)
                    uiState._runtimeStatus.value = RuntimeStatus.IDLE
                    uiState._modelLoadDiagnostics.value = ModelLoadDiagnostics(
                        modelId = modelId,
                        state = "loaded",
                        loadMs = SystemClock.elapsedRealtime() - loadStartedAt,
                        modelBytes = activeModel.bytes,
                        availableMemoryMb = memory.availableMb,
                        lowMemory = memory.lowMemory,
                        message = "Model loaded ($backendName)",
                        gpuLayersOffloaded = gpuLayersOffloaded,
                        backendName = backendName,
                        isKleidiAiEnabled = isKleidiAiEnabled,
                    )
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_ACTIVE_MODEL, modelId)
                        .apply()
                    onRefreshReadiness()
                } else {
                    lastLoadedPlanKey = null
                    val memory = deviceProfiler.deviceMemorySnapshot()
                    uiState._currentModel.value = null
                    uiState._activeModelInfo.value = null
                    uiState._runtimeStatus.value = RuntimeStatus.ERROR
                    uiState._modelLoadDiagnostics.value = ModelLoadDiagnostics(
                        modelId = modelId,
                        state = "failed",
                        loadMs = SystemClock.elapsedRealtime() - loadStartedAt,
                        modelBytes = activeModel.bytes,
                        availableMemoryMb = memory.availableMb,
                        lowMemory = memory.lowMemory,
                        message = "Native runtime failed to load model",
                    )
                    eventBus.publish("Failed to load model $modelId")
                    onRefreshReadiness()
                }
                loaded
            }
        }
    }

    private fun nativeLoadRejection(model: ModelStorageManager.ActiveModelInfo): NativeLoadRejection? {
        val profile = deviceProfiler.captureProfile()
        val fit = modelReadinessAssessor.estimateModelFit(model, profile)
        val overHardCap = model.bytes > ModelLoadLimits.HARD_CAP_BYTES
        // Block only when the model physically cannot fit. A tight-but-fitting model
        // (RISKY) still loads — slow is not blocked — and a model already proven on
        // this device overrides even the physical projection.
        val exceedsAvailableRam = fit.requiredRamBytes > fit.availableRamAfterUnloadBytes
        val allowed = !overHardCap && (!exceedsAvailableRam || fit.provenUsable)
        if (allowed) {
            if (fit.rating == ModelFitRating.RISKY) {
                Log.i(
                    TAG,
                    "model_load_advisory model=${model.id} reason=${fit.reason} " +
                        "required=${fit.requiredRamBytes} avail=${fit.availableRamAfterUnloadBytes}",
                )
                eventBus.publish(
                    "Loading ${model.id}: tight memory (needs ~${FormatUtils.formatBytesForMessage(fit.requiredRamBytes)}, " +
                        "~${FormatUtils.formatBytesForMessage(fit.availableRamAfterUnloadBytes)} available). " +
                        "It may be slow; proceeding.",
                )
            }
            return null
        }
        Log.w(
            TAG,
            "model_load_rejected model=${model.id} bytes=${model.bytes} overHardCap=$overHardCap " +
                "required=${fit.requiredRamBytes} avail=${fit.availableRamAfterUnloadBytes} " +
                "proven=${fit.provenUsable} rating=${fit.rating} lowMemory=${profile.lowMemory}",
        )
        return NativeLoadRejection(
            message = nativeLoadRejectionMessage(model, fit),
        )
    }

    private fun nativeLoadRejectionMessage(
        model: ModelStorageManager.ActiveModelInfo,
        fit: ModelFitEstimate,
    ): String {
        val modelSize = FormatUtils.formatBytesForMessage(model.bytes)
        val estimatedNeed = FormatUtils.formatBytesForMessage(fit.requiredRamBytes)
        val available = FormatUtils.formatBytesForMessage(fit.availableRamAfterUnloadBytes)
        return when {
            model.bytes > ModelLoadLimits.HARD_CAP_BYTES ->
                "Model ${model.id} is $modelSize, above this build's ${FormatUtils.formatBytesForMessage(ModelLoadLimits.HARD_CAP_BYTES)} load cap."
            else ->
                "Model ${model.id} needs about $estimatedNeed RAM, but only $available is available after unload."
        }
    }

}
