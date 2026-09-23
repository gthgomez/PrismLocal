package com.prismai.llmhost.agent.tools

import android.content.ContextWrapper
import com.prismai.llmhost.model.DeviceProfiler
import com.prismai.llmhost.model.ModelDownloadManager
import com.prismai.llmhost.model.ModelImportManager
import com.prismai.llmhost.model.ModelReadinessAssessor
import com.prismai.llmhost.storage.ModelStorageManager
import com.prismai.llmhost.tools.AgentToolCall
import com.prismai.llmhost.tools.AgentToolErrorCode
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.ui.UiEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelToolsDeletionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun confirmedDeletionUsesSerializedCallbackAndReturnsSuccess() = runBlocking {
        val callbackModelIds = mutableListOf<String>()
        var directDeletionCalls = 0
        var refreshCalls = 0
        val tools = newModelTools(
            directDelete = {
                directDeletionCalls += 1
                false
            },
            serializedDelete = { modelId ->
                callbackModelIds += modelId
                true
            },
            onRefreshReadiness = { refreshCalls += 1 },
        )

        val result = tools.deleteModel(deleteCall(), confirmed = true)

        assertTrue(result.success)
        assertEquals(AgentToolErrorCode.OK, result.errorCode)
        assertEquals(listOf("model-a"), callbackModelIds)
        assertEquals(0, directDeletionCalls)
        assertEquals(1, refreshCalls)
        assertEquals(4096L, result.details.getLong("bytes_freed"))
    }

    @Test
    fun failedSerializedCallbackReturnsFailureWithoutDirectDeletion() = runBlocking {
        val callbackModelIds = mutableListOf<String>()
        var directDeletionCalls = 0
        var refreshCalls = 0
        val tools = newModelTools(
            directDelete = {
                directDeletionCalls += 1
                true
            },
            serializedDelete = { modelId ->
                callbackModelIds += modelId
                false
            },
            onRefreshReadiness = { refreshCalls += 1 },
        )

        val result = tools.deleteModel(deleteCall(), confirmed = true)

        assertFalse(result.success)
        assertEquals(AgentToolErrorCode.FAILED, result.errorCode)
        assertEquals(listOf("model-a"), callbackModelIds)
        assertEquals(0, directDeletionCalls)
        assertEquals(0, refreshCalls)
    }

    @Test
    fun nullSerializedCallbackUsesDirectDeletionAndRefreshesAfterSuccess() = runBlocking {
        val directModelIds = mutableListOf<String>()
        var refreshCalls = 0
        val tools = newModelTools(
            directDelete = { modelId ->
                directModelIds += modelId
                true
            },
            serializedDelete = null,
            onRefreshReadiness = { refreshCalls += 1 },
        )

        val result = tools.deleteModel(deleteCall(), confirmed = true)

        assertTrue(result.success)
        assertEquals(AgentToolErrorCode.OK, result.errorCode)
        assertEquals(listOf("model-a"), directModelIds)
        assertEquals(1, refreshCalls)
        assertEquals(4096L, result.details.getLong("bytes_freed"))
    }

    @Test
    fun nullSerializedCallbackUsesDirectDeletionAndDoesNotRefreshAfterFailure() = runBlocking {
        val directModelIds = mutableListOf<String>()
        var refreshCalls = 0
        val tools = newModelTools(
            directDelete = { modelId ->
                directModelIds += modelId
                false
            },
            serializedDelete = null,
            onRefreshReadiness = { refreshCalls += 1 },
        )

        val result = tools.deleteModel(deleteCall(), confirmed = true)

        assertFalse(result.success)
        assertEquals(AgentToolErrorCode.FAILED, result.errorCode)
        assertEquals(listOf("model-a"), directModelIds)
        assertEquals(0, refreshCalls)
    }

    @Test
    fun rejectedConfirmationDoesNotInvokeEitherDeletionRoute() = runBlocking {
        var directDeletionCalls = 0
        var serializedDeletionCalls = 0
        val tools = newModelTools(
            directDelete = {
                directDeletionCalls += 1
                true
            },
            serializedDelete = {
                serializedDeletionCalls += 1
                true
            },
        )

        val result = tools.deleteModel(deleteCall(), confirmed = false)

        assertFalse(result.success)
        assertEquals(AgentToolErrorCode.CONFIRMATION_REQUIRED, result.errorCode)
        assertEquals(0, directDeletionCalls)
        assertEquals(0, serializedDeletionCalls)
    }

    private fun newModelTools(
        directDelete: suspend (String) -> Boolean,
        serializedDelete: (suspend (String) -> Boolean)?,
        onRefreshReadiness: () -> Unit = {},
    ): ModelTools {
        val filesDir = tempFolder.newFolder()
        val context = ContextWrapper(null)
        val uiState = ServiceUiState()
        val eventBus = UiEventBus()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val storageManager = ModelStorageManager(context)
        val installedModel = ModelStorageManager.ActiveModelInfo(
            id = "model-a",
            versionId = "v1",
            file = File(filesDir, "model-a.gguf"),
            fileName = "model-a.gguf",
            sha256 = "sha-a",
            bytes = 4096L,
            importedAt = "2026-09-23T00:00:00Z",
            validation = ModelStorageManager.ModelValidation(
                format = "GGUF",
                ggufVersion = 3,
                status = "imported",
                validatedAt = "2026-09-23T00:00:00Z",
            ),
        )
        return ModelTools(
            listInstalledModelInfos = { listOf(installedModel) },
            deleteModelDirectly = directDelete,
            modelReadinessAssessor = ModelReadinessAssessor(
                storageManager,
                DeviceProfiler(context),
                uiState,
            ),
            modelImportManager = ModelImportManager(
                storageManager,
                uiState,
                eventBus,
                scope,
            ),
            modelDownloadManager = ModelDownloadManager(
                context,
                uiState,
                eventBus,
                scope,
                onRefreshReadiness = {},
                onAutoLoadModel = { false },
            ),
            uiState = uiState,
            onRefreshReadiness = onRefreshReadiness,
            filesDir = filesDir,
            chatDirectory = { filesDir },
            benchmarkFileSize = { 0L },
            chatIndexFile = { filesDir },
            deleteModelSafely = serializedDelete,
        )
    }

    private fun deleteCall(): AgentToolCall = AgentToolCall(
        name = "delete_model",
        arguments = JSONObject().put("model_id", "model-a"),
    )
}
