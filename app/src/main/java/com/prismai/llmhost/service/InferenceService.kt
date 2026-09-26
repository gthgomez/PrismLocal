package com.prismai.llmhost.service
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import org.json.JSONObject
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.ui.UiEventBus
import com.prismai.llmhost.engine.EngineConfigStore
import com.prismai.llmhost.chat.ChatManager
import com.prismai.llmhost.chat.ChatSearchIndex
import com.prismai.llmhost.chat.TranscriptStore
import com.prismai.llmhost.export.ChatExporter
import com.prismai.llmhost.model.DeviceMemorySnapshot
import com.prismai.llmhost.model.DeviceProfiler
import com.prismai.llmhost.model.ModelDownloadManager
import com.prismai.llmhost.model.ModelImportManager
import com.prismai.llmhost.model.ModelManager
import com.prismai.llmhost.model.ModelReadinessAssessor
import com.prismai.llmhost.generation.GenerationIdleInputs
import com.prismai.llmhost.generation.GenerationIdlePolicy
import com.prismai.llmhost.generation.GenerationStartGate
import com.prismai.llmhost.generation.BackgroundGenerationOwnership
import com.prismai.llmhost.generation.GenerationMetrics
import com.prismai.llmhost.generation.GenerationOrchestrator
import com.prismai.llmhost.generation.PromptBuilder
import com.prismai.llmhost.agent.AgentTrace
import com.prismai.llmhost.agent.AgentToolConfirmation
import com.prismai.llmhost.agent.AgentToolRouter
import com.prismai.llmhost.agent.ChatTransitionGate
import com.prismai.llmhost.agent.tools.toolFailure
import com.prismai.llmhost.util.FormatUtils

class InferenceService : Service() {
    companion object {
        private const val TAG = "InferenceService"
        private const val PREFS_NAME = "llm_host_prefs"
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_ACTIVE_CHAT = "active_chat"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_CONTEXT_LENGTH = "context_length"
        private const val KEY_BATCH_SIZE = "batch_size"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_TOP_P = "top_p"
        private const val KEY_REPEAT_PENALTY = "repeat_penalty"
        private const val KEY_GPU_LAYERS = "gpu_layers"
        private const val GENERATION_CHANNEL_ID = "llm_generation"
        private const val GENERATION_NOTIFICATION_ID = 1001
        private const val LEGACY_TRANSCRIPT_FILE_NAME = "chat_transcript.json"
        private const val CHAT_INDEX_FILE_NAME = "chat_index.json"
        private const val BENCHMARK_RUNS_FILE_NAME = "benchmark_runs.json"
        private const val CHAT_DIR_NAME = "chats"
        private const val TRANSCRIPT_PERSIST_THROTTLE_MS = 1000L
        private const val MAX_BENCHMARK_RUNS = 250
        private val CHAT_IDENTITY_TOOLS = setOf("clear_chat", "delete_chat", "delete_or_clear_chat")
        private const val KEY_AGENT_ENABLED = "agent_enabled"
        private const val KEY_PENDING_AGENT_ACTION_PREFIX = "pending_agent_action_"
        private const val AGENT_CHAT_CLEANUP_TIMEOUT_MS = 1_000L
        private const val CHAT_IDENTITY_RESULT_APPLIED = "agent_chat_identity_applied"
        // onDestroy() is a main-thread callback. Native teardown (freeing the
        // loaded ggml/llama model, context and compute buffers) can block for
        // seconds, which would trip an ANR. We therefore only wait this long
        // for the off-main teardown to finish before returning; the teardown
        // job itself keeps running if it overruns (see onDestroy).
        private const val NATIVE_TEARDOWN_WAIT_MS = 2_000L
    }

    private fun pendingAgentActionKey(chatId: String): String = "$KEY_PENDING_AGENT_ACTION_PREFIX$chatId"

