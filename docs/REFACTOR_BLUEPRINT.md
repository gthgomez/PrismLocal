# Refactor Blueprint — InferenceService Split & Future-Proofing

> **Status**: Completed & Reference-Only  
> **Last updated**: 2026-07-19  
> **Target**: Historical blueprint and execution record for the InferenceService refactoring, splitting the 5,391-line monolith into focused domain packages.

---

## 1. Current State: The Monolith

### 1.1 Size & Scope

| Metric | Value |
|--------|-------|
| Lines | 5,391 |
| Private methods | ~90 |
| Public API methods | ~30 |
| StateFlow/SharedFlow properties | 22 |
| `lateinit` dependencies | 12 |
| Agent tool handler methods | 60+ |
| Imported types | 55 |

### 1.2 Concern Inventory (line ranges approximate)

| # | Concern | Lines | Description |
|---|---------|-------|-------------|
| 1 | Service Lifecycle | 58–285, 5369–5391 | `onCreate`, `onDestroy`, `onBind`, foreground notification, channel |
| 2 | UI State | 59–205 | 22 StateFlow/SharedFlow properties, public exposure |
| 3 | Model Management | 624–1172 | `switchModel`, device profiling, memory snapshots, native load validation, performance estimation, model readiness |
| 4 | Model Import/Download | 287–533 | `importModel`, `cancelImport`, HuggingFace download, WorkManager observation |
| 5 | Chat/Transcript | 1239–1396, 4820–5009 | CRUD, chat index, transcript files, search index, pending agent tool persistence |
| 6 | Generation Engine | 1568–1984, 4198–4553 | `generateSafely`, `continueGenerationSafely`, agent follow-up loop, prompt building |
| 7 | Agent Tool System | 2025–4188 | `handleAgentToolCall` router, confirmation gates, 60+ tool handlers, tool routing sets |
| 8 | Benchmark | 1417–1566, 4518–4814 | Preset/thread-sweep/native benchmark execution, CSV/JSON export, persist/load |
| 9 | Memory System | 3802–3902 | `remember_fact`, `recall_facts`, `forget_fact`, `list_memories` handlers, refresh |
| 10 | RAG System | 3906–4196 | Document ingestion, query, context injection, knowledge pack orchestration |
| 11 | Settings | 1366–1415, 4563–4598 | `updateGenerationSettings`, persist/load, reload detection, native conversation reset |
| 12 | Web Search | 3509–3602 | DuckDuckGo search, HTML parsing |
| 13 | Voice I/O | 3959–4013 | STT/TTS tool handlers |
| 14 | Data Connectors | 4013–4094 | Calendar/contacts/SMS tool handlers |
| 15 | Knowledge Pack | 4094–4150 | Grokipedia search/download/status handlers |
| 16 | Background Agent | 4150–4188 | Queue/status/cancel handlers |
| 17 | Analytics/Tracing | 2489–2558 | Agent step recording, trace finalization, JSONL output |
| 18 | Chat Export | 1168–1220 | Markdown/text/JSON export formatters |

### 1.3 Pain Points

1. **Untestable in isolation** — You cannot unit-test agent tool routing without initializing the entire service with native engine, model storage, memory store, etc.
2. **Merge conflict magnet** — Every feature touches this file. Phase 2–7 work all adds handlers to the same class.
3. **No clear boundaries** — `agentToolWebSearch()` directly calls `URL.readText()` inside a 5,000-line service. No way to swap implementations.
4. **Startup coupling** — `onCreate()` initializes 12 subsystems. If one fails (e.g., VectorStore schema migration), the whole service is dead.
5. **Agent tool discovery** — Adding a new tool means: add handler method here, add to routing `when` block, add to `cheapTools`/`confirmTools`/etc. sets, update `AgentTools.kt` definitions. Four touch points, all in the same file.

---

## 2. Target Architecture

### 2.1 Package Map

