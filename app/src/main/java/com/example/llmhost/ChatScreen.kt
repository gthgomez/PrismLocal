package com.example.llmhost

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    service: InferenceService?,
    uiMessage: String?,
    onClearUiMessage: (String) -> Unit,
    onImportPickerStarted: () -> Unit,
    onImportPickerFinished: () -> Unit,
    onSwitchModel: (String) -> Unit,
) {
    MaterialTheme(colorScheme = LlmHostPrismaticColorScheme) {
        var snackbarMessage by remember { mutableStateOf<String?>(null) }
        var controlsVisible by remember { mutableStateOf(false) }
        var chatsVisible by remember { mutableStateOf(false) }
        val controlSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val chatSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        LaunchedEffect(uiMessage) {
            val message = uiMessage ?: return@LaunchedEffect
            snackbarMessage = message
            delay(8000)
            if (snackbarMessage == message) {
                snackbarMessage = null
                onClearUiMessage(message)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val minChatHeight = maxHeight * 0.70f
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        val context = LocalContext.current
                        var refreshKey by remember { mutableIntStateOf(0) }
                        var models by remember(service) { mutableStateOf(service?.listModels() ?: emptyList()) }
                        var hfCatalog by remember(service) { mutableStateOf(service?.huggingFaceCatalog() ?: emptyList()) }
                        val currentModel by (service?.currentModel ?: emptyFlow()).collectAsStateWithLifecycle(initialValue = null)
                        val activeModelInfo by (service?.activeModelInfo ?: emptyFlow()).collectAsStateWithLifecycle(initialValue = null)
                        val isGenerating by (service?.isGenerating ?: emptyFlow()).collectAsStateWithLifecycle(initialValue = false)
                        val runtimeStatus by (service?.runtimeStatus ?: emptyFlow()).collectAsStateWithLifecycle(initialValue = RuntimeStatus.IDLE)
                        val importState by (service?.importState ?: emptyFlow()).collectAsStateWithLifecycle(initialValue = ImportState.Idle)
                        val modelDownloadState by (service?.modelDownloadState ?: emptyFlow()).collectAsStateWithLifecycle(initialValue = ModelDownloadState.Idle)
                        val recoveryTranscript by (service?.recoveryTranscript ?: emptyFlow()).collectAsStateWithLifecycle(initialValue = null)
                        val chatSessions by (service?.chatSessions ?: emptyFlow()).collectAsStateWithLifecycle(initialValue = emptyList())
                        val currentChatId by (service?.currentChatId ?: emptyFlow()).collectAsStateWithLifecycle(initialValue = null)
                        val transcript by (service?.transcript ?: emptyFlow()).collectAsStateWithLifecycle(initialValue = emptyList())
                        val generationSettings by (service?.generationSettings ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = GenerationSettings()
                        )
                        val generationPerformance by (service?.generationPerformance ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = null
                        )
                        val benchmarkRuns by (service?.benchmarkRuns ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = emptyList()
                        )
                        val benchmarkStatus by (service?.benchmarkStatus ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = BenchmarkStatus()
                        )
                        val modelLoadDiagnostics by (service?.modelLoadDiagnostics ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = null
                        )
                        val deviceCapabilityProfile by (service?.deviceCapabilityProfile ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = null
                        )
                        val modelReadiness by (service?.modelReadiness ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = emptyList()
                        )
                        var prompt by remember { mutableStateOf("") }
                        var importStatus by remember { mutableStateOf("") }
                        var pendingBenchmarkCsv by remember { mutableStateOf<String?>(null) }
                        var pendingBenchmarkJson by remember { mutableStateOf<String?>(null) }
                        val listState = rememberLazyListState()
                        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                            onImportPickerFinished()
                            if (uri == null) {
                                return@rememberLauncherForActivityResult
                            }
                            runCatching {
                                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            service?.importModel(uri)
                        }
                        val benchmarkExportLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.CreateDocument("text/csv")
                        ) { uri ->
                            val csv = pendingBenchmarkCsv
                            pendingBenchmarkCsv = null
                            if (uri == null || csv == null) {
                                return@rememberLauncherForActivityResult
                            }
                            runCatching {
                                context.contentResolver.openOutputStream(uri)?.use { stream ->
                                    stream.write(csv.toByteArray(Charsets.UTF_8))
                                } ?: error("Could not open export target")
                            }.onSuccess {
                                snackbarMessage = "Benchmark CSV exported"
                            }.onFailure { error ->
                                snackbarMessage = "Benchmark export failed: ${error.message ?: error::class.java.simpleName}"
                            }
                        }
                        val benchmarkJsonExportLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.CreateDocument("application/json")
                        ) { uri ->
                            val json = pendingBenchmarkJson
                            pendingBenchmarkJson = null
                            if (uri == null || json == null) {
                                return@rememberLauncherForActivityResult
                            }
                            runCatching {
                                context.contentResolver.openOutputStream(uri)?.use { stream ->
                                    stream.write(json.toByteArray(Charsets.UTF_8))
                                } ?: error("Could not open export target")
                            }.onSuccess {
                                snackbarMessage = "Benchmark JSON exported"
                            }.onFailure { error ->
                                snackbarMessage = "Benchmark export failed: ${error.message ?: error::class.java.simpleName}"
                            }
                        }

                        LaunchedEffect(service, refreshKey, importState) {
                            service?.refreshDeviceAndModelReadiness()
                            models = service?.listModels() ?: emptyList()
                            hfCatalog = service?.huggingFaceCatalog() ?: emptyList()
                        }

                        LaunchedEffect(importState) {
                            when (val state = importState) {
                                ImportState.Cancelled -> importStatus = "Import cancelled"
                                is ImportState.Failure -> {
                                    importStatus = state.message
                                    snackbarMessage = state.message
                                }
                                ImportState.Idle -> Unit
                                is ImportState.Running -> {
                                    val total = state.totalBytes
                                    importStatus = if (total != null && total > 0) {
                                        "Importing ${formatBytes(state.bytesCopied)} / ${formatBytes(total)}"
                                    } else {
                                        "Importing ${formatBytes(state.bytesCopied)}"
                                    }
                                }
                                is ImportState.Success -> {
                                    refreshKey++
                                    importStatus = "Imported ${state.modelId}"
                                    snackbarMessage = "Imported ${state.modelId}"
                                }
                            }
                        }

                        LaunchedEffect(transcript.size, transcript.lastOrNull()?.text, isGenerating) {
                            if (transcript.isNotEmpty()) {
                                listState.animateScrollToItem(transcript.lastIndex)
                            }
                        }

                        ChatTopBar(
                            runtimeStatus = runtimeStatus,
                            chatTitle = chatSessions.firstOrNull { it.id == currentChatId }?.title,
                            modelName = currentModel,
                            importStatus = importStatus,
                            importState = importState,
                            onOpenChats = { chatsVisible = true },
                            onOpenControls = { controlsVisible = true },
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val activeAssistantMessageId = transcript
                            .lastOrNull { it.role == TranscriptRole.ASSISTANT }
                            ?.id

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = minChatHeight)
                                .fillMaxWidth(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(transcript, key = { it.id }) { message ->
                                val isUser = message.role == TranscriptRole.USER
                                MessageBubble(
                                    label = if (isUser) "You" else "Assistant",
                                    text = message.text.ifBlank { if (isGenerating && !isUser) "..." else "" },
                                    isUser = isUser,
                                    showLoading = isGenerating && !isUser && message.id == activeAssistantMessageId,
                                    performance = generationPerformance.takeIf {
                                        isGenerating && !isUser && message.id == activeAssistantMessageId
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        PromptComposer(
                            prompt = prompt,
                            enabled = service != null && currentModel != null,
                            isGenerating = isGenerating,
                            onPromptChange = { prompt = it },
                            onCancel = { service?.cancelGeneration() },
                            onSend = {
                                val text = prompt.trim()
                                if (text.isNotEmpty()) {
                                    prompt = ""
                                    service?.generateSafely(text)
                                }
                            },
                        )

                        if (controlsVisible) {
                            ModalBottomSheet(
                                onDismissRequest = { controlsVisible = false },
                                sheetState = controlSheetState,
                                containerColor = Color.White,
                                contentColor = PrismText,
                            ) {
                                ControlPlaneSheet(
                                    models = models,
                                    currentModel = currentModel,
                                    activeModelInfo = activeModelInfo,
                                    runtimeStatus = runtimeStatus,
                                    importState = importState,
                                    modelDownloadState = modelDownloadState,
                                    importStatus = importStatus,
                                    recoveryTranscript = recoveryTranscript,
                                    generationSettings = generationSettings,
                                    generationPerformance = generationPerformance,
                                    benchmarkRuns = benchmarkRuns,
                                    benchmarkStatus = benchmarkStatus,
                                    modelLoadDiagnostics = modelLoadDiagnostics,
                                    deviceCapabilityProfile = deviceCapabilityProfile,
                                    modelReadiness = modelReadiness,
                                    hfCatalog = hfCatalog,
                                    isGenerating = isGenerating,
                                    serviceAvailable = service != null,
                                    onSwitchModel = onSwitchModel,
                                    onImportModel = {
                                        onImportPickerStarted()
                                        importLauncher.launch(arrayOf("*/*"))
                                    },
                                    onCancelImport = { service?.cancelImport() },
                                    onDownloadModel = { entryId -> service?.downloadHuggingFaceModel(entryId) },
                                    onSettingsChange = { settings -> service?.updateGenerationSettings(settings) },
                                    onRunBenchmark = { presetId -> service?.runBenchmarkPreset(presetId) },
                                    onRunThreadSweep = { service?.runThreadSweepBenchmark() },
                                    onExportBenchmarksCsv = {
                                        val csv = service?.benchmarkCsv().orEmpty()
                                        pendingBenchmarkCsv = csv
                                        benchmarkExportLauncher.launch("llm-host-benchmarks-${System.currentTimeMillis()}.csv")
                                    },
                                    onExportBenchmarksJson = {
                                        val json = service?.benchmarkJson().orEmpty()
                                        pendingBenchmarkJson = json
                                        benchmarkJsonExportLauncher.launch("llm-host-benchmarks-${System.currentTimeMillis()}.json")
                                    },
                                    onClearBenchmarks = { service?.clearBenchmarkRuns() },
                                )
                            }
                        }
                        if (chatsVisible) {
                            ModalBottomSheet(
                                onDismissRequest = { chatsVisible = false },
                                sheetState = chatSheetState,
                                containerColor = Color.White,
                                contentColor = PrismText,
                            ) {
                                ChatListSheet(
                                    sessions = chatSessions,
                                    currentChatId = currentChatId,
                                    isGenerating = isGenerating,
                                    hasCurrentTranscript = transcript.isNotEmpty(),
                                    onNewChat = {
                                        service?.createChat()
                                        chatsVisible = false
                                    },
                                    onSwitchChat = { chatId ->
                                        if (service?.switchChat(chatId) == true) {
                                            chatsVisible = false
                                        }
                                    },
                                    onRenameChat = { chatId, title -> service?.renameChat(chatId, title) },
                                    onDeleteChat = { chatId -> service?.deleteChat(chatId) },
                                    onClearCurrentChat = { service?.clearTranscript() },
                                )
                            }
                        }
                    }
                }
            }
            (snackbarMessage ?: uiMessage)?.let { message ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    containerColor = PrismSlate,
                    contentColor = PrismOnDark,
                ) {
                    Text(message)
                }
            }
        }
    }
}