    inner class LocalBinder : Binder() {
        fun getService(): InferenceService = this@InferenceService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // Dedicated scope for native engine teardown. It is intentionally NOT a
    // child of serviceScope: onDestroy() cancels serviceScope, which must not
    // abort an in-flight native free. onDestroy() launches teardown here and
    // waits a bounded budget on the main thread; if that budget expires the
    // job continues to completion off-main.
    private val teardownScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val chatTransitionGate = ChatTransitionGate(teardownScope)
    // Guards onDestroy() against a duplicate teardown launch (reentrancy /
    // defensive double-destroy). destroySafely() is itself idempotent, but this
    // avoids enqueuing redundant cancellation work.
    private val teardownStarted = AtomicBoolean(false)

    private lateinit var engine: NativeLlmBridge
    private lateinit var memoryGovernor: MemoryGovernor
    private lateinit var modelStorageManager: ModelStorageManager
    private var generationJob: Job? = null
    private var importJob: Job? = null
    private var downloadObserverJob: Job? = null
    private var criticalMemoryAlertActive = false
    @Volatile
    private var generationForegroundActive = false
    private val operationMutex = Mutex()
    private val generationStartGate = GenerationStartGate()
    private val backgroundGenerationOwnership = BackgroundGenerationOwnership()
    @Volatile
    private var activeGenerationSource: String = "user"
    @Volatile
    private var agentFollowUpScheduled = false
    private val confirmedChatTransitionChain = AtomicReference<Long?>(null)
    private val confirmedChatTransitionThread = ThreadLocal.withInitial { false }
    private val transcriptLock = Any()
    private var generationSession = 0L

    private data class ConfirmedAgentOperationContext(
        val authorization: AgentToolConfirmation.PendingToolAuthorization,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ConfirmedAgentOperationContext>
    }

    @Volatile
    private var previousRuntimeSettings: GenerationSettings? = null
    // nextTranscriptId / activeAssistantTranscriptId / lastTranscriptPersistAt
    // are now delegated to chatManager (see delegation properties below)
    private var reloadPending = false
    // ── Extracted UI state & event bus (Phase A refactor) ─────────────
    private val uiState = ServiceUiState()
    private val eventBus = UiEventBus()
    private lateinit var configStore: EngineConfigStore

    // ── Extracted chat/transcript classes (Phase A refactor) ──────────
    private val transcriptStore = TranscriptStore(this)
    private val chatSearchIndex = ChatSearchIndex()
    private lateinit var chatManager: ChatManager

    // ── Extracted model management (Phase B refactor) ─────────────────────
    private lateinit var deviceProfiler: DeviceProfiler
    private lateinit var modelReadinessAssessor: ModelReadinessAssessor
    private lateinit var modelImportManager: ModelImportManager
    private lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var modelManager: ModelManager

    // ── Extracted generation & agent core (Phase C refactor) ──────────────
    private lateinit var generationMetrics: GenerationMetrics
    private lateinit var promptBuilder: PromptBuilder
    private lateinit var agentTrace: AgentTrace
    private lateinit var agentToolConfirmation: AgentToolConfirmation
    private lateinit var agentToolRouter: AgentToolRouter
    private lateinit var generationOrchestrator: GenerationOrchestrator

    // ── Extracted benchmarks (Phase E refactor) ────────────────────────────
    private lateinit var benchmarkStore: com.prismai.llmhost.benchmark.BenchmarkStore
    private lateinit var benchmarkRunner: com.prismai.llmhost.benchmark.BenchmarkRunner

    // ── Extracted agent tool handlers (Phase D refactor) ───────────────────
    private lateinit var chatTools: com.prismai.llmhost.agent.tools.ChatTools
    private lateinit var modelTools: com.prismai.llmhost.agent.tools.ModelTools
    private lateinit var runtimeTools: com.prismai.llmhost.agent.tools.RuntimeTools
    private lateinit var memoryTools: com.prismai.llmhost.agent.tools.MemoryTools
    private lateinit var ragHandlerTools: com.prismai.llmhost.agent.tools.RagTools
    private lateinit var knowledgePackHandlerTools: com.prismai.llmhost.agent.tools.KnowledgePackTools
    private lateinit var voiceHandlerTools: com.prismai.llmhost.agent.tools.VoiceTools
    private lateinit var dataConnectorHandlerTools: com.prismai.llmhost.agent.tools.DataConnectorHandlerTools
    private lateinit var backgroundAgentHandlerTools: com.prismai.llmhost.agent.tools.BackgroundAgentTools
    private lateinit var webSearchTools: com.prismai.llmhost.agent.tools.WebSearchTools
    private lateinit var systemHandlerTools: com.prismai.llmhost.agent.tools.SystemTools
    private lateinit var workspaceTools: com.prismai.llmhost.agent.tools.WorkspaceTools

    // ── Chat state delegated to ChatManager ───────────────────────────
    private var nextTranscriptId
        get() = chatManager.nextTranscriptId
        set(value) { chatManager.nextTranscriptId = value }
    private var activeAssistantTranscriptId
        get() = chatManager.activeAssistantTranscriptId
        set(value) { chatManager.activeAssistantTranscriptId = value }
    private var lastTranscriptPersistAt
        get() = chatManager.lastTranscriptPersistAt
        set(value) { chatManager.lastTranscriptPersistAt = value }

    // ── StateFlow delegations — backing fields now live in ServiceUiState ──
    // Getter delegation preserves all existing _x.value = y write sites.
    private val _uiMessage get() = eventBus._uiMessage
    private val _lastAgentTracePath get() = uiState._lastAgentTracePath
    private val _memories get() = uiState._memories
    private val _voiceInputResult get() = uiState._voiceInputResult
    private val _voiceState get() = uiState._voiceState
    private val _currentModel get() = uiState._currentModel
    private val _activeModelInfo get() = uiState._activeModelInfo
    private val _isGenerating get() = uiState._isGenerating
    private val _runtimeStatus get() = uiState._runtimeStatus
    private val _importState get() = uiState._importState
    private val _modelDownloadState get() = uiState._modelDownloadState
    private val _recoveryTranscript get() = uiState._recoveryTranscript
    private val _chatSessions get() = uiState._chatSessions
    private val _currentChatId get() = uiState._currentChatId
    private val _transcript get() = uiState._transcript
    private val _generationSettings get() = uiState._generationSettings
    private val _generationPerformance get() = uiState._generationPerformance
    private val _benchmarkRuns get() = uiState._benchmarkRuns
    private val _benchmarkStatus get() = uiState._benchmarkStatus
    private val _modelLoadDiagnostics get() = uiState._modelLoadDiagnostics
    private val _deviceCapabilityProfile get() = uiState._deviceCapabilityProfile
    private val _modelReadiness get() = uiState._modelReadiness
    private val _pendingAgentToolAction get() = uiState._pendingAgentToolAction

    // Event bus — SharedFlow write sites (only 3 call sites) switched to eventBus methods
    private lateinit var memoryStore: SqlMemoryStore
    // Phase 2 — RAG
    private lateinit var vectorStore: VectorStore
    private lateinit var ragManager: RagManager
    // Phase 4 — Voice I/O
    private lateinit var voiceIoManager: VoiceIoManager
    // Phase 7a — Background agent state (public for UI observation)
    val backgroundAgentState: StateFlow<BackgroundAgentState> get() = if (::backgroundAgentManager.isInitialized) backgroundAgentManager.state else MutableStateFlow(BackgroundAgentState()).asStateFlow()
    // Phase 5 — Data connectors
    private lateinit var dataConnectorTools: DataConnectorTools
    // Phase 3 — Grokipedia Knowledge Pack
    private lateinit var grokipediaClient: GrokipediaClient
    private lateinit var knowledgePackManager: KnowledgePackManager
    // Phase 7a — Background Agent Execution
    private lateinit var backgroundAgentManager: BackgroundAgentManager
    private var webSearchUsedThisSession = false
    // Chat search index now lives in ChatSearchIndex (declared in Phase A section above).

    // ── Public StateFlow/SharedFlow exposures — delegated to extracted classes ──
    val lastAgentTracePath: StateFlow<String?> get() = uiState.lastAgentTracePath
    val memories: StateFlow<List<MemoryFact>> get() = uiState.memories
    val vectorChunks: StateFlow<List<com.prismai.llmhost.storage.VectorChunk>> get() = uiState.vectorChunks
    val thermalGovernorState: StateFlow<com.prismai.llmhost.util.ThermalGovernorState> get() = uiState.thermalGovernorState
    val voiceInputResult: StateFlow<String?> get() = uiState.voiceInputResult
    val voiceState: StateFlow<VoiceState> get() = uiState.voiceState
    val currentModel: StateFlow<String?> get() = uiState.currentModel
    val activeModelInfo: StateFlow<ModelStorageManager.ActiveModelInfo?> get() = uiState.activeModelInfo
    val isGenerating: StateFlow<Boolean> get() = uiState.isGenerating
    val runtimeStatus: StateFlow<RuntimeStatus> get() = uiState.runtimeStatus
    val importState: StateFlow<ImportState> get() = uiState.importState
    val modelDownloadState: StateFlow<ModelDownloadState> get() = uiState.modelDownloadState
    val recoveryTranscript: StateFlow<String?> get() = uiState.recoveryTranscript
    val chatSessions: StateFlow<List<ChatSession>> get() = uiState.chatSessions
    val currentChatId: StateFlow<String?> get() = uiState.currentChatId
    val transcript: StateFlow<List<TranscriptMessage>> get() = uiState.transcript
    val generationSettings: StateFlow<GenerationSettings> get() = uiState.generationSettings
    val generationPerformance: StateFlow<GenerationPerformance?> get() = uiState.generationPerformance
    val benchmarkRuns: StateFlow<List<BenchmarkRun>> get() = uiState.benchmarkRuns
    val benchmarkStatus: StateFlow<BenchmarkStatus> get() = uiState.benchmarkStatus
    val modelLoadDiagnostics: StateFlow<ModelLoadDiagnostics?> get() = uiState.modelLoadDiagnostics
    val deviceCapabilityProfile: StateFlow<DeviceCapabilityProfile?> get() = uiState.deviceCapabilityProfile
    val modelReadiness: StateFlow<List<ModelReadiness>> get() = uiState.modelReadiness
    val pendingAgentToolAction: StateFlow<PendingAgentToolAction?> get() = uiState.pendingAgentToolAction
    val panelRequests: SharedFlow<String> get() = eventBus.panelRequests
    val uiMessage: StateFlow<String?> get() = eventBus.uiMessage
    val uiEvents: SharedFlow<String> get() = eventBus.uiEvents
    val streamState get() = uiState.streamState

    override fun onCreate() {
        super.onCreate()
        configStore = EngineConfigStore(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))
        chatManager = ChatManager(this, transcriptStore, chatSearchIndex, uiState, eventBus, serviceScope)
        createNotificationChannel()
        CapabilityRegistryHolder.auditLog.init(filesDir)
        engine = NativeLlmBridge.create()
        memoryGovernor = MemoryGovernor(this)
        modelStorageManager = ModelStorageManager(this)
        // ── Phase B: extracted model management classes ──────────────
        deviceProfiler = DeviceProfiler(this)
        modelReadinessAssessor = ModelReadinessAssessor(modelStorageManager, deviceProfiler, uiState)
        modelImportManager = ModelImportManager(
            modelStorageManager, uiState, eventBus, serviceScope,
            onRefreshReadiness = { refreshDeviceAndModelReadiness() },
        )
        modelDownloadManager = ModelDownloadManager(
            this, uiState, eventBus, serviceScope,
            onRefreshReadiness = { refreshDeviceAndModelReadiness() },
            onAutoLoadModel = { modelId -> switchModel(modelId) },
        )
        modelManager = ModelManager(
            this, engine, modelStorageManager, uiState, eventBus, chatManager,
            deviceProfiler, modelReadinessAssessor,
            onRefreshReadiness = { refreshDeviceAndModelReadiness() },
        )
        memoryStore = SqlMemoryStore(this)
        refreshMemoriesList()
        // Phase 2 — RAG vector store + manager
        vectorStore = VectorStore(this)
        ragManager = RagManager(vectorStore, DocumentChunker) { text ->
            engine.encode(text)
        }
        // Phase 4 — Voice I/O manager
        voiceIoManager = VoiceIoManager(this).also { mgr ->
            mgr.onSpeechResult = { text ->
                _voiceInputResult.value = text
                _voiceState.value = _voiceState.value.copy(isListening = false, partialTranscript = null)
                publishUiEvent("Voice input captured")
                if (_generationSettings.value.autoSendVoice && text.isNotBlank()) {
                    generateSafely(text)
                }
            }
            mgr.onSpeechError = { error ->
                _voiceInputResult.value = null
                _voiceState.value = _voiceState.value.copy(isListening = false)
                publishUiEvent("Voice input error: $error")
            }
            mgr.onSpeechPartialResult = { partial ->
                _voiceState.value = _voiceState.value.copy(partialTranscript = partial)
            }
        }
        _voiceState.value = VoiceState(
            sttAvailable = voiceIoManager.isSttAvailable(),
            ttsAvailable = voiceIoManager.isTtsAvailable(),
        )
        // Phase 5 — Data connector tools
        dataConnectorTools = DataConnectorTools(this)
        // Phase 3 — Grokipedia Knowledge Pack
        grokipediaClient = GrokipediaClient()
        knowledgePackManager = KnowledgePackManager(
            grokipediaClient = grokipediaClient,
            vectorStore = vectorStore,
            ragManager = ragManager,
            chunker = DocumentChunker,
        )
        // Phase 7a — Background Agent Execution
        backgroundAgentManager = BackgroundAgentManager(
            context = this,
            executeTask = { bgTask ->
                check(backgroundGenerationOwnership.begin(bgTask.id)) {
                    "Another background task already owns the generation engine"
                }
                try {
                    generateSafelyAndAwait(
                        prompt = bgTask.prompt,
                        preserveBenchmarkQueue = true,
                        initiatedByBackground = true,
                        sourceChatId = bgTask.sourceChatId,
                        backgroundTaskId = bgTask.id,
                    )
                } finally {
                    withContext(NonCancellable) {
                        operationMutex.withLock {
                            val deferredChatId = backgroundGenerationOwnership.finish(bgTask.id)
                            applyPendingBackgroundChatSwitchLocked(bgTask.sourceChatId, deferredChatId)
                        }
                    }
                }
            },
            cancelNativeGeneration = {
                val sourceIsBackground = operationMutex.withLock { activeGenerationSource == "background" }
                if (sourceIsBackground) {
                    cancelGenerationAndJoin("background task cancelled")
                }
            },
            isDeviceBusyWithUserGeneration = {
                _isGenerating.value || _pendingAgentToolAction.value != null
            },
        )

        // ── Phase C: generation & agent core ────────────────────────────
        generationMetrics = GenerationMetrics(uiState)
        promptBuilder = PromptBuilder(memoryStore, ragManager)
        // Trace writes use the non-cancelled teardown scope so shutdown/cancellation
        // cannot discard the final ownership artifact.
        agentTrace = AgentTrace(uiState, filesDir, teardownScope)
        agentToolConfirmation = AgentToolConfirmation(
            uiState = uiState,
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE),
            currentChatId = { _currentChatId.value },
            pendingActionKey = { chatId -> pendingAgentActionKey(chatId) },
            getDeviceProfile = { getDeviceCapabilityProfileCached() },
        )
        agentToolRouter = AgentToolRouter(
            uiState = uiState,
            agentTrace = agentTrace,
            confirmation = agentToolConfirmation,
            getCachedProfile = { deviceProfiler.getCachedProfile() },
            executeTool = { call, confirmed -> executeAgentTool(call, confirmed) },
            onFollowUp = { prompt, result, depth, chainId ->
                startAgentFollowUpGeneration(prompt, result, depth, chainId)
            },
            onAppendTranscriptMessage = { role, text -> appendTranscriptMessage(role, text) },
            onPublishUiEvent = { msg -> publishUiEvent(msg) },
            scope = serviceScope,
            currentChatId = { _currentChatId.value },
        )
        generationOrchestrator = GenerationOrchestrator(
            engine = engine,
            uiState = uiState,
            eventBus = eventBus,
            chatManager = chatManager,
            promptBuilder = promptBuilder,
            metrics = generationMetrics,
            agentToolRouter = agentToolRouter,
            agentTrace = agentTrace,
            agentToolConfirmation = agentToolConfirmation,
            deviceProfiler = deviceProfiler,
            scope = serviceScope,
            onStartForeground = { startGenerationForeground() },
            onStopForeground = { stopGenerationForeground() },
            storeGenerationJob = { job -> generationJob = job },
            getActiveSession = { generationSession },
            incrementSession = { ++generationSession },
            onSaveTranscript = { persistTranscriptNow() },
            onRecordBenchmarkRun = { prompt, output, perf, reason, detail ->
                benchmarkStore.record(prompt, output, perf, reason, detail)
            },
            // Runner is constructed after the orchestrator; guard the lazy reference.
            onBenchmarkComplete = { if (::benchmarkRunner.isInitialized) benchmarkRunner.runNextQueued() },
            onBeforeChatIdentityChange = { reason, mutation ->
                prepareAgentChatTransition(reason, afterCleanup = mutation)
            },
            onDeferredReload = { modelId -> switchModel(modelId) },
            getReloadPending = { reloadPending },
            setReloadPending = { v -> reloadPending = v },
        )