```
com.example.llmhost/
│
├── service/                          # Thin orchestrator + Android lifecycle
│   ├── InferenceService.kt           (~200 lines)
│   └── ServiceNotifications.kt       (~100 lines)
│
├── engine/                           # Native bridge wrapper
│   ├── NativeLlmBridge.kt            (existing — unchanged)
│   ├── EngineSession.kt              (~200 lines)
│   └── EngineConfigStore.kt          (~50 lines)
│
├── model/                            # Model lifecycle & device awareness
│   ├── ModelManager.kt               (~400 lines)
│   ├── ModelImportManager.kt         (~200 lines)
│   ├── ModelDownloadManager.kt       (~250 lines)
│   ├── DeviceProfiler.kt             (~300 lines)
│   └── ModelReadinessAssessor.kt     (~200 lines)
│
├── chat/                             # Chat/transcript persistence
│   ├── ChatManager.kt                (~400 lines)
│   ├── TranscriptStore.kt            (~250 lines)
│   ├── ChatSearchIndex.kt            (~100 lines)
│   └── models/                       (existing types)
│       ├── ChatSession.kt
│       └── TranscriptMessage.kt
│
├── generation/                       # Prompt → token stream lifecycle
│   ├── GenerationOrchestrator.kt     (~500 lines)
│   ├── PromptBuilder.kt              (~200 lines)
│   └── GenerationMetrics.kt          (~150 lines)
│
├── agent/                            # Agentic tool-call system
│   ├── AgentToolRouter.kt            (~300 lines)
│   ├── AgentToolConfirmation.kt      (~150 lines)
│   ├── AgentTrace.kt                 (~150 lines)
│   ├── AgentToolProtocol.kt          (existing — unchanged)
│   └── tools/
│       ├── ChatTools.kt              (~400 lines)
│       ├── ModelTools.kt             (~500 lines)
│       ├── RuntimeTools.kt           (~300 lines)
│       ├── MemoryTools.kt            (~150 lines)
│       ├── RagTools.kt               (~200 lines)
│       ├── KnowledgePackTools.kt     (~100 lines)
│       ├── VoiceTools.kt             (~80 lines)
│       ├── DataConnectorTools.kt     (~100 lines)
│       ├── BackgroundAgentTools.kt   (~100 lines)
│       ├── WebSearchTools.kt         (~120 lines)
│       └── SystemTools.kt            (~200 lines)
│
├── memory/                           # Persistent user memory (Phase 1)
│   ├── MemoryModels.kt               (existing — unchanged)
│   ├── MemoryStore.kt                (existing — SqlMemoryStore, unchanged)
│   └── MemoryContextBuilder.kt       (~80 lines, extracted from InferenceService)
│
├── rag/                              # Retrieval-Augmented Generation (Phase 2)
│   ├── RagManager.kt                 (existing — unchanged)
│   ├── VectorStore.kt                (existing — unchanged)
│   └── DocumentChunker.kt            (existing — unchanged)
│
├── voice/                            # Voice I/O (Phase 4)
│   └── VoiceIoManager.kt             (existing — unchanged)
│
├── data/                             # Data connectors (Phase 5)
│   └── DataConnectorTools.kt         (existing — unchanged)
│
├── benchmark/                        # Performance benchmarking
│   ├── BenchmarkRunner.kt            (~400 lines)
│   ├── BenchmarkStore.kt             (~200 lines)
│   └── models/
│       ├── BenchmarkPreset.kt
│       └── BenchmarkRun.kt
│
├── ui/                               # UI state projection
│   ├── ServiceUiState.kt             (~150 lines)
│   └── UiEventBus.kt                 (~50 lines)
│
└── export/                           # Chat export formatters
    └── ChatExporter.kt               (~100 lines)
```

### 2.2 InferenceService After Refactor (~200 lines)

