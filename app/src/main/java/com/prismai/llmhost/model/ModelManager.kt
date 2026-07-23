package com.prismai.llmhost.model

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.prismai.llmhost.*
import com.prismai.llmhost.chat.ChatManager
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

    companion object {
        private const val PREFS_NAME = "llm_host_prefs"
        private const val KEY_ACTIVE_MODEL = "active_model"
    }

    private data class NativeLoadRejection(
        val message: String,
    )

    fun listModels(): List<String> = modelStorageManager.listInstalledModels()

    suspend fun switchModel(modelId: String): Boolean {
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

        return when (val resolved = modelStorageManager.resolveActiveModel(modelId, verifyHash = false)) {
            is ModelStorageManager.ModelResolveResult.Failure -> {
                if (uiState._currentModel.value == modelId) {
                    engine.unloadModel()
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
                if (uiState._currentModel.value == modelId && uiState._activeModelInfo.value?.sha256 == activeModel.sha256) {
                    uiState._activeModelInfo.value = activeModel
                    uiState._runtimeStatus.value = RuntimeStatus.IDLE
                    val memory = deviceProfiler.deviceMemorySnapshot()
                    uiState._modelLoadDiagnostics.value = ModelLoadDiagnostics(
                        modelId = modelId,
                        state = "current",
                        loadMs = 0L,
                        modelBytes = activeModel.bytes,
                        availableMemoryMb = memory.availableMb,
                        lowMemory = memory.lowMemory,
                        message = "Model already loaded",
                    )
                    return true
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
                    return false
                }
                Log.d(TAG, "unloadModel before switch modelId=$modelId current=${uiState._currentModel.value}")
                engine.unloadModel()
                Log.d(TAG, "unloadModel complete modelId=$modelId")
                uiState.streamState.clear()
                val loaded = engine.loadModel(activeModel.file.absolutePath, uiState._generationSettings.value.clamped())
                Log.d(TAG, "switchModel path=${activeModel.file.absolutePath} result=$loaded")
                if (loaded) {
                    val memory = deviceProfiler.deviceMemorySnapshot()
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
                        message = "Model loaded",
                    )
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_ACTIVE_MODEL, modelId)
                        .apply()
                    onRefreshReadiness()
                } else {
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
        val availableAfterCurrentUnload = fit.availableRamAfterUnloadBytes
        val reserve = minOf(ModelLoadLimits.MEMORY_RESERVE_BYTES, availableAfterCurrentUnload / 3L)
        val budget = minOf(
            ModelLoadLimits.HARD_CAP_BYTES,
            (availableAfterCurrentUnload - reserve).coerceAtLeast(0L),
        )
        val allowed = fit.rating != ModelFitRating.TOO_LARGE && model.bytes <= budget
        if (!allowed) {
            Log.w(
                TAG,
                "model_load_rejected_too_large model=${model.id} bytes=${model.bytes} " +
                    "required=${fit.requiredRamBytes} budget=$budget reserve=$reserve avail=${profile.availableRamBytes} " +
                    "projectedAvail=$availableAfterCurrentUnload lowMemory=${profile.lowMemory} rating=${fit.rating}",
            )
        }
        return if (allowed) {
            null
        } else {
            NativeLoadRejection(
                message = nativeLoadRejectionMessage(model, fit, budget),
            )
        }
    }

    private fun nativeLoadRejectionMessage(
        model: ModelStorageManager.ActiveModelInfo,
        fit: ModelFitEstimate,
        budgetBytes: Long,
    ): String {
        val modelSize = FormatUtils.formatBytesForMessage(model.bytes)
        val estimatedNeed = FormatUtils.formatBytesForMessage(fit.requiredRamBytes)
        val available = FormatUtils.formatBytesForMessage(fit.availableRamAfterUnloadBytes)
        val budget = FormatUtils.formatBytesForMessage(budgetBytes)
        return when {
            model.bytes > ModelLoadLimits.HARD_CAP_BYTES ->
                "Model ${model.id} is $modelSize, above this build's ${FormatUtils.formatBytesForMessage(ModelLoadLimits.HARD_CAP_BYTES)} load cap."
            fit.rating == ModelFitRating.TOO_LARGE ->
                "Model ${model.id} needs about $estimatedNeed RAM, but only $available is available after unload ($budget load budget)."
            else ->
                "Model ${model.id} is $modelSize, above the current $budget load budget."
        }
    }

}