@Composable
private fun ChatTopBar(
    runtimeStatus: RuntimeStatus,
    chatTitle: String?,
    modelName: String?,
    importStatus: String,
    importState: ImportState,
    onOpenChats: () -> Unit,
    onOpenControls: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LLM Host",
                    style = MaterialTheme.typography.titleLarge,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${chatTitle ?: ChatTitles.DEFAULT_TITLE} | ${compactStatus(runtimeStatus, modelName, importStatus, importState)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onOpenChats) {
                Text("Chats")
            }
            TextButton(onClick = onOpenControls) {
                Text("Settings")
            }
        }
    }
}

@Composable
private fun PromptComposer(
    prompt: String,
    enabled: Boolean,
    isGenerating: Boolean,
    onPromptChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = prompt,
            onValueChange = onPromptChange,
            label = { Text("Prompt") },
            enabled = enabled,
            singleLine = false,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (isGenerating) {
            Button(onClick = onCancel) {
                Text("Stop")
            }
        } else {
            Button(
                enabled = enabled && prompt.isNotBlank(),
                onClick = onSend,
            ) {
                Text("Send")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlPlaneSheet(
    models: List<String>,
    currentModel: String?,
    activeModelInfo: ModelStorageManager.ActiveModelInfo?,
    runtimeStatus: RuntimeStatus,
    importState: ImportState,
    modelDownloadState: ModelDownloadState,
    importStatus: String,
    recoveryTranscript: String?,
    generationSettings: GenerationSettings,
    generationPerformance: GenerationPerformance?,
    benchmarkRuns: List<BenchmarkRun>,
    benchmarkStatus: BenchmarkStatus,
    modelLoadDiagnostics: ModelLoadDiagnostics?,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    modelReadiness: List<ModelReadiness>,
    hfCatalog: List<HuggingFaceModelEntry>,
    isGenerating: Boolean,
    serviceAvailable: Boolean,
    onSwitchModel: (String) -> Unit,
    onImportModel: () -> Unit,
    onCancelImport: () -> Unit,
    onDownloadModel: (String) -> Unit,
    onSettingsChange: (GenerationSettings) -> Unit,
    onRunBenchmark: (String) -> Unit,
    onRunThreadSweep: () -> Unit,
    onExportBenchmarksCsv: () -> Unit,
    onExportBenchmarksJson: () -> Unit,
    onClearBenchmarks: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingModelId by remember { mutableStateOf<String?>(null) }
    var riskyModel by remember { mutableStateOf<ModelReadiness?>(null) }
    val isImporting = importState is ImportState.Running
    val isLoadingModel = runtimeStatus == RuntimeStatus.LOADING_MODEL
    val controlsEnabled = !isGenerating && !isLoadingModel && !isImporting

    LaunchedEffect(isLoadingModel, currentModel) {
        if (!isLoadingModel) {
            pendingModelId = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Model & Runtime",
            style = MaterialTheme.typography.titleMedium,
            color = PrismBlue,
            fontWeight = FontWeight.SemiBold,
        )

        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = !menuExpanded && serviceAvailable && !isLoadingModel && !isImporting },
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                readOnly = true,
                value = compactModelName(pendingModelId ?: currentModel),
                onValueChange = {},
                label = { Text("Model") },
                placeholder = { Text(if (models.isEmpty()) "No models installed" else "Select model") },
                enabled = serviceAvailable && !isLoadingModel && !isImporting,
                singleLine = true,
                trailingIcon = {
                    if (isLoadingModel) {
                        InfinityLoadingIndicator(modifier = Modifier.size(28.dp))
                    } else {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                    }
                },
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No models installed") },
                        onClick = { menuExpanded = false },
                        enabled = false,
                    )
                } else {
                    models.forEach { modelId ->
                        val readiness = modelReadiness.firstOrNull { it.info.id == modelId }
                        DropdownMenuItem(
                            text = {
                                ModelPickerRow(
                                    modelId = modelId,
                                    readiness = readiness,
                                )
                            },
                            enabled = !isLoadingModel && readiness?.fit?.rating != ModelFitRating.TOO_LARGE,
                            onClick = {
                                menuExpanded = false
                                if (readiness?.fit?.rating == ModelFitRating.RISKY) {
                                    riskyModel = readiness
                                } else {
                                    pendingModelId = modelId
                                    onSwitchModel(modelId)
                                }
                            },
                        )
                    }
                }
            }
        }

        if (isLoadingModel) {
            LoadingModelStatus(
                modelId = pendingModelId ?: currentModel,
                diagnostics = modelLoadDiagnostics,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = serviceAvailable && !isImporting && !isLoadingModel,
                onClick = onImportModel,
            ) {
                Text("Import Model")
            }
            if (isImporting) {
                Button(onClick = onCancelImport) {
                    Text("Cancel Import")
                }
            }
        }

        if (importStatus.isNotEmpty()) {
            Text(
                text = importStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (importState is ImportState.Running) {
            ImportProgressBar(importState)
        }

        HuggingFaceDownloadPanel(
            entries = hfCatalog,
            state = modelDownloadState,
            deviceCapabilityProfile = deviceCapabilityProfile,
            enabled = serviceAvailable && !isImporting && !isLoadingModel && !isGenerating,
            onDownload = onDownloadModel,
            onCancel = onCancelImport,
        )

        deviceCapabilityProfile?.let { profile ->
            DeviceCapabilityCard(profile)
        }

        activeModelInfo?.let { model ->
            ModelMetadata(
                model = model,
                diagnostics = modelLoadDiagnostics?.takeIf { it.modelId == model.id },
                readiness = modelReadiness.firstOrNull { it.info.id == model.id },
            )
        }

        recoveryTranscript?.let { path ->
            Text(
                text = "Recovery transcript: $path",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        RuntimeControls(
            settings = generationSettings,
            performance = generationPerformance,
            enabled = controlsEnabled,
            onSettingsChange = onSettingsChange,
        )

        BenchmarkCenter(
            models = models,
            activeModelInfo = activeModelInfo,
            deviceCapabilityProfile = deviceCapabilityProfile,
            runs = benchmarkRuns,
            readiness = modelReadiness,
            presets = BenchmarkPresets.defaults,
            status = benchmarkStatus,
            isGenerating = isGenerating,
            disabledReason = benchmarkDisabledReason(
                serviceAvailable = serviceAvailable,
                currentModel = currentModel,
                isGenerating = isGenerating,
                status = benchmarkStatus,
            ),
            onRunPreset = onRunBenchmark,
            onRunThreadSweep = onRunThreadSweep,
            onExportCsv = onExportBenchmarksCsv,
            onExportJson = onExportBenchmarksJson,
            onClear = onClearBenchmarks,
        )
    }

    riskyModel?.let { readiness ->
        AlertDialog(
            onDismissRequest = { riskyModel = null },
            title = { Text("Load risky model?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = compactModelName(readiness.info.id),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${readiness.fit.reason}. Estimated RAM need ${formatBytes(readiness.fit.requiredRamBytes)} with ${formatBytes(readiness.fit.availableRamAfterUnloadBytes)} available after unload.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Expected ${predictionRange(readiness.prediction)} ${readiness.prediction.basis}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isLoadingModel && !isImporting,
                    onClick = {
                        pendingModelId = readiness.info.id
                        onSwitchModel(readiness.info.id)
                        riskyModel = null
                    },
                ) {
                    Text("Load Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { riskyModel = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun LoadingModelStatus(modelId: String?, diagnostics: ModelLoadDiagnostics?) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InfinityLoadingIndicator(modifier = Modifier.size(34.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Loading model",
                    style = MaterialTheme.typography.labelMedium,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = compactModelName(modelId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                diagnostics?.let { info ->
                    Text(
                        text = "RAM ${info.availableMemoryMb ?: 0} MB | ${info.state}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    modelId: String,
    readiness: ModelReadiness?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = modelId,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        readiness?.let { info ->
            Text(
                text = "${info.performance.label} | ${info.fit.quantization ?: "quant unknown"} | expected ${predictionRange(info.prediction)}",
                style = MaterialTheme.typography.labelSmall,
                color = performanceColor(info.performance.tier),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DeviceCapabilityCard(profile: DeviceCapabilityProfile) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Device",
                style = MaterialTheme.typography.labelMedium,
                color = PrismBlue,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "RAM ${formatBytes(profile.availableRamBytes)} / ${formatBytes(profile.totalRamBytes)} | app ${profile.memoryClassMb} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${profile.cpuCoreCount} cores | Android ${profile.androidSdk} | ${profile.abis.firstOrNull() ?: "ABI unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Storage ${formatBytes(profile.storageFreeBytes)} free | battery ${profile.batteryPercent?.let { "$it%" } ?: "unknown"} | thermal ${profile.thermalStatus ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profile.lowMemory) {
                Text(
                    text = "Android reports low memory",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismAmber,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun HuggingFaceDownloadPanel(
    entries: List<HuggingFaceModelEntry>,
    state: ModelDownloadState,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    enabled: Boolean,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Hugging Face Text Models",
                style = MaterialTheme.typography.labelMedium,
                color = PrismBlue,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Curated GGUF downloads only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                ModelDownloadState.Idle -> Unit
                ModelDownloadState.Cancelled -> Text(
                    text = "Download cancelled",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismAmber,
                )
                is ModelDownloadState.Failure -> Text(
                    text = "${state.entryName}: ${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismRed,
                )
                is ModelDownloadState.Success -> Text(
                    text = "Downloaded ${state.entryName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismGreen,
                )
                is ModelDownloadState.Running -> {
                    Text(
                        text = "${state.stage.label()} ${state.entry.name}: ${formatBytes(state.bytesDone)} / ${state.totalBytes?.let(::formatBytes) ?: "unknown"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DownloadProgressBar(
                        bytesDone = state.bytesDone,
                        totalBytes = state.totalBytes,
                    )
                    Button(onClick = onCancel) {
                        Text("Cancel Download")
                    }
                }
            }
            entries.forEach { entry ->
                DownloadCatalogRow(
                    entry = entry,
                    deviceCapabilityProfile = deviceCapabilityProfile,
                    enabled = enabled && state !is ModelDownloadState.Running,
                    onDownload = { onDownload(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun DownloadCatalogRow(
    entry: HuggingFaceModelEntry,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${entry.parameters} | ${entry.quantization} | ${formatBytes(entry.expectedBytes)} | ${entry.license}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    enabled = enabled && hasEnoughFreeStorage(entry, deviceCapabilityProfile),
                    onClick = onDownload,
                ) {
                    Text("Download")
                }
            }
            Text(
                text = entry.notes,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.repoId,
                style = MaterialTheme.typography.labelSmall,
                color = PrismBlue,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hasEnoughFreeStorage(entry, deviceCapabilityProfile)) {
                Text(
                    text = "Needs more free storage",
                    style = MaterialTheme.typography.labelSmall,
                    color = PrismRed,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressBar(bytesDone: Long, totalBytes: Long?) {
    val progress = totalBytes
        ?.takeIf { it > 0L }
        ?.let { total -> (bytesDone.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() }
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = PrismBlue,
            trackColor = AssistantBubble,
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = PrismBlue,
            trackColor = AssistantBubble,
        )
    }
}

private fun ModelDownloadState.Running.Stage.label(): String = when (this) {
    ModelDownloadState.Running.Stage.DOWNLOADING -> "Downloading"
    ModelDownloadState.Running.Stage.IMPORTING -> "Importing"
}

private fun hasEnoughFreeStorage(
    entry: HuggingFaceModelEntry,
    profile: DeviceCapabilityProfile?,
): Boolean =
    profile?.let { it.storageFreeBytes > entry.expectedBytes + 512L * 1024L * 1024L } ?: true

@Composable
private fun ImportProgressBar(state: ImportState.Running) {
    val progress = state.progressFraction()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = PrismBlue,
                trackColor = AssistantBubble,
            )
            Text(
                text = "${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = PrismBlue,
                trackColor = AssistantBubble,
            )
        }
    }
}

@Composable
private fun InfinityLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = PrismBlue,
) {
    val transition = rememberInfiniteTransition(label = "infinityLoading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1300)),
        label = "infinityRotation",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "infinityPulse",
    )

    Canvas(modifier = modifier.size(32.dp)) {
        val strokeWidth = size.minDimension * 0.07f
        val path = launcherInfinityPath(size.width, size.height)
        drawPath(
            path = path,
            color = PrismViolet.copy(alpha = 0.18f),
            style = Stroke(width = strokeWidth * 2.0f, cap = StrokeCap.Round),
        )
        rotate(degrees = rotation) {
            drawPath(
                path = path,
                color = color.copy(alpha = alpha),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawPath(
                path = path,
                color = PrismCyan.copy(alpha = alpha * 0.64f),
                style = Stroke(width = strokeWidth * 0.55f, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun ChatListSheet(
    sessions: List<ChatSession>,
    currentChatId: String?,
    isGenerating: Boolean,
    hasCurrentTranscript: Boolean,
    onNewChat: () -> Unit,
    onSwitchChat: (String) -> Unit,
    onRenameChat: (String, String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onClearCurrentChat: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "Chats",
                style = MaterialTheme.typography.titleMedium,
                color = PrismBlue,
                fontWeight = FontWeight.SemiBold,
            )
            Button(
                enabled = !isGenerating,
                onClick = onNewChat,
            ) {
                Text("New Chat")
            }
        }

        if (sessions.isEmpty()) {
            Text(
                text = "No chats yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    ChatSessionRow(
                        session = session,
                        selected = session.id == currentChatId,
                        isGenerating = isGenerating,
                        onOpen = { onSwitchChat(session.id) },
                        onRename = {
                            renameTarget = session
                            renameTitle = session.title
                        },
                        onDelete = { deleteTarget = session },
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        TextButton(
            enabled = hasCurrentTranscript && !isGenerating,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onClick = onClearCurrentChat,
        ) {
            Text("Clear Current Chat")
        }
    }

    renameTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = renameTitle,
                    onValueChange = { renameTitle = it },
                    singleLine = true,
                    label = { Text("Title") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameTitle.isNotBlank(),
                    onClick = {
                        onRenameChat(session.id, renameTitle)
                        renameTarget = null
                    },
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete chat?") },
            text = {
                Text(
                    text = "This removes the local transcript for \"${session.title}\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isGenerating,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        onDeleteChat(session.id)
                        deleteTarget = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ChatSessionRow(
    session: ChatSession,
    selected: Boolean,
    isGenerating: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) UserBubble else Color.White,
        contentColor = PrismText,
        border = BorderStroke(1.dp, if (selected) PrismBlue else PrismGlassBorder),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${session.messageCount} messages | ${compactModelName(session.modelId)} | ${formatChatTimestamp(session.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !selected && !isGenerating,
                    onClick = onOpen,
                ) {
                    Text(if (selected) "Current" else "Open")
                }
                TextButton(
                    enabled = !isGenerating,
                    onClick = onRename,
                ) {
                    Text("Rename")
                }
                TextButton(
                    enabled = !isGenerating,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = onDelete,
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun ModelMetadata(
    model: ModelStorageManager.ActiveModelInfo,
    diagnostics: ModelLoadDiagnostics?,
    readiness: ModelReadiness?,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${model.id} / ${model.versionId}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${model.fileName} | ${formatBytes(model.bytes)} | GGUF v${model.validation.ggufVersion} ${model.validation.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "SHA-256: ${shortHash(model.sha256)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            diagnostics?.let { load ->
                Text(
                    text = "Load ${load.loadMs} ms | RAM ${load.availableMemoryMb ?: 0} MB | ${load.state}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            readiness?.let { modelReadiness ->
                Text(
                    text = "${modelReadiness.performance.label} | needs ${formatBytes(modelReadiness.fit.requiredRamBytes)} | expected ${predictionRange(modelReadiness.prediction)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = performanceColor(modelReadiness.performance.tier),
                )
            }
        }
    }
}

@Composable
private fun RuntimeControls(
    settings: GenerationSettings,
    performance: GenerationPerformance?,
    enabled: Boolean,
    onSettingsChange: (GenerationSettings) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Runtime",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        SettingSlider(
            label = "Tokens",
            valueText = settings.maxTokens.toString(),
            value = settings.maxTokens.toFloat(),
            valueRange = GenerationSettings.MIN_MAX_TOKENS.toFloat()..GenerationSettings.MAX_MAX_TOKENS.toFloat(),
            steps = 15,
            enabled = enabled,
            onValueChange = { value ->
                onSettingsChange(settings.copy(maxTokens = snapTokens(value)))
            },
        )
        SettingSlider(
            label = "Threads",
            valueText = settings.threadCount.toString(),
            value = settings.threadCount.toFloat(),
            valueRange = GenerationSettings.MIN_THREAD_COUNT.toFloat()..GenerationSettings.MAX_THREAD_COUNT.toFloat(),
            steps = GenerationSettings.MAX_THREAD_COUNT - GenerationSettings.MIN_THREAD_COUNT - 1,
            enabled = enabled,
            onValueChange = { value ->
                onSettingsChange(settings.copy(threadCount = value.roundToInt()))
            },
        )
        performance?.let { stats ->
            Text(
                text = "Perf: ${stats.generatedTokens} tok | ${formatTokensPerSecond(stats.tokensPerSecond)} tok/s | ${stats.totalMs} ms${stats.terminalSuffix()}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Prompt ${stats.promptEvalMs} ms | decode ${stats.decodeMs} ms",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private enum class BenchmarkTab(val label: String) {
    Runs("Runs"),
    Compare("Compare"),
    Models("Models"),
    Export("Export"),
}

private data class BenchmarkSummary(
    val count: Int,
    val avgTokensPerSecond: Double?,
    val bestTokensPerSecond: Double?,
    val avgPromptMs: Long?,
    val avgTotalMs: Long?,
)

@Composable
private fun BenchmarkCenter(
    models: List<String>,
    activeModelInfo: ModelStorageManager.ActiveModelInfo?,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    runs: List<BenchmarkRun>,
    readiness: List<ModelReadiness>,
    presets: List<BenchmarkPreset>,
    status: BenchmarkStatus,
    isGenerating: Boolean,
    disabledReason: String?,
    onRunPreset: (String) -> Unit,
    onRunThreadSweep: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onClear: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(BenchmarkTab.Runs) }
    val summary = benchmarkSummary(runs)
    val enabled = disabledReason == null

    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Benchmarks",
                style = MaterialTheme.typography.labelMedium,
                color = PrismBlue,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (summary.count == 0) {
                    "No runs recorded"
                } else {
                    "${summary.count} runs | avg ${formatOptionalTps(summary.avgTokensPerSecond)} | best ${formatOptionalTps(summary.bestTokensPerSecond)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (status.isRunning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InfinityLoadingIndicator(modifier = Modifier.size(24.dp), color = PrismViolet)
                    Text(
                        text = "Running ${status.presetName ?: "benchmark"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PrismText,
                    )
                }
            }
            disabledReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismAmber,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BenchmarkTab.values().forEach { tab ->
                    val selected = selectedTab == tab
                    TextButton(
                        onClick = { selectedTab = tab },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (selected) PrismBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(tab.label)
                    }
                }
            }

            when (selectedTab) {
                BenchmarkTab.Runs -> BenchmarkRunsTab(
                    runs = runs,
                    presets = presets,
                    enabled = enabled,
                    onRunPreset = onRunPreset,
                    onRunThreadSweep = onRunThreadSweep,
                )
                BenchmarkTab.Compare -> BenchmarkCompareTab(
                    runs = runs,
                    readiness = readiness,
                )
                BenchmarkTab.Models -> BenchmarkModelsTab(
                    models = models,
                    activeModelInfo = activeModelInfo,
                    deviceCapabilityProfile = deviceCapabilityProfile,
                    readiness = readiness,
                    runs = runs,
                )
                BenchmarkTab.Export -> BenchmarkExportTab(
                    hasRuns = runs.isNotEmpty(),
                    isGenerating = isGenerating || status.isRunning,
                    onExportCsv = onExportCsv,
                    onExportJson = onExportJson,
                    onClear = onClear,
                )
            }
        }
    }
}

@Composable
private fun BenchmarkRunsTab(
    runs: List<BenchmarkRun>,
    presets: List<BenchmarkPreset>,
    enabled: Boolean,
    onRunPreset: (String) -> Unit,
    onRunThreadSweep: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Presets",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = onRunThreadSweep,
        ) {
            Text("Run Thread Sweep 2/4/6/8", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        presets.forEach { preset ->
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = { onRunPreset(preset.id) },
            ) {
                Text("Run ${preset.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (runs.isEmpty()) {
            Text(
                text = "Run a preset or send a chat prompt to collect local performance data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            runs.take(5).forEach { run -> BenchmarkRunRow(run) }
        }
    }
}

@Composable
private fun BenchmarkCompareTab(
    runs: List<BenchmarkRun>,
    readiness: List<ModelReadiness>,
) {
    val modelIds = (readiness.map { it.info.id } + runs.mapNotNull { it.modelId })
        .distinct()
    val byModel = modelIds
        .map { modelId ->
            val modelRuns = runs.filter { it.modelId == modelId }
            val modelReadiness = readiness.firstOrNull { it.info.id == modelId }
            Triple(modelId, benchmarkSummary(modelRuns), modelReadiness)
        }
        .sortedByDescending { it.second.avgTokensPerSecond ?: it.third?.prediction?.maxTokensPerSecond ?: 0.0 }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (byModel.isEmpty()) {
            Text(
                text = "No comparison data yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            byModel.take(6).forEach { (modelId, summary, modelReadiness) ->
                val actual = summary.avgTokensPerSecond?.let { "${formatTokensPerSecond(it)} tok/s actual" } ?: "No actual yet"
                val predicted = modelReadiness?.prediction?.let { prediction ->
                    "predicted ${predictionRange(prediction)}"
                } ?: "prediction pending"
                BenchmarkMetricRow(
                    label = compactModelName(modelId),
                    value = actual,
                    detail = "$predicted | ${summary.count} runs | first ${formatOptionalMs(summary.avgPromptMs)}",
                )
            }
        }
    }
}

@Composable
private fun BenchmarkModelsTab(
    models: List<String>,
    activeModelInfo: ModelStorageManager.ActiveModelInfo?,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    readiness: List<ModelReadiness>,
    runs: List<BenchmarkRun>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        deviceCapabilityProfile?.let { profile ->
            Text(
                text = "Device RAM ${formatBytes(profile.availableRamBytes)} available | heap ${formatBytes(profile.appHeapMaxBytes)} max",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (models.isEmpty()) {
            Text(
                text = "No models installed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            models.forEach { modelId ->
                val summary = benchmarkSummary(runs.filter { it.modelId == modelId })
                val activeSize = activeModelInfo?.takeIf { it.id == modelId }?.bytes
                val modelReadiness = readiness.firstOrNull { it.info.id == modelId }
                BenchmarkMetricRow(
                    label = compactModelName(modelId),
                    value = modelReadiness?.performance?.label ?: "Unknown",
                    detail = listOfNotNull(
                        activeSize?.let { formatBytes(it) } ?: modelReadiness?.info?.bytes?.let { formatBytes(it) },
                        modelReadiness?.fit?.quantization,
                        modelReadiness?.performance?.averageTokensPerSecond?.let { "actual ${formatTokensPerSecond(it)} tok/s" },
                        modelReadiness?.fit?.requiredRamBytes?.let { "needs ${formatBytes(it)}" },
                        modelReadiness?.prediction?.let { "expected ${predictionRange(it)}" },
                        "${summary.count} runs",
                    ).joinToString(" | "),
                )
            }
        }
    }
}

@Composable
private fun BenchmarkExportTab(
    hasRuns: Boolean,
    isGenerating: Boolean,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = hasRuns && !isGenerating,
            onClick = onExportCsv,
        ) {
            Text("Export CSV")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = hasRuns && !isGenerating,
            onClick = onExportJson,
        ) {
            Text("Export JSON")
        }
        TextButton(
            enabled = hasRuns && !isGenerating,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onClick = onClear,
        ) {
            Text("Clear Benchmark History")
        }
    }
}

@Composable
private fun BenchmarkRunRow(run: BenchmarkRun) {
    BenchmarkMetricRow(
        label = "${run.presetName ?: run.source.replaceFirstChar { it.uppercase() }} | ${formatChatTimestamp(run.createdAt)}",
        value = "${formatTokensPerSecond(run.tokensPerSecond)} tok/s",
        detail = "${compactModelName(run.modelId)} | ${run.generatedTokens} tok | first ${run.promptEvalMs} ms | ${run.terminalReason}",
    )
}

@Composable
private fun BenchmarkMetricRow(label: String, value: String, detail: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
    }
}

@Composable
private fun MessageBubble(
    label: String,
    text: String,
    isUser: Boolean,
    showLoading: Boolean,
    performance: GenerationPerformance? = null,
) {
    val bubbleColor = if (isUser) {
        UserBubble
    } else {
        AssistantBubble
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val labelColor = if (isUser) PrismBlue else PrismViolet

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .background(color = bubbleColor, shape = RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    fontWeight = FontWeight.SemiBold,
                )
                if (showLoading) {
                    InfinityLoadingIndicator(
                        modifier = Modifier.size(22.dp),
                        color = PrismViolet,
                    )
                    performance?.let { stats ->
                        Text(
                            text = "${stats.generatedTokens} tok | ${formatTokensPerSecond(stats.tokensPerSecond)} tok/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (isUser) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = PrismText)
            } else {
                MarkdownText(text = text, color = PrismText)
            }
        }
    }
}

@Composable
private fun GlassSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = PrismGlass,
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

private val PrismCyan = Color(0xFF06B6D4)
private val PrismBlue = Color(0xFF2563EB)
private val PrismViolet = Color(0xFFA855F7)
private val PrismGreen = Color(0xFF16A34A)
private val PrismAmber = Color(0xFFD97706)
private val PrismRed = Color(0xFFDC2626)
private val PrismText = Color(0xFF0F172A)
private val PrismSlate = Color(0xFF1E293B)
private val PrismOnDark = Color(0xFFF8FAFC)
private val PrismGlass = Color(0xEFFFFFFF)
private val PrismGlassBorder = Color(0x6693C5FD)
private val UserBubble = Color(0xFFE0F7FF)
private val AssistantBubble = Color(0xFFF4E8FF)

private val LlmHostPrismaticColorScheme = lightColorScheme(
    primary = PrismBlue,
    onPrimary = Color.White,
    primaryContainer = UserBubble,
    onPrimaryContainer = Color(0xFF082F49),
    secondary = PrismViolet,
    onSecondary = Color.White,
    secondaryContainer = AssistantBubble,
    onSecondaryContainer = Color(0xFF3B0764),
    tertiary = PrismCyan,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF042F2E),
    background = Color(0xFFFBFCFF),
    onBackground = PrismText,
    surface = Color.White,
    onSurface = PrismText,
    surfaceVariant = Color(0xFFEFF6FF),
    onSurfaceVariant = Color(0xFF334155),
    outline = Color(0xFF93C5FD),
    outlineVariant = Color(0xFFD8B4FE),
    error = Color(0xFFDC2626),
    onError = Color.White,
)

private fun compactStatus(
    status: RuntimeStatus,
    modelName: String?,
    importStatus: String,
    importState: ImportState,
): String {
    return if (importState is ImportState.Running && importStatus.isNotBlank()) {
        "${status.label()} | $importStatus"
    } else {
        "${status.label()} | ${compactModelName(modelName)}"
    }
}

private fun ImportState.Running.progressFraction(): Float? =
    totalBytes
        ?.takeIf { it > 0L }
        ?.let { total -> (bytesCopied.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() }

private fun launcherInfinityPath(width: Float, height: Float): Path =
    Path().apply {
        fun x(value: Float) = width * value / 108f
        fun y(value: Float) = height * value / 108f

        moveTo(x(27f), y(54f))
        cubicTo(x(27f), y(43f), x(38f), y(39f), x(47f), y(48f))
        lineTo(x(54f), y(55f))
        lineTo(x(61f), y(48f))
        cubicTo(x(70f), y(39f), x(81f), y(43f), x(81f), y(54f))
        cubicTo(x(81f), y(65f), x(70f), y(69f), x(61f), y(60f))
        lineTo(x(54f), y(53f))
        lineTo(x(47f), y(60f))
        cubicTo(x(38f), y(69f), x(27f), y(65f), x(27f), y(54f))
    }

internal fun compactModelName(modelId: String?): String {
    if (modelId.isNullOrBlank()) {
        return "No model selected"
    }
    val withoutExtension = modelId.removeSuffix(".gguf")
    val withoutQuant = withoutExtension
        .replace(Regex("[-_](?:I?Q\\d(?:_[Kk])?_[A-Za-z0-9]+|F16|BF16|Q\\d_\\d)$"), "")
        .replace(Regex("[-_](?:GGUF|gguf)$"), "")
    return withoutQuant
        .replace('-', ' ')
        .replace('_', ' ')
        .trim()
        .ifBlank { withoutExtension }
        .let { name ->
            if (name.length <= 38) {
                name
            } else {
                "${name.take(35).trimEnd()}..."
            }
        }
}

private fun snapTokens(value: Float): Int {
    val step = GenerationSettings.MAX_TOKEN_STEP
    val snapped = (value / step).roundToInt() * step
    return snapped.coerceIn(GenerationSettings.MIN_MAX_TOKENS, GenerationSettings.MAX_MAX_TOKENS)
}

private fun formatTokensPerSecond(value: Double): String =
    String.format(Locale.US, "%.2f", value)

private fun formatOptionalTps(value: Double?): String =
    value?.let { "${formatTokensPerSecond(it)} tok/s" } ?: "pending"

private fun formatOptionalMs(value: Long?): String =
    value?.let { "$it ms" } ?: "pending"

private fun benchmarkDisabledReason(
    serviceAvailable: Boolean,
    currentModel: String?,
    isGenerating: Boolean,
    status: BenchmarkStatus,
): String? = when {
    !serviceAvailable -> "Benchmark unavailable while the service reconnects"
    currentModel == null -> "Select a model before running benchmarks"
    status.isRunning -> "Benchmark already running: ${status.presetName ?: "current run"}"
    isGenerating -> "Stop the current generation before running another benchmark"
    else -> null
}

private fun predictionRange(prediction: PerformancePrediction): String =
    "${formatTokensPerSecond(prediction.minTokensPerSecond)}-${formatTokensPerSecond(prediction.maxTokensPerSecond)} tok/s"

private fun fitLabel(rating: ModelFitRating): String = when (rating) {
    ModelFitRating.SAFE -> "Recommended"
    ModelFitRating.RISKY -> "May be slow"
    ModelFitRating.TOO_LARGE -> "Likely too large"
}

private fun fitColor(rating: ModelFitRating): Color = when (rating) {
    ModelFitRating.SAFE -> PrismGreen
    ModelFitRating.RISKY -> PrismAmber
    ModelFitRating.TOO_LARGE -> PrismRed
}

private fun performanceColor(tier: ModelPerformanceTier): Color = when (tier) {
    ModelPerformanceTier.UNKNOWN -> PrismSlate
    ModelPerformanceTier.NOT_RECOMMENDED -> PrismRed
    ModelPerformanceTier.VERY_SLOW -> PrismAmber
    ModelPerformanceTier.USABLE -> PrismBlue
    ModelPerformanceTier.RECOMMENDED -> PrismGreen
}

private fun benchmarkSummary(runs: List<BenchmarkRun>): BenchmarkSummary {
    val completed = runs.filter { it.generatedTokens > 0 && it.decodeMs > 0L }
    return BenchmarkSummary(
        count = runs.size,
        avgTokensPerSecond = completed.takeIf { it.isNotEmpty() }?.map { it.tokensPerSecond }?.average(),
        bestTokensPerSecond = completed.maxOfOrNull { it.tokensPerSecond },
        avgPromptMs = completed.takeIf { it.isNotEmpty() }?.map { it.promptEvalMs }?.average()?.roundToInt()?.toLong(),
        avgTotalMs = completed.takeIf { it.isNotEmpty() }?.map { it.totalMs }?.average()?.roundToInt()?.toLong(),
    )
}

private fun GenerationPerformance.terminalSuffix(): String =
    terminalReason?.let { " | $it" }.orEmpty()

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        "%.1f MB".format(mb)
    } else {
        "$bytes B"
    }
}

private fun shortHash(hash: String): String =
    if (hash.length <= 18) hash else "${hash.take(10)}...${hash.takeLast(8)}"

private fun formatChatTimestamp(updatedAt: Long): String {
    val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
    val minuteMs = 60_000L
    val hourMs = 60L * minuteMs
    val dayMs = 24L * hourMs
    return when {
        ageMs < minuteMs -> "Just now"
        ageMs < hourMs -> "${ageMs / minuteMs}m ago"
        ageMs < dayMs -> "${ageMs / hourMs}h ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.US).format(Date(updatedAt))
    }
}

private fun RuntimeStatus.label(): String {
    return when (this) {
        RuntimeStatus.IDLE -> "Idle"
        RuntimeStatus.LOADING_MODEL -> "Loading model"
        RuntimeStatus.IMPORTING -> "Importing"
        RuntimeStatus.GENERATING -> "Generating"
        RuntimeStatus.CANCELLING -> "Cancelling"
        RuntimeStatus.ERROR -> "Error"
    }
}