```kotlin
class InferenceService : Service() {
    // ── Dependencies (wired in onCreate) ──
    private lateinit var engine: NativeLlmBridge
    private lateinit var uiState: ServiceUiState
    private lateinit var modelManager: ModelManager
    private lateinit var chatManager: ChatManager
    private lateinit var generationOrchestrator: GenerationOrchestrator
    private lateinit var agentToolRouter: AgentToolRouter
    private lateinit var benchmarkRunner: BenchmarkRunner
    private lateinit var memoryGovernor: MemoryGovernor

    // ── Lifecycle ──
    override fun onCreate() {
        // 1. Create engine
        // 2. Create UI state (all StateFlows)
        // 3. Create domain services (constructor-injected)
        // 4. Wire cross-cutting (memory pressure, downloads observer)
        // 5. Restore persisted state
    }

    override fun onDestroy() {
        // 1. Save transcript
        // 2. Cancel jobs
        // 3. Destroy engine
        // 4. Unregister callbacks
        // 5. Cancel scope
    }

    // ── Public API (delegates to domain services) ──
    val currentModel: StateFlow<String?> get() = uiState.currentModel
    val transcript: StateFlow<List<TranscriptMessage>> get() = uiState.transcript
    // ... all existing public properties delegate to uiState

    fun generateSafely(...) = generationOrchestrator.generate(...)
    fun switchModel(id: String) = modelManager.switchModel(id)
    fun createChat(): String = chatManager.createChat()
    // ... all existing public methods delegate
}
```

### 2.3 Key Design Principles

| Principle | Implementation |
|-----------|---------------|
| **Constructor injection** | Each extracted class receives dependencies via constructor. `InferenceService.onCreate()` is the composition root. |
| **Interface contracts** | `ChatStore`, `ModelStore`, `BenchmarkStore` interfaces enable fakes for testing. |
| **Public API stability** | All existing `StateFlow` properties and public methods are preserved as delegation — zero `ChatScreen.kt` changes. |
| **Single-direction dependency** | `generation` → `agent` → `tools/*`; `ui` reads from everyone, writes to no one. |
| **Feature flags** | Each Phase 2–7 capability gated behind `CapabilityRegistry` (already exists, to be expanded). |
| **Resilient startup** | Each subsystem initialized independently; failure in one (e.g., VectorStore schema) doesn't crash the service. |

---

## 3. Extraction Sequence (5 Phases)

### Phase A: Foundation (1–2 sessions)

**Goal**: Extract UI state, settings, chat/transcript, and export — the lowest-risk, least-coupled concerns.

| Step | Extract | Target File | Lines Out |
|------|---------|-------------|-----------|
| A1 | All 22 StateFlows + exposure | `ui/ServiceUiState.kt` | ~150 |
| A2 | SharedFlow events + panel requests | `ui/UiEventBus.kt` | ~50 |
| A3 | `persistGenerationSettings` + `loadGenerationSettings` + `shouldReload` | `engine/EngineConfigStore.kt` | ~80 |
| A4 | `createChat`, `switchChat`, `renameChat`, `deleteChat`, chat index, `loadChats`, `persistChatIndex` | `chat/ChatManager.kt` | ~400 |
| A5 | `transcriptFile`, `saveTranscriptSafely`, `loadTranscript`, `appendTranscriptMessage`, legacy migration | `chat/TranscriptStore.kt` | ~250 |
| A6 | `chatExportMarkdown`, `chatExportText`, `chatExportJson` | `export/ChatExporter.kt` | ~100 |
| A7 | `chatSearchIndex` + `updateChatSearchIndex` + `agentToolSearchChats` | `chat/ChatSearchIndex.kt` | ~100 |

**Verification gate**: `ChatScreen.kt` compiles unchanged (all StateFlows still available via delegation). `testDebugUnitTest` passes.

### Phase B: Model & Device (1–2 sessions)

**Goal**: Extract model management, import/download, and device profiling.

| Step | Extract | Target File | Lines Out |
|------|---------|-------------|-----------|
| B1 | `switchModel`, `listModels`, native load rejection, memory snapshots | `model/ModelManager.kt` | ~400 |
| B2 | `importModel`, `cancelImport`, `clearImportState`, `GGUF` validation logic | `model/ModelImportManager.kt` | ~200 |
| B3 | `downloadHuggingFaceModel`, `observeHuggingFaceDownloadWork`, `applyDownloadWorkInfo` | `model/ModelDownloadManager.kt` | ~250 |
| B4 | `captureDeviceCapabilityProfile`, `getDeviceCapabilityProfileCached`, `deviceMemorySnapshot`, thermal/battery | `model/DeviceProfiler.kt` | ~300 |
| B5 | `buildModelReadiness`, `summarizeModelPerformance`, `estimateModelFit`, `predictPerformance`, `agentToolRecommendModel`, `agentToolCompareModels` | `model/ModelReadinessAssessor.kt` | ~400 |

