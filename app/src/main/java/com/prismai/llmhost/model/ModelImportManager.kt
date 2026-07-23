package com.prismai.llmhost.model
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.net.Uri
import android.util.Log
import com.prismai.llmhost.ImportState
import com.prismai.llmhost.ModelDownloadState
import com.prismai.llmhost.RuntimeStatus
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.ui.UiEventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Manages model import from a content URI (local file import).
 */
class ModelImportManager(
    private val modelStorageManager: ModelStorageManager,
    private val uiState: ServiceUiState,
    private val eventBus: UiEventBus,
    private val serviceScope: CoroutineScope,
    private val onRefreshReadiness: () -> Unit = {},
) {
    private val TAG = "ModelImportManager"

    fun importModel(uri: Uri): Job? {
        val tag = "importModel"
        if (uiState._importState.value is ImportState.Running || uiState._modelDownloadState.value is ModelDownloadState.Running) {
            // Check if any import/download is active (job state tracked elsewhere)
            eventBus.publish("A model import is already running")
            return null
        }
        uiState._runtimeStatus.value = RuntimeStatus.IMPORTING
        uiState._importState.value = ImportState.Running(fileName = "selected model", bytesCopied = 0, totalBytes = null)
        val job = serviceScope.launch(Dispatchers.IO) {
            try {
                val result = modelStorageManager.importModel(uri) { progress ->
                    ensureActive()
                    uiState._importState.value = ImportState.Running(
                        fileName = "selected model",
                        bytesCopied = progress.bytesCopied,
                        totalBytes = progress.totalBytes,
                    )
                }
                when (result) {
                    is ModelStorageManager.ImportResult.Failure -> {
                        uiState._importState.value = ImportState.Failure(
                            message = result.error.userMessage,
                            code = result.error.code.name,
                        )
                        eventBus.publish(result.error.userMessage)
                        uiState._runtimeStatus.value = RuntimeStatus.ERROR
                    }
                    is ModelStorageManager.ImportResult.Success -> {
                        uiState._importState.value = ImportState.Success(
                            modelId = result.model.id,
                            bytes = result.model.bytes,
                            sha256 = result.model.sha256,
                        )
                        onRefreshReadiness()
                        eventBus.publish("Imported ${result.model.id}")
                        uiState._runtimeStatus.value = RuntimeStatus.IDLE
                    }
                }
            } catch (e: CancellationException) {
                uiState._importState.value = ImportState.Cancelled
                uiState._runtimeStatus.value = RuntimeStatus.IDLE
                eventBus.publish("Model import cancelled")
                throw e
            }
        }
        return job
    }

    fun cancelImport() {
        // Note: importJob cancel happens in InferenceService wrapper
        uiState._importState.value = ImportState.Cancelled
        uiState._modelDownloadState.value = ModelDownloadState.Cancelled
        uiState._runtimeStatus.value = RuntimeStatus.IDLE
    }

    fun clearImportState() {
        if (uiState._importState.value !is ImportState.Running) {
            uiState._importState.value = ImportState.Idle
        }
    }
}
