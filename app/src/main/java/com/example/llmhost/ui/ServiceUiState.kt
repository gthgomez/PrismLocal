package com.example.llmhost.ui

import com.example.llmhost.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central holder for all UI-observable state in the inference service.
 *
 * Each flow follows the internal-mutable pattern: the public [StateFlow] is
 * read-only for external consumers; module-internal code writes through the
 * underscore-prefixed [MutableStateFlow] references directly.
 */
class ServiceUiState {

    // ── Model ──────────────────────────────────────────────────────────

    internal val _currentModel = MutableStateFlow<String?>(null)
    val currentModel: StateFlow<String?> = _currentModel.asStateFlow()

    internal val _activeModelInfo = MutableStateFlow<ModelStorageManager.ActiveModelInfo?>(null)
    val activeModelInfo: StateFlow<ModelStorageManager.ActiveModelInfo?> = _activeModelInfo.asStateFlow()

    // ── Generation ─────────────────────────────────────────────────────

    internal val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    internal val _runtimeStatus = MutableStateFlow(RuntimeStatus.IDLE)
    val runtimeStatus: StateFlow<RuntimeStatus> = _runtimeStatus.asStateFlow()

    // ── Model Import / Download ────────────────────────────────────────

    internal val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    internal val _modelDownloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val modelDownloadState: StateFlow<ModelDownloadState> = _modelDownloadState.asStateFlow()

    // ── Chat / Transcript ──────────────────────────────────────────────

    internal val _recoveryTranscript = MutableStateFlow<String?>(null)
    val recoveryTranscript: StateFlow<String?> = _recoveryTranscript.asStateFlow()

    internal val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val chatSessions: StateFlow<List<ChatSession>> = _chatSessions.asStateFlow()

    internal val _currentChatId = MutableStateFlow<String?>(null)
    val currentChatId: StateFlow<String?> = _currentChatId.asStateFlow()

    internal val _transcript = MutableStateFlow<List<TranscriptMessage>>(emptyList())
    val transcript: StateFlow<List<TranscriptMessage>> = _transcript.asStateFlow()

    // ── Settings ───────────────────────────────────────────────────────

    internal val _generationSettings = MutableStateFlow(GenerationSettings())
    val generationSettings: StateFlow<GenerationSettings> = _generationSettings.asStateFlow()

    // ── Performance ────────────────────────────────────────────────────

    internal val _generationPerformance = MutableStateFlow<GenerationPerformance?>(null)
    val generationPerformance: StateFlow<GenerationPerformance?> = _generationPerformance.asStateFlow()

    // ── Benchmark ──────────────────────────────────────────────────────

    internal val _benchmarkRuns = MutableStateFlow<List<BenchmarkRun>>(emptyList())
    val benchmarkRuns: StateFlow<List<BenchmarkRun>> = _benchmarkRuns.asStateFlow()

    internal val _benchmarkStatus = MutableStateFlow(BenchmarkStatus())
    val benchmarkStatus: StateFlow<BenchmarkStatus> = _benchmarkStatus.asStateFlow()

    // ── Diagnostics ────────────────────────────────────────────────────

    internal val _modelLoadDiagnostics = MutableStateFlow<ModelLoadDiagnostics?>(null)
    val modelLoadDiagnostics: StateFlow<ModelLoadDiagnostics?> = _modelLoadDiagnostics.asStateFlow()

    internal val _deviceCapabilityProfile = MutableStateFlow<DeviceCapabilityProfile?>(null)
    val deviceCapabilityProfile: StateFlow<DeviceCapabilityProfile?> = _deviceCapabilityProfile.asStateFlow()

    internal val _modelReadiness = MutableStateFlow<List<ModelReadiness>>(emptyList())
    val modelReadiness: StateFlow<List<ModelReadiness>> = _modelReadiness.asStateFlow()

    // ── Agent ──────────────────────────────────────────────────────────

    internal val _pendingAgentToolAction = MutableStateFlow<PendingAgentToolAction?>(null)
    val pendingAgentToolAction: StateFlow<PendingAgentToolAction?> = _pendingAgentToolAction.asStateFlow()

    internal val _lastAgentTracePath = MutableStateFlow<String?>(null)
    val lastAgentTracePath: StateFlow<String?> = _lastAgentTracePath.asStateFlow()

    // ── Memory ─────────────────────────────────────────────────────────

    internal val _memories = MutableStateFlow<List<MemoryFact>>(emptyList())
    val memories: StateFlow<List<MemoryFact>> = _memories.asStateFlow()

    // ── Voice ──────────────────────────────────────────────────────────

    internal val _voiceInputResult = MutableStateFlow<String?>(null)
    val voiceInputResult: StateFlow<String?> = _voiceInputResult.asStateFlow()

    internal val _voiceState = MutableStateFlow(VoiceState())
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    // ── Streaming text (not a StateFlow — mutable state object) ────────

    val streamState = StreamingTextState()
}