### Phase C: Generation & Agent Core (2–3 sessions)

**Goal**: Extract the generation orchestrator and the agent tool routing system. This is the highest-risk extraction.

| Step | Extract | Target File | Lines Out |
|------|---------|-------------|-----------|
| C1 | `generateSafely` + helper methods (agent chain, system prompt injection, memory context, RAG context, safety gates) | `generation/GenerationOrchestrator.kt` | ~500 |
| C2 | `buildMemoryContextForPrompt`, `buildRagContextForPrompt`, `buildSystemPrompt` | `generation/PromptBuilder.kt` | ~200 |
| C3 | `publishGenerationPerformance`, `clearActiveGenerationMetrics`, token counting, latency tracking | `generation/GenerationMetrics.kt` | ~150 |
| C4 | `handleAgentToolCall` + routing `when` block + `shouldContinueAfterTool` + `cheapTools`/`confirmTools`/etc. sets | `agent/AgentToolRouter.kt` | ~350 |
| C5 | `buildAgentToolConfirmation`, `restorePendingAgentToolCall`, `persistPendingAgentToolCall`, `clearPendingAgentToolMemoryState` | `agent/AgentToolConfirmation.kt` | ~200 |
| C6 | `recordAgentStep`, `finalizeAgentTrace`, `activeAgentChainPrompt`, `activeAgentChainStartTime`, JSONL writing | `agent/AgentTrace.kt` | ~150 |

### Phase D: Agent Tool Handlers (1–2 sessions)

**Goal**: Extract all 60+ tool handler methods into focused files.

| Step | Extract | Target File | Lines Out |
|------|---------|-------------|-----------|
| D1 | `agentToolSearchChats`, `agentToolSummarizeCurrentChat`, `agentToolRenameCurrentChat`, `agentToolExportChat`, `agentToolClearChat`, `agentToolDeleteChat`, `agentToolDeleteOrClearChat` | `agent/tools/ChatTools.kt` | ~400 |
| D2 | `agentToolListInstalledModels`, `agentToolGetModelCard`, `agentToolRecommendModel`, `agentToolCompareModels`, `agentToolListCuratedDownloadableModels`, `agentToolGetDownloadStatus`, `agentToolDownloadModel`, `agentToolRecommendRuntimeSettings`, `agentToolExplainRuntimeSettings` | `agent/tools/ModelTools.kt` | ~500 |
| D3 | `agentToolSetRuntimeSettings`, `agentToolValidateRuntimeSettings`, `agentToolRestorePreviousRuntimeSettings`, `agentToolDiagnosePerformance`, `agentToolRunBenchmark`, `agentToolListBenchmarkRuns`, `agentToolGetActiveOperation`, `agentToolCancelActiveOperation`, `agentToolCancelGeneration`, `agentToolContinueGeneration` | `agent/tools/RuntimeTools.kt` | ~350 |
| D4 | `agentToolRememberFact`, `agentToolRecallFacts`, `agentToolForgetFact`, `agentToolListMemories`, `refreshMemoriesList` | `agent/tools/MemoryTools.kt` | ~150 |
| D5 | RAG tool handlers (document ingest, query, delete, list) | `agent/tools/RagTools.kt` | ~200 |
| D6 | Knowledge pack handlers | `agent/tools/KnowledgePackTools.kt` | ~100 |
| D7 | Voice I/O handlers | `agent/tools/VoiceTools.kt` | ~80 |
| D8 | Data connector handlers | `agent/tools/DataConnectorTools.kt` | ~100 |
| D9 | Background agent handlers | `agent/tools/BackgroundAgentTools.kt` | ~100 |
| D10 | `agentToolWebSearch` + DuckDuckGo parsing | `agent/tools/WebSearchTools.kt` | ~120 |
| D11 | `agentToolModelStatus`, `agentToolGetToolCapabilities`, `agentToolGetAppVersionInfo`, `agentToolGetStorageStatus`, `agentToolGetPrivacySummary`, `agentToolOpenAppPanel`, `agentToolUseGuidanceSkill`, `agentToolPreviewAction` | `agent/tools/SystemTools.kt` | ~200 |

