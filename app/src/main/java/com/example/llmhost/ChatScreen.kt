package com.example.llmhost

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val MAX_PROMPT_ATTACHMENTS = 6

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
        var memoriesVisible by remember { mutableStateOf(false) }
        val controlSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val chatSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val memoriesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            Surface(modifier = Modifier.fillMaxSize(), color = PrismCanvas) {
                PrismBackdrop(modifier = Modifier.fillMaxSize())
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val minChatHeight = maxHeight * 0.70f
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
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
                        val pendingAgentToolAction by (service?.pendingAgentToolAction ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = null
                        )
                        val memories by (service?.memories ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = emptyList()
                        )
                        var prompt by remember { mutableStateOf("") }
                        var attachments by remember { mutableStateOf<List<PromptAttachment>>(emptyList()) }
                        var importStatus by remember { mutableStateOf("") }
                        var pendingBenchmarkCsv by remember { mutableStateOf<String?>(null) }
                        var pendingBenchmarkJson by remember { mutableStateOf<String?>(null) }
                        val listState = rememberLazyListState()
                        val bottomAnchorIndex = if (transcript.isEmpty()) 0 else transcript.size
                        val isAtBottomAnchor by remember(transcript.size) {
                            derivedStateOf {
                                transcript.isEmpty() ||
                                    listState.layoutInfo.visibleItemsInfo.any { item -> item.index == bottomAnchorIndex }
                            }
                        }
                        val headerCollapsed by remember(transcript.size) {
                            derivedStateOf {
                                transcript.isNotEmpty() &&
                                    (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 16)
                            }
                        }
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
                        val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                            onImportPickerFinished()
                            if (uris.isEmpty()) {
                                return@rememberLauncherForActivityResult
                            }
                            val importedModels = mutableListOf<String>()
                            val attached = mutableListOf<PromptAttachment>()
                            uris.forEach { uri ->
                                runCatching {
                                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val name = AttachmentTextExtractor.displayName(context, uri)
                                if (name.endsWith(".gguf", ignoreCase = true)) {
                                    service?.importModel(uri)
                                    importedModels += name
                                } else {
                                    attached += AttachmentTextExtractor.fromUri(context, uri)
                                }
                            }
                            if (attached.isNotEmpty()) {
                                attachments = (attachments + attached)
                                    .distinctBy { it.uriString }
                                    .takeLast(MAX_PROMPT_ATTACHMENTS)
                            }
                            snackbarMessage = when {
                                importedModels.isNotEmpty() && attached.isNotEmpty() ->
                                    "Importing ${importedModels.size} model(s), attached ${attached.size} file(s)"
                                importedModels.isNotEmpty() ->
                                    "Importing ${importedModels.size} model(s)"
                                attached.isNotEmpty() ->
                                    "Attached ${attached.size} file(s)"
                                else -> null
                            }
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

                        LaunchedEffect(service) {
                            service?.panelRequests?.collect { panel ->
                                when (panel) {
                                    "chats" -> chatsVisible = true
                                    "model_manager",
                                    "benchmarks",
                                    "settings" -> controlsVisible = true
                                }
                            }
                        }

                        LaunchedEffect(transcript.size, isGenerating) {
                            if (transcript.isNotEmpty()) {
                                if (!isGenerating || transcript.size <= 2 || isAtBottomAnchor) {
                                    listState.animateScrollToItem(bottomAnchorIndex)
                                }
                            }
                        }

                        ChatTopBar(
                            runtimeStatus = runtimeStatus,
                            chatTitle = chatSessions.firstOrNull { it.id == currentChatId }?.title,
                            modelName = currentModel,
                            importStatus = importStatus,
                            importState = importState,
                            collapsed = headerCollapsed,
                            onOpenChats = { chatsVisible = true },
                            onOpenControls = { controlsVisible = true },
                            onOpenMemories = { memoriesVisible = true },
                        )

                        Spacer(modifier = Modifier.height(22.dp))

                        val activeAssistantMessageId by remember(transcript.size, isGenerating) {
                            derivedStateOf {
                                transcript.lastOrNull { it.role == TranscriptRole.ASSISTANT }?.id
                            }
                        }
                        val modelActionsEnabled = service != null &&
                            !isGenerating &&
                            runtimeStatus != RuntimeStatus.LOADING_MODEL &&
                            importState !is ImportState.Running &&
                            modelDownloadState !is ModelDownloadState.Running

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = minChatHeight)
                                .fillMaxWidth(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (transcript.isEmpty()) {
                                item(key = "model-onboarding") {
                                    ModelOnboardingCard(
                                        currentModel = currentModel,
                                        installedModelCount = models.size,
                                        recommendedModel = hfCatalog.firstOrNull(),
                                        downloadState = modelDownloadState,
                                        enabled = modelActionsEnabled,
                                        onImportModel = {
                                            onImportPickerStarted()
                                            importLauncher.launch(arrayOf("*/*"))
                                        },
                                        onDownloadRecommended = { entry ->
                                            service?.downloadHuggingFaceModel(entry.id)
                                        },
                                        onOpenControls = { controlsVisible = true },
                                    )
                                }
                            } else {
                                items(transcript, key = { it.id }) { message ->
                                    when (message.role) {
                                        TranscriptRole.TOOL -> ToolEventCard(message.text)
                                        TranscriptRole.USER,
                                        TranscriptRole.ASSISTANT -> {
                                            val isUser = message.role == TranscriptRole.USER
                                            val isActiveAssistant = isGenerating && !isUser && message.id == activeAssistantMessageId
                                            if (!isUser && message.text.isBlank() && !isActiveAssistant) {
                                                Spacer(modifier = Modifier.height(0.dp))
                                            } else {
                                                MessageBubble(
                                                    label = if (isUser) "You" else "Assistant",
                                                    text = message.text.ifBlank { if (isActiveAssistant) "..." else "" },
                                                    isUser = isUser,
                                                    showLoading = isActiveAssistant,
                                                    performance = generationPerformance.takeIf { isActiveAssistant },
                                                )
                                            }
                                        }
                                    }
                                }
                                item(key = "bottom-anchor") {
                                    Spacer(modifier = Modifier.fillMaxWidth().height(1.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        PromptComposer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding(),
                            prompt = prompt,
                            enabled = service != null,
                            hasModel = currentModel != null,
                            isGenerating = isGenerating,
                            performance = generationPerformance,
                            attachments = attachments,
                            canContinue = generationPerformance?.terminalReason == "MAX_TOKENS" &&
                                transcript.lastOrNull()?.role == TranscriptRole.ASSISTANT,
                            onPromptChange = { prompt = it },
                            onAddAttachment = {
                                onImportPickerStarted()
                                attachmentLauncher.launch(arrayOf("*/*"))
                            },
                            onRemoveAttachment = { attachment ->
                                attachments = attachments.filterNot { it.uriString == attachment.uriString }
                            },
                            onCancel = { service?.cancelGeneration() },
                            onContinue = { service?.continueGenerationSafely() },
                            onSend = {
                                val text = AttachmentTextExtractor.buildPrompt(prompt.trim(), attachments)
                                if (text.isNotEmpty()) {
                                    prompt = ""
                                    attachments = emptyList()
                                    service?.generateSafely(text)
                                }
                            },
                        )

                        AnimatedVisibility(
                            visible = controlsVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
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
                                    onRunNativeBenchmark = { service?.runNativePpTgBenchmark() },
                                    onExportBenchmarksCsv = {
                                        val csv = service?.benchmarkCsv().orEmpty()
                                        pendingBenchmarkCsv = csv
                                        benchmarkExportLauncher.launch("prism-local-benchmarks-${System.currentTimeMillis()}.csv")
                                    },
                                    onExportBenchmarksJson = {
                                        val json = service?.benchmarkJson().orEmpty()
                                        pendingBenchmarkJson = json
                                        benchmarkJsonExportLauncher.launch("prism-local-benchmarks-${System.currentTimeMillis()}.json")
                                    },
                                    onClearBenchmarks = { service?.clearBenchmarkRuns() },
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = chatsVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            ModalBottomSheet(
                                onDismissRequest = { chatsVisible = false },
                                sheetState = chatSheetState,
                                containerColor = Color.White,
                                contentColor = PrismText,
                                dragHandle = { SheetDragHandle() },
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
                        AnimatedVisibility(
                            visible = memoriesVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            ModalBottomSheet(
                                onDismissRequest = { memoriesVisible = false },
                                sheetState = memoriesSheetState,
                                containerColor = Color.White,
                                contentColor = PrismText,
                                dragHandle = { SheetDragHandle() },
                            ) {
                                MemoryBrowser(
                                    memories = memories,
                                    onDelete = { id -> service?.deleteMemory(id) },
                                    onRefresh = { service?.refreshMemories() },
                                )
                            }
                        }
                        pendingAgentToolAction?.let { action ->
                            AgentToolConfirmationDialog(
                                action = action,
                                onConfirm = { service?.confirmPendingAgentTool() },
                                onDismiss = { service?.cancelPendingAgentTool() },
                            )
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
private fun PrismBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFF8FCFF),
                    Color(0xFFF3F8FF),
                    Color(0xFFFDF6FF),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        )
        drawCircle(
            color = PrismCyan.copy(alpha = 0.14f),
            radius = size.minDimension * 0.28f,
            center = Offset(size.width * 0.04f, size.height * 0.34f),
        )
        drawCircle(
            color = PrismCyan.copy(alpha = 0.10f),
            radius = size.minDimension * 0.22f,
            center = Offset(size.width * 0.02f, size.height * 0.78f),
        )
        drawCircle(
            color = PrismViolet.copy(alpha = 0.14f),
            radius = size.minDimension * 0.30f,
            center = Offset(size.width * 1.02f, size.height * 0.66f),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.58f),
            radius = size.minDimension * 0.38f,
            center = Offset(size.width * 0.62f, size.height * 0.48f),
        )
    }
}

@Composable
private fun ChatTopBar(
    runtimeStatus: RuntimeStatus,
    chatTitle: String?,
    modelName: String?,
    importStatus: String,
    importState: ImportState,
    collapsed: Boolean,
    onOpenChats: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenMemories: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), radius = 32.dp) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            val compact = maxWidth < 520.dp
            if (collapsed) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrismLogoTile(size = 38.dp)
                    Text(
                        modifier = Modifier.weight(1f),
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = PrismBlue)) {
                                append("Prism ")
                            }
                            withStyle(SpanStyle(color = PrismViolet)) {
                                append("Local")
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TopBarIconAction(label = "Chats", onClick = onOpenChats)
                    TopBarIconAction(label = "Memory", onClick = onOpenMemories)
                    TopBarIconAction(label = "Settings", onClick = onOpenControls)
                }
            } else if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PrismLogoTile(size = 54.dp)
                        TopBarTitle(
                            modifier = Modifier.weight(1f),
                            runtimeStatus = runtimeStatus,
                            chatTitle = chatTitle,
                            modelName = modelName,
                            importStatus = importStatus,
                            importState = importState,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TopBarAction(
                            modifier = Modifier.weight(1f),
                            label = "Chats",
                            compact = true,
                            onClick = onOpenChats,
                        )
                        TopBarAction(
                            modifier = Modifier.weight(1f),
                            label = "Memory",
                            compact = true,
                            onClick = onOpenMemories,
                        )
                        TopBarAction(
                            modifier = Modifier.weight(1f),
                            label = "Settings",
                            compact = true,
                            onClick = onOpenControls,
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PrismLogoTile()
                    TopBarTitle(
                        modifier = Modifier.weight(1f),
                        runtimeStatus = runtimeStatus,
                        chatTitle = chatTitle,
                        modelName = modelName,
                        importStatus = importStatus,
                        importState = importState,
                    )
                    TopBarAction(label = "Chats", onClick = onOpenChats)
                    TopBarAction(label = "Memory", onClick = onOpenMemories)
                    TopBarAction(label = "Settings", onClick = onOpenControls)
                }
            }
        }
    }
}