        // ── Phase D: agent tool handler classes ─────────────────────────
        chatTools = com.prismai.llmhost.agent.tools.ChatTools(
            chatManager = chatManager,
            chatSearchIndex = chatSearchIndex,
            transcriptStore = transcriptStore,
            currentChatId = { _currentChatId.value },
            chatSessions = { _chatSessions.value },
            transcript = { _transcript.value },
            isGenerating = { _isGenerating.value },
            filesDir = filesDir,
            onPersistTranscript = { persistTranscriptNow() },
            onSwitchChat = { id -> switchChat(id) },
            onClearTranscript = { clearTranscript() },
            onDeleteChat = { id -> deleteChat(id) },
            onRenameChat = { id, title -> renameChat(id, title) },
            onResetNativeConversation = { reason -> resetNativeConversationAsync(reason) },
        )
        modelTools = com.prismai.llmhost.agent.tools.ModelTools(
            listInstalledModelInfos = modelStorageManager::listInstalledModelInfos,
            deleteModelDirectly = modelManager::deleteModel,
            modelReadinessAssessor = modelReadinessAssessor,
            modelImportManager = modelImportManager,
            modelDownloadManager = modelDownloadManager,
            uiState = uiState,
            onRefreshReadiness = { refreshDeviceAndModelReadiness() },
            filesDir = filesDir,
            chatDirectory = { chatDirectory() },
            benchmarkFileSize = { benchmarkRunsFile().sizeRecursive() },
            chatIndexFile = { chatIndexFile() },
            deleteModelSafely = { modelId -> deleteModel(modelId) },
        )
        runtimeTools = com.prismai.llmhost.agent.tools.RuntimeTools(
            uiState = uiState,
            deviceProfiler = deviceProfiler,
            onUpdateSettings = { s -> updateGenerationSettings(s) },
            onContinueGeneration = { continueGenerationSafely() },
            onCancelGeneration = { cancelGeneration() },
            onCancelImport = { cancelImport() },
            onRunBenchmarkPreset = { id -> runBenchmarkPreset(id) },
            onRunNativePpTgBenchmark = { runNativePpTgBenchmark() },
            onRunThreadSweepBenchmark = { runThreadSweepBenchmark() },
            getPreviousSettings = { previousRuntimeSettings },
            setPreviousSettings = { v -> previousRuntimeSettings = v },
            activeOperationJson = { activeOperationJson() },
            importJobIsActive = { importJob?.isActive == true },
            importState = { _importState.value },
            modelDownloadState = { _modelDownloadState.value },
        )
        memoryTools = com.prismai.llmhost.agent.tools.MemoryTools(
            memoryStore = memoryStore,
            currentChatId = { _currentChatId.value },
            refreshMemoriesList = { refreshMemoriesList() },
        )
        ragHandlerTools = com.prismai.llmhost.agent.tools.RagTools(
            ragManager = ragManager,
            vectorStore = vectorStore,
        )
        knowledgePackHandlerTools = com.prismai.llmhost.agent.tools.KnowledgePackTools(
            knowledgePackManager = knowledgePackManager,
            grokipediaClient = grokipediaClient,
        )
        voiceHandlerTools = com.prismai.llmhost.agent.tools.VoiceTools(
            appContext = this,
            voiceIoManager = voiceIoManager,
            uiState = uiState,
        )
        dataConnectorHandlerTools = com.prismai.llmhost.agent.tools.DataConnectorHandlerTools(
            dataConnectorTools = dataConnectorTools,
        )
        backgroundAgentHandlerTools = com.prismai.llmhost.agent.tools.BackgroundAgentTools(
            backgroundAgentManager = backgroundAgentManager,
        )
        webSearchTools = com.prismai.llmhost.agent.tools.WebSearchTools(
            onFirstUse = { publishUiEvent("Web search: DuckDuckGo") },
        )
        systemHandlerTools = com.prismai.llmhost.agent.tools.SystemTools(
            uiState = uiState,
            eventBus = eventBus,
            deviceProfiler = deviceProfiler,
            modelStorageManager = modelStorageManager,
            agentToolConfirmation = agentToolConfirmation,
            onRefreshReadiness = { refreshDeviceAndModelReadiness() },
            onSwitchModel = { modelId -> switchModel(modelId) },
            filesDir = filesDir,
            chatDirectory = { chatDirectory() },
            chatIndexFile = { chatIndexFile() },
            benchmarkRunsFile = { benchmarkRunsFile() },
            activeOperationJson = { activeOperationJson() },
            onCancelImport = { cancelImport() },
            importJobIsActive = { importJob?.isActive == true },
        )
        workspaceTools = com.prismai.llmhost.agent.tools.WorkspaceTools(
            rootDir = com.prismai.llmhost.agent.tools.WorkspaceTools.defaultRoot(filesDir),
        )

        // ── Phase E: benchmark classes ──────────────────────────────────
        benchmarkStore = com.prismai.llmhost.benchmark.BenchmarkStore(
            uiState = uiState,
            metrics = generationMetrics,
            deviceProfiler = deviceProfiler,
            filesDir = filesDir,
            onRefreshReadiness = { refreshDeviceAndModelReadiness() },
        )
        benchmarkRunner = com.prismai.llmhost.benchmark.BenchmarkRunner(
            uiState = uiState,
            eventBus = eventBus,
            engine = engine,
            metrics = generationMetrics,
            store = benchmarkStore,
            orchestrator = generationOrchestrator,
            scope = serviceScope,
        )