### Phase E: Benchmark & Cleanup (1 session)

**Goal**: Extract benchmarks, final verification, remove dead code.

| Step | Extract | Target File | Lines Out |
|------|---------|-------------|-----------|
| E1 | `runBenchmarkPreset`, `runThreadSweepBenchmark`, `runNativePpTgBenchmark`, `runNextQueuedBenchmark` | `benchmark/BenchmarkRunner.kt` | ~400 |
| E2 | `benchmarkCsv`, `benchmarkJson`, `loadBenchmarkRuns`, `persistBenchmarkRuns`, `recordBenchmarkRun`, `recordNativeBenchmarkRun`, `recordInterruptedBenchmarkRun` | `benchmark/BenchmarkStore.kt` | ~250 |
| E3 | Delete `InferenceService.kt` dead code, old comments, unused imports | — | — |
| E4 | Final integration: run full `assembleDebug` + `testDebugUnitTest` + `connectedDebugAndroidTest` | — | — |

---

## 4. Dependency Injection Strategy

### 4.1 Composition Root (`InferenceService.onCreate()`)

```kotlin
override fun onCreate() {
    super.onCreate()
    createNotificationChannel()

    // ── Layer 0: Infrastructure ──
    engine = NativeLlmBridge.create()
    memoryGovernor = MemoryGovernor(this)
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    // ── Layer 1: Storage ──
    val modelStorage = ModelStorageManager(this)
    val memoryStore = SqlMemoryStore(this)
    val vectorStore = VectorStore(this)
    val transcriptStore = TranscriptStore(this, prefs)
    val benchmarkStore = BenchmarkStore(this, prefs)
    val engineConfig = EngineConfigStore(prefs)

    // ── Layer 2: Domain Services ──
    val deviceProfiler = DeviceProfiler(this)
    val modelReadiness = ModelReadinessAssessor(deviceProfiler, modelStorage)
    val modelManager = ModelManager(engine, modelStorage, modelReadiness, deviceProfiler, engineConfig)
    val modelImport = ModelImportManager(this, modelStorage, modelManager)
    val modelDownload = ModelDownloadManager(this, modelStorage, modelImport)
    val chatManager = ChatManager(this, transcriptStore, prefs)
    val chatExporter = ChatExporter()
    val chatSearch = ChatSearchIndex()

    // ── Layer 3: RAG & Knowledge ──
    val chunker = DocumentChunker
    val ragManager = RagManager(vectorStore, chunker) { text -> engine.encode(text) }
    val grokipediaClient = GrokipediaClient()
    val knowledgePackManager = KnowledgePackManager(grokipediaClient, vectorStore, ragManager, chunker)

    // ── Layer 4: Optional capabilities (fail-safe) ──
    val voiceIoManager = runCatching { VoiceIoManager(this) }.getOrNull()
    val dataConnectorTools = runCatching { DataConnectorTools(this) }.getOrNull()
    val backgroundAgent = runCatching { BackgroundAgentManager(this) }.getOrNull()

    // ── Layer 5: Agent Tools ──
    val toolRegistry = AgentToolRegistry.build(
        chatTools = ChatTools(chatManager, chatExporter, chatSearch),
        modelTools = ModelTools(modelManager, modelStorage, modelReadiness, modelImport, modelDownload),
        runtimeTools = RuntimeTools(engine, engineConfig, benchmarkStore),
        memoryTools = MemoryTools(memoryStore),
        ragTools = RagTools(ragManager, vectorStore),
        knowledgePackTools = KnowledgePackTools(knowledgePackManager),
        voiceTools = VoiceTools(voiceIoManager),
        dataConnectorTools = DataConnectorHandlerTools(dataConnectorTools),
        backgroundAgentTools = BackgroundAgentHandlerTools(backgroundAgent),
        webSearchTools = WebSearchTools(),
        systemTools = SystemTools(this, modelManager, deviceProfiler),
    )
    val agentToolRouter = AgentToolRouter(toolRegistry)
    val agentToolConfirmation = AgentToolConfirmation(this, prefs)
    val agentTrace = AgentTrace(this)

    // ── Layer 6: Generation ──
    val promptBuilder = PromptBuilder(memoryStore, ragManager)
    val generationMetrics = GenerationMetrics()
    val generationOrchestrator = GenerationOrchestrator(
        engine = engine,
        agentToolRouter = agentToolRouter,
        agentToolConfirmation = agentToolConfirmation,
        agentTrace = agentTrace,
        promptBuilder = promptBuilder,
        metrics = generationMetrics,
        deviceProfiler = deviceProfiler,
        transcriptStore = transcriptStore,
        uiState = uiState,
        scope = serviceScope,
    )

    // ── Layer 7: Benchmark ──
    val benchmarkRunner = BenchmarkRunner(engine, benchmarkStore, generationMetrics)

    // ── Layer 8: UI State ──
    uiState = ServiceUiState(
        modelManager = modelManager,
        chatManager = chatManager,
        transcriptStore = transcriptStore,
        generationOrchestrator = generationOrchestrator,
        benchmarkRunner = benchmarkRunner,
    )

    // ── Cross-cutting ──
    memoryGovernor.register { state ->
        serviceScope.launch { engine.setMemoryPressure(state.level) }
    }
    memoryGovernor.monitorMemory()
        .distinctUntilChanged()
        .onEach { state ->
            engine.setMemoryPressure(state.level)
            if (state == MemoryState.CRITICAL) {
                transcriptStore.saveTranscriptSafely(currentChatId, currentTranscript)
                uiState.publishEvent("Memory critical; transcript saved")
            }
        }
        .launchIn(serviceScope)

    modelDownload.observeWorkInfo()
    engineConfig.load { settings -> uiState.updateSettings(settings) }
    chatManager.loadChats()
    agentToolConfirmation.restore()

    // Lazy model restore (requires all subsystems ready)
    serviceScope.launch {
        prefs.getString(KEY_ACTIVE_MODEL, null)?.let { modelManager.switchModel(it) }
    }
}
```

