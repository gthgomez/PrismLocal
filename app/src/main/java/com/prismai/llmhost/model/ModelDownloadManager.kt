package com.prismai.llmhost.model
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.prismai.llmhost.*
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.ui.UiEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages HuggingFace model downloads via WorkManager.
 */
class ModelDownloadManager(
    private val context: Context,
    private val uiState: ServiceUiState,
    private val eventBus: UiEventBus,
    private val serviceScope: CoroutineScope,
    private val onRefreshReadiness: () -> Unit,
    private val onAutoLoadModel: suspend (modelId: String) -> Boolean,
) {
    private val TAG = "ModelDownloadManager"
    private var downloadObserverJob: Job? = null

    fun downloadHuggingFaceModel(entryId: String) {
        val entry = HuggingFaceModelCatalog.find(entryId)
        if (entry == null) {
            eventBus.publish("Model catalog entry not found")
            return
        }
        if (uiState._importState.value is ImportState.Running || uiState._modelDownloadState.value is ModelDownloadState.Running) {
            eventBus.publish("A model import or download is already running")
            return
        }
        uiState._runtimeStatus.value = RuntimeStatus.IMPORTING
        uiState._modelDownloadState.value = ModelDownloadState.Running(
            entry = entry,
            stage = ModelDownloadState.Running.Stage.QUEUED,
            bytesDone = 0L,
            totalBytes = entry.expectedBytes,
            message = "Queued with WorkManager",
        )
        uiState._importState.value = ImportState.Running(
            fileName = entry.fileName,
            bytesCopied = 0L,
            totalBytes = entry.expectedBytes,
        )
        enqueueHuggingFaceDownload(context, entry.id)
    }

    fun downloadCustomHuggingFaceModel(repoId: String, fileName: String) {
        if (repoId.isBlank() || fileName.isBlank()) {
            eventBus.publish("Repository ID and filename are required")
            return
        }
        val entry = HuggingFaceModelCatalog.createCustomEntry(repoId, fileName)
        downloadHuggingFaceModel(entry.id)
    }

    fun observeDownloadWork() {
        downloadObserverJob?.cancel()
        downloadObserverJob = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(HuggingFaceDownloadWork.UNIQUE_WORK_NAME)
            .onEach { infos ->
                val info = infos.firstOrNull() ?: return@onEach
                applyDownloadWorkInfo(info)
            }
            .catch { error ->
                Log.w(TAG, "Download work observation failed", error)
            }
            .launchIn(serviceScope)
    }

    fun cancelObserver() {
        downloadObserverJob?.cancel()
        downloadObserverJob = null
    }

    suspend fun cancelDownloadForModel(modelId: String) {
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context)
                .cancelAllWorkByTag(HuggingFaceDownloadWork.MODEL_OWNER_TAG_PREFIX + modelId)
                .result
                .get()
        }
    }

    private fun applyDownloadWorkInfo(info: WorkInfo) {
        val data = when (info.state) {
            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED -> info.outputData
            else -> info.progress
        }
        val entryId = data.getString(HuggingFaceDownloadWork.KEY_ENTRY_ID)
        val entry = entryId?.let(HuggingFaceModelCatalog::find)
        val entryName = data.getString(HuggingFaceDownloadWork.KEY_ENTRY_NAME)
            ?: entry?.name
            ?: "Hugging Face model"
        val totalBytes = data.getLong(HuggingFaceDownloadWork.KEY_TOTAL_BYTES, -1L)
            .takeIf { it > 0L }
            ?: entry?.expectedBytes
        val bytesDone = data.getLong(HuggingFaceDownloadWork.KEY_BYTES_DONE, 0L)
        val message = data.getString(HuggingFaceDownloadWork.KEY_MESSAGE).orEmpty()

        when (info.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> {
                entry?.let {
                    uiState._runtimeStatus.value = RuntimeStatus.IMPORTING
                    uiState._modelDownloadState.value = ModelDownloadState.Running(
                        entry = it,
                        stage = ModelDownloadState.Running.Stage.QUEUED,
                        bytesDone = bytesDone,
                        totalBytes = totalBytes,
                        message = message.ifBlank { "Queued with WorkManager" },
                    )
                    uiState._importState.value = ImportState.Running(it.fileName, bytesDone, totalBytes)
                }
            }
            WorkInfo.State.RUNNING -> {
                entry?.let {
                    val stage = data.getString(HuggingFaceDownloadWork.KEY_STAGE)
                        ?.let { raw -> runCatching { ModelDownloadState.Running.Stage.valueOf(raw) }.getOrNull() }
                        ?: ModelDownloadState.Running.Stage.DOWNLOADING
                    uiState._runtimeStatus.value = RuntimeStatus.IMPORTING
                    uiState._modelDownloadState.value = ModelDownloadState.Running(
                        entry = it,
                        stage = stage,
                        bytesDone = bytesDone,
                        totalBytes = totalBytes,
                        message = message.ifBlank { null },
                    )
                    uiState._importState.value = ImportState.Running(it.fileName, bytesDone, totalBytes)
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                val modelId = data.getString(HuggingFaceDownloadWork.KEY_MODEL_ID)
                    ?: entry?.fileName
                    ?: entryName
                val modelBytes = data.getLong(HuggingFaceDownloadWork.KEY_MODEL_BYTES, 0L)
                val modelSha256 = data.getString(HuggingFaceDownloadWork.KEY_MODEL_SHA256).orEmpty()
                // Absent/unknown integrity means unverified: never present an unchecked import as verified.
                val integrityVerified =
                    data.getString(HuggingFaceDownloadWork.KEY_INTEGRITY) == HuggingFaceDownloadWork.INTEGRITY_VERIFIED
                val previous = uiState._modelDownloadState.value
                uiState._modelDownloadState.value = ModelDownloadState.Success(modelId, entryName, integrityVerified)
                uiState._importState.value = ImportState.Success(
                    modelId = modelId,
                    bytes = modelBytes,
                    sha256 = modelSha256,
                )
                uiState._runtimeStatus.value = RuntimeStatus.IDLE
                onRefreshReadiness()
                val verifiedSuffix = if (integrityVerified) "" else " (unverified: no SHA-256 available)"
                if (uiState._currentModel.value == null) {
                    serviceScope.launch {
                        if (onAutoLoadModel(modelId)) {
                            eventBus.publish("Downloaded and loaded $entryName$verifiedSuffix")
                        } else if (previous !is ModelDownloadState.Success) {
                            eventBus.publish("Downloaded $entryName$verifiedSuffix")
                        }
                    }
                } else {
                    eventBus.publish("Downloaded $entryName$verifiedSuffix. Select it in Model to load it.")
                }
            }
            WorkInfo.State.FAILED -> {
                val failure = message.ifBlank { "Download failed" }
                uiState._modelDownloadState.value = ModelDownloadState.Failure(entryName, failure)
                uiState._importState.value = ImportState.Failure(message = failure, code = "DOWNLOAD_FAILED")
                uiState._runtimeStatus.value = RuntimeStatus.ERROR
                eventBus.publish("Download failed: $failure")
            }
            WorkInfo.State.CANCELLED -> {
                uiState._modelDownloadState.value = ModelDownloadState.Cancelled
                uiState._importState.value = ImportState.Cancelled
                uiState._runtimeStatus.value = RuntimeStatus.IDLE
            }
        }
    }
}
