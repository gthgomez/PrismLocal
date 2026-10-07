package com.prismai.llmhost.ui
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.content.Intent
import android.net.Uri
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.ui.components.*
import com.prismai.llmhost.ui.composer.*
import com.prismai.llmhost.ui.memory.*
import com.prismai.llmhost.ui.chat.*
import com.prismai.llmhost.ui.benchmark.*
import com.prismai.llmhost.ui.controlplane.*
import com.prismai.llmhost.ui.rag.*
import com.prismai.llmhost.ui.voice.*
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
    PrismLocalTheme {
        var snackbarMessage by remember { mutableStateOf<String?>(null) }
        var controlsVisible by remember { mutableStateOf(false) }
        var chatsVisible by remember { mutableStateOf(false) }
        var memoriesVisible by remember { mutableStateOf(false) }
        var ragBrowserVisible by remember { mutableStateOf(false) }
        var ingestStatus by remember { mutableStateOf<String?>(null) }
        val controlSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val chatSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val memoriesSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val ragSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()

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
            Surface(modifier = Modifier.fillMaxSize(), color = prismCanvasColor()) {
                PrismBackdrop(modifier = Modifier.fillMaxSize())
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isShortHeight = LayoutPolicy.isShortHeight(maxHeight)
                    val headerCompact = LayoutPolicy.headerCompact(maxHeight)
                    // The old maxHeight * 0.70f floor fought the IME: with the
                    // keyboard open the list could not shrink below 70% of a
                    // window that had already shrunk.
                    val minChatHeight = 0.dp
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        val context = LocalContext.current
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
                        // One observable installed-model state, owned by the
                        // service, replaces the old remember snapshot that only
                        // refreshed on ImportState.Success.
                        val models by (service?.installedModels ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = emptyList()
                        )
                        val pendingAgentToolAction by (service?.pendingAgentToolAction ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = null
                        )
                        val thermalGovernorState by (service?.thermalGovernorState ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = com.prismai.llmhost.util.ThermalGovernorState()
                        )
                        val memories by (service?.memories ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = emptyList()
                        )
                        val vectorChunks by (service?.vectorChunks ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = emptyList()
                        )
                        val staleVectorChunkCount by (service?.staleVectorChunkCount ?: emptyFlow())
                            .collectAsStateWithLifecycle(initialValue = 0)
                        val voiceState by (service?.voiceState ?: emptyFlow()).collectAsStateWithLifecycle(
                            initialValue = com.prismai.llmhost.tools.VoiceState()
                        )
                        // The active chat's draft is the live state here and is saveable,
                        // so rotation and activity recreation restore it even though the
                        // service is not yet bound on the first frame. DraftStore holds
                        // the drafts of chats that are not active, keyed by chat id.
                        var prompt by rememberSaveable { mutableStateOf("") }
                        var savedAttachments by rememberSaveable { mutableStateOf(emptyList<String>()) }
                        var savedChatId by rememberSaveable { mutableStateOf<String?>(null) }
                        val attachments: List<PromptAttachment> =
                            remember(savedAttachments) {
                                savedAttachments.mapNotNull(AttachmentTextCodec::decode)
                            }

                        // Rebuild the store around the restored active draft before any
                        // effect can move away from it. On the first frame after
                        // recreation `service`/`currentChatId` are still unknown, so the
                        // saved chat id is what attributes the restored draft.
                        // `rememberSaveable` (not plain `remember`): the whole store
                        // carries every keyed draft through process death, not only
                        // the active chat's prompt/attachments.
                        val draftStore = rememberSaveable(saver = DraftStore.Saver) {
                            DraftStore(savedChatId).apply {
                                restore(savedChatId, prompt, attachments)
                            }
                        }

                        // Follow chat switches. Wait for the bound service; by then the
                        // store already owns the restored draft under its saved chat id,
                        // so the service reporting that same id is a no-op. The live
                        // draft is read here, never captured from a previous composition,
                        // so a switch cannot clobber the incoming chat with stale values.
                        LaunchedEffect(currentChatId, service) {
                            if (service == null) return@LaunchedEffect
                            val loaded = draftStore.applyLiveDraft(
                                currentChatId = currentChatId,
                                liveText = prompt,
                                liveAttachments = savedAttachments.mapNotNull(AttachmentTextCodec::decode),
                            ) ?: return@LaunchedEffect
                            prompt = loaded.first
                            savedAttachments = loaded.second.map(AttachmentTextCodec::encode)
                            savedChatId = draftStore.chatId
                        }

                        var importStatus by remember { mutableStateOf("") }
                        var pendingBenchmarkCsv by remember { mutableStateOf<String?>(null) }
                        var pendingBenchmarkJson by remember { mutableStateOf<String?>(null) }
                        val scrollFollowPolicy = remember { ScrollFollowPolicy() }
                        val listState = rememberLazyListState()
                        val bottomAnchorIndex = if (transcript.isEmpty()) 0 else transcript.size
                        val isAtBottomAnchor by remember(transcript.size) {
                            derivedStateOf {
                                transcript.isEmpty() ||
                                    listState.layoutInfo.visibleItemsInfo.any { item -> item.index == bottomAnchorIndex }
                            }
                        }
                        // scrollFollowPolicy: pin while streaming. Detach only on user-driven
                        // scroll away from bottom; content growth alone must not clear follow.
                        var suppressStickDetach by remember { mutableStateOf(false) }
                        LaunchedEffect(listState, transcript.size) {
                            snapshotFlow {
                                val bottomIndex = if (transcript.isEmpty()) 0 else transcript.size
                                val atBottom = transcript.isEmpty() ||
                                    listState.layoutInfo.visibleItemsInfo.any { item ->
                                        item.index == bottomIndex
                                    }
                                listState.isScrollInProgress to atBottom
                            }.collect { (scrolling, atBottom) ->
                                when {
                                    atBottom -> scrollFollowPolicy.onUserScrolledToBottom()
                                    scrolling && !suppressStickDetach -> scrollFollowPolicy.onUserScrolledAway()
                                }
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
                            if (uris.isEmpty()) return@rememberLauncherForActivityResult

                            // Apply the limit before reading anything: the old path read
                            // every provider stream on the UI thread and then discarded
                            // all but the last six. takeUpTo dedups by id and caps, so
                            // the helper on the production path is what the unit test
                            // actually guards.
                            val cappedIds = AttachmentSelection.takeUpTo(
                                uris.map { it.toString() },
                                AttachmentSelection.MAX_PROMPT_ATTACHMENTS,
                            )
                            val capped = cappedIds.mapNotNull { id -> uris.firstOrNull { it.toString() == id } }
                            val dropped = uris.size - capped.size

                            scope.launch {
                                val attached = mutableListOf<PromptAttachment>()
                                val ggufUris = mutableListOf<Uri>()

                                for (uri in capped) {
                                    ensureActive()
                                    runCatching {
                                        context.contentResolver.takePersistableUriPermission(
                                            uri,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    }
                                    val name = AttachmentTextExtractor.displayName(context, uri)
                                    if (name.endsWith(".gguf", ignoreCase = true)) {
                                        ggufUris += uri
                                    } else {
                                        AttachmentTextExtractor.fromUriAsync(context, uri)
                                            ?.let { attached += it }
                                    }
                                }

                                if (attached.isNotEmpty()) {
                                    savedAttachments = (attachments + attached)
                                        .distinctBy { it.uriString }
                                        .takeLast(AttachmentSelection.MAX_PROMPT_ATTACHMENTS)
                                        .map(AttachmentTextCodec::encode)
                                }
                                // Task 4 imports every selected GGUF, one at a time,
                                // rather than dropping all but the first.
                                if (ggufUris.isNotEmpty()) {
                                    service?.importModels(ggufUris)
                                }
                                snackbarMessage = when {
                                    dropped > 0 ->
                                        "Added $dropped fewer attachment(s) (limit ${AttachmentSelection.MAX_PROMPT_ATTACHMENTS})"
                                    ggufUris.isNotEmpty() && attached.isNotEmpty() ->
                                        "Importing ${ggufUris.size} model(s), attached ${attached.size} file(s)"
                                    ggufUris.isNotEmpty() ->
                                        "Importing ${ggufUris.size} model(s)"
                                    attached.isNotEmpty() ->
                                        "Attached ${attached.size} file(s)"
                                    else -> null
                                }
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

                        // Refresh readiness only when an import reaches a terminal
                        // state. Running emits on every progress tick, and each
                        // refresh re-reads installed models on the main thread.
                        LaunchedEffect(service, importState) {
                            if (importState is ImportState.Running) return@LaunchedEffect
                            service?.refreshDeviceAndModelReadiness()
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
                                    importStatus = "Imported ${state.modelId}"
                                    snackbarMessage = "Imported ${state.modelId}"
                                }
                            }
                        }

                        LaunchedEffect(service) {
                            service?.panelRequests?.collect { panel ->
                                when (panel) {
                                    "chats" -> chatsVisible = true
                                    "rag", "documents", "knowledge" -> ragBrowserVisible = true
                                    "memories" -> memoriesVisible = true
                                    "model_manager",
                                    "benchmarks",
                                    "settings" -> controlsVisible = true
                                }
                            }
                        }

                        val activeAssistantTextLength = remember(transcript) {
                            transcript.lastOrNull { it.role == TranscriptRole.ASSISTANT }?.text?.length ?: 0
                        }
                        val generatedTokenCount = generationPerformance?.generatedTokens ?: 0

                        LaunchedEffect(
                            transcript.size,
                            isGenerating,
                            generatedTokenCount,
                            activeAssistantTextLength,
                        ) {
                            if (transcript.isNotEmpty() && scrollFollowPolicy.shouldAutoScroll()) {
                                suppressStickDetach = true
                                try {
                                    listState.scrollToItem(bottomAnchorIndex)
                                } finally {
                                    suppressStickDetach = false
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
                            isShortHeight = isShortHeight,
                            thermalGovernorState = thermalGovernorState,
                            generationPerformance = generationPerformance,
                            onOpenChats = { chatsVisible = true },
                            onOpenControls = { controlsVisible = true },
                            onOpenMemories = { memoriesVisible = true },
                            onOpenRag = { ragBrowserVisible = true },
                        )

                        Spacer(
                            modifier = Modifier.height(
                                if (headerCompact) 8.dp else 22.dp
                            )
                        )

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
                                                    messageId = message.id.toString(),
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

                        VoiceOverlay(
                            voiceState = voiceState,
                            onStopListening = { service?.stopVoiceInput() },
                        )

                        AnimatedVisibility(
                            visible = !isAtBottomAnchor && transcript.isNotEmpty(),
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 6.dp),
                        ) {
                            val fabDescription = if (isGenerating) {
                                "Jump to latest generation"
                            } else {
                                "Scroll to bottom"
                            }
                            Surface(
                                modifier = Modifier.semantics {
                                    contentDescription = fabDescription
                                    role = Role.Button
                                },
                                onClick = {
                                    scrollFollowPolicy.onUserScrolledToBottom()
                                    scope.launch {
                                        suppressStickDetach = true
                                        try {
                                            listState.animateScrollToItem(bottomAnchorIndex)
                                        } finally {
                                            suppressStickDetach = false
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(999.dp),
                                color = PrismBlue,
                                contentColor = Color.White,
                                shadowElevation = 4.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = if (isGenerating) "↓ Generating..." else "↓ Scroll to bottom",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                    )
                                }
                            }
                        }

                        // Inside the IME-padded region: the old placement aligned to the
                        // outer Box, so refusals were invisible behind the keyboard.
                        (snackbarMessage ?: uiMessage)?.let { message ->
                            Snackbar(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                containerColor = PrismSlate,
                                contentColor = PrismOnDark,
                                action = {
                                    TextButton(onClick = {
                                        if (snackbarMessage != null) {
                                            snackbarMessage = null
                                        }
                                        onClearUiMessage(message)
                                    }) {
                                        Text("Dismiss", color = PrismBlue)
                                    }
                                },
                            ) {
                                Text(message)
                            }
                        }

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
                            isShortHeight = isShortHeight,
                            canContinue = generationPerformance?.terminalReason == "MAX_TOKENS" &&
                                transcript.lastOrNull()?.role == TranscriptRole.ASSISTANT,
                            onPromptChange = { prompt = it },
                            onAddAttachment = {
                                onImportPickerStarted()
                                attachmentLauncher.launch(arrayOf("*/*"))
                            },
                            onRemoveAttachment = { attachment ->
                                savedAttachments = attachments
                                    .filterNot { it.uriString == attachment.uriString }
                                    .map(AttachmentTextCodec::encode)
                            },
                            onCancel = { service?.cancelGeneration() },
                            onContinue = { service?.continueGenerationSafely() },
                            onSend = {
                                val text = AttachmentTextExtractor.buildPrompt(prompt.trim(), attachments)
                                if (text.isNotEmpty()) {
                                    // Ask the service before destroying anything, off the
                                    // main thread because acceptance reads SQLite memory.
                                    // A refusal must leave the composed text and
                                    // attachments intact.
                                    scope.launch {
                                        val result = service?.preflightSend(text)
                                        if (result == null) {
                                            snackbarMessage = "Service unavailable"
                                        } else if (result.accepted) {
                                            prompt = ""
                                            savedAttachments = emptyList()
                                            draftStore.clear()
                                            service?.generateSafely(text)
                                        } else {
                                            snackbarMessage = result.reason ?: "Message refused; draft kept"
                                        }
                                    }
                                }
                            },
                            onVoiceClick = { service?.startVoiceInput() },
                            voiceState = voiceState,
                        )

                        AnimatedVisibility(
                            visible = controlsVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            ModalBottomSheet(
                                onDismissRequest = { controlsVisible = false },
                                sheetState = controlSheetState,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
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
                                    onDeleteModel = { identity ->
                                        scope.launch { service?.deleteModel(identity.modelId, identity) }
                                    },
                                    onImportModel = {
                                        onImportPickerStarted()
                                        importLauncher.launch(arrayOf("*/*"))
                                    },
                                    onCancelImport = { service?.cancelImport() },
                                    onDownloadModel = { entryId -> service?.downloadHuggingFaceModel(entryId) },
                                    onDownloadCustomHfModel = { repo, file -> service?.downloadCustomHuggingFaceModel(repo, file) },
                                    storageBreakdown = service?.storageBreakdown(),
                                    onClearCache = { service?.clearCacheAndTempFiles() },
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
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
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
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                dragHandle = { SheetDragHandle() },
                            ) {
                                MemoryBrowser(
                                    memories = memories,
                                    onDelete = { id -> service?.deleteMemory(id) },
                                    onRefresh = { service?.refreshMemories() },
                                    onAddMemory = { fact, category -> service?.addMemory(fact, category) },
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = ragBrowserVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            ModalBottomSheet(
                                onDismissRequest = { ragBrowserVisible = false },
                                sheetState = ragSheetState,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                dragHandle = { SheetDragHandle() },
                            ) {
                                DocumentBrowser(
                                    chunks = vectorChunks,
                                    staleDocumentCount = staleVectorChunkCount,
                                    ingestStatus = ingestStatus,
                                    onDeleteStaleDocuments = { service?.deleteStaleVectorChunks() },
                                    onIngestDocument = { id, title, text ->
                                        ingestStatus = null
                                        scope.launch {
                                            ingestStatus = withContext(Dispatchers.IO) {
                                                service?.ingestDocument(id, title, text)
                                            } ?: "Indexing unavailable"
                                        }
                                    },
                                    onDeleteDocument = { id -> service?.deleteDocument(id) },
                                    onQueryVectorStore = { query ->
                                        service?.queryVectorStore(query) ?: emptyList()
                                    },
                                    onRefresh = { service?.refreshVectorStore() },
                                )
                            }
                        }
                        pendingAgentToolAction?.let { action ->
                            AgentToolConfirmationDialog(
                                action = action,
                                onConfirm = { service?.confirmPendingAgentTool(action.id) },
                                onDismiss = { service?.cancelPendingAgentTool(action.id) },
                            )
                        }
                    }
                }
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
                    text = if (downloadState.integrityVerified) {
                        "Downloaded ${downloadState.entryName}"
                    } else {
                        "Downloaded ${downloadState.entryName} (unverified: no SHA-256 available)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloadState.integrityVerified) PrismGreen else PrismAmber,
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