### 4.2 Tool Registration Pattern

Each tool handler file exposes its handlers as a simple data class or interface implementation:

```kotlin
// agent/tools/ChatTools.kt
class ChatTools(
    private val chatManager: ChatManager,
    private val chatExporter: ChatExporter,
    private val chatSearch: ChatSearchIndex,
) {
    fun searchChats(call: AgentToolCall): AgentToolResult { ... }
    fun summarizeCurrentChat(call: AgentToolCall): AgentToolResult { ... }
    fun renameCurrentChat(call: AgentToolCall, confirmed: Boolean): AgentToolResult { ... }
    fun exportChat(call: AgentToolCall, confirmed: Boolean): AgentToolResult { ... }
    fun clearChat(call: AgentToolCall, confirmed: Boolean): AgentToolResult { ... }
    fun deleteChat(call: AgentToolCall, confirmed: Boolean): AgentToolResult { ... }
}
```

The `AgentToolRouter` maps tool names to handler closures:

```kotlin
// agent/AgentToolRouter.kt
class AgentToolRouter(private val registry: AgentToolRegistry) {
    private val handlers: Map<String, suspend (AgentToolCall, Boolean) -> AgentToolResult> = buildMap {
        // Chat tools
        put("search_chats") { call, _ -> registry.chatTools.searchChats(call) }
        put("rename_chat") { call, confirmed -> registry.chatTools.renameCurrentChat(call, confirmed) }
        // ... etc
    }

    suspend fun route(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
        return handlers[call.name]?.invoke(call, confirmed)
            ?: toolFailure(call, "Unknown tool: ${call.name}")
    }
}
```

---

## 5. Future-Proofing

### 5.1 Feature Flag System

Each roadmap capability is gated behind a `CapabilityRegistry` check:

```kotlin
// CapabilityRegistry.kt (expand existing)
object CapabilityRegistry {
    // v1.0 capabilities (always on)
    const val MEMORY = "memory"           // Phase 1 ✅
    const val AGENT_TOOLS = "agent_tools" // Core ✅

    // Experimental capabilities (feature-flagged)
    const val RAG = "rag"                 // Phase 2 🟡 in progress
    const val KNOWLEDGE_PACK = "kp"       // Phase 3 🟡 in progress
    const val VOICE_IO = "voice"          // Phase 4 🟡 in progress
    const val DATA_CONNECTORS = "data"    // Phase 5 🔴 planned
    const val BACKGROUND_AGENT = "bg"     // Phase 7a 🟡 deferred
    const val LORA = "lora"               // Phase 7c 🔴 planned
    const val P2P_SHARING = "p2p"         // Phase 9 🔴 planned

    private val enabled = mutableSetOf(MEMORY, AGENT_TOOLS)

    fun isEnabled(capability: String): Boolean = capability in enabled
    fun enable(capability: String) { enabled.add(capability) }
    fun disable(capability: String) { enabled.remove(capability) }
}
```

Usage in tool registration:
```kotlin
// In tool handler registrations:
if (CapabilityRegistry.isEnabled(CapabilityRegistry.RAG)) {
    put("ingest_document") { call, _ -> registry.ragTools.ingestDocument(call) }
    put("query_documents") { call, _ -> registry.ragTools.queryDocuments(call) }
}
```

### 5.2 Adding a New Capability (Future Developer Workflow)

1. **Create tool handler class** in `agent/tools/` (e.g., `AutomationTools.kt`)
2. **Define tool definitions** in `AgentTools.kt` (existing pattern)
3. **Register in `AgentToolRegistry`** — add to the data class (compile error guides you)
4. **Add capability flag** to `CapabilityRegistry`
5. **Gate registration** behind flag
6. **Test** — tool handler is independently testable with mock dependencies

**Before refactor**: Add handler method to InferenceService.kt (line ~4000), add to `when` block, add to 3–4 routing sets. Merge conflicts with all other in-flight feature branches.

**After refactor**: Create new file in `agent/tools/`. One line added to registry. No merge conflicts.

### 5.3 Safety Architecture (Roadmap Phase 6)

The roadmap calls for replacing `restrictedReason()` keyword blocklists with a structured capability system. The refactored architecture enables this:

```kotlin
// Future: agent/tools/ToolCapability.kt
@RequiresCapability(Capability.NETWORK)
fun webSearch(call: AgentToolCall): AgentToolResult { ... }

@RequiresCapability(Capability.FILE_WRITE)
fun exportChat(call: AgentToolCall, confirmed: Boolean): AgentToolResult { ... }

// agent/AgentToolRouter.kt validates before dispatch:
suspend fun route(call: AgentToolCall, confirmed: Boolean): AgentToolResult {
    val definition = registry.find(call.name) ?: return toolFailure(...)
    if (!capabilityChecker.isAllowed(definition.requiredCapabilities)) {
        return toolFailure(call, "Capability not authorized: ${definition.requiredCapabilities}")
    }
    return handlers[call.name]?.invoke(call, confirmed) ?: toolFailure(...)
}
```

### 5.4 Testability Gains

| Before | After |
|--------|-------|
| Testing `agentToolSearchChats` requires starting `InferenceService` with `NativeLlmBridge`, `ModelStorageManager`, `MemoryStore`, `VectorStore`, etc. | `ChatTools` takes `ChatManager` (interface) — pass a fake with 3 pre-seeded chats. |
| Testing `generateSafely` agent loop requires real model load. | `GenerationOrchestrator` takes `AgentToolRouter` (interface) — mock it to return fixed tool responses. |
| Testing benchmark CSV output requires running real benchmarks. | `BenchmarkStore` is pure data transformation — no Android dependencies. |

---

## 6. Migration Strategy

### 6.1 Zero-Downtime Migration

1. **Extract one concern** into a new file
2. **Keep the original method** in `InferenceService.kt` as a delegation wrapper:
   ```kotlin
   // InferenceService.kt — thin delegation while migrating
   private val chatManager by lazy { ChatManager(this, transcriptStore, prefs) }

   fun createChat(): String = chatManager.createChat()  // delegates
   ```