@Composable
private fun TopBarIconAction(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.64f),
        contentColor = PrismBlue,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.76f)),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            TopBarActionIcon(label = label)
        }
    }
}

@Composable
private fun TopBarTitle(
    modifier: Modifier = Modifier,
    runtimeStatus: RuntimeStatus,
    chatTitle: String?,
    modelName: String?,
    importStatus: String,
    importState: ImportState,
) {
    Column(modifier = modifier) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = PrismBlue)) {
                    append("Prism ")
                }
                withStyle(SpanStyle(color = PrismViolet)) {
                    append("Local")
                }
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = topBarSubtitle(runtimeStatus, modelName, importStatus, importState),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(width = 40.dp, height = 4.dp),
            shape = RoundedCornerShape(999.dp),
            color = Color(0xFFD1D5DB),
            contentColor = Color.Transparent,
            shadowElevation = 0.dp,
        ) {}
    }
}

@Composable
private fun PrismLogoTile(size: Dp = 62.dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.72f),
        contentColor = PrismBlue,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.74f)),
        tonalElevation = 2.dp,
        shadowElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            InfinityLoadingIndicator(modifier = Modifier.size(size * 0.68f), color = PrismViolet)
        }
    }
}

@Composable
private fun TopBarAction(
    modifier: Modifier = Modifier,
    label: String,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = if (compact) {
            modifier.height(56.dp)
        } else {
            modifier.size(width = 82.dp, height = 68.dp)
        },
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.64f),
        contentColor = PrismBlue,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.76f)),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        onClick = onClick,
    ) {
        val contentModifier = Modifier.padding(
            vertical = if (compact) 7.dp else 8.dp,
            horizontal = 8.dp,
        )
        if (compact) {
            Row(
                modifier = contentModifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopBarActionIcon(label = label)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismSlate,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            Column(
                modifier = contentModifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TopBarActionIcon(label = label)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismSlate,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun TopBarActionIcon(label: String) {
    Canvas(modifier = Modifier.size(25.dp)) {
        val stroke = Stroke(width = 2.5f, cap = StrokeCap.Round)
        if (label == "Chats") {
            drawRoundRect(
                color = PrismBlue,
                topLeft = Offset(size.width * 0.16f, size.height * 0.18f),
                size = Size(size.width * 0.68f, size.height * 0.52f),
                cornerRadius = CornerRadius(6f, 6f),
                style = stroke,
            )
            drawLine(
                color = PrismBlue,
                start = Offset(size.width * 0.34f, size.height * 0.38f),
                end = Offset(size.width * 0.66f, size.height * 0.38f),
                strokeWidth = 2.2f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = PrismBlue,
                start = Offset(size.width * 0.34f, size.height * 0.52f),
                end = Offset(size.width * 0.56f, size.height * 0.52f),
                strokeWidth = 2.2f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = PrismBlue,
                start = Offset(size.width * 0.36f, size.height * 0.70f),
                end = Offset(size.width * 0.26f, size.height * 0.84f),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round,
            )
        } else {
            drawCircle(
                color = PrismSlate.copy(alpha = 0.72f),
                radius = size.minDimension * 0.18f,
                center = center,
                style = stroke,
            )
            repeat(8) { index ->
                val angle = (index * 45.0) * Math.PI / 180.0
                val inner = size.minDimension * 0.34f
                val outer = size.minDimension * 0.44f
                drawLine(
                    color = PrismSlate.copy(alpha = 0.72f),
                    start = Offset(
                        x = center.x + kotlin.math.cos(angle).toFloat() * inner,
                        y = center.y + kotlin.math.sin(angle).toFloat() * inner,
                    ),
                    end = Offset(
                        x = center.x + kotlin.math.cos(angle).toFloat() * outer,
                        y = center.y + kotlin.math.sin(angle).toFloat() * outer,
                    ),
                    strokeWidth = 2.2f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun ModelOnboardingCard(
    currentModel: String?,
    installedModelCount: Int,
    recommendedModel: HuggingFaceModelEntry?,
    downloadState: ModelDownloadState,
    enabled: Boolean,
    onImportModel: () -> Unit,
    onDownloadRecommended: (HuggingFaceModelEntry) -> Unit,
    onOpenControls: () -> Unit,
) {
    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (currentModel == null) "Choose a local model" else "Start a chat",
                style = MaterialTheme.typography.titleMedium,
                color = PrismBlue,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    currentModel != null -> compactModelName(currentModel)
                    installedModelCount > 0 -> "$installedModelCount installed models available"
                    recommendedModel != null -> "Starter pick: ${recommendedModel.name} (${recommendedModel.parameters}, ${recommendedModel.quantization})"
                    else -> "Import a GGUF model to begin"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            when (downloadState) {
                is ModelDownloadState.Running -> {
                    Text(
                        text = "${downloadState.stage.label()} ${downloadState.entry.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DownloadProgressBar(
                        bytesDone = downloadState.bytesDone,
                        totalBytes = downloadState.totalBytes,
                    )
                }
                is ModelDownloadState.Success -> Text(
                    text = "Downloaded ${downloadState.entryName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismGreen,
                )
                is ModelDownloadState.Failure -> Text(
                    text = downloadState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismRed,
                )
                ModelDownloadState.Cancelled -> Text(
                    text = "Download cancelled",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismAmber,
                )
                ModelDownloadState.Idle -> Unit
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recommendedModel?.let { entry ->
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        onClick = { onDownloadRecommended(entry) },
                    ) {
                        Text(
                            text = "Download ${entry.name}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    onClick = onImportModel,
                ) {
                    Text("Import GGUF", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled || installedModelCount > 0,
                    onClick = onOpenControls,
                ) {
                    Text("Model & Runtime", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PromptComposer(
    modifier: Modifier = Modifier,
    prompt: String,
    enabled: Boolean,
    hasModel: Boolean,
    isGenerating: Boolean,
    performance: GenerationPerformance?,
    attachments: List<PromptAttachment>,
    canContinue: Boolean,
    onPromptChange: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onRemoveAttachment: (PromptAttachment) -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    onSend: () -> Unit,
) {
    val placeholder = when {
        !enabled -> "Reconnecting to Prism Local"
        hasModel -> "Ask about code..."
        else -> "Ask for model help or import a GGUF"
    }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        color = Color.White.copy(alpha = 0.78f),
        contentColor = PrismText,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.80f)),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ComposerIconButton(action = ComposerAction.Add, enabled = enabled && !isGenerating, onClick = onAddAttachment)
                ComposerTextInput(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp, max = 132.dp),
                    value = prompt,
                    onValueChange = onPromptChange,
                    placeholder = placeholder,
                    enabled = enabled,
                )
                if (isGenerating) {
                    ComposerIconButton(
                        action = ComposerAction.Stop,
                        enabled = enabled,
                        onClick = onCancel,
                    )
                } else {
                    val hasPrompt = prompt.isNotBlank()
                    val hasAttachments = attachments.isNotEmpty()
                    ComposerIconButton(
                        action = if (!hasPrompt && canContinue) ComposerAction.More else ComposerAction.Send,
                        enabled = enabled && (hasPrompt || hasAttachments || canContinue),
                        onClick = {
                            if (hasPrompt || hasAttachments) {
                                onSend()
                            } else {
                                onContinue()
                            }
                        },
                    )
                }
            }
            AttachmentTray(
                attachments = attachments,
                onRemove = onRemoveAttachment,
            )
            if (!imeVisible) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ComposerChip(label = "Local & Private", accent = PrismGreen)
                ComposerChip(label = "Python", accent = PrismBlue)
                ComposerChip(
                    label = performance?.let { "${formatTokensPerSecond(it.tokensPerSecond)} tok/s" } ?: "Local LLM",
                    accent = PrismViolet,
                )
                }
            }
        }
    }
}

private enum class ComposerAction {
    Add,
    Send,
    Stop,
    More,
}

@Composable
private fun AttachmentTray(
    attachments: List<PromptAttachment>,
    onRemove: (PromptAttachment) -> Unit,
) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.54f),
                contentColor = PrismText,
                border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.70f)),
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (attachment.isImage) "Image" else "File",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (attachment.isImage) PrismViolet else PrismBlue,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text = attachment.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = PrismText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    attachment.sizeBytes?.let { size ->
                        Text(
                            text = formatBytes(size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    TextButton(onClick = { onRemove(attachment) }) {
                        Text("Remove", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        if (attachments.any { it.isImage }) {
            Text(
                text = "Images are attached as metadata in this build; true vision needs the native multimodal image bridge.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ComposerTextInput(
    modifier: Modifier = Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.44f),
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.68f)),
        shadowElevation = 0.dp,
    ) {
        BasicTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium.merge(
                TextStyle(color = if (enabled) PrismText else MaterialTheme.colorScheme.onSurfaceVariant)
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun ComposerIconButton(
    action: ComposerAction,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val isPrimary = action == ComposerAction.Send || action == ComposerAction.Stop || action == ComposerAction.More
    Surface(
        modifier = Modifier.size(52.dp),
        shape = RoundedCornerShape(29.dp),
        color = if (isPrimary) Color.Transparent else Color.White.copy(alpha = 0.72f),
        contentColor = if (isPrimary) Color.White else PrismBlue,
        border = if (isPrimary) null else BorderStroke(1.dp, PrismGlassBorder),
        shadowElevation = if (enabled && isPrimary) 4.dp else 0.dp,
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .background(
                    brush = if (enabled) {
                        if (isPrimary) {
                            Brush.linearGradient(listOf(PrismCyan, PrismViolet))
                        } else {
                            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.72f), Color.White.copy(alpha = 0.72f)))
                        }
                    } else {
                        Brush.linearGradient(listOf(Color(0xFFE5E7EB), Color(0xFFD1D5DB)))
                    },
                    shape = RoundedCornerShape(29.dp),
                )
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            ComposerActionGlyph(action = action, enabled = enabled, primary = isPrimary)
        }
    }
}

@Composable
private fun ComposerActionGlyph(
    action: ComposerAction,
    enabled: Boolean,
    primary: Boolean,
) {
    val color = when {
        !enabled -> Color.White.copy(alpha = 0.72f)
        primary -> Color.White
        else -> PrismBlue
    }
    Canvas(modifier = Modifier.size(24.dp)) {
        val strokeWidth = 2.8f
        when (action) {
            ComposerAction.Add -> {
                drawLine(
                    color = color,
                    start = Offset(center.x, size.height * 0.22f),
                    end = Offset(center.x, size.height * 0.78f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.22f, center.y),
                    end = Offset(size.width * 0.78f, center.y),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            ComposerAction.Send -> {
                val path = Path().apply {
                    moveTo(size.width * 0.16f, size.height * 0.84f)
                    lineTo(size.width * 0.86f, size.height * 0.14f)
                    lineTo(size.width * 0.62f, size.height * 0.86f)
                    lineTo(size.width * 0.48f, size.height * 0.52f)
                    close()
                }
                drawPath(path = path, color = color.copy(alpha = 0.18f))
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.16f, size.height * 0.84f),
                    end = Offset(size.width * 0.86f, size.height * 0.14f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.86f, size.height * 0.14f),
                    end = Offset(size.width * 0.62f, size.height * 0.86f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.48f, size.height * 0.52f),
                    end = Offset(size.width * 0.62f, size.height * 0.86f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
            ComposerAction.Stop -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.26f, size.height * 0.26f),
                    size = Size(size.width * 0.48f, size.height * 0.48f),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }
            ComposerAction.More -> {
                repeat(3) { index ->
                    drawCircle(
                        color = color,
                        radius = size.minDimension * 0.08f,
                        center = Offset(size.width * (0.30f + index * 0.20f), center.y),
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerChip(label: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.54f),
        contentColor = PrismSlate,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(modifier = Modifier.size(7.dp)) {
                drawCircle(color = accent)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = PrismSlate,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DashboardCard(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = tint,
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.58f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = PrismBlue,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.invoke()
    }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { (label, value) ->
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        label = label,
                        value = value,
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = PrismGlass.copy(alpha = 0.42f),
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.34f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = PrismText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoBadge(
    text: String,
    color: Color = PrismBlue,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.20f)),
        shadowElevation = 0.dp,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
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
    onRunNativeBenchmark: () -> Unit,
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
        DashboardCard {
            SectionHeader(
                title = "Model & Runtime",
                subtitle = "Choose a local model and manage imports",
                action = {
                    Button(
                        enabled = serviceAvailable && !isImporting && !isLoadingModel,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        onClick = onImportModel,
                    ) {
                        Text("Import", maxLines = 1, softWrap = false)
                    }
                },
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
                    label = { Text("Selected model") },
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
                                enabled = !isLoadingModel,
                                onClick = {
                                    menuExpanded = false
                                    if (readiness?.fit?.rating != null && readiness.fit.rating != ModelFitRating.SAFE) {
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

            if (isImporting) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancelImport,
                ) {
                    Text("Cancel Import", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (importStatus.isNotEmpty()) {
                Text(
                    text = importStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (importState is ImportState.Running) {
                ImportProgressBar(importState)
            }
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
                performanceBuild = BuildConfig.LLMHOST_PERFORMANCE_BUILD,
                currentModel = currentModel,
                isGenerating = isGenerating,
                status = benchmarkStatus,
            ),
            onRunPreset = onRunBenchmark,
            onRunThreadSweep = onRunThreadSweep,
            onRunNativeBenchmark = onRunNativeBenchmark,
            onExportCsv = onExportBenchmarksCsv,
            onExportJson = onExportBenchmarksJson,
            onClear = onClearBenchmarks,
        )
    }

    riskyModel?.let { readiness ->
        val isTooLarge = readiness.fit.rating == ModelFitRating.TOO_LARGE
        AlertDialog(
            onDismissRequest = { riskyModel = null },
            title = {
                Text(if (isTooLarge) "Model too large right now" else "Load risky model?")
            },
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
                    if (isTooLarge) {
                        Text(
                            text = "This model is visible because it is installed, but the native loader will not start it until the current RAM estimate has enough headroom.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "Expected ${predictionRange(readiness.prediction)} ${readiness.prediction.basis}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                if (isTooLarge) {
                    TextButton(onClick = { riskyModel = null }) {
                        Text("OK")
                    }
                } else {
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
                }
            },
            dismissButton = {
                if (!isTooLarge) {
                    TextButton(onClick = { riskyModel = null }) {
                        Text("Cancel")
                    }
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
                text = "${info.performance.label} • ${info.fit.quantization ?: "quant unknown"} • expected ${predictionRange(info.prediction)}",
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
    DashboardCard {
        SectionHeader(title = "Device", subtitle = "Local runtime capacity")
        MetricGrid(
            listOf(
                "RAM free" to formatBytes(profile.availableRamBytes),
                "RAM total" to formatBytes(profile.totalRamBytes),
                "App memory" to "${profile.memoryClassMb} MB",
                "CPU" to "${profile.cpuCoreCount} cores",
                "OS" to "Android ${profile.androidSdk}",
                "ABI" to (profile.abis.firstOrNull() ?: "Unknown"),
                "Storage" to "${formatBytes(profile.storageFreeBytes)} free",
                "Battery" to (profile.batteryPercent?.let { "$it%" } ?: "Unknown"),
                "Thermal" to (profile.thermalStatus?.replaceFirstChar { it.titlecase(Locale.US) } ?: "Unknown"),
            )
        )
        if (profile.lowMemory) {
            InfoBadge(text = "Android reports low memory", color = PrismAmber)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HuggingFaceDownloadPanel(
    entries: List<HuggingFaceModelEntry>,
    state: ModelDownloadState,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
    enabled: Boolean,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedEntryId by remember(entries) { mutableStateOf(entries.firstOrNull()?.id) }
    val selectedEntry = entries.firstOrNull { it.id == selectedEntryId } ?: entries.firstOrNull()
    DashboardCard {
            SectionHeader(
                title = "Hugging Face Text Models",
                subtitle = "Curated GGUF downloads. Resumable. Size/hash verified when metadata is available.",
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
                    state.message?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DownloadProgressBar(
                        bytesDone = state.bytesDone,
                        totalBytes = state.totalBytes,
                    )
                    Button(onClick = onCancel) {
                        Text("Cancel Download")
                    }
                }
            }
            if (entries.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = menuExpanded,
                    onExpandedChange = {
                        if (enabled && state !is ModelDownloadState.Running) {
                            menuExpanded = !menuExpanded
                        }
                    },
                ) {
                    OutlinedTextField(
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        value = selectedEntry?.name.orEmpty(),
                        onValueChange = {},
                        readOnly = true,
                        enabled = enabled && state !is ModelDownloadState.Running,
                        label = { Text("Download model") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded)
                        },
                    )
                    ExposedDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        entries.forEach { entry ->
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(entry.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            text = "${entry.parameters} | ${entry.quantization} | ${formatBytes(entry.expectedBytes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    selectedEntryId = entry.id
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            selectedEntry?.let { entry ->
                DownloadCatalogRow(
                    entry = entry,
                    deviceCapabilityProfile = deviceCapabilityProfile,
                    enabled = enabled && state !is ModelDownloadState.Running,
                    onDownload = { onDownload(entry.id) },
                )
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
        shape = RoundedCornerShape(12.dp),
        color = PrismGlass.copy(alpha = 0.36f),
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.44f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoBadge(text = entry.parameters, color = PrismBlue)
                        InfoBadge(text = entry.quantization, color = PrismViolet)
                        InfoBadge(text = formatBytes(entry.expectedBytes), color = PrismSlate)
                    }
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
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            MetricGrid(listOf("License" to entry.license, "Repository" to entry.repoId))
            if (!hasEnoughFreeStorage(entry, deviceCapabilityProfile)) {
                Text(
                    text = "Needs more free storage for download plus installed copy",
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
    ModelDownloadState.Running.Stage.QUEUED -> "Queued"
    ModelDownloadState.Running.Stage.VERIFYING_METADATA -> "Checking"
    ModelDownloadState.Running.Stage.DOWNLOADING -> "Downloading"
    ModelDownloadState.Running.Stage.VERIFYING_FILE -> "Verifying"
    ModelDownloadState.Running.Stage.IMPORTING -> "Importing"
}

private fun hasEnoughFreeStorage(
    entry: HuggingFaceModelEntry,
    profile: DeviceCapabilityProfile?,
): Boolean =
    profile?.let { it.storageFreeBytes > entry.expectedBytes * 2L + 512L * 1024L * 1024L } ?: true

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
    var clearCurrentRequested by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Chats",
                    style = MaterialTheme.typography.titleMedium,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${sessions.size} ${if (sessions.size == 1) "conversation" else "conversations"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Button(
                enabled = !isGenerating,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                onClick = onNewChat,
            ) {
                Text("+ New", maxLines = 1, softWrap = false)
            }
        }

        Text(
            text = "Offline AI workspace",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

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
                        onClearCurrent = { clearCurrentRequested = true },
                    )
                }
            }
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

    if (clearCurrentRequested) {
        AlertDialog(
            onDismissRequest = { clearCurrentRequested = false },
            title = { Text("Clear current chat?") },
            text = {
                Text(
                    text = "This removes the messages in the active chat but keeps the chat itself.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = hasCurrentTranscript && !isGenerating,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        onClearCurrentChat()
                        clearCurrentRequested = false
                    },
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearCurrentRequested = false }) {
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
    onClearCurrent: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Color(0xFFF8FBFF) else Color.White,
        contentColor = PrismText,
        border = BorderStroke(1.dp, if (selected) PrismViolet.copy(alpha = 0.30f) else PrismGlassBorder),
        shadowElevation = 0.dp,
        enabled = !isGenerating,
        onClick = {
            if (!selected) {
                onOpen()
            }
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            brush = Brush.verticalGradient(listOf(PrismCyan, PrismViolet)),
                            shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                        ),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = if (selected) 12.dp else 14.dp,
                        top = 12.dp,
                        end = 10.dp,
                        bottom = 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = polishedChatTitle(session.title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        ActiveBadge()
                    }
                    Box {
                        ChatOverflowButton(
                            enabled = !isGenerating,
                            onClick = { menuExpanded = true },
                        )
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                enabled = !isGenerating,
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                },
                            )
                            if (selected) {
                                DropdownMenuItem(
                                    text = { Text("Clear messages") },
                                    enabled = !isGenerating,
                                    onClick = {
                                        menuExpanded = false
                                        onClearCurrent()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                enabled = !isGenerating,
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
                ModelBadge(modelName = polishedModelName(session.modelId))
                Text(
                    text = "${session.messageCount} ${if (session.messageCount == 1) "message" else "messages"} • ${formatChatTimestamp(session.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActiveBadge() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = PrismViolet.copy(alpha = 0.10f),
        contentColor = PrismViolet,
        border = BorderStroke(1.dp, PrismViolet.copy(alpha = 0.24f)),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Canvas(modifier = Modifier.size(6.dp)) {
                drawCircle(color = PrismGreen)
            }
            Text(
                text = "Active",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ModelBadge(modelName: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = PrismBlue.copy(alpha = 0.08f),
        contentColor = PrismBlue,
        border = BorderStroke(1.dp, PrismBlue.copy(alpha = 0.14f)),
        shadowElevation = 0.dp,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            text = modelName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatOverflowButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(17.dp),
        color = Color.White.copy(alpha = 0.56f),
        contentColor = PrismSlate,
        border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.70f)),
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(18.dp)) {
                repeat(3) { index ->
                    drawCircle(
                        color = PrismSlate,
                        radius = size.minDimension * 0.08f,
                        center = Offset(center.x, size.height * (0.28f + index * 0.22f)),
                    )
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
    val metadata = model.validation.metadata
    DashboardCard {
        SectionHeader(
            title = "Loaded model",
            subtitle = polishedModelName(model.id),
        )
        MetricGrid(
            listOfNotNull(
                "Size" to formatBytes(model.bytes),
                "Format" to "GGUF v${model.validation.ggufVersion}",
                "Validation" to model.validation.status.replaceFirstChar { it.titlecase(Locale.US) },
                "SHA-256" to shortHash(model.sha256),
                metadata?.architecture?.let { "Family" to it.uppercase(Locale.US) },
                metadata?.sizeLabel?.let { "Params" to it },
                metadata?.contextLength?.let { "Context" to it.toString() },
                metadata?.fileType?.let { "Type" to it.toString() },
                "Template" to if (metadata?.hasChatTemplate == true) "Chat" else "Unknown",
                diagnostics?.loadMs?.let { "Load time" to "$it ms" },
                diagnostics?.availableMemoryMb?.let { "RAM free" to "$it MB" },
                diagnostics?.state?.let { "Status" to it.replaceFirstChar { char -> char.titlecase(Locale.US) } },
            )
        )
        readiness?.let { modelReadiness ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = performanceColor(modelReadiness.performance.tier).copy(alpha = 0.10f),
                contentColor = performanceColor(modelReadiness.performance.tier),
                border = BorderStroke(1.dp, performanceColor(modelReadiness.performance.tier).copy(alpha = 0.22f)),
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = modelReadiness.performance.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Needs ${formatBytes(modelReadiness.fit.requiredRamBytes)} • expected ${predictionRange(modelReadiness.prediction)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
    var advancedVisible by remember { mutableStateOf(false) }
    DashboardCard {
        SectionHeader(
            title = "Runtime",
            subtitle = "Generation limits and CPU scheduling",
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Agentic Tools",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Allow local model to invoke on-device tools",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.agentEnabled,
                onCheckedChange = { isChecked ->
                    onSettingsChange(settings.copy(agentEnabled = isChecked))
                },
                enabled = enabled,
            )
        }
        AnimatedVisibility(visible = settings.agentEnabled) {
            Column {
                SettingSlider(
                    label = "Agent iterations",
                    valueText = settings.maxAgentIterations.toString(),
                    value = settings.maxAgentIterations.toFloat(),
                    valueRange = GenerationSettings.MIN_MAX_AGENT_ITERATIONS.toFloat()..GenerationSettings.MAX_MAX_AGENT_ITERATIONS.toFloat(),
                    steps = GenerationSettings.MAX_MAX_AGENT_ITERATIONS - GenerationSettings.MIN_MAX_AGENT_ITERATIONS - 1,
                    enabled = enabled,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(maxAgentIterations = value.roundToInt()))
                    },
                )
                Text(
                    text = "Max tool-call chain depth before auto-stop. Lower = safer, higher = more autonomous.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = PrismGlass.copy(alpha = 0.32f),
            contentColor = PrismText,
            border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.28f)),
            onClick = { advancedVisible = !advancedVisible },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "Advanced runtime settings",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (advancedVisible) "Hide" else "Show",
                    style = MaterialTheme.typography.labelSmall,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (advancedVisible) "⌃" else "⌄",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (advancedVisible) {
            SettingSlider(
                label = "Context",
                valueText = settings.contextLength.toString(),
                value = settings.contextLength.toFloat(),
                valueRange = GenerationSettings.MIN_CONTEXT_LENGTH.toFloat()..GenerationSettings.MAX_CONTEXT_LENGTH.toFloat(),
                steps = ((GenerationSettings.MAX_CONTEXT_LENGTH - GenerationSettings.MIN_CONTEXT_LENGTH) / GenerationSettings.CONTEXT_LENGTH_STEP) - 1,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(contextLength = snapStep(value, GenerationSettings.CONTEXT_LENGTH_STEP)))
                },
            )
            SettingSlider(
                label = "Batch",
                valueText = settings.batchSize.toString(),
                value = settings.batchSize.toFloat(),
                valueRange = GenerationSettings.MIN_BATCH_SIZE.toFloat()..GenerationSettings.MAX_BATCH_SIZE.toFloat(),
                steps = ((GenerationSettings.MAX_BATCH_SIZE - GenerationSettings.MIN_BATCH_SIZE) / GenerationSettings.BATCH_SIZE_STEP) - 1,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(batchSize = snapStep(value, GenerationSettings.BATCH_SIZE_STEP)))
                },
            )
            SettingSlider(
                label = "Temperature",
                valueText = String.format(Locale.US, "%.2f", settings.temperature),
                value = settings.temperature,
                valueRange = GenerationSettings.MIN_TEMPERATURE..GenerationSettings.MAX_TEMPERATURE,
                steps = 28,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(temperature = value))
                },
            )
            SettingSlider(
                label = "Top P",
                valueText = String.format(Locale.US, "%.2f", settings.topP),
                value = settings.topP,
                valueRange = GenerationSettings.MIN_TOP_P..GenerationSettings.MAX_TOP_P,
                steps = 18,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(topP = value))
                },
            )
            SettingSlider(
                label = "GPU layers",
                valueText = settings.gpuLayers.toString(),
                value = settings.gpuLayers.toFloat(),
                valueRange = GenerationSettings.MIN_GPU_LAYERS.toFloat()..GenerationSettings.MAX_GPU_LAYERS.toFloat(),
                steps = GenerationSettings.MAX_GPU_LAYERS - GenerationSettings.MIN_GPU_LAYERS - 1,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(gpuLayers = value.roundToInt()))
                },
            )
        }
        performance?.let { stats ->
            MetricGrid(
                listOf(
                    "Speed" to "${formatTokensPerSecond(stats.tokensPerSecond)} tok/s",
                    "Generated" to "${stats.generatedTokens} tok",
                    "Total" to "${stats.totalMs} ms${stats.terminalSuffix()}",
                    "Prompt/decode" to "${stats.promptEvalMs}/${stats.decodeMs} ms",
                )
            )
        }
    }
}

private enum class BenchmarkTab(val label: String) {
    Runs("Runs"),
    Compare("Compare"),
    Models("Models"),
}

private data class BenchmarkSummary(
    val totalCount: Int,
    val completedCount: Int,
    val cleanCount: Int,
    val truncatedCount: Int,
    val errorCount: Int,
    val interruptedCount: Int,
    val avgCompletedTokensPerSecond: Double?,
    val avgAllTokensPerSecond: Double?,
    val bestCompletedTokensPerSecond: Double?,
    val avgPromptMs: Long?,
    val avgTotalMs: Long?,
) {
    val failedCount: Int
        get() = errorCount + interruptedCount

    val reliabilityScore: Double?
        get() = avgCompletedTokensPerSecond?.let { average ->
            if (totalCount == 0) {
                average
            } else {
                average * ((cleanCount + truncatedCount).toDouble() / totalCount.toDouble())
            }
        }

    companion object {
        val Empty = BenchmarkSummary(
            totalCount = 0,
            completedCount = 0,
            cleanCount = 0,
            truncatedCount = 0,
            errorCount = 0,
            interruptedCount = 0,
            avgCompletedTokensPerSecond = null,
            avgAllTokensPerSecond = null,
            bestCompletedTokensPerSecond = null,
            avgPromptMs = null,
            avgTotalMs = null,
        )
    }
}

private data class BenchmarkComparisonRow(
    val modelId: String,
    val run: BenchmarkRun?,
    val summary: BenchmarkSummary,
    val readiness: ModelReadiness?,
)

private data class BenchmarkAction(
    val label: String,
    val onClick: () -> Unit,
)

private enum class BenchmarkRunStatus(
    val label: String,
    val color: Color,
) {
    Clean("Clean", PrismGreen),
    Truncated("Truncated", PrismAmber),
    Error("Error", PrismRed),
    Interrupted("Interrupted", PrismAmber),
    Partial("Partial", PrismBlue),
    Unknown("Unknown", PrismSlate),
}

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
    onRunNativeBenchmark: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onClear: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(BenchmarkTab.Runs) }
    var exportMenuExpanded by remember { mutableStateOf(false) }
    val allSummary = benchmarkSummary(runs)
    val currentModelId = activeModelInfo?.id
    val currentSummary = remember(runs, currentModelId) {
        currentModelId
            ?.let { modelId -> benchmarkSummary(runs.filter { it.modelId == modelId }) }
            ?: BenchmarkSummary.Empty
    }
    val enabled = disabledReason == null

    DashboardCard {
            SectionHeader(
                title = "Benchmarks",
                subtitle = "Local speed, reliability, and model comparisons",
                action = {
                    Box {
                        ChatOverflowButton(
                            enabled = runs.isNotEmpty() && !isGenerating && !status.isRunning,
                            onClick = { exportMenuExpanded = true },
                        )
                        DropdownMenu(
                            expanded = exportMenuExpanded,
                            onDismissRequest = { exportMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export CSV") },
                                enabled = runs.isNotEmpty() && !isGenerating && !status.isRunning,
                                onClick = {
                                    exportMenuExpanded = false
                                    onExportCsv()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export JSON") },
                                enabled = runs.isNotEmpty() && !isGenerating && !status.isRunning,
                                onClick = {
                                    exportMenuExpanded = false
                                    onExportJson()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear history", color = MaterialTheme.colorScheme.error) },
                                enabled = runs.isNotEmpty() && !isGenerating && !status.isRunning,
                                onClick = {
                                    exportMenuExpanded = false
                                    onClear()
                                },
                            )
                        }
                    }
                },
            )
            BenchmarkSummaryPanel(
                currentSummary = currentSummary,
                allSummary = allSummary,
                currentEmptyText = if (currentModelId == null) "No model selected" else "No runs for this model",
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
                    onRunNativeBenchmark = onRunNativeBenchmark,
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
            }
    }
}

@Composable
private fun BenchmarkSummaryPanel(
    currentSummary: BenchmarkSummary,
    allSummary: BenchmarkSummary,
    currentEmptyText: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BenchmarkSummaryGroup(
            title = "Current model",
            summary = currentSummary,
            emptyText = currentEmptyText,
        )
        BenchmarkSummaryGroup(
            title = "All models",
            summary = allSummary,
            emptyText = "No runs recorded",
        )
    }
}

@Composable
private fun BenchmarkSummaryGroup(
    title: String,
    summary: BenchmarkSummary,
    emptyText: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = PrismGlass.copy(alpha = 0.34f),
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.30f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = PrismSlate,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.totalCount == 0) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                MetricGrid(
                    listOf(
                        "Runs" to summary.totalCount.toString(),
                        "Completed" to summary.completedCount.toString(),
                        "Failed" to summary.failedCount.toString(),
                        "Truncated" to summary.truncatedCount.toString(),
                        "Avg completed" to formatOptionalTps(summary.avgCompletedTokensPerSecond),
                        "Avg overall" to formatOptionalTps(summary.avgAllTokensPerSecond),
                        "Best" to formatOptionalTps(summary.bestCompletedTokensPerSecond),
                        "Avg first token" to formatOptionalMs(summary.avgPromptMs),
                    )
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
    onRunNativeBenchmark: () -> Unit,
) {
    val actions = listOf(
        BenchmarkAction("Sweep 2/4/6/8", onRunThreadSweep),
        BenchmarkAction("Native PP/TG", onRunNativeBenchmark),
    ) + presets.map { preset ->
        BenchmarkAction(preset.name) { onRunPreset(preset.id) }
    }
    var selectedActionIndex by remember(actions.size) { mutableIntStateOf(actions.indexOfFirst { it.label == "Python Coding" }.coerceAtLeast(0)) }
    val selectedAction = actions.getOrNull(selectedActionIndex) ?: actions.first()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(
            title = "Preset",
            subtitle = "Choose a benchmark task, then run it.",
        )
        actions.chunked(2).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowActions.forEach { action ->
                    val actionIndex = actions.indexOf(action)
                    BenchmarkPresetTile(
                        modifier = Modifier.weight(1f),
                        label = action.label,
                        enabled = enabled,
                        selected = actionIndex == selectedActionIndex,
                        onClick = { selectedActionIndex = actionIndex },
                    )
                }
                if (rowActions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            enabled = enabled,
            onClick = selectedAction.onClick,
        ) {
            Text("Run ${selectedAction.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun BenchmarkPresetTile(
    modifier: Modifier = Modifier,
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.heightIn(min = 42.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) PrismBlue.copy(alpha = 0.10f) else PrismGlass.copy(alpha = 0.28f),
        contentColor = if (selected) PrismBlue else PrismSlate,
        border = BorderStroke(1.dp, if (selected) PrismBlue.copy(alpha = 0.34f) else PrismGlassBorder.copy(alpha = 0.36f)),
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BenchmarkCompareTab(
    runs: List<BenchmarkRun>,
    readiness: List<ModelReadiness>,
) {
    val runRows = runs
        .groupBy { it.comparisonKey() }
        .map { (_, modelRuns) ->
            val representative = modelRuns.maxByOrNull { it.createdAt } ?: return@map null
            val modelId = representative.modelId ?: "Unknown model"
            val modelReadiness = readiness.firstOrNull { it.info.id == representative.modelId }
            BenchmarkComparisonRow(
                modelId = modelId,
                run = representative,
                summary = benchmarkSummary(modelRuns),
                readiness = modelReadiness,
            )
        }
        .filterNotNull()
    val modelsWithRuns = runRows.map { it.modelId }.toSet()
    val readinessRows = readiness
        .filter { it.info.id !in modelsWithRuns }
        .map { modelReadiness ->
            BenchmarkComparisonRow(
                modelId = modelReadiness.info.id,
                run = null,
                summary = BenchmarkSummary.Empty,
                readiness = modelReadiness,
            )
        }
    val byModel = (runRows + readinessRows)
        .sortedByDescending { it.summary.reliabilityScore ?: it.readiness?.prediction?.maxTokensPerSecond ?: 0.0 }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (byModel.isEmpty()) {
            Text(
                text = "No comparison data yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            byModel.take(8).forEach { row ->
                val summary = row.summary
                val modelReadiness = row.readiness
                val actual = summary.avgCompletedTokensPerSecond?.let { "${formatTokensPerSecond(it)} tok/s completed" } ?: "No actual yet"
                val predicted = modelReadiness?.prediction?.let { prediction ->
                    "predicted ${predictionRange(prediction)}"
                } ?: "prediction pending"
                val runtimeDetail = row.run?.settingsLabel() ?: modelReadiness?.fit?.quantization ?: "settings pending"
                BenchmarkMetricRow(
                    label = row.run?.let { "${polishedModelName(row.modelId)} • ${it.shortHashLabel()}" } ?: polishedModelName(row.modelId),
                    value = actual,
                    detail = "$predicted • $runtimeDetail • ${summaryStatusLine(summary)} • first ${formatOptionalMs(summary.avgPromptMs)}",
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
                val modelRuns = runs.filter { it.modelId == modelId }
                val runVariants = modelRuns.map { it.comparisonKey() }.distinct().size
                val activeSize = activeModelInfo?.takeIf { it.id == modelId }?.bytes
                val modelReadiness = readiness.firstOrNull { it.info.id == modelId }
                BenchmarkMetricRow(
                    label = polishedModelName(modelId),
                    value = modelReadiness?.performance?.label ?: "Unknown",
                    detail = listOfNotNull(
                        activeSize?.let { formatBytes(it) } ?: modelReadiness?.info?.bytes?.let { formatBytes(it) },
                        modelReadiness?.fit?.quantization,
                        modelReadiness?.performance?.averageTokensPerSecond?.let { "actual ${formatTokensPerSecond(it)} tok/s" },
                        modelReadiness?.fit?.requiredRamBytes?.let { "needs ${formatBytes(it)}" },
                        modelReadiness?.prediction?.let { "expected ${predictionRange(it)}" },
                        summaryStatusLine(summary),
                        if (runVariants > 1) "$runVariants variants" else null,
                    ).joinToString(" • "),
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder.copy(alpha = 0.48f)),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = run.presetName ?: run.source.replaceFirstChar { it.titlecase(Locale.US) },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatChatTimestamp(run.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = "${formatTokensPerSecond(run.tokensPerSecond)} tok/s",
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                text = polishedModelName(run.modelId),
                style = MaterialTheme.typography.labelMedium,
                color = PrismSlate,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "ctx ${run.contextLength} • batch ${run.batchSize} • threads ${run.threadCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BenchmarkStatusChip(status = run.status())
                Text(
                    text = "${run.runtimeBackend} • ${run.generatedTokens}/${run.maxTokens} tok • first ${run.promptEvalMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BenchmarkMetricRow(
    label: String,
    value: String,
    detail: String,
    status: BenchmarkRunStatus? = null,
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
                status?.let { runStatus ->
                    BenchmarkStatusChip(status = runStatus)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
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
private fun BenchmarkStatusChip(status: BenchmarkRunStatus) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = status.color.copy(alpha = 0.12f),
        contentColor = status.color,
        border = BorderStroke(1.dp, status.color.copy(alpha = 0.32f)),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
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
            InfoBadge(text = valueText, color = PrismBlue)
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

// ---- Memory Browser ----

@Composable
private fun MemoryBrowser(
    memories: List<MemoryFact>,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredMemories by remember {
        derivedStateOf {
            if (searchQuery.isBlank()) memories
            else memories.filter { it.fact.contains(searchQuery, ignoreCase = true) }
        }
    }

    DashboardCard {
        SectionHeader(
            title = "Memory",
            subtitle = "${memories.size} facts stored",
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search memories...") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        if (filteredMemories.isEmpty()) {
            Text(
                text = "No memories stored yet. Memories are extracted from conversations automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredMemories, key = { it.id }) { memory ->
                    MemoryFactRow(
                        fact = memory,
                        onDelete = { onDelete(memory.id) },
                    )
                }
            }
        }

        TextButton(
            onClick = onRefresh,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Refresh")
        }
    }
}

@Composable
private fun MemoryFactRow(
    fact: MemoryFact,
    onDelete: () -> Unit,
) {
    val categoryColor = when (fact.category) {
        MemoryCategory.PERSONAL -> PrismBlue
        MemoryCategory.PREFERENCE -> PrismViolet
        MemoryCategory.PROJECT -> PrismGreen
        MemoryCategory.RELATIONSHIP -> PrismAmber
        MemoryCategory.KNOWLEDGE -> PrismCyan
        MemoryCategory.GENERAL -> PrismSlate
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = categoryColor.copy(alpha = 0.12f),
                    contentColor = categoryColor,
                ) {
                    Text(
                        text = fact.category.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                InfoBadge(
                    text = "${(fact.confidence * 100).roundToInt()}%",
                    color = when {
                        fact.confidence >= 0.7f -> PrismGreen
                        fact.confidence >= 0.4f -> PrismAmber
                        else -> PrismSlate
                    },
                )
            }
            Text(
                text = fact.fact,
                style = MaterialTheme.typography.bodySmall,
                color = PrismText,
            )
        }
        TextButton(
            onClick = onDelete,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(32.dp).height(32.dp),
        ) {
            Text(
                text = "✕",
                color = PrismRed.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
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
        Color.White.copy(alpha = 0.76f)
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val labelColor = if (isUser) PrismBlue else PrismViolet
    val context = LocalContext.current
    val copyLabel = if (isUser) "prompt" else "response"

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.78f else 0.94f)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(if (isUser) 28.dp else 32.dp),
                )
                .padding(if (isUser) 18.dp else 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isUser) {
                    AssistantBadge()
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!isUser) {
                    PerformancePill(
                        modifier = Modifier.weight(1f, fill = false),
                        performance = performance,
                        loading = showLoading,
                    )
                }
                if (showLoading) {
                    InfinityLoadingIndicator(
                        modifier = Modifier.size(22.dp),
                        color = PrismViolet,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (!isUser) {
                    TextButton(
                        modifier = Modifier.widthIn(min = 56.dp),
                        enabled = text.isNotBlank(),
                        onClick = {
                            copyTextToClipboard(context, label, text)
                            Toast.makeText(context, "Copied $copyLabel", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("Copy", maxLines = 1, softWrap = false)
                    }
                }
            }
            Spacer(modifier = Modifier.height(if (isUser) 10.dp else 12.dp))
            SelectionContainer {
                if (isUser) {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = PrismText)
                } else {
                    MarkdownText(text = text, color = PrismText)
                }
            }
        }
    }
}

@Composable
private fun AssistantBadge() {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = PrismViolet.copy(alpha = 0.10f),
        contentColor = PrismViolet,
        border = BorderStroke(1.dp, PrismViolet.copy(alpha = 0.36f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("*", color = PrismViolet, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PerformancePill(performance: GenerationPerformance?) {
    PerformancePill(modifier = Modifier, performance = performance, loading = false)
}

@Composable
private fun PerformancePill(
    modifier: Modifier,
    performance: GenerationPerformance?,
    loading: Boolean,
) {
    Surface(
        modifier = modifier.widthIn(max = 150.dp),
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.56f),
        contentColor = PrismSlate,
        border = BorderStroke(1.dp, PrismGlassBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(modifier = Modifier.size(7.dp)) {
                drawCircle(color = PrismGreen)
            }
            Text(
                text = when {
                    performance != null -> "${formatTokensPerSecond(performance.tokensPerSecond)} tok/s"
                    loading -> "typing"
                    else -> "Local LLM"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToolEventCard(rawText: String) {
    val event = remember(rawText) { AgentToolProtocol.parseToolEvent(rawText) }
    val status = event?.optString("status")?.takeIf { it.isNotBlank() } ?: "done"
    val tool = event?.optString("tool")?.takeIf { it.isNotBlank() } ?: "tool"
    val summary = event?.optString("summary")?.takeIf { it.isNotBlank() } ?: rawText
    val color = when (status) {
        "done" -> PrismGreen
        "failed" -> PrismRed
        "pending" -> PrismAmber
        "cancelled" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> PrismBlue
    }
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Tool",
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    modifier = Modifier.weight(1f),
                    text = tool,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = PrismText,
            )
        }
    }
}

@Composable
private fun AgentToolConfirmationDialog(
    action: PendingAgentToolAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = action.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PrismText,
                )
                if (action.changes.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        action.changes.forEach { change ->
                            Text(
                                text = change,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (action.riskNotes.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        action.riskNotes.forEach { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (action.destructive) PrismRed else PrismAmber,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Text(
                    text = action.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = action.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = action.argumentsJson,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(action.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(action.cancelLabel)
            }
        },
    )
}

private fun copyTextToClipboard(context: android.content.Context, label: String, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

@Composable
private fun GlassSurface(
    modifier: Modifier = Modifier,
    radius: Dp = 22.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(radius),
        color = PrismGlass,
        contentColor = PrismText,
        border = BorderStroke(1.dp, PrismGlassBorder),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
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
private val PrismCanvas = Color(0xFFF8FCFF)
private val PrismGlass = Color(0xDFFFFFFF)
private val PrismGlassBorder = Color(0x8FBFDBFE)
private val UserBubble = Color(0xDDE0F7FF)
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

private fun topBarSubtitle(
    status: RuntimeStatus,
    modelName: String?,
    importStatus: String,
    importState: ImportState,
): String {
    return if (importState is ImportState.Running && importStatus.isNotBlank()) {
        importStatus
    } else if (status == RuntimeStatus.LOADING_MODEL || status == RuntimeStatus.GENERATING || status == RuntimeStatus.ERROR) {
        status.label()
    } else if (!modelName.isNullOrBlank()) {
        "${polishedModelName(modelName)} • Offline"
    } else {
        "Offline AI workspace"
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

private fun polishedModelName(modelId: String?): String {
    val compact = compactModelName(modelId)
    if (compact == "No model selected") {
        return compact
    }
    return compact
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            when {
                token.equals("it", ignoreCase = true) -> "IT"
                token.equals("llm", ignoreCase = true) -> "LLM"
                token.equals("gguf", ignoreCase = true) -> "GGUF"
                token.equals("cpu", ignoreCase = true) -> "CPU"
                token.equals("gpu", ignoreCase = true) -> "GPU"
                token.matches(Regex("\\d+[a-zA-Z]?")) -> token.uppercase(Locale.US)
                token.any { it.isDigit() } -> token.uppercase(Locale.US)
                token.length <= 2 -> token.uppercase(Locale.US)
                else -> token.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
                }
            }
        }
        .ifBlank { compact }
}

private fun polishedChatTitle(title: String): String =
    title
        .replace("Benchmark - ", "Benchmark: ")
        .trim()
        .ifBlank { ChatTitles.DEFAULT_TITLE }

private fun snapTokens(value: Float): Int {
    val step = GenerationSettings.MAX_TOKEN_STEP
    val snapped = (value / step).roundToInt() * step
    return snapped.coerceIn(GenerationSettings.MIN_MAX_TOKENS, GenerationSettings.MAX_MAX_TOKENS)
}

private fun snapStep(value: Float, step: Int): Int =
    ((value / step).roundToInt() * step)

private fun formatTokensPerSecond(value: Double): String =
    String.format(Locale.US, "%.2f", value)

private fun formatOptionalTps(value: Double?): String =
    value?.let { "${formatTokensPerSecond(it)} tok/s" } ?: "pending"

private fun formatOptionalMs(value: Long?): String =
    value?.let { "$it ms" } ?: "pending"

private fun benchmarkDisabledReason(
    serviceAvailable: Boolean,
    performanceBuild: Boolean,
    currentModel: String?,
    isGenerating: Boolean,
    status: BenchmarkStatus,
): String? = when {
    !serviceAvailable -> "Benchmark unavailable while the service reconnects"
    !performanceBuild -> "Switch Android Studio Build Variant to benchmark or profile before testing performance"
    currentModel == null -> "Select a model before running benchmarks"
    status.isRunning -> "Benchmark already running: ${status.presetName ?: "current run"}"
    isGenerating -> "Stop the current generation before running another benchmark"
    else -> null
}

private fun summaryStatusLine(summary: BenchmarkSummary): String =
    "${summary.totalCount} runs / ${summary.completedCount} completed / ${summary.failedCount} failed"

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
    val allTokensPerSecond = runs.map { it.tokensPerSecond }
    return BenchmarkSummary(
        totalCount = runs.size,
        completedCount = completed.size,
        cleanCount = runs.count { it.terminalReason == "EOF" },
        truncatedCount = runs.count { it.terminalReason == "MAX_TOKENS" },
        errorCount = runs.count { it.terminalReason == "ERROR" },
        interruptedCount = runs.count { it.terminalReason.contains("INTERRUPTED", ignoreCase = true) },
        avgCompletedTokensPerSecond = completed.takeIf { it.isNotEmpty() }?.map { it.tokensPerSecond }?.average(),
        avgAllTokensPerSecond = allTokensPerSecond.takeIf { it.isNotEmpty() }?.average(),
        bestCompletedTokensPerSecond = completed.maxOfOrNull { it.tokensPerSecond },
        avgPromptMs = completed.takeIf { it.isNotEmpty() }?.map { it.promptEvalMs }?.average()?.roundToInt()?.toLong(),
        avgTotalMs = completed.takeIf { it.isNotEmpty() }?.map { it.totalMs }?.average()?.roundToInt()?.toLong(),
    )
}

private fun BenchmarkRun.comparisonKey(): String =
    listOf(
        modelId.orEmpty(),
        modelSha256Prefix.orEmpty(),
        contextLength,
        batchSize,
        threadCount,
        gpuLayers,
        runtimeBackend,
        presetId.orEmpty(),
        source,
    ).joinToString("|")

private fun BenchmarkRun.shortHashLabel(): String =
    modelSha256Prefix?.takeIf { it.isNotBlank() }?.let { "hash ${shortHash(it)}" } ?: "hash pending"

private fun BenchmarkRun.status(): BenchmarkRunStatus = when {
    terminalReason == "EOF" -> BenchmarkRunStatus.Clean
    terminalReason == "MAX_TOKENS" -> BenchmarkRunStatus.Truncated
    terminalReason == "ERROR" -> BenchmarkRunStatus.Error
    terminalReason.contains("INTERRUPTED", ignoreCase = true) -> BenchmarkRunStatus.Interrupted
    generatedTokens > 0 && decodeMs > 0L -> BenchmarkRunStatus.Partial
    else -> BenchmarkRunStatus.Unknown
}

private fun BenchmarkRun.settingsLabel(): String =
    "ctx $contextLength | batch $batchSize | th $threadCount | gpu $gpuLayers | $runtimeBackend"

private fun GenerationPerformance.terminalSuffix(): String =
    terminalReason?.let { reason ->
        " | " + when (reason) {
            "MAX_TOKENS" -> "token limit"
            else -> reason
        }
    }.orEmpty()

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