        benchmarkStore.load()
        loadChats()
        agentToolConfirmation.restore()
        loadGenerationSettings()
        refreshDeviceAndModelReadiness()
        observeHuggingFaceDownloadWork()
        serviceScope.launch {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_ACTIVE_MODEL, null)
                ?.let { savedModel ->
                    switchModel(savedModel)
                }
        }
        // Register push-based trim callback (instant notification)
        memoryGovernor.register { state ->
            serviceScope.launch { engine.setMemoryPressure(state.level) }
        }
        // Keep polling flow as fallback (gradual pressure detection)
        memoryGovernor.monitorMemory()
            .distinctUntilChanged()
            .onEach { state ->
                engine.setMemoryPressure(state.level)
                if (state == MemoryState.CRITICAL) {
                    saveTranscriptSafely()
                    if (!criticalMemoryAlertActive) {
                        publishUiEvent("Memory critical; transcript saved")
                    }
                    criticalMemoryAlertActive = true
                } else {
                    criticalMemoryAlertActive = false
                }
            }
            .launchIn(serviceScope)
    }

    fun listModels(): List<String> = modelManager.listModels()

    fun huggingFaceCatalog(): List<HuggingFaceModelEntry> = HuggingFaceModelCatalog.entries

    fun benchmarkPresets(): List<BenchmarkPreset> = BenchmarkPresets.defaults

    fun refreshDeviceAndModelReadiness() {
        val profile = deviceProfiler.captureProfile()
        _deviceCapabilityProfile.value = profile
        _modelReadiness.value = modelStorageManager.listInstalledModelInfos()
            .map { info -> modelReadinessAssessor.buildReadiness(info, profile) }
    }

    fun importModel(uri: Uri) {
        if (importJob?.isActive == true) {
            publishUiEvent("A model import is already running")
            return
        }
        importJob = modelImportManager.importModel(uri)
    }

    fun downloadHuggingFaceModel(entryId: String) {
        modelDownloadManager.downloadHuggingFaceModel(entryId)
    }

    fun storageBreakdown(): ModelStorageManager.StorageBreakdown = modelStorageManager.getStorageBreakdown()

    fun clearCacheAndTempFiles(): Long {
        val freed = modelStorageManager.clearCacheAndTempFiles()
        publishUiEvent("Cleared ${FormatUtils.formatBytesForMessage(freed)} of cache and temp files")
        return freed
    }

    fun downloadCustomHuggingFaceModel(repoId: String, fileName: String) {
        modelDownloadManager.downloadCustomHuggingFaceModel(repoId, fileName)
    }

    fun linkExternalModel(uri: Uri) {
        val result = modelStorageManager.linkExternalModelUri(uri)
        if (result is ModelStorageManager.ImportResult.Success) {
            refreshDeviceAndModelReadiness()
            publishUiEvent("Linked external GGUF ${result.model.id}")
        } else if (result is ModelStorageManager.ImportResult.Failure) {
            publishUiEvent("Failed to link GGUF: ${result.error.userMessage}")
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        WorkManager.getInstance(this).cancelUniqueWork(HuggingFaceDownloadWork.UNIQUE_WORK_NAME)
        modelImportManager.cancelImport()
    }

    fun clearImportState() {
        modelImportManager.clearImportState()
    }

    private fun observeHuggingFaceDownloadWork() {
        modelDownloadManager.observeDownloadWork()
    }

    suspend fun switchModel(modelId: String): Boolean {
        val confirmedAuthorization = currentCoroutineContext()[ConfirmedAgentOperationContext]?.authorization
        return operationMutex.withLock {
            if (confirmedAuthorization != null &&
                !agentTrace.isCurrentChain(confirmedAuthorization.chainId)
            ) {
                return@withLock false
            }
            val preserveConfirmedChain = confirmedAuthorization != null
            try {
                Log.d(TAG, "switchModel requested modelId=$modelId")
                cancelAndJoinGenerationLocked(
                    reason = "model switch",
                    finalizeActiveTrace = !preserveConfirmedChain,
                    preserveActiveTrace = preserveConfirmedChain,
                )
                modelManager.switchModel(modelId)
            } finally {
                if (preserveConfirmedChain) {
                    agentTrace.releaseChainPreservation(confirmedAuthorization.chainId)
                }
            }
        }
    }

    suspend fun deleteModel(modelId: String): Boolean {
        val confirmedAuthorization = currentCoroutineContext()[ConfirmedAgentOperationContext]?.authorization
        return operationMutex.withLock {
            if (confirmedAuthorization != null &&
                !agentTrace.isCurrentChain(confirmedAuthorization.chainId)
            ) {
                return@withLock false
            }
            val preserveConfirmedChain = confirmedAuthorization != null
            try {
                Log.d(TAG, "deleteModel requested modelId=$modelId")
                modelDownloadManager.cancelDownloadForModel(modelId)
                cancelAndJoinGenerationLocked(
                    reason = "model delete",
                    finalizeActiveTrace = !preserveConfirmedChain,
                    preserveActiveTrace = preserveConfirmedChain,
                )
                modelManager.deleteModel(modelId)
            } finally {
                if (preserveConfirmedChain) {
                    agentTrace.releaseChainPreservation(confirmedAuthorization.chainId)
                }
            }
        }
    }

    private fun getDeviceCapabilityProfileCached(maxAgeMs: Long = 90000L): DeviceCapabilityProfile {
        val profile = deviceProfiler.getCachedProfile(maxAgeMs)
        _deviceCapabilityProfile.value = profile
        return profile
    }

    fun cancelGeneration() {
        benchmarkRunner.cancelGeneration()
        serviceScope.launch {
            operationMutex.withLock {
                cancelAndJoinGenerationLocked("user cancel")
            }
        }
    }

    fun clearUiMessage(message: String) {
        if (_uiMessage.value == message) {
            _uiMessage.value = null
        }
    }

    fun createChat(): String {
        if (_isGenerating.value || generationStartGate.isStartInProgress()) {
            publishUiEvent("Cancel generation before creating a new chat")
            return _currentChatId.value.orEmpty()
        }
        abandonPendingAgentTool(
            reason = "Chat changed before tool confirmation",
            afterCleanup = {
                generationStartGate.runChatTransition {
                    chatManager.createChatInternal(ChatTitles.DEFAULT_TITLE)
                    resetNativeConversationAsync("new chat")
                }
            },
        )
        // The UI does not use this value; identity mutation is intentionally
        // completed by the transition worker after cleanup.
        return _currentChatId.value.orEmpty()
    }

    private suspend fun ensureChatForGeneration() {
        if (_currentChatId.value != null) return
        val prepared = prepareAgentChatTransition(
            reason = "Preparing generation chat",
            afterCleanup = {
                if (_currentChatId.value == null) {
                    chatManager.createChatInternal(ChatTitles.DEFAULT_TITLE)
                    resetNativeConversationAsync("new chat")
                }
            },
        )
        if (!prepared && _currentChatId.value == null) {
            chatManager.createChatInternal(ChatTitles.DEFAULT_TITLE)
            resetNativeConversationAsync("new chat")
        }
    }

    private fun ensureChatForGenerationBlocking() {
        if (_currentChatId.value == null) {
            chatManager.createChatInternal(ChatTitles.DEFAULT_TITLE)
            resetNativeConversationAsync("new chat")
        }
    }

    private fun performSwitchChat(chatId: String): Boolean {
        return generationStartGate.runChatTransition {
            if (_isGenerating.value || backgroundGenerationOwnership.hasOwner()) return@runChatTransition false
            if (_chatSessions.value.none { it.id == chatId }) return@runChatTransition false
            val switched = chatManager.switchChat(chatId)
            if (switched) {
                agentToolConfirmation.restore()
                resetNativeConversationAsync("chat switch")
            }
            switched
        } ?: false
    }

    private fun performClearTranscript(): Boolean {
        return generationStartGate.runChatTransition {
            if (_isGenerating.value || backgroundGenerationOwnership.hasOwner()) return@runChatTransition false
            chatManager.clearTranscript()
            resetNativeConversationAsync("clear transcript")
            true
        } ?: false
    }

    private fun performDeleteChat(chatId: String): Boolean {
        return generationStartGate.runChatTransition {
            if (_isGenerating.value) return@runChatTransition false
            val deleted = backgroundAgentManager.deleteChatAndInvalidateTasks(chatId) {
                chatManager.deleteChat(chatId)
            }
            if (deleted) agentTrace.deleteForChat(chatId)
            deleted
        } ?: false
    }

    fun switchChat(chatId: String): Boolean {
        if (_chatSessions.value.none { it.id == chatId }) {
            publishUiEvent("Chat no longer exists")
            return false
        }
        val backgroundSwitchQueued = backgroundGenerationOwnership.deferChatSwitch(chatId)
        if (backgroundSwitchQueued) {
            publishUiEvent("Chat switch queued until the background task finishes")
            return true
        }
        if (confirmedChatTransitionThread.get() == true) return performSwitchChat(chatId)
        if (_isGenerating.value || generationStartGate.isStartInProgress()) {
            publishUiEvent("Cancel generation before switching chats")
            return false
        }
        if (_chatSessions.value.none { it.id == chatId }) {
            publishUiEvent("Chat no longer exists")
            return false
        }
        return abandonPendingAgentTool(
            reason = "Chat changed before tool confirmation",
            afterCleanup = {
                performSwitchChat(chatId)
                Unit
            },
        )
    }

    fun renameChat(chatId: String, title: String) {
        chatManager.renameChat(chatId, title)
    }

    fun deleteChat(chatId: String): Boolean {
        if (confirmedChatTransitionThread.get() == true) return performDeleteChat(chatId)
        if (_isGenerating.value || generationStartGate.isStartInProgress()) {
            publishUiEvent("Cancel generation before deleting a chat")
            return false
        }
        return abandonPendingAgentTool(
            reason = "Chat deleted before tool confirmation",
            afterCleanup = {
                performDeleteChat(chatId)
                Unit
            },
        )
    }

    fun clearTranscript(): Boolean {
        if (confirmedChatTransitionThread.get() == true) return performClearTranscript()
        if (_isGenerating.value) {
            publishUiEvent("Cancel generation before clearing chat")
            return false
        }
        return abandonPendingAgentTool(
            reason = "Transcript cleared before tool confirmation",
            afterCleanup = {
                performClearTranscript()
                Unit
            },
        )
    }

    fun updateGenerationSettings(settings: GenerationSettings) {
        val previous = _generationSettings.value.clamped()
        val safeSettings = settings.clamped()
        // Close authorization admission before publishing agent mode as disabled.
        syncCapabilities(safeSettings)
        _generationSettings.value = safeSettings
        if (previous.agentEnabled && !safeSettings.agentEnabled && ::agentTrace.isInitialized) {
            agentTrace.activeChainId?.let { chainId ->
                agentTrace.abortTrace(chainId, "Agent mode disabled")
            }
            agentToolConfirmation.clearMemory()
        }
        refreshDeviceAndModelReadiness()
        persistGenerationSettings(safeSettings)
        if (settingsRequireReload(previous, safeSettings)) {
            val modelId = _currentModel.value
            if (modelId != null) {
                if (!_isGenerating.value) {
                    publishUiEvent("Reloading model for context/backend settings")
                    serviceScope.launch { switchModel(modelId) }
                } else {
                    reloadPending = true
                }
            }
        }
    }

    private fun resetNativeConversationAsync(reason: String) {
        serviceScope.launch {
            operationMutex.withLock {
                runCatching {
                    engine.resetConversation()
                    Log.d(TAG, "native conversation reset reason=$reason")
                }.onFailure { error ->
                    Log.w(TAG, "native conversation reset failed reason=$reason", error)
                }
            }
        }
    }

    private fun settingsRequireReload(previous: GenerationSettings, next: GenerationSettings): Boolean =
        EngineConfigStore.requiresReload(previous, next)

    private fun persistGenerationSettings(settings: GenerationSettings) {
        configStore.save(settings)
    }

    fun clearBenchmarkRuns() = benchmarkStore.clear()

    fun runBenchmarkPreset(presetId: String) = benchmarkRunner.runPreset(presetId)

    fun runThreadSweepBenchmark() = benchmarkRunner.runThreadSweep()

    fun runNativePpTgBenchmark() = benchmarkRunner.runNativePpTg()

    fun benchmarkCsv(): String = benchmarkStore.csv()
    fun benchmarkJson(): String = benchmarkStore.json()

    fun generateSafely(
        prompt: String,
        benchmarkPreset: BenchmarkPreset? = null,
        preserveBenchmarkQueue: Boolean = false,
    ) {
        serviceScope.launch {
            generateSafelyAndAwait(prompt, benchmarkPreset, preserveBenchmarkQueue)
        }
    }

    suspend fun generateSafelyAndAwait(
        prompt: String,
        benchmarkPreset: BenchmarkPreset? = null,
        preserveBenchmarkQueue: Boolean = false,
        initiatedByBackground: Boolean = false,
        sourceChatId: String? = null,
        backgroundTaskId: String? = null,
    ): String {
        if (confirmedChatTransitionChain.get() != null) {
            return "Chat transition in progress"
        }
        var restoreChatId: String? = null
        operationMutex.withLock {
            if (!initiatedByBackground && backgroundGenerationOwnership.hasOwner()) {
                publishUiEvent("A background task currently owns the generation engine")
                return "Background task owns the engine"
            }
            if (initiatedByBackground &&
                (_isGenerating.value || _pendingAgentToolAction.value != null)
            ) {
                return "Background task deferred: device busy"
            }
            if (!generationStartGate.beginStart()) {
                return "Generation deferred while a chat transition completes"
            }
            val startGateReserved = true
            try {
                if (initiatedByBackground) {
                    if (!backgroundGenerationOwnership.isOwner(backgroundTaskId)) {
                        throw IllegalStateException("Background task generation ownership changed")
                    }
                    val ownerChatId = sourceChatId?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("Background task has no source chat owner")
                    if (_chatSessions.value.none { it.id == ownerChatId }) {
                        throw IllegalStateException("Background task source chat was deleted")
                    }
                    val selectedChatId = _currentChatId.value
                    if (selectedChatId != ownerChatId) {
                        if (!chatManager.switchChat(ownerChatId)) {
                            throw IllegalStateException("Could not restore background task source chat")
                        }
                        engine.resetConversation()
                        restoreChatId = selectedChatId
                    }
                }
                activeGenerationSource = if (initiatedByBackground) "background" else "user"
                cancelAndJoinGenerationLocked(
                    reason = "new generation",
                    clearQueuedBenchmarks = !preserveBenchmarkQueue,
                )
                if (benchmarkPreset == null && _currentChatId.value == null) {
                    ensureChatForGeneration()
                }
                generationOrchestrator.generate(prompt, benchmarkPreset)
            } finally {
                if (startGateReserved) generationStartGate.finishStart()
            }
        }
        try {
            if (!awaitGenerationSessionIdle()) {
                if (initiatedByBackground) {
                    throw IllegalStateException("Generation session exceeded its bounded wait")
                }
                return "Generation session wait timed out"
            }
            if (initiatedByBackground && !backgroundGenerationOwnership.isOwner(backgroundTaskId)) {
                throw IllegalStateException("Background task generation ownership changed")
            }
            return streamState.snapshotText().takeIf { it.isNotBlank() } ?: "Background task completed"
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                cancelGenerationAndJoin("generation owner cancelled")
            }
            throw cancelled
        } finally {
            if (restoreChatId != null || backgroundTaskId != null) {
                withContext(NonCancellable) {
                    operationMutex.withLock {
                        if (!_isGenerating.value && !shouldWaitForGenerationSession() &&
                            _currentChatId.value == sourceChatId
                        ) {
                            val requestedChatId = if (backgroundTaskId != null) {
                                backgroundGenerationOwnership.takeDeferredChatSwitch(backgroundTaskId)
                                    ?: restoreChatId
                            } else restoreChatId
                            if (requestedChatId != null &&
                                _chatSessions.value.any { it.id == requestedChatId } &&
                                chatManager.switchChat(requestedChatId)
                            ) {
                                agentToolConfirmation.restore()
                                engine.resetConversation()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun agentContinuationAvailable(chainId: Long): Boolean {
        if (chainId <= 0L || !_generationSettings.value.agentEnabled || _currentModel.value == null) return false
        if (_generationPerformance.value?.terminalReason != "MAX_TOKENS") return false
        return _transcript.value.any { message ->
            message.role == TranscriptRole.ASSISTANT && message.text.isNotBlank()
        }
    }

    private suspend fun applyPendingBackgroundChatSwitchLocked(
        sourceChatId: String?,
        targetChatId: String?,
    ) {
        if (_isGenerating.value || shouldWaitForGenerationSession() ||
            (sourceChatId != null && _currentChatId.value != sourceChatId)
        ) return
        if (targetChatId == null) return
        if (_chatSessions.value.none { it.id == targetChatId }) return
        if (chatManager.switchChat(targetChatId)) {
            agentToolConfirmation.restore()
            engine.resetConversation()
        }
    }

    private fun generationIdleInputs(): GenerationIdleInputs {
        val chainId = if (::agentTrace.isInitialized) agentTrace.activeChainId else null
        val toolActive = chainId != null && ::agentToolRouter.isInitialized &&
            agentToolRouter.hasActiveToolJobs(chainId)
        val pendingConfirmation = _pendingAgentToolAction.value != null || agentFollowUpScheduled
        val continuationAvailable = chainId != null && agentContinuationAvailable(chainId)
        return GenerationIdleInputs(
            generationRunning = _isGenerating.value,
            generationJobActive = generationJob?.isActive == true,
            agentToolActive = toolActive,
            confirmationPending = pendingConfirmation,
            agentChainActive = chainId != null,
            continuationAvailable = continuationAvailable,
        )
    }

    private fun shouldWaitForGenerationSession(): Boolean {
        val inputs = generationIdleInputs()
        if (GenerationIdlePolicy.shouldAbortUnresumableChain(inputs)) {
            val chainId = if (::agentTrace.isInitialized) agentTrace.activeChainId else null
            val aborted = chainId != null && agentTrace.abortTrace(
                chainId,
                "Continuation unavailable: agent mode or response cannot continue",
            )
            return if (aborted) {
                GenerationIdlePolicy.shouldWait(
                    inputs.copy(agentChainActive = false, continuationAvailable = false),
                )
            } else {
                GenerationIdlePolicy.shouldWait(inputs)
            }
        }
        return GenerationIdlePolicy.shouldWait(inputs)
    }

    private suspend fun awaitGenerationSessionIdle(
        pollMs: Long = 20L,
        maxWaitMs: Long = 30 * 60 * 1000L,
    ): Boolean {
        return com.prismai.llmhost.generation.GenerationSessionWait.awaitSessionIdle(
            isGenerating = { shouldWaitForGenerationSession() },
            getJob = { generationJob },
            pollMs = pollMs,
            maxWaitMs = maxWaitMs,
            elapsedTimeMs = { android.os.SystemClock.elapsedRealtime() },
            onTimeout = { Log.w(TAG, "awaitGenerationSessionIdle timed out") },
        )
    }

    suspend fun cancelGenerationAndJoin(reason: String = "user cancellation") {
        operationMutex.withLock {
            cancelAndJoinGenerationLocked(reason)
        }
    }

    fun cancelGenerationSafely() {
        serviceScope.launch {
            val backgroundTaskId = backgroundGenerationOwnership.ownerId()
            if (backgroundTaskId != null) {
                backgroundAgentManager.cancelTask(backgroundTaskId)
            } else {
                cancelGenerationAndJoin("user cancellation")
            }
        }
    }

    fun continueGenerationSafely() {
        serviceScope.launch {
            operationMutex.withLock {
                if (backgroundGenerationOwnership.hasOwner()) {
                    publishUiEvent("A background task currently owns the generation engine")
                    return@withLock
                }
                if (!generationStartGate.beginStart()) return@withLock
                try {
                    cancelAndJoinGenerationLocked(
                        reason = "continue generation",
                        finalizeActiveTrace = false,
                        preserveActiveTrace = true,
                    )
                    generationOrchestrator.continueGeneration()
                } finally {
                    generationStartGate.finishStart()
                }
            }
        }
    }

    private data class AgentChatTransition(
        val claim: AgentToolRouter.ChatTransitionClaim,
        val sourceChatId: String?,
        val currentChatId: String?,
    )

    private fun claimAgentChatTransition(
        reason: String,
        expectedToken: String? = null,
    ): AgentChatTransition? {
        if (confirmedChatTransitionChain.get() != null) return null
        val claim = agentToolRouter.claimChatTransition(
            expectedToken = expectedToken,
            abortReason = reason,
        ) ?: return null
        claim.pending?.let { pending ->
            agentToolRouter.removeFromHistory(pending.call)
        }
        val transition = AgentChatTransition(
            claim = claim,
            sourceChatId = claim.pending?.sourceChatId ?: _currentChatId.value,
            currentChatId = _currentChatId.value,
        )
        // Remove the in-memory card immediately; persistence cleanup is
        // deferred to the lifecycle transition worker.
        agentToolConfirmation.clearMemory()
        return transition
    }

    private suspend fun cleanupAgentChatTransition(transition: AgentChatTransition) {
        transition.claim.chainIds.forEach { chainId ->
            val joined = agentToolRouter.joinInvalidatedChainBounded(
                chainId = chainId,
                timeoutMs = AGENT_CHAT_CLEANUP_TIMEOUT_MS,
            )
            if (!joined) {
                Log.w(TAG, "Timed out joining invalidated agent chain $chainId")
            }
        }
        // Do not touch SharedPreferences when there was no authorization to
        // remove. For a real pending card, apply() schedules disk cleanup.
        if (transition.claim.pending != null) {
            agentToolConfirmation.clearPersistedForChat(transition.sourceChatId)
            if (transition.currentChatId != null && transition.currentChatId != transition.sourceChatId) {
                agentToolConfirmation.clearPersistedForChat(transition.currentChatId)
            }
        }
    }

    /** Claims synchronously, then performs bounded cleanup and mutation off-main. */
    private fun abandonPendingAgentTool(
        reason: String,
        expectedToken: String? = null,
        afterCleanup: (suspend () -> Unit)? = null,
    ): Boolean {
        val transition = claimAgentChatTransition(reason, expectedToken) ?: return false
        chatTransitionGate.enqueue(
            cleanup = { cleanupAgentChatTransition(transition) },
            mutation = { afterCleanup?.invoke() },
        )
        return true
    }

    /** Suspending variant for lifecycle paths that must await cleanup. */
    private suspend fun prepareAgentChatTransition(
        reason: String,
        expectedToken: String? = null,
        afterCleanup: (suspend () -> Unit)? = null,
    ): Boolean {
        val transition = claimAgentChatTransition(reason, expectedToken) ?: return false
        chatTransitionGate.enqueueAndAwait(
            cleanup = { cleanupAgentChatTransition(transition) },
            mutation = { afterCleanup?.invoke() },
        )
        return true
    }

    fun cancelPendingAgentTool(actionToken: String) {
        val action = _pendingAgentToolAction.value ?: return
        if (actionToken != action.id) return
        abandonPendingAgentTool(
            reason = "Tool confirmation cancelled",
            expectedToken = actionToken,
            afterCleanup = {
                CapabilityRegistryHolder.auditLog.record("CONFIRM_CANCELLED", action.name, "")
                appendTranscriptMessage(
                    TranscriptRole.TOOL,
                    AgentToolProtocol.toolEventJson(
                        status = "cancelled",
                        toolName = action.name,
                        summary = "Tool cancelled",
                    ),
                )
                publishUiEvent("Tool cancelled: ${action.name}")
            },
        )
    }

    fun confirmPendingAgentTool(actionToken: String) {
        val staged = agentToolConfirmation.pendingAuthorization?.takeIf { it.token == actionToken } ?: return
        val authorization = agentToolConfirmation.consumePendingAndRevalidate(
            token = staged.token,
            activeChainId = agentTrace.activeChainId,
            activeChatId = _currentChatId.value,
        )
        if (authorization == null) {
            agentToolConfirmation.discardStaleAuthorization(
                token = staged.token,
                sourceChatId = staged.sourceChatId,
            )
            publishUiEvent("Tool confirmation is no longer active")
            return
        }
        val confirmedJob = agentToolRouter.launchConfirmedToolJob(
            chainId = authorization.chainId,
            sourceChatId = authorization.sourceChatId,
        ) confirmedJob@{
            if (!agentToolConfirmation.isAuthorizationCurrent(
                    authorization = authorization,
                    activeChainId = agentTrace.activeChainId,
                    activeChatId = _currentChatId.value,
                )
            ) {
                agentToolConfirmation.clearConsumedAuthorization(authorization)
                agentToolRouter.failTrace(authorization.chainId, "Tool confirmation authorization is stale")
                return@confirmedJob
            }
            val chatIdentityOperation = authorization.call.name in setOf(
                "clear_chat", "delete_chat", "delete_or_clear_chat",
            )
            if (!chatIdentityOperation && !agentToolConfirmation.tryBeginDispatch(authorization)) {
                agentToolConfirmation.clearConsumedAuthorization(authorization)
                agentToolRouter.failTrace(
                    authorization.chainId,
                    "Tool confirmation was revoked before dispatch",
                )
                return@confirmedJob
            }
            val stepStart = SystemClock.elapsedRealtime()
            val result = try {
                withContext(ConfirmedAgentOperationContext(authorization)) {
                    executeAgentTool(
                        call = authorization.call,
                        confirmed = true,
                        authorization = authorization,
                    )
                }
            } catch (e: CancellationException) {
                agentToolRouter.failTrace(authorization.chainId, "Tool confirmation cancelled")
                throw e
            } catch (e: Exception) {
                AgentToolResult(
                    call = authorization.call,
                    success = false,
                    summary = "Tool execution failed with exception: ${e.message ?: "unknown error"}",
                    errorCode = AgentToolErrorCode.FAILED,
                )
            }
            val transitionApplied = result.details.optBoolean(CHAT_IDENTITY_RESULT_APPLIED, false)
            result.details.remove(CHAT_IDENTITY_RESULT_APPLIED)
            if (!transitionApplied && !agentToolConfirmation.isOperationOwnerCurrent(
                    authorization = authorization,
                    activeChainId = agentTrace.activeChainId,
                    activeChatId = _currentChatId.value,
                )
            ) {
                agentToolRouter.failTrace(authorization.chainId, "Tool confirmation authorization changed during execution")
                return@confirmedJob
            }
            val latency = SystemClock.elapsedRealtime() - stepStart
            val sanitizedResult = com.prismai.llmhost.agent.ToolInputSanitizer.sanitizeResult(result)
            agentTrace.recordStep(authorization.call, sanitizedResult, latency, authorization.chainId)
            val appended = if (transitionApplied) {
                agentToolRouter.appendAuthorizedTransitionToolResult(
                    chainId = authorization.chainId,
                    result = sanitizedResult,
                )
            } else {
                agentToolRouter.appendOwnedToolResult(
                    chainId = authorization.chainId,
                    sourceChatId = authorization.sourceChatId,
                    result = sanitizedResult,
                )
            }
            if (!appended) {
                agentToolRouter.failTrace(authorization.chainId, "Tool result arrived after chat transition")
                return@confirmedJob
            }
            CapabilityRegistryHolder.auditLog.record(
                "CONFIRM_EXECUTED",
                authorization.call.name,
                result.summary.take(160),
            )
            val shouldContinue = agentToolRouter.completeConfirmedTool(
                call = authorization.call,
                result = result,
                depth = authorization.depth,
                maxIterations = _generationSettings.value.maxAgentIterations,
                chainId = authorization.chainId,
            )
            if (shouldContinue && (
                    transitionApplied ||
                        agentToolRouter.isCurrentOwner(
                            authorization.chainId,
                            authorization.sourceChatId,
                        )
                    )
            ) {
                startAgentFollowUpGeneration(
                    authorization.originalPrompt,
                    result,
                    authorization.depth + 1,
                    authorization.chainId,
                )
            }
        }
        if (authorization.call.name in CHAT_IDENTITY_TOOLS) {
            confirmedJob.invokeOnCompletion {
                confirmedChatTransitionChain.compareAndSet(authorization.chainId, null)
                confirmedChatTransitionThread.remove()
            }
        }
        agentToolConfirmation.clearConsumedAuthorization(authorization)
    }

    private fun appendTranscriptMessage(role: TranscriptRole, text: String): Long {
        if (_currentChatId.value == null) {
            ensureChatForGenerationBlocking()
        }
        val id = synchronized(transcriptLock) {
            val id = nextTranscriptId++
            val sum = if (role == TranscriptRole.TOOL) {
                AgentToolProtocol.parseToolEvent(text)
                    ?.optString("summary")
                    ?.takeIf { it.isNotBlank() }
            } else null
            val mutable = _transcript.value.toMutableList()
            mutable.add(TranscriptMessage(id, role, text, sum))
            _transcript.value = mutable
            id
        }
        touchCurrentChat(_transcript.value, updateTitle = role == TranscriptRole.USER)
        persistTranscriptThrottled(force = false)
        return id
    }

    private fun appendToolResult(result: AgentToolResult) {
        appendTranscriptMessage(
            TranscriptRole.TOOL,
            AgentToolProtocol.toolEventJson(
                status = if (result.success) "done" else "failed",
                toolName = result.call.name,
                summary = result.summary,
                details = result.details,
            ),
        )
    }

    private suspend fun executeConfirmedChatIdentityTool(
        call: AgentToolCall,
        authorization: AgentToolConfirmation.PendingToolAuthorization,
    ): AgentToolResult {
        if (!_generationSettings.value.agentEnabled) {
            return toolFailure(
                call,
                AgentToolErrorCode.CONFIRMATION_REQUIRED,
                "Agent mode is disabled",
            )
        }
        if (!confirmedChatTransitionChain.compareAndSet(null, authorization.chainId)) {
            return toolFailure(
                call,
                AgentToolErrorCode.BUSY,
                "Another confirmed chat transition is already running",
            )
        }
        val outcome = CompletableDeferred<AgentToolResult>()
        try {
            chatTransitionGate.enqueueAndAwait(
                cleanup = {},
                mutation = {
                    if (!agentToolConfirmation.isOperationOwnerCurrent(
                            authorization = authorization,
                            activeChainId = agentTrace.activeChainId,
                            activeChatId = _currentChatId.value,
                        ) || !agentToolConfirmation.tryBeginDispatch(authorization)
                    ) {
                        outcome.complete(
                            toolFailure(
                                call,
                                AgentToolErrorCode.CONFIRMATION_REQUIRED,
                                "Chat transition authorization is stale",
                            ),
                        )
                    } else {
                        confirmedChatTransitionThread.set(true)
                        try {
                            val result = when (call.name) {
                                "clear_chat" -> chatTools.clearChat(call, confirmed = true)
                                "delete_chat" -> chatTools.deleteChat(call, confirmed = true)
                                "delete_or_clear_chat" -> chatTools.deleteOrClearChat(call, confirmed = true)
                                else -> toolFailure(call, AgentToolErrorCode.FAILED, "Unsupported chat transition")
                            }
                            result.details.put(
                                CHAT_IDENTITY_RESULT_APPLIED,
                                _currentChatId.value != authorization.sourceChatId,
                            )
                            outcome.complete(result)
                        } catch (error: Exception) {
                            outcome.complete(
                                toolFailure(
                                    call,
                                    AgentToolErrorCode.FAILED,
                                    "Chat transition failed: ${error.message ?: "unknown error"}",
                                ),
                            )
                        } finally {
                            confirmedChatTransitionThread.remove()
                        }
                    }
                },
            )
        } catch (error: CancellationException) {
            if (!outcome.isCompleted) {
                outcome.complete(
                    toolFailure(call, AgentToolErrorCode.FAILED, "Chat transition cancelled"),
                )
            }
            throw error
        } catch (error: Exception) {
            if (!outcome.isCompleted) {
                outcome.complete(
                    toolFailure(call, AgentToolErrorCode.FAILED, "Chat transition failed"),
                )
            }
        }
        return outcome.await()
    }

    private suspend fun executeAgentTool(
        call: AgentToolCall,
        confirmed: Boolean,
        authorization: AgentToolConfirmation.PendingToolAuthorization? = null,
    ): AgentToolResult {
        if (confirmed && authorization != null &&
            !agentToolConfirmation.isOperationOwnerCurrent(
                authorization = authorization,
                activeChainId = agentTrace.activeChainId,
                activeChatId = _currentChatId.value,
            )
        ) {
            return AgentToolResult(
                call = call,
                success = false,
                summary = "Tool confirmation authorization is no longer valid",
                errorCode = AgentToolErrorCode.CONFIRMATION_REQUIRED,
            )
        }
        if (confirmed && authorization != null && call.name in CHAT_IDENTITY_TOOLS) {
            return executeConfirmedChatIdentityTool(call, authorization)
        }
        return when (call.name) {
            // ── System tools ────────────────────────────────────────────────
            "get_model_status" -> systemHandlerTools.modelStatus(call)
            "get_tool_capabilities" -> systemHandlerTools.getToolCapabilities(call)
            "get_app_version_info" -> systemHandlerTools.getAppVersionInfo(call)
            "get_storage_status" -> systemHandlerTools.getStorageStatus(call)
            "get_privacy_summary" -> systemHandlerTools.getPrivacySummary(call)
            "open_app_panel" -> systemHandlerTools.openAppPanel(call)
            "use_guidance_skill", "apply_agent_skill" -> systemHandlerTools.useGuidanceSkill(call)
            "preview_action" -> systemHandlerTools.previewAction(call)
            "switch_model" -> systemHandlerTools.switchModel(call, confirmed)
            // ── Model tools ──────────────────────────────────────────────────
            "list_installed_models" -> modelTools.listInstalledModels(call)
            "get_model_card" -> modelTools.getModelCard(call)
            "recommend_model" -> modelTools.recommendModel(call)
            "compare_models" -> modelTools.compareModels(call)
            "list_curated_downloadable_models" -> modelTools.listCuratedDownloadableModels(call)
            "get_download_status" -> modelTools.getDownloadStatus(call)
            "download_model" -> modelTools.downloadModel(call, confirmed)
            "delete_model" -> modelTools.deleteModel(call, confirmed)
            "recommend_runtime_settings" -> modelTools.recommendRuntimeSettings(call)
            "explain_runtime_settings" -> modelTools.explainRuntimeSettings(call)
            // ── Runtime tools ────────────────────────────────────────────────
            "set_runtime_settings" -> runtimeTools.setRuntimeSettings(call, confirmed)
            "validate_runtime_settings" -> runtimeTools.validateRuntimeSettings(call)
            "restore_previous_runtime_settings" -> runtimeTools.restorePreviousRuntimeSettings(call, confirmed)
            "diagnose_performance" -> runtimeTools.diagnosePerformance(call)
            "list_benchmark_runs" -> runtimeTools.listBenchmarkRuns(call)
            "run_benchmark" -> runtimeTools.runBenchmark(call, confirmed)
            "get_active_operation" -> runtimeTools.getActiveOperation(call)
            "cancel_generation" -> runtimeTools.cancelGeneration(call)
            "cancel_active_operation" -> runtimeTools.cancelActiveOperation(call, confirmed)
            "cancel_active_job" -> runtimeTools.cancelActiveJob(call, confirmed)
            "continue_generation" -> runtimeTools.continueGeneration(call, confirmed)
            // ── Chat tools ───────────────────────────────────────────────────
            "summarize_current_chat" -> chatTools.summarizeCurrentChat(call)
            "rename_current_chat" -> chatTools.renameCurrentChat(call, confirmed)
            "search_chats" -> chatTools.searchChats(call)
            "export_chat" -> chatTools.exportChat(call, confirmed)
            "clear_chat" -> chatTools.clearChat(call, confirmed)
            "delete_chat" -> chatTools.deleteChat(call, confirmed)
            "delete_or_clear_chat" -> chatTools.deleteOrClearChat(call, confirmed)
            // ── Memory tools ─────────────────────────────────────────────────
            "remember_fact" -> memoryTools.rememberFact(call, confirmed)
            "recall_facts" -> memoryTools.recallFacts(call)
            "forget_fact" -> memoryTools.forgetFact(call, confirmed)
            "list_memories" -> memoryTools.listMemories(call)
            // ── RAG tools ────────────────────────────────────────────────────
            "ingest_document" -> ragHandlerTools.ingestDocument(call, confirmed)
            "search_documents" -> ragHandlerTools.searchDocuments(call)
            "list_documents" -> ragHandlerTools.listDocuments(call)
            "delete_document" -> ragHandlerTools.deleteDocument(call, confirmed)
            // ── Knowledge Pack tools ─────────────────────────────────────────
            "search_knowledge" -> knowledgePackHandlerTools.searchKnowledge(call)
            "fetch_grokipedia_article" -> knowledgePackHandlerTools.fetchGrokipediaArticle(call, confirmed)
            "list_knowledge_packs" -> knowledgePackHandlerTools.listKnowledgePacks(call)
            "download_knowledge_pack" -> knowledgePackHandlerTools.downloadKnowledgePack(call, confirmed)
            // ── Voice I/O tools ──────────────────────────────────────────────
            "voice_input" -> voiceHandlerTools.voiceInput(call)
            "speak_output" -> voiceHandlerTools.speakOutput(call)
            "stop_speaking" -> voiceHandlerTools.stopSpeaking(call)
            // ── Data connector tools ─────────────────────────────────────────
            "search_contacts" -> dataConnectorHandlerTools.searchContacts(call, confirmed)
            "get_calendar_events" -> dataConnectorHandlerTools.getCalendarEvents(call, confirmed)
            "list_sms_threads" -> dataConnectorHandlerTools.listSmsThreads(call, confirmed)
            // ── Workspace file tools ─────────────────────────────────────────
            "list_workspace_files" -> workspaceTools.listWorkspaceFiles(call)
            "read_workspace_file" -> workspaceTools.readWorkspaceFile(call)
            "search_workspace_files" -> workspaceTools.searchWorkspaceFiles(call)
            // ── Background agent tools ───────────────────────────────────────
            "run_in_background" -> backgroundAgentHandlerTools.runInBackground(
                call,
                confirmed,
                sourceChatId = authorization?.sourceChatId,
            )
            "check_background_tasks" -> backgroundAgentHandlerTools.checkBackgroundTasks(call)
            "cancel_background_task" -> backgroundAgentHandlerTools.cancelBackgroundTask(call, confirmed)
            // ── Web search ───────────────────────────────────────────────────
            "web_search" -> webSearchTools.webSearch(call)
            else -> AgentToolResult(call, success = false, summary = "Unknown tool: ${call.name}")
        }
    }

    private fun File.sizeRecursive(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return listFiles()?.sumOf { it.sizeRecursive() } ?: 0L
    }

    private fun activeOperationJson(): JSONObject {
        val download = _modelDownloadState.value
        val import = _importState.value
        val operation = when {
            _isGenerating.value && generationMetrics.activeBenchmarkPreset != null -> "benchmark"
            _isGenerating.value -> "generation"
            download is ModelDownloadState.Running -> "download"
            import is ImportState.Running -> "import"
            ::benchmarkRunner.isInitialized && benchmarkRunner.queue.isNotEmpty() -> "benchmark"
            else -> "none"
        }
        val progress = when (download) {
            is ModelDownloadState.Running -> download.totalBytes?.takeIf { it > 0L }?.let { download.bytesDone.toDouble() / it }
            else -> (import as? ImportState.Running)?.totalBytes?.takeIf { it > 0L }?.let { import.bytesCopied.toDouble() / it }
        }
        return JSONObject()
            .put("operation_type", operation)
            .put("can_cancel", operation != "none")
            .put("progress", progress ?: JSONObject.NULL)
            .put("is_generating", _isGenerating.value)
            .put("benchmark_queue", if (::benchmarkRunner.isInitialized) benchmarkRunner.queue.size else 0)
    }

    private fun refreshMemoriesList() {
        runCatching { _memories.value = memoryStore.getAllActive() }
    }

    /** Public API for the memory browser UI. Deletes a single memory fact by id and refreshes the observable list. */
    fun deleteMemory(id: String) {
        memoryStore.delete(id)
        refreshMemoriesList()
    }

    /** Public API for the memory browser UI. Reloads the observable memory list from the store. */
    fun refreshMemories() {
        refreshMemoriesList()
    }

    /** Public API for the memory browser UI. Adds a manual memory fact and refreshes the observable list. */
    fun addMemory(fact: String, category: MemoryCategory = MemoryCategory.GENERAL, confidence: Float = 0.85f) {
        memoryStore.insert(
            MemoryFact(
                fact = fact,
                category = category,
                confidence = confidence,
            )
        )
        refreshMemoriesList()
    }

    /** Public API for the document browser UI. Ingests a text document into local SQLite VectorStore. */
    suspend fun ingestDocument(id: String, title: String, text: String): Int {
        val count = ragManager.ingestDocument(id, title, text)
        refreshVectorChunksList()
        return count
    }

    /** Public API for the document browser UI. Deletes all chunks for a document id. */
    fun deleteDocument(id: String): Int {
        val count = ragManager.deleteDocument(id)
        refreshVectorChunksList()
        return count
    }

    /** Public API for the document browser UI. Performs vector cosine similarity search. */
    suspend fun queryVectorStore(query: String, topK: Int = 5): List<Pair<com.prismai.llmhost.storage.VectorChunk, Float>> {
        return ragManager.query(query, topK = topK)
    }

    /** Public API to force refresh vector store chunks StateFlow. */
    fun refreshVectorStore() {
        refreshVectorChunksList()
    }

    /** Public API for voice input & TTS */
    fun startVoiceInput(): Boolean {
        return voiceIoManager.startListening().also { success ->
            if (success) {
                _voiceState.value = _voiceState.value.copy(isListening = true, partialTranscript = null)
            }
        }
    }

    fun stopVoiceInput() {
        voiceIoManager.stopListening()
        _voiceState.value = _voiceState.value.copy(isListening = false, partialTranscript = null)
    }

    fun speakText(text: String): Boolean = voiceIoManager.speak(text)

    fun stopSpeaking() {
        voiceIoManager.stopSpeaking()
        _voiceState.value = _voiceState.value.copy(isSpeaking = false)
    }

    private fun refreshVectorChunksList() {
        runCatching { uiState._vectorChunks.value = vectorStore.getAllChunks() }
    }

    private fun startAgentFollowUpGeneration(
        originalPrompt: String,
        toolResult: AgentToolResult,
        depth: Int,
        chainId: Long? = null,
    ) {
        agentFollowUpScheduled = true
        serviceScope.launch {
            try {
                operationMutex.withLock {
                    if (!generationStartGate.beginStart()) return@withLock
                    try {
                        generationOrchestrator.startFollowUp(originalPrompt, toolResult, depth, chainId)
                    } finally {
                        generationStartGate.finishStart()
                    }
                }
            } finally {
                agentFollowUpScheduled = false
            }
        }
    }



    private fun saveTranscriptSafely() {
        val path = transcriptStore.saveRecoveryTranscript(streamState.snapshotText())
        if (path != null) {
            _recoveryTranscript.value = path
        }
        persistTranscriptNow()
    }

    private fun loadGenerationSettings() {
        val settings = configStore.load()
        _generationSettings.value = settings
        syncCapabilities(settings)
    }

    private fun syncCapabilities(settings: GenerationSettings) {
        val registry = CapabilityRegistryHolder.registry
        if (settings.agentEnabled) {
            registry.setAgentModeEnabled(true)
            Log.d(TAG, "Restricted capabilities granted (Agent mode enabled)")
        } else {
            registry.setAgentModeEnabled(false)
            Log.d(TAG, "Restricted capabilities revoked (Agent mode disabled)")
        }
    }


    private fun chatIndexFile(): File = transcriptStore.chatIndexFile()

    private fun benchmarkRunsFile(): File = File(filesDir, BENCHMARK_RUNS_FILE_NAME)

    private fun chatDirectory(): File = transcriptStore.chatDirectory()

    private fun transcriptFile(chatId: String): File = transcriptStore.transcriptFile(chatId)

    private fun legacyTranscriptFile(): File = transcriptStore.legacyTranscriptFile()

    private fun loadChats() {
        chatManager.loadChats()
        agentToolConfirmation.cleanupStale(_chatSessions.value.map { it.id }.toSet())
    }

    private fun readChatIndex(): List<ChatSession> = chatManager.readChatIndex()

    private fun persistChatIndex() = chatManager.persistChatIndex()

    private fun readTranscriptFile(file: File): List<TranscriptMessage> = transcriptStore.readTranscriptFile(file)

    private fun writeTranscriptFile(file: File, messages: List<TranscriptMessage>) =
        transcriptStore.writeTranscriptFile(file, messages)

    private fun promoteTempFile(temp: File, target: File) = transcriptStore.promoteTempFile(temp, target)

    private fun firstUserTitle(messages: List<TranscriptMessage>): String? = transcriptStore.firstUserTitle(messages)


    private fun updateTranscriptMessage(id: Long, text: String, persistImmediately: Boolean = false) {
        synchronized(transcriptLock) {
            val list = _transcript.value
            val index = list.indexOfLast { it.id == id }
            if (index >= 0) {
                val message = list[index]
                if (message.text != text) {
                    val mutableList = list.toMutableList()
                    val sum = if (message.role == TranscriptRole.TOOL) {
                        AgentToolProtocol.parseToolEvent(text)
                            ?.optString("summary")
                            ?.takeIf { it.isNotBlank() }
                    } else message.summary
                    mutableList[index] = message.copy(text = text, summary = sum)
                    _transcript.value = mutableList
                }
            }
        }
        if (persistImmediately) {
            touchCurrentChat(_transcript.value, updateTitle = false)
        }
        persistTranscriptThrottled(force = persistImmediately)
    }

    private fun persistTranscriptThrottled(force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastTranscriptPersistAt < TRANSCRIPT_PERSIST_THROTTLE_MS) {
            return
        }
        lastTranscriptPersistAt = now
        persistTranscriptNow()
    }

    private fun persistTranscriptNow() {
        val chatId = _currentChatId.value ?: return
        val revision = chatManager.transcriptWriteRevision(chatId)
        val messages = synchronized(transcriptLock) {
            _transcript.value
        }
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                chatManager.persistTranscript(chatId, messages, revision)
            }.onFailure { error ->
                Log.w(TAG, "failed to persist transcript", error)
            }
        }
    }

    private fun touchCurrentChat(messages: List<TranscriptMessage>, updateTitle: Boolean) {
        chatManager.touchCurrentChat(messages, updateTitle)
    }

    private suspend fun cancelAndJoinGenerationLocked(
        reason: String,
        clearQueuedBenchmarks: Boolean = true,
        finalizeActiveTrace: Boolean = true,
        preserveActiveTrace: Boolean = false,
    ) {
        val job = generationJob
        val shouldPreserveTrace = !finalizeActiveTrace || preserveActiveTrace
        val traceOwner = if (::agentTrace.isInitialized) {
            agentTrace.activeChainId
        } else {
            null
        }
        if (shouldPreserveTrace && traceOwner != null) {
            agentTrace.preserveChainForCancellation(traceOwner)
        }
        generationSession++
        generationJob = null
        if (job != null) {
            Log.d(TAG, "cancelAndJoinGeneration reason=$reason")
            job.cancelAndJoin()
            benchmarkStore.recordInterrupted(reason, streamState.snapshotText())
        }
        if (finalizeActiveTrace && traceOwner != null && agentTrace.isCurrentChain(traceOwner)) {
            agentTrace.finalizeOwnedTrace(
                chainId = traceOwner,
                success = false,
                abortReason = "Generation cancelled: $reason",
            )
        }
        stopGenerationForeground()
        _isGenerating.value = false
        generationMetrics.clear()
        if (clearQueuedBenchmarks && ::benchmarkRunner.isInitialized) {
            benchmarkRunner.queue.clear()
        }
        _benchmarkStatus.value = BenchmarkStatus()
        chatManager.activeAssistantTranscriptId?.let { assistantMessageId ->
            chatManager.updateTranscriptMessage(
                assistantMessageId,
                streamState.snapshotText(),
            )
            chatManager.activeAssistantTranscriptId = null
        }
        if (_runtimeStatus.value == RuntimeStatus.GENERATING || _runtimeStatus.value == RuntimeStatus.CANCELLING) {
            _runtimeStatus.value = RuntimeStatus.IDLE
        }
    }

    private fun publishUiEvent(message: String) {
        Log.d(TAG, "uiEvent=$message")
        eventBus.publish(message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            GENERATION_CHANNEL_ID,
            "LLM generation",
            NotificationManager.IMPORTANCE_LOW,
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun generationNotification(): Notification =
        NotificationCompat.Builder(this, GENERATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("LLM generation running")
            .setContentText("Streaming response from ${_currentModel.value ?: "selected model"}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun startGenerationForeground() {
        val notification = generationNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    GENERATION_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(GENERATION_NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "foreground generation notification started")
            generationForegroundActive = true
        }.onFailure { error ->
            Log.e(TAG, "failed to start foreground generation notification", error)
            generationForegroundActive = false
            publishUiEvent("Foreground generation notification failed")
        }
    }

    private fun stopGenerationForeground() {
        if (!generationForegroundActive) {
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }.onFailure { error ->
            Log.w(TAG, "failed to stop foreground generation notification", error)
        }.also {
            generationForegroundActive = false
            Log.d(TAG, "foreground generation notification stopped")
        }
    }

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timeout startId=$startId type=$fgsType")
        publishUiEvent("Generation timed out by Android foreground-service policy")
        cancelGeneration()
        stopSelf(startId)
    }

    fun debugIsGenerationForegroundActive(): Boolean = generationForegroundActive

    override fun onDestroy() {
        saveTranscriptSafely()
        importJob?.cancel()
        importJob = null

        // Run cancellation + native teardown off the main thread and wait only a
        // bounded budget for it. If the budget expires the job is deliberately
        // NOT cancelled: the native engine free must still run, and blocking the
        // main thread until it finishes risks an ANR. destroySafely() is
        // idempotent (isDestroyed guarded under modelMutex), so a late or
        // duplicate call cannot free the native handle twice.
        if (teardownStarted.compareAndSet(false, true)) {
            val teardownJob = teardownScope.launch {
                operationMutex.withLock {
                    cancelAndJoinGenerationLocked("service destroy")
                    engine.destroySafely()
                }
            }
            val finishedInTime = runBlocking {
                withTimeoutOrNull(NATIVE_TEARDOWN_WAIT_MS) { teardownJob.join() } != null
            }
            if (!finishedInTime) {
                Log.w(
                    TAG,
                    "Native engine teardown exceeded ${NATIVE_TEARDOWN_WAIT_MS}ms; " +
                        "continuing off-main and returning from onDestroy",
                )
            }
        } else {
            Log.d(TAG, "Native engine teardown already started; skipping duplicate onDestroy request")
        }

        memoryGovernor.unregister()
        if (::backgroundAgentManager.isInitialized) backgroundAgentManager.shutdown()
        serviceScope.cancel()
        if (::voiceIoManager.isInitialized) voiceIoManager.shutdown()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