3. **Verify**: `assembleDebug` + `testDebugUnitTest`
4. **Commit**
5. **Repeat** for next concern
6. **Final cleanup**: Remove the delegation wrappers and dead code from `InferenceService.kt`

### 6.2 Backward Compatibility Contract

These public APIs are preserved exactly:

| API | Current Location | After Refactor |
|-----|-----------------|----------------|
| All `StateFlow` properties (22) | `InferenceService` | Delegated from `ServiceUiState` |
| `fun generateSafely(...)` | `InferenceService` | Delegated to `GenerationOrchestrator` |
| `fun switchModel(id)` | `InferenceService` | Delegated to `ModelManager` |
| `fun createChat()` | `InferenceService` | Delegated to `ChatManager` |
| `fun listModels()` | `InferenceService` | Delegated to `ModelManager` |
| `fun cancelGeneration()` | `InferenceService` | Delegated to `GenerationOrchestrator` |
| `fun importModel(uri)` | `InferenceService` | Delegated to `ModelImportManager` |
| All `fun agentTool*` methods | `InferenceService` | Moved to tool handler classes (internal — not part of public API) |
| `fun deleteMemory(id)` | `InferenceService` | Delegated to `MemoryStore` |

---

## 7. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Agent tool routing breaks during extraction | Medium | High | Extract routing table as a data structure first; test with existing tool call fixtures |
| StateFlow update order changes | Low | Medium | `ServiceUiState` preserves the same emission order; `ChatScreen` collects same flows |
| `lateinit` initialization order breaks | Medium | High | Explicit DAG in `onCreate()`; each layer only depends on previous layers (see §4.1) |
| `generationJob` / `importJob` cancellation races | Medium | Medium | `GenerationOrchestrator` owns `generationJob`; `ModelImportManager` owns `importJob`; `onDestroy` cancels each explicitly |
| Threading (coroutine context) changes | Low | High | All extracted classes receive `serviceScope` (or a narrower scope); no context changes |
| `connectedDebugAndroidTest` breaks | Low | Medium | Instrumentation tests target `InferenceService` public API, which doesn't change |

---

## 8. Verification Gates

Each phase has a non-negotiable verification gate:

| Phase | Gate |
|-------|------|
| A (Foundation) | `assembleDebug` + `testDebugUnitTest` + manual smoke: ChatScreen loads, chats list renders |
| B (Model & Device) | A gates + model switch cycle (load → generate → switch → generate) |
| C (Generation & Agent) | B gates + agent tool loop smoke test (search_chats, remember_fact, web_search) |
| D (Tool Handlers) | C gates + every tool exercised at least once via agent mode |
| E (Benchmark & Cleanup) | Full `connectedDebugAndroidTest` on device/emulator + `InferenceService.kt` < 250 lines |

---

## 9. Session Planning

### Session 1: Phase A (Foundation)
- Extract `ServiceUiState`, `UiEventBus`, `EngineConfigStore`
- Extract `ChatManager`, `TranscriptStore`, `ChatExporter`, `ChatSearchIndex`
- Verify: `assembleDebug` + `testDebugUnitTest`
- ~800 lines out of `InferenceService.kt`

### Session 2: Phase B (Model & Device)
- Extract `ModelManager`, `ModelImportManager`, `ModelDownloadManager`
- Extract `DeviceProfiler`, `ModelReadinessAssessor`
- Verify: model switch cycle works
- ~1,550 lines out

### Session 3–4: Phase C (Generation & Agent Core)
- Extract `GenerationOrchestrator`, `PromptBuilder`, `GenerationMetrics`
- Extract `AgentToolRouter`, `AgentToolConfirmation`, `AgentTrace`
- Verify: agent tool loop smoke test
- ~1,350 lines out

### Session 5: Phase D (Tool Handlers)
- Extract all 11 tool handler files
- Verify: every tool exercised
- ~2,150 lines out

### Session 6: Phase E (Benchmark & Cleanup)
- Extract `BenchmarkRunner`, `BenchmarkStore`
- Run `connectedDebugAndroidTest`
- Final `InferenceService.kt` line count: < 250
- ~950 lines out

---

*This blueprint is a living document. Update it as extraction reveals unexpected couplings or as new capabilities are added to the roadmap.*
