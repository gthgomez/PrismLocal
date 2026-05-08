package com.example.llmhost

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.pow

class InferenceService : Service() {
    companion object {
        private const val TAG = "InferenceService"
        private const val PREFS_NAME = "llm_host_prefs"
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_ACTIVE_CHAT = "active_chat"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val GENERATION_CHANNEL_ID = "llm_generation"
        private const val GENERATION_NOTIFICATION_ID = 1001
        private const val MODEL_LOAD_MEMORY_RESERVE_BYTES = 768L * 1024L * 1024L
        private const val MODEL_LOAD_HARD_CAP_BYTES = 3L * 1024L * 1024L * 1024L
        private const val MODEL_RUNTIME_MIN_OVERHEAD_BYTES = 640L * 1024L * 1024L
        private const val MODEL_CONTEXT_ESTIMATE_BYTES = 256L * 1024L * 1024L
        private const val MODEL_THREAD_SCRATCH_BYTES = 24L * 1024L * 1024L
        private const val LEGACY_TRANSCRIPT_FILE_NAME = "chat_transcript.json"
        private const val CHAT_INDEX_FILE_NAME = "chat_index.json"
        private const val BENCHMARK_RUNS_FILE_NAME = "benchmark_runs.json"
        private const val CHAT_DIR_NAME = "chats"
        private const val TRANSCRIPT_PERSIST_THROTTLE_MS = 1000L
        private const val MAX_BENCHMARK_RUNS = 250
    }

    inner class LocalBinder : Binder() {
        fun getService(): InferenceService = this@InferenceService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private lateinit var engine: NativeLlmBridge
    private lateinit var memoryGovernor: MemoryGovernor
    private lateinit var modelStorageManager: ModelStorageManager
    private var generationJob: Job? = null
    private var importJob: Job? = null
    private var criticalMemoryAlertActive = false
    @Volatile
    private var generationForegroundActive = false
    private val operationMutex = Mutex()
    private val transcriptLock = Any()
    private var generationSession = 0L
    private var nextTranscriptId = 1L
    private var activeAssistantTranscriptId: Long? = null
    private var lastTranscriptPersistAt = 0L
    private var activeBenchmarkPreset: BenchmarkPreset? = null
    private var activeGenerationPrompt: String? = null
    private var activeGenerationStartedAt: Long? = null
    private var activeGenerationFirstTokenAt: Long? = null
    private var activeGenerationTokens = 0
    private var activeGenerationSettings: GenerationSettings? = null
    private val benchmarkQueue = ArrayDeque<BenchmarkPreset>()
    private val _currentModel = MutableStateFlow<String?>(null)
    private val _activeModelInfo = MutableStateFlow<ModelStorageManager.ActiveModelInfo?>(null)
    private val _isGenerating = MutableStateFlow(false)
    private val _runtimeStatus = MutableStateFlow(RuntimeStatus.IDLE)
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    private val _modelDownloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    private val _recoveryTranscript = MutableStateFlow<String?>(null)
    private val _chatSessions = MutableStateFlow<List<ChatSession>>(emptyList())
    private val _currentChatId = MutableStateFlow<String?>(null)
    private val _transcript = MutableStateFlow<List<TranscriptMessage>>(emptyList())
    private val _generationSettings = MutableStateFlow(GenerationSettings())
    private val _generationPerformance = MutableStateFlow<GenerationPerformance?>(null)
    private val _benchmarkRuns = MutableStateFlow<List<BenchmarkRun>>(emptyList())
    private val _benchmarkStatus = MutableStateFlow(BenchmarkStatus())
    private val _modelLoadDiagnostics = MutableStateFlow<ModelLoadDiagnostics?>(null)
    private val _deviceCapabilityProfile = MutableStateFlow<DeviceCapabilityProfile?>(null)
    private val _modelReadiness = MutableStateFlow<List<ModelReadiness>>(emptyList())
    private val _uiMessage = MutableStateFlow<String?>(null)
    private val _uiEvents = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val currentModel: StateFlow<String?> = _currentModel.asStateFlow()
    val activeModelInfo: StateFlow<ModelStorageManager.ActiveModelInfo?> = _activeModelInfo.asStateFlow()
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    val runtimeStatus: StateFlow<RuntimeStatus> = _runtimeStatus.asStateFlow()
    val importState: StateFlow<ImportState> = _importState.asStateFlow()
    val modelDownloadState: StateFlow<ModelDownloadState> = _modelDownloadState.asStateFlow()
    val recoveryTranscript: StateFlow<String?> = _recoveryTranscript.asStateFlow()
    val chatSessions: StateFlow<List<ChatSession>> = _chatSessions.asStateFlow()
    val currentChatId: StateFlow<String?> = _currentChatId.asStateFlow()
    val transcript: StateFlow<List<TranscriptMessage>> = _transcript.asStateFlow()
    val generationSettings: StateFlow<GenerationSettings> = _generationSettings.asStateFlow()
    val generationPerformance: StateFlow<GenerationPerformance?> = _generationPerformance.asStateFlow()
    val benchmarkRuns: StateFlow<List<BenchmarkRun>> = _benchmarkRuns.asStateFlow()
    val benchmarkStatus: StateFlow<BenchmarkStatus> = _benchmarkStatus.asStateFlow()
    val modelLoadDiagnostics: StateFlow<ModelLoadDiagnostics?> = _modelLoadDiagnostics.asStateFlow()
    val deviceCapabilityProfile: StateFlow<DeviceCapabilityProfile?> = _deviceCapabilityProfile.asStateFlow()
    val modelReadiness: StateFlow<List<ModelReadiness>> = _modelReadiness.asStateFlow()
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()
    val streamState = StreamingTextState()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        engine = NativeLlmBridge.create()
        memoryGovernor = MemoryGovernor(this)
        modelStorageManager = ModelStorageManager(this)
        loadBenchmarkRuns()
        loadChats()
        loadGenerationSettings()
        refreshDeviceAndModelReadiness()
        serviceScope.launch {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_ACTIVE_MODEL, null)
                ?.let { savedModel ->
                    switchModel(savedModel)
                }
        }
        memoryGovernor.monitorMemory()
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

    fun listModels(): List<String> = modelStorageManager.listInstalledModels()

    fun huggingFaceCatalog(): List<HuggingFaceModelEntry> = HuggingFaceModelCatalog.entries

    fun benchmarkPresets(): List<BenchmarkPreset> = BenchmarkPresets.defaults

    fun refreshDeviceAndModelReadiness() {
        val profile = captureDeviceCapabilityProfile()
        _deviceCapabilityProfile.value = profile
        _modelReadiness.value = modelStorageManager.listInstalledModelInfos()
            .map { info -> buildModelReadiness(info, profile) }
    }

    fun importModel(uri: Uri) {
        if (importJob?.isActive == true) {
            publishUiEvent("A model import is already running")
            return
        }
        _runtimeStatus.value = RuntimeStatus.IMPORTING
        _importState.value = ImportState.Running(fileName = "selected model", bytesCopied = 0, totalBytes = null)
        importJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val result = modelStorageManager.importModel(uri) { progress ->
                    ensureActive()
                    _importState.value = ImportState.Running(
                        fileName = "selected model",
                        bytesCopied = progress.bytesCopied,
                        totalBytes = progress.totalBytes,
                    )
                }
                when (result) {
                    is ModelStorageManager.ImportResult.Failure -> {
                        _importState.value = ImportState.Failure(
                            message = result.error.userMessage,
                            code = result.error.code.name,
                        )
                        publishUiEvent(result.error.userMessage)
                        _runtimeStatus.value = RuntimeStatus.ERROR
                    }
                    is ModelStorageManager.ImportResult.Success -> {
                        _importState.value = ImportState.Success(
                            modelId = result.model.id,
                            bytes = result.model.bytes,
                            sha256 = result.model.sha256,
                        )
                        refreshDeviceAndModelReadiness()
                        publishUiEvent("Imported ${result.model.id}")
                        _runtimeStatus.value = RuntimeStatus.IDLE
                    }
                }
            } catch (e: CancellationException) {
                _importState.value = ImportState.Cancelled
                _runtimeStatus.value = RuntimeStatus.IDLE
                publishUiEvent("Model import cancelled")
                throw e
            }
        }
    }

    fun downloadHuggingFaceModel(entryId: String) {
        val entry = HuggingFaceModelCatalog.find(entryId)
        if (entry == null) {
            publishUiEvent("Model catalog entry not found")
            return
        }
        if (importJob?.isActive == true) {
            publishUiEvent("A model import or download is already running")
            return
        }
        _runtimeStatus.value = RuntimeStatus.IMPORTING
        _modelDownloadState.value = ModelDownloadState.Running(
            entry = entry,
            stage = ModelDownloadState.Running.Stage.DOWNLOADING,
            bytesDone = 0L,
            totalBytes = entry.expectedBytes,
        )
        _importState.value = ImportState.Running(
            fileName = entry.fileName,
            bytesCopied = 0L,
            totalBytes = entry.expectedBytes,
        )
        importJob = serviceScope.launch(Dispatchers.IO) {
            val temp = File(cacheDir, "hf-${entry.id}-${SystemClock.uptimeMillis()}.gguf")
            try {
                downloadCatalogFile(entry, temp)
                ensureActive()
                _modelDownloadState.value = ModelDownloadState.Running(
                    entry = entry,
                    stage = ModelDownloadState.Running.Stage.IMPORTING,
                    bytesDone = 0L,
                    totalBytes = temp.length(),
                )
                val result = temp.inputStream().use { input ->
                    modelStorageManager.importModelFromStream(
                        displayName = entry.fileName,
                        reportedSize = temp.length(),
                        input = input,
                    ) { progress ->
                        ensureActive()
                        _modelDownloadState.value = ModelDownloadState.Running(
                            entry = entry,
                            stage = ModelDownloadState.Running.Stage.IMPORTING,
                            bytesDone = progress.bytesCopied,
                            totalBytes = progress.totalBytes,
                        )
                        _importState.value = ImportState.Running(
                            fileName = entry.fileName,
                            bytesCopied = progress.bytesCopied,
                            totalBytes = progress.totalBytes,
                        )
                    }
                }
                when (result) {
                    is ModelStorageManager.ImportResult.Failure -> {
                        _modelDownloadState.value = ModelDownloadState.Failure(entry.name, result.error.userMessage)
                        _importState.value = ImportState.Failure(
                            message = result.error.userMessage,
                            code = result.error.code.name,
                        )
                        publishUiEvent(result.error.userMessage)
                        _runtimeStatus.value = RuntimeStatus.ERROR
                    }
                    is ModelStorageManager.ImportResult.Success -> {
                        _modelDownloadState.value = ModelDownloadState.Success(result.model.id, entry.name)
                        _importState.value = ImportState.Success(
                            modelId = result.model.id,
                            bytes = result.model.bytes,
                            sha256 = result.model.sha256,
                        )
                        refreshDeviceAndModelReadiness()
                        publishUiEvent("Downloaded ${entry.name}")
                        _runtimeStatus.value = RuntimeStatus.IDLE
                    }
                }
            } catch (e: CancellationException) {
                _modelDownloadState.value = ModelDownloadState.Cancelled
                _importState.value = ImportState.Cancelled
                _runtimeStatus.value = RuntimeStatus.IDLE
                publishUiEvent("Model download cancelled")
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e::class.java.simpleName
                Log.e(TAG, "Hugging Face download failed entry=${entry.id}", e)
                _modelDownloadState.value = ModelDownloadState.Failure(entry.name, message)
                _importState.value = ImportState.Failure(message = message, code = "DOWNLOAD_FAILED")
                _runtimeStatus.value = RuntimeStatus.ERROR
                publishUiEvent("Download failed: $message")
            } finally {
                runCatching { temp.delete() }
                importJob = null
            }
        }
    }

    private suspend fun downloadCatalogFile(entry: HuggingFaceModelEntry, target: File) {
        val connection = (URL(entry.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "LLMHostAndroid/1.0")
        }
        try {
            val http = connection
            val code = http.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from Hugging Face")
            }
            val total = http.contentLengthLong.takeIf { it > 0L } ?: entry.expectedBytes
            target.parentFile?.mkdirs()
            var copied = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            http.inputStream.use { input ->
                target.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        copied += read
                        _modelDownloadState.value = ModelDownloadState.Running(
                            entry = entry,
                            stage = ModelDownloadState.Running.Stage.DOWNLOADING,
                            bytesDone = copied,
                            totalBytes = total,
                        )
                        _importState.value = ImportState.Running(
                            fileName = entry.fileName,
                            bytesCopied = copied,
                            totalBytes = total,
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importState.value = ImportState.Cancelled
        _modelDownloadState.value = ModelDownloadState.Cancelled
        _runtimeStatus.value = RuntimeStatus.IDLE
    }

    fun clearImportState() {
        if (_importState.value !is ImportState.Running) {
            _importState.value = ImportState.Idle
        }
    }

    suspend fun switchModel(modelId: String): Boolean {
        return operationMutex.withLock {
            Log.d(TAG, "switchModel requested modelId=$modelId")
            cancelAndJoinGenerationLocked("model switch")
            _runtimeStatus.value = RuntimeStatus.LOADING_MODEL
            val loadStartedAt = SystemClock.elapsedRealtime()
            val initialMemory = deviceMemorySnapshot()
            _modelLoadDiagnostics.value = ModelLoadDiagnostics(
                modelId = modelId,
                state = "loading",
                loadMs = 0L,
                modelBytes = null,
                availableMemoryMb = initialMemory.availableMb,
                lowMemory = initialMemory.lowMemory,
                message = "Loading model",
            )

            when (val resolved = modelStorageManager.resolveActiveModel(modelId)) {
                is ModelStorageManager.ModelResolveResult.Failure -> {
                    if (_currentModel.value == modelId) {
                        engine.unloadModel()
                        streamState.clear()
                        _currentModel.value = null
                        _activeModelInfo.value = null
                    }
                    _runtimeStatus.value = RuntimeStatus.ERROR
                    _modelLoadDiagnostics.value = ModelLoadDiagnostics(
                        modelId = modelId,
                        state = "failed",
                        loadMs = SystemClock.elapsedRealtime() - loadStartedAt,
                        modelBytes = null,
                        availableMemoryMb = deviceMemorySnapshot().availableMb,
                        lowMemory = deviceMemorySnapshot().lowMemory,
                        message = resolved.error.userMessage,
                    )
                    Log.w(TAG, "switchModel failed modelId=$modelId error=${resolved.error}")
                    publishUiEvent(resolved.error.userMessage)
                    false
                }
                is ModelStorageManager.ModelResolveResult.Success -> {
                    val activeModel = resolved.model
                    if (_currentModel.value == modelId && _activeModelInfo.value?.sha256 == activeModel.sha256) {
                        _activeModelInfo.value = activeModel
                        _runtimeStatus.value = RuntimeStatus.IDLE
                        val memory = deviceMemorySnapshot()
                        _modelLoadDiagnostics.value = ModelLoadDiagnostics(
                            modelId = modelId,
                            state = "current",
                            loadMs = 0L,
                            modelBytes = activeModel.bytes,
                            availableMemoryMb = memory.availableMb,
                            lowMemory = memory.lowMemory,
                            message = "Model already loaded",
                        )
                        return@withLock true
                    }
                    if (!canAttemptNativeLoad(activeModel)) {
                        val memory = deviceMemorySnapshot()
                        val message = "Model ${activeModel.id} needs ${formatBytesForMessage(activeModel.bytes)}. " +
                            "This build allows up to ${formatBytesForMessage(MODEL_LOAD_HARD_CAP_BYTES)} " +
                            "when enough memory is free."
                        _runtimeStatus.value = RuntimeStatus.ERROR
                        _modelLoadDiagnostics.value = ModelLoadDiagnostics(
                            modelId = modelId,
                            state = "rejected",
                            loadMs = SystemClock.elapsedRealtime() - loadStartedAt,
                            modelBytes = activeModel.bytes,
                            availableMemoryMb = memory.availableMb,
                            lowMemory = memory.lowMemory,
                            message = message,
                        )
                        publishUiEvent(message)
                        return@withLock false
                    }
                    Log.d(TAG, "unloadModel before switch modelId=$modelId current=${_currentModel.value}")
                    engine.unloadModel()
                    Log.d(TAG, "unloadModel complete modelId=$modelId")
                    streamState.clear()
                    val loaded = engine.loadModel(activeModel.file.absolutePath)
                    Log.d(TAG, "switchModel path=${activeModel.file.absolutePath} result=$loaded")
                    if (loaded) {
                        val memory = deviceMemorySnapshot()
                        _currentModel.value = modelId
                        _activeModelInfo.value = activeModel
                        touchCurrentChat(_transcript.value, updateTitle = false)
                        _runtimeStatus.value = RuntimeStatus.IDLE
                        _modelLoadDiagnostics.value = ModelLoadDiagnostics(
                            modelId = modelId,
                            state = "loaded",
                            loadMs = SystemClock.elapsedRealtime() - loadStartedAt,
                            modelBytes = activeModel.bytes,
                            availableMemoryMb = memory.availableMb,
                            lowMemory = memory.lowMemory,
                            message = "Model loaded",
                        )
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_ACTIVE_MODEL, modelId)
                            .apply()
                        refreshDeviceAndModelReadiness()
                    } else {
                        val memory = deviceMemorySnapshot()
                        _currentModel.value = null
                        _activeModelInfo.value = null
                        _runtimeStatus.value = RuntimeStatus.ERROR
                        _modelLoadDiagnostics.value = ModelLoadDiagnostics(
                            modelId = modelId,
                            state = "failed",
                            loadMs = SystemClock.elapsedRealtime() - loadStartedAt,
                            modelBytes = activeModel.bytes,
                            availableMemoryMb = memory.availableMb,
                            lowMemory = memory.lowMemory,
                            message = "Native runtime failed to load model",
                        )
                        publishUiEvent("Failed to load model $modelId")
                        refreshDeviceAndModelReadiness()
                    }
                    loaded
                }
            }
        }
    }

    private data class DeviceMemorySnapshot(
        val totalBytes: Long,
        val availableBytes: Long,
        val lowMemory: Boolean,
    ) {
        val availableMb: Long = availableBytes / (1024L * 1024L)
    }

    private fun deviceMemorySnapshot(): DeviceMemorySnapshot {
        val activityManager = getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return DeviceMemorySnapshot(
            totalBytes = memoryInfo.totalMem,
            availableBytes = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
        )
    }

    private fun captureDeviceCapabilityProfile(): DeviceCapabilityProfile {
        val activityManager = getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batteryScale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (batteryLevel >= 0 && batteryScale > 0) {
            (batteryLevel * 100 / batteryScale).coerceIn(0, 100)
        } else {
            null
        }
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val powerManager = getSystemService(PowerManager::class.java)
        val stat = StatFs(filesDir.absolutePath)
        return DeviceCapabilityProfile(
            totalRamBytes = memoryInfo.totalMem,
            availableRamBytes = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
            cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            androidSdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            storageFreeBytes = stat.availableBytes,
            batteryPercent = batteryPercent,
            isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS,
            thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                thermalStatusLabel(powerManager.currentThermalStatus)
            } else {
                null
            },
            memoryClassMb = activityManager.memoryClass,
            largeMemoryClassMb = activityManager.largeMemoryClass,
            appHeapMaxBytes = Runtime.getRuntime().maxMemory(),
        )
    }

    private fun buildModelReadiness(
        info: ModelStorageManager.ActiveModelInfo,
        profile: DeviceCapabilityProfile,
    ): ModelReadiness {
        val fit = estimateModelFit(info, profile)
        val prediction = predictPerformance(info, fit, profile)
        return ModelReadiness(
            info = info,
            fit = fit,
            prediction = prediction,
            performance = summarizeModelPerformance(info, fit, prediction),
        )
    }

    private fun summarizeModelPerformance(
        info: ModelStorageManager.ActiveModelInfo,
        fit: ModelFitEstimate,
        prediction: PerformancePrediction,
    ): ModelPerformanceSummary {
        val exactRuns = _benchmarkRuns.value.filter {
            it.modelId == info.id && it.generatedTokens > 0 && it.decodeMs > 0L
        }
        val actualAverage = exactRuns.takeIf { it.isNotEmpty() }
            ?.map { it.tokensPerSecond }
            ?.average()
        val score = actualAverage ?: ((prediction.minTokensPerSecond + prediction.maxTokensPerSecond) / 2.0)
        val tier = when {
            fit.rating == ModelFitRating.TOO_LARGE -> ModelPerformanceTier.NOT_RECOMMENDED
            actualAverage == null && prediction.sampleCount == 0 -> ModelPerformanceTier.UNKNOWN
            score < 0.5 -> ModelPerformanceTier.NOT_RECOMMENDED
            score < 2.0 -> ModelPerformanceTier.VERY_SLOW
            score < 6.0 -> ModelPerformanceTier.USABLE
            else -> ModelPerformanceTier.RECOMMENDED
        }
        val label = when (tier) {
            ModelPerformanceTier.UNKNOWN -> "Needs benchmark"
            ModelPerformanceTier.NOT_RECOMMENDED -> "Not recommended"
            ModelPerformanceTier.VERY_SLOW -> "Very slow"
            ModelPerformanceTier.USABLE -> "Usable"
            ModelPerformanceTier.RECOMMENDED -> "Recommended"
        }
        return ModelPerformanceSummary(
            tier = tier,
            label = label,
            averageTokensPerSecond = actualAverage,
            sampleCount = exactRuns.size.takeIf { it > 0 } ?: prediction.sampleCount,
            basedOnActualRuns = exactRuns.isNotEmpty(),
        )
    }

    private fun estimateModelFit(
        info: ModelStorageManager.ActiveModelInfo,
        profile: DeviceCapabilityProfile,
    ): ModelFitEstimate {
        val availableAfterCurrentUnload = profile.availableRamBytes + (_activeModelInfo.value?.bytes ?: 0L)
        val settings = _generationSettings.value.clamped()
        val quantization = quantizationHint(info.fileName) ?: quantizationHint(info.id)
        val runtimeOverhead = maxOf(
            MODEL_RUNTIME_MIN_OVERHEAD_BYTES,
            (info.bytes * quantizationOverheadMultiplier(quantization)).toLong(),
        )
        val requiredRam = info.bytes +
            runtimeOverhead +
            MODEL_CONTEXT_ESTIMATE_BYTES +
            (settings.threadCount * MODEL_THREAD_SCRATCH_BYTES)
        val safeBudget = (availableAfterCurrentUnload * 0.78).toLong()
        val riskyBudget = (availableAfterCurrentUnload * 0.98).toLong()
        val rating = when {
            info.bytes > MODEL_LOAD_HARD_CAP_BYTES -> ModelFitRating.TOO_LARGE
            requiredRam <= safeBudget && !profile.lowMemory -> ModelFitRating.SAFE
            requiredRam <= riskyBudget -> ModelFitRating.RISKY
            else -> ModelFitRating.TOO_LARGE
        }
        val reason = when (rating) {
            ModelFitRating.SAFE -> "Recommended"
            ModelFitRating.RISKY -> if (profile.lowMemory) "May be slow; device reports low memory" else "May be slow; limited RAM headroom"
            ModelFitRating.TOO_LARGE -> "Likely too large for current RAM headroom"
        }
        return ModelFitEstimate(
            modelId = info.id,
            fileName = info.fileName,
            modelBytes = info.bytes,
            quantization = quantization,
            requiredRamBytes = requiredRam,
            availableRamAfterUnloadBytes = availableAfterCurrentUnload,
            storageFreeBytes = profile.storageFreeBytes,
            rating = rating,
            reason = reason,
        )
    }

    private fun predictPerformance(
        info: ModelStorageManager.ActiveModelInfo,
        fit: ModelFitEstimate,
        profile: DeviceCapabilityProfile,
    ): PerformancePrediction {
        val completedRuns = _benchmarkRuns.value.filter { it.generatedTokens > 0 && it.decodeMs > 0L }
        val modelRuns = completedRuns.filter { it.modelId == info.id }
        if (modelRuns.isNotEmpty()) {
            val average = modelRuns.map { it.tokensPerSecond }.average().coerceAtLeast(0.1)
            val spread = if (modelRuns.size == 1) 0.25 else 0.18
            return PerformancePrediction(
                minTokensPerSecond = (average * (1.0 - spread)).coerceAtLeast(0.1),
                maxTokensPerSecond = (average * (1.0 + spread)).coerceAtLeast(0.2),
                basis = "based on exact model history",
                sampleCount = modelRuns.size,
            )
        }
        val sizeSimilarRuns = completedRuns.filter { run ->
            val runBytes = run.modelBytes ?: return@filter false
            val sizeRatio = runBytes.toDouble() / info.bytes.toDouble()
            val runQuant = quantizationHint(run.modelId.orEmpty())
            runQuant == fit.quantization && sizeRatio in 0.65..1.55
        }
        if (sizeSimilarRuns.isNotEmpty()) {
            val estimates = sizeSimilarRuns.mapNotNull { run -> adjustedTokensPerSecond(run, info, fit) }
            if (estimates.isNotEmpty()) {
                val average = estimates.average().coerceAtLeast(0.1)
                return PerformancePrediction(
                    minTokensPerSecond = (average * 0.72).coerceAtLeast(0.1),
                    maxTokensPerSecond = (average * 1.28).coerceAtLeast(0.2),
                    basis = "based on similar ${fit.quantization ?: "quant"} models",
                    sampleCount = estimates.size,
                )
            }
        }
        val globalBaselineRuns = completedRuns.filter { it.modelBytes != null }
        if (globalBaselineRuns.isNotEmpty()) {
            val estimates = globalBaselineRuns.mapNotNull { run -> adjustedTokensPerSecond(run, info, fit) }
            if (estimates.isNotEmpty()) {
                val average = estimates.average().coerceAtLeast(0.1)
                return PerformancePrediction(
                    minTokensPerSecond = (average * 0.55).coerceAtLeast(0.1),
                    maxTokensPerSecond = (average * 1.55).coerceAtLeast(0.2),
                    basis = "based on device baseline",
                    sampleCount = estimates.size,
                )
            }
        }
        val sizeGiB = (info.bytes / (1024.0 * 1024.0 * 1024.0)).coerceAtLeast(0.5)
        val cpuFactor = profile.cpuCoreCount.coerceIn(1, _generationSettings.value.threadCount).toDouble()
        val quantFactor = quantizationSpeedMultiplier(fit.quantization)
        val memoryFactor = when (fit.rating) {
            ModelFitRating.SAFE -> 1.0
            ModelFitRating.RISKY -> 0.72
            ModelFitRating.TOO_LARGE -> 0.35
        }
        val rough = ((cpuFactor * 2.4 * quantFactor * memoryFactor) / sizeGiB.pow(0.82))
            .coerceIn(0.2, 35.0)
        return PerformancePrediction(
            minTokensPerSecond = (rough * 0.65).coerceAtLeast(0.1),
            maxTokensPerSecond = (rough * 1.30).coerceAtLeast(0.2),
            basis = "rough device estimate",
            sampleCount = 0,
        )
    }

    private fun adjustedTokensPerSecond(
        run: BenchmarkRun,
        target: ModelStorageManager.ActiveModelInfo,
        targetFit: ModelFitEstimate,
    ): Double? {
        val runBytes = run.modelBytes?.takeIf { it > 0L } ?: return null
        val runQuant = quantizationHint(run.modelId.orEmpty())
        val sizeFactor = (runBytes.toDouble() / target.bytes.toDouble()).pow(0.82)
        val quantFactor = quantizationSpeedMultiplier(targetFit.quantization) /
            quantizationSpeedMultiplier(runQuant).coerceAtLeast(0.1)
        val threadFactor = if (run.threadCount > 0) {
            (_generationSettings.value.threadCount.toDouble() / run.threadCount.toDouble())
                .coerceIn(0.65, 1.35)
        } else {
            1.0
        }
        return (run.tokensPerSecond * sizeFactor * quantFactor * threadFactor)
            .takeIf { it.isFinite() && it > 0.0 }
    }

    private fun quantizationHint(value: String): String? =
        Regex("(?:^|[-_])((?:I?Q\\d(?:_[Kk])?_[A-Za-z0-9]+)|Q\\d_[A-Za-z0-9]+|F16|BF16)(?:[-_.]|$)")
            .find(value.uppercase(Locale.US))
            ?.groupValues
            ?.getOrNull(1)

    private fun quantizationOverheadMultiplier(quantization: String?): Double = when {
        quantization == null -> 0.30
        quantization.startsWith("Q2") || quantization.startsWith("Q3") -> 0.22
        quantization.startsWith("Q4") -> 0.25
        quantization.startsWith("Q5") -> 0.30
        quantization.startsWith("Q6") -> 0.34
        quantization.startsWith("Q8") -> 0.40
        quantization == "F16" || quantization == "BF16" -> 0.48
        else -> 0.30
    }

    private fun quantizationSpeedMultiplier(quantization: String?): Double = when {
        quantization == null -> 0.85
        quantization.startsWith("Q2") || quantization.startsWith("Q3") -> 1.15
        quantization.startsWith("Q4") -> 1.0
        quantization.startsWith("Q5") -> 0.86
        quantization.startsWith("Q6") -> 0.76
        quantization.startsWith("Q8") -> 0.60
        quantization == "F16" || quantization == "BF16" -> 0.42
        else -> 0.85
    }

    private fun thermalStatusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    private fun canAttemptNativeLoad(model: ModelStorageManager.ActiveModelInfo): Boolean {
        val profile = captureDeviceCapabilityProfile()
        val fit = estimateModelFit(model, profile)
        val availableAfterCurrentUnload = fit.availableRamAfterUnloadBytes
        val reserve = minOf(MODEL_LOAD_MEMORY_RESERVE_BYTES, availableAfterCurrentUnload / 3L)
        val budget = minOf(
            MODEL_LOAD_HARD_CAP_BYTES,
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
        return allowed
    }

    private fun formatBytesForMessage(bytes: Long): String {
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        val mib = bytes / (1024.0 * 1024.0)
        return if (gib >= 1.0) {
            "%.2f GiB".format(gib)
        } else {
            "%.1f MiB".format(mib)
        }
    }

    fun cancelGeneration() {
        Log.d(TAG, "cancelGeneration requested")
        _runtimeStatus.value = RuntimeStatus.CANCELLING
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
        if (_isGenerating.value) {
            publishUiEvent("Cancel generation before creating a new chat")
            return _currentChatId.value.orEmpty()
        }
        val session = newSession(title = ChatTitles.DEFAULT_TITLE, messageCount = 0)
        synchronized(transcriptLock) {
            _chatSessions.value = (_chatSessions.value + session).sortedByDescending { it.updatedAt }
            _currentChatId.value = session.id
            _transcript.value = emptyList()
            nextTranscriptId = 1L
            activeAssistantTranscriptId = null
            lastTranscriptPersistAt = 0L
        }
        streamState.clear()
        persistChatIndex()
        persistTranscriptNow()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_CHAT, session.id)
            .apply()
        return session.id
    }

    fun switchChat(chatId: String): Boolean {
        if (_isGenerating.value) {
            publishUiEvent("Cancel generation before switching chats")
            return false
        }
        if (_chatSessions.value.none { it.id == chatId }) {
            publishUiEvent("Chat no longer exists")
            return false
        }
        persistTranscriptNow()
        synchronized(transcriptLock) {
            _currentChatId.value = chatId
            _transcript.value = readTranscriptFile(transcriptFile(chatId))
            nextTranscriptId = (_transcript.value.maxOfOrNull { it.id } ?: 0L) + 1L
            activeAssistantTranscriptId = null
            lastTranscriptPersistAt = 0L
        }
        streamState.clear()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_CHAT, chatId)
            .apply()
        return true
    }

    fun renameChat(chatId: String, title: String) {
        val safeTitle = title.replace(Regex("\\s+"), " ").trim().take(64)
        if (safeTitle.isBlank()) {
            publishUiEvent("Chat title cannot be empty")
            return
        }
        _chatSessions.value = _chatSessions.value
            .map { session ->
                if (session.id == chatId) {
                    session.copy(title = safeTitle, updatedAt = System.currentTimeMillis())
                } else {
                    session
                }
            }
            .sortedByDescending { it.updatedAt }
        persistChatIndex()
    }

    fun deleteChat(chatId: String) {
        if (_isGenerating.value) {
            publishUiEvent("Cancel generation before deleting a chat")
            return
        }
        val remaining = _chatSessions.value.filterNot { it.id == chatId }
        runCatching { transcriptFile(chatId).delete() }
            .onFailure { error -> Log.w(TAG, "failed to delete chat transcript", error) }
        if (remaining.isEmpty()) {
            _chatSessions.value = emptyList()
            createChat()
            return
        }
        _chatSessions.value = remaining.sortedByDescending { it.updatedAt }
        persistChatIndex()
        if (_currentChatId.value == chatId) {
            switchChat(_chatSessions.value.first().id)
        }
    }

    fun clearTranscript() {
        if (_isGenerating.value) {
            publishUiEvent("Cancel generation before clearing chat")
            return
        }
        synchronized(transcriptLock) {
            _transcript.value = emptyList()
            nextTranscriptId = 1L
            activeAssistantTranscriptId = null
            lastTranscriptPersistAt = 0L
        }
        streamState.clear()
        touchCurrentChat(emptyList(), updateTitle = false)
        runCatching {
            _currentChatId.value?.let { transcriptFile(it).delete() }
        }.onFailure { error ->
            Log.w(TAG, "failed to delete transcript", error)
        }
        persistChatIndex()
    }

    fun updateGenerationSettings(settings: GenerationSettings) {
        val safeSettings = settings.clamped()
        _generationSettings.value = safeSettings
        refreshDeviceAndModelReadiness()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_TOKENS, safeSettings.maxTokens)
            .putInt(KEY_THREAD_COUNT, safeSettings.threadCount)
            .apply()
    }

    fun clearBenchmarkRuns() {
        _benchmarkRuns.value = emptyList()
        runCatching { benchmarkRunsFile().delete() }
            .onFailure { error -> Log.w(TAG, "failed to clear benchmark runs", error) }
        refreshDeviceAndModelReadiness()
        publishUiEvent("Benchmark history cleared")
    }

    fun runBenchmarkPreset(presetId: String) {
        val preset = BenchmarkPresets.find(presetId)
        if (preset == null) {
            publishUiEvent("Benchmark preset not found")
            return
        }
        if (_isGenerating.value) {
            publishUiEvent("Stop the current generation before running a benchmark")
            return
        }
        generateSafely(prompt = preset.prompt, benchmarkPreset = preset)
    }

    fun runThreadSweepBenchmark() {
        if (_isGenerating.value) {
            publishUiEvent("Stop the current generation before running a thread sweep")
            return
        }
        benchmarkQueue.clear()
        BenchmarkPresets.threadSweep.forEach { preset -> benchmarkQueue.add(preset) }
        publishUiEvent("Thread sweep queued: 2, 4, 6, and 8 threads")
        runNextQueuedBenchmark()
    }

    private fun runNextQueuedBenchmark() {
        val next = benchmarkQueue.pollFirst() ?: return
        generateSafely(prompt = next.prompt, benchmarkPreset = next)
    }

    fun benchmarkCsv(): String {
        val header = listOf(
            "created_at_ms",
            "model_id",
            "source",
            "preset_id",
            "preset_name",
            "prompt_chars",
            "output_chars",
            "prompt_eval_ms",
            "decode_ms",
            "total_ms",
            "generated_tokens",
            "tokens_per_second",
            "max_tokens",
            "thread_count",
            "model_bytes",
            "model_sha256_prefix",
            "available_memory_mb",
            "model_load_ms",
            "terminal_reason",
        ).joinToString(",")
        val rows = _benchmarkRuns.value
            .sortedBy { it.createdAt }
            .joinToString("\n") { run ->
                listOf(
                    run.createdAt.toString(),
                    csvCell(run.modelId.orEmpty()),
                    csvCell(run.source),
                    csvCell(run.presetId.orEmpty()),
                    csvCell(run.presetName.orEmpty()),
                    run.promptChars.toString(),
                    run.outputChars.toString(),
                    run.promptEvalMs.toString(),
                    run.decodeMs.toString(),
                    run.totalMs.toString(),
                    run.generatedTokens.toString(),
                    String.format(java.util.Locale.US, "%.4f", run.tokensPerSecond),
                    run.maxTokens.toString(),
                    run.threadCount.toString(),
                    run.modelBytes?.toString().orEmpty(),
                    csvCell(run.modelSha256Prefix.orEmpty()),
                    run.availableMemoryMb?.toString().orEmpty(),
                    run.modelLoadMs?.toString().orEmpty(),
                    csvCell(run.terminalReason),
                ).joinToString(",")
            }
        return if (rows.isBlank()) {
            "$header\n"
        } else {
            "$header\n$rows\n"
        }
    }

    fun benchmarkJson(): String {
        val array = JSONArray()
        _benchmarkRuns.value
            .sortedBy { it.createdAt }
            .forEach { run -> array.put(run.toJson()) }
        return JSONObject()
            .put("schema", "llm-host-benchmarks-v2")
            .put("exported_at_ms", System.currentTimeMillis())
            .put("runs", array)
            .toString(2)
    }

    fun generateSafely(prompt: String, benchmarkPreset: BenchmarkPreset? = null) {
        serviceScope.launch {
            operationMutex.withLock {
                cancelAndJoinGenerationLocked("new generation")
                if (_currentModel.value == null) {
                    publishUiEvent("Select a model before sending a prompt")
                    return@withLock
                }
                activeBenchmarkPreset = benchmarkPreset
                _benchmarkStatus.value = benchmarkPreset?.let { preset ->
                    BenchmarkStatus(
                        isRunning = true,
                        presetId = preset.id,
                        presetName = preset.name,
                        startedAt = System.currentTimeMillis(),
                    )
                } ?: BenchmarkStatus()
                val session = ++generationSession
                val baseSettings = _generationSettings.value.clamped()
                val settings = benchmarkPreset?.overrideSettings(baseSettings) ?: baseSettings
                if (baseSettings != _generationSettings.value) {
                    updateGenerationSettings(baseSettings)
                }
                _generationPerformance.value = null
                appendTranscriptMessage(TranscriptRole.USER, prompt)
                val assistantMessageId = appendTranscriptMessage(TranscriptRole.ASSISTANT, "")
                activeAssistantTranscriptId = assistantMessageId
                streamState.beginGeneration()
                _isGenerating.value = true
                _runtimeStatus.value = RuntimeStatus.GENERATING
                startGenerationForeground()
                val startedAt = SystemClock.elapsedRealtime()
                var firstTokenAt: Long? = null
                var generatedTokens = 0
                activeGenerationPrompt = prompt
                activeGenerationStartedAt = startedAt
                activeGenerationFirstTokenAt = null
                activeGenerationTokens = 0
                activeGenerationSettings = settings
                var lastPerformancePublishAt = 0L
                var lastTranscriptUpdateAt = 0L
                var terminalReason: String? = null
                Log.d(
                    TAG,
                    "generateSafely start promptLength=${prompt.length} model=${_currentModel.value} " +
                        "session=$session settings=$settings",
                )
                generationJob = engine.generate(prompt, settings)
                    .onEach { chunk ->
                        if (session == generationSession) {
                            val uiChunk = Utf8TextPipeline.normalizeChunk(chunk)
                            val now = SystemClock.elapsedRealtime()
                            if (!uiChunk.isTerminal && uiChunk.tokenCount > 0) {
                                if (firstTokenAt == null) {
                                    firstTokenAt = now
                                    activeGenerationFirstTokenAt = now
                                }
                                generatedTokens += uiChunk.tokenCount
                                activeGenerationTokens = generatedTokens
                            }
                            if (uiChunk.isTerminal) {
                                terminalReason = uiChunk.terminalReason
                            }
                            streamState.append(uiChunk)
                            if (uiChunk.isTerminal || now - lastTranscriptUpdateAt >= 75L) {
                                lastTranscriptUpdateAt = now
                                updateTranscriptMessage(
                                    assistantMessageId,
                                    streamState.snapshotText(),
                                    persistImmediately = uiChunk.isTerminal,
                                )
                            }
                            if (uiChunk.isTerminal || now - lastPerformancePublishAt >= 1000L) {
                                lastPerformancePublishAt = now
                                publishGenerationPerformance(
                                    startedAt = startedAt,
                                    firstTokenAt = firstTokenAt,
                                    now = now,
                                    generatedTokens = generatedTokens,
                                    settings = settings,
                                    terminalReason = if (uiChunk.isTerminal) uiChunk.terminalReason else null,
                                )
                            }
                            if (uiChunk.isTerminal && uiChunk.terminalReason == "ERROR") {
                                _runtimeStatus.value = RuntimeStatus.ERROR
                                publishUiEvent("Generation failed in native runtime")
                            }
                        } else {
                            Log.d(TAG, "ignored stale generation chunk nativeGeneration=${chunk.generationId} session=$session active=$generationSession")
                        }
                    }
                    .catch { error ->
                        if (error !is CancellationException && session == generationSession) {
                            Log.e(TAG, "generation failed", error)
                            _runtimeStatus.value = RuntimeStatus.ERROR
                            terminalReason = "ERROR"
                            publishGenerationPerformance(
                                startedAt = startedAt,
                                firstTokenAt = firstTokenAt,
                                now = SystemClock.elapsedRealtime(),
                                generatedTokens = generatedTokens,
                                settings = settings,
                                terminalReason = terminalReason,
                            )
                            publishUiEvent("Generation failed: ${error.message ?: error::class.java.simpleName}")
                        }
                    }
                    .onCompletion { cause ->
                        if (session == generationSession) {
                            if (cause is CancellationException) {
                                Log.d(TAG, "generation cancelled session=$session")
                            }
                            if (terminalReason == null && cause == null && generatedTokens == 0) {
                                terminalReason = "ERROR"
                                _runtimeStatus.value = RuntimeStatus.ERROR
                                publishUiEvent("Generation did not start")
                            }
                            val finalReason = terminalReason ?: if (cause is CancellationException) "CANCELLED" else "EOF"
                            val finalPerformance = publishGenerationPerformance(
                                startedAt = startedAt,
                                firstTokenAt = firstTokenAt,
                                now = SystemClock.elapsedRealtime(),
                                generatedTokens = generatedTokens,
                                settings = settings,
                                terminalReason = finalReason,
                            )
                            recordBenchmarkRun(
                                prompt = prompt,
                                output = streamState.snapshotText(),
                                performance = finalPerformance,
                                terminalReason = finalReason,
                            )
                            val completedPreset = activeBenchmarkPreset
                            clearActiveGenerationMetrics()
                            _benchmarkStatus.value = BenchmarkStatus()
                            updateTranscriptMessage(
                                assistantMessageId,
                                streamState.snapshotText(),
                                persistImmediately = true,
                            )
                            _isGenerating.value = false
                            generationJob = null
                            activeAssistantTranscriptId = null
                            persistTranscriptNow()
                            stopGenerationForeground()
                            if (_runtimeStatus.value == RuntimeStatus.GENERATING ||
                                _runtimeStatus.value == RuntimeStatus.CANCELLING
                            ) {
                                _runtimeStatus.value = RuntimeStatus.IDLE
                            }
                            if (completedPreset?.suiteId != null && benchmarkQueue.isNotEmpty()) {
                                serviceScope.launch { runNextQueuedBenchmark() }
                            }
                        }
                    }
                    .launchIn(serviceScope)
            }
        }
    }

    private fun BenchmarkPreset.overrideSettings(base: GenerationSettings): GenerationSettings =
        base.copy(
            maxTokens = maxTokensOverride ?: base.maxTokens,
            threadCount = threadCountOverride ?: base.threadCount,
        ).clamped()

    private fun recordInterruptedBenchmarkRun(reason: String) {
        if (activeBenchmarkPreset == null) {
            return
        }
        val prompt = activeGenerationPrompt ?: return
        val startedAt = activeGenerationStartedAt ?: return
        val settings = activeGenerationSettings ?: _generationSettings.value.clamped()
        val terminalReason = "INTERRUPTED_${reason.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), "_")}"
        val performance = publishGenerationPerformance(
            startedAt = startedAt,
            firstTokenAt = activeGenerationFirstTokenAt,
            now = SystemClock.elapsedRealtime(),
            generatedTokens = activeGenerationTokens,
            settings = settings,
            terminalReason = terminalReason,
        )
        recordBenchmarkRun(
            prompt = prompt,
            output = streamState.snapshotText(),
            performance = performance,
            terminalReason = terminalReason,
        )
    }

    private fun clearActiveGenerationMetrics() {
        activeBenchmarkPreset = null
        activeGenerationPrompt = null
        activeGenerationStartedAt = null
        activeGenerationFirstTokenAt = null
        activeGenerationTokens = 0
        activeGenerationSettings = null
    }

    private fun saveTranscriptSafely() {
        val currentText = streamState.snapshotText()
        if (currentText.isNotEmpty()) {
            val transcript = File(filesDir, "recovery_transcript.txt")
            transcript.writeText(currentText)
            _recoveryTranscript.value = transcript.absolutePath
        }
        persistTranscriptNow()
    }

    private fun loadGenerationSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        _generationSettings.value = GenerationSettings(
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, GenerationSettings.DEFAULT_MAX_TOKENS),
            threadCount = prefs.getInt(KEY_THREAD_COUNT, GenerationSettings.DEFAULT_THREAD_COUNT),
        ).clamped()
    }

    private fun publishGenerationPerformance(
        startedAt: Long,
        firstTokenAt: Long?,
        now: Long,
        generatedTokens: Int,
        settings: GenerationSettings,
        terminalReason: String?,
    ): GenerationPerformance {
        val firstTokenOrNow = firstTokenAt ?: now
        val promptEvalMs = (firstTokenOrNow - startedAt).coerceAtLeast(0L)
        val decodeMs = (now - firstTokenOrNow).coerceAtLeast(0L)
        val totalMs = (now - startedAt).coerceAtLeast(0L)
        val tokensPerSecond = if (decodeMs > 0L && generatedTokens > 0) {
            generatedTokens * 1000.0 / decodeMs
        } else {
            0.0
        }
        val performance = GenerationPerformance(
            promptEvalMs = promptEvalMs,
            decodeMs = decodeMs,
            totalMs = totalMs,
            generatedTokens = generatedTokens,
            tokensPerSecond = tokensPerSecond,
            settings = settings,
            terminalReason = terminalReason,
        )
        _generationPerformance.value = performance
        return performance
    }

    private fun recordBenchmarkRun(
        prompt: String,
        output: String,
        performance: GenerationPerformance,
        terminalReason: String,
    ) {
        val now = System.currentTimeMillis()
        val preset = activeBenchmarkPreset
        val activeModel = _activeModelInfo.value
        val memory = deviceMemorySnapshot()
        val loadMs = _modelLoadDiagnostics.value
            ?.takeIf { it.modelId == _currentModel.value && it.state in setOf("loaded", "current") }
            ?.loadMs
        val run = BenchmarkRun(
            id = "bench_${now}_${SystemClock.uptimeMillis()}",
            createdAt = now,
            modelId = _currentModel.value,
            source = if (preset == null) "chat" else "preset",
            presetId = preset?.id,
            presetName = preset?.name,
            promptChars = prompt.length,
            outputChars = output.length,
            promptEvalMs = performance.promptEvalMs,
            decodeMs = performance.decodeMs,
            totalMs = performance.totalMs,
            generatedTokens = performance.generatedTokens,
            tokensPerSecond = performance.tokensPerSecond,
            maxTokens = performance.settings.maxTokens,
            threadCount = performance.settings.threadCount,
            modelBytes = activeModel?.bytes,
            modelSha256Prefix = activeModel?.sha256?.take(12),
            availableMemoryMb = memory.availableMb,
            modelLoadMs = loadMs,
            terminalReason = terminalReason,
        )
        _benchmarkRuns.value = (_benchmarkRuns.value + run)
            .sortedByDescending { it.createdAt }
            .take(MAX_BENCHMARK_RUNS)
        persistBenchmarkRuns()
        refreshDeviceAndModelReadiness()
    }

    private fun loadBenchmarkRuns() {
        _benchmarkRuns.value = runCatching {
            val file = benchmarkRunsFile()
            if (!file.isFile) {
                return@runCatching emptyList<BenchmarkRun>()
            }
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BenchmarkRun(
                            id = item.optString("id", "bench_${item.optLong("created_at_ms", 0L)}_$index"),
                            createdAt = item.optLong("created_at_ms", 0L),
                            modelId = item.optString("model_id").takeIf { it.isNotBlank() },
                            source = item.optString("source", "chat"),
                            presetId = item.optString("preset_id").takeIf { it.isNotBlank() },
                            presetName = item.optString("preset_name").takeIf { it.isNotBlank() },
                            promptChars = item.optInt("prompt_chars", 0),
                            outputChars = item.optInt("output_chars", 0),
                            promptEvalMs = item.optLong("prompt_eval_ms", 0L),
                            decodeMs = item.optLong("decode_ms", 0L),
                            totalMs = item.optLong("total_ms", 0L),
                            generatedTokens = item.optInt("generated_tokens", 0),
                            tokensPerSecond = item.optDouble("tokens_per_second", 0.0),
                            maxTokens = item.optInt("max_tokens", GenerationSettings.DEFAULT_MAX_TOKENS),
                            threadCount = item.optInt("thread_count", GenerationSettings.DEFAULT_THREAD_COUNT),
                            modelBytes = item.optLongOrNull("model_bytes"),
                            modelSha256Prefix = item.optString("model_sha256_prefix").takeIf { it.isNotBlank() },
                            availableMemoryMb = item.optLongOrNull("available_memory_mb"),
                            modelLoadMs = item.optLongOrNull("model_load_ms"),
                            terminalReason = item.optString("terminal_reason", "UNKNOWN"),
                        )
                    )
                }
            }.sortedByDescending { it.createdAt }.take(MAX_BENCHMARK_RUNS)
        }.onFailure { error ->
            Log.w(TAG, "failed to load benchmark runs", error)
        }.getOrDefault(emptyList())
    }

    private fun persistBenchmarkRuns() {
        runCatching {
            val array = JSONArray()
            _benchmarkRuns.value.forEach { run ->
                array.put(run.toJson())
            }
            val target = benchmarkRunsFile()
            val temp = File(filesDir, "$BENCHMARK_RUNS_FILE_NAME.tmp")
            temp.writeText(array.toString())
            promoteTempFile(temp, target)
        }.onFailure { error ->
            Log.w(TAG, "failed to persist benchmark runs", error)
        }
    }

    private fun BenchmarkRun.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("created_at_ms", createdAt)
            .put("model_id", modelId)
            .put("source", source)
            .put("preset_id", presetId)
            .put("preset_name", presetName)
            .put("prompt_chars", promptChars)
            .put("output_chars", outputChars)
            .put("prompt_eval_ms", promptEvalMs)
            .put("decode_ms", decodeMs)
            .put("total_ms", totalMs)
            .put("generated_tokens", generatedTokens)
            .put("tokens_per_second", tokensPerSecond)
            .put("max_tokens", maxTokens)
            .put("thread_count", threadCount)
            .put("model_bytes", modelBytes)
            .put("model_sha256_prefix", modelSha256Prefix)
            .put("available_memory_mb", availableMemoryMb)
            .put("model_load_ms", modelLoadMs)
            .put("terminal_reason", terminalReason)

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    private fun csvCell(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    private fun chatIndexFile(): File = File(filesDir, CHAT_INDEX_FILE_NAME)

    private fun benchmarkRunsFile(): File = File(filesDir, BENCHMARK_RUNS_FILE_NAME)

    private fun chatDirectory(): File =
        File(filesDir, CHAT_DIR_NAME).apply {
            if (!isDirectory) {
                mkdirs()
            }
        }

    private fun transcriptFile(chatId: String): File =
        File(chatDirectory(), "$chatId.json")

    private fun legacyTranscriptFile(): File = File(filesDir, LEGACY_TRANSCRIPT_FILE_NAME)

    private fun newSession(title: String, messageCount: Int): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(
            id = "chat_${now}_${SystemClock.uptimeMillis()}",
            title = title,
            createdAt = now,
            updatedAt = now,
            modelId = _currentModel.value,
            messageCount = messageCount,
        )
    }

    private fun loadChats() {
        val indexedSessions = readChatIndex()
        val sessions = if (indexedSessions.isNotEmpty()) {
            indexedSessions
        } else {
            val legacyMessages = readTranscriptFile(legacyTranscriptFile())
            val session = newSession(
                title = firstUserTitle(legacyMessages) ?: ChatTitles.DEFAULT_TITLE,
                messageCount = legacyMessages.size,
            )
            writeTranscriptFile(transcriptFile(session.id), legacyMessages)
            listOf(session)
        }
        val activeChat = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_ACTIVE_CHAT, null)
            ?.takeIf { candidate -> sessions.any { it.id == candidate } }
            ?: sessions.maxByOrNull { it.updatedAt }?.id
            ?: newSession(ChatTitles.DEFAULT_TITLE, 0).id
        val activeSession = sessions.firstOrNull { it.id == activeChat }
            ?: newSession(ChatTitles.DEFAULT_TITLE, 0)
        val normalizedSessions = if (sessions.any { it.id == activeSession.id }) {
            sessions
        } else {
            sessions + activeSession
        }.sortedByDescending { it.updatedAt }
        val restored = readTranscriptFile(transcriptFile(activeSession.id))

        synchronized(transcriptLock) {
            _chatSessions.value = normalizedSessions
            _currentChatId.value = activeSession.id
            _transcript.value = restored
            nextTranscriptId = (restored.maxOfOrNull { it.id } ?: 0L) + 1L
        }
        persistChatIndex()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_CHAT, activeSession.id)
            .apply()
    }

    private fun readChatIndex(): List<ChatSession> =
        runCatching {
            val file = chatIndexFile()
            if (!file.isFile) {
                return@runCatching emptyList<ChatSession>()
            }
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        ChatSession(
                            id = item.getString("id"),
                            title = item.optString("title", ChatTitles.DEFAULT_TITLE),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                            modelId = item.optString("modelId").takeIf { it.isNotBlank() },
                            messageCount = item.optInt("messageCount", 0),
                        )
                    )
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "failed to load chat index", error)
        }.getOrDefault(emptyList())

    private fun persistChatIndex() {
        runCatching {
            val array = JSONArray()
            _chatSessions.value.forEach { session ->
                array.put(
                    JSONObject()
                        .put("id", session.id)
                        .put("title", session.title)
                        .put("createdAt", session.createdAt)
                        .put("updatedAt", session.updatedAt)
                        .put("modelId", session.modelId)
                        .put("messageCount", session.messageCount)
                )
            }
            val target = chatIndexFile()
            val temp = File(filesDir, "$CHAT_INDEX_FILE_NAME.tmp")
            temp.writeText(array.toString())
            promoteTempFile(temp, target)
        }.onFailure { error ->
            Log.w(TAG, "failed to persist chat index", error)
        }
    }

    private fun readTranscriptFile(file: File): List<TranscriptMessage> =
        runCatching {
            if (!file.isFile) {
                return@runCatching emptyList<TranscriptMessage>()
            }
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val role = runCatching {
                        TranscriptRole.valueOf(item.getString("role"))
                    }.getOrDefault(TranscriptRole.ASSISTANT)
                    add(
                        TranscriptMessage(
                            id = item.getLong("id"),
                            role = role,
                            text = item.optString("text", ""),
                        )
                    )
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "failed to load transcript file=${file.absolutePath}", error)
        }.getOrDefault(emptyList())

    private fun writeTranscriptFile(file: File, messages: List<TranscriptMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("role", message.role.name)
                    .put("text", message.text)
            )
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile ?: filesDir, "${file.name}.tmp")
        temp.writeText(array.toString())
        promoteTempFile(temp, file)
    }

    private fun promoteTempFile(temp: File, target: File) {
        runCatching {
            Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        }.getOrElse {
            if (!temp.renameTo(target)) {
                target.delete()
                check(temp.renameTo(target)) { "Failed to promote temp file ${temp.absolutePath}" }
            }
        }
    }

    private fun firstUserTitle(messages: List<TranscriptMessage>): String? =
        messages.firstOrNull { it.role == TranscriptRole.USER }
            ?.text
            ?.let(ChatTitles::fromPrompt)

    private fun appendTranscriptMessage(role: TranscriptRole, text: String): Long {
        if (_currentChatId.value == null) {
            createChat()
        }
        val id = synchronized(transcriptLock) {
            val id = nextTranscriptId++
            _transcript.value = _transcript.value + TranscriptMessage(id, role, text)
            id
        }
        touchCurrentChat(_transcript.value, updateTitle = role == TranscriptRole.USER)
        persistTranscriptThrottled(force = true)
        return id
    }

    private fun updateTranscriptMessage(id: Long, text: String, persistImmediately: Boolean = false) {
        synchronized(transcriptLock) {
            _transcript.value = _transcript.value.map { message ->
                if (message.id == id && message.text != text) {
                    message.copy(text = text)
                } else {
                    message
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
        val messages = synchronized(transcriptLock) {
            _transcript.value
        }
        runCatching {
            writeTranscriptFile(transcriptFile(chatId), messages)
        }.onFailure { error ->
            Log.w(TAG, "failed to persist transcript", error)
        }
    }

    private fun touchCurrentChat(messages: List<TranscriptMessage>, updateTitle: Boolean) {
        val chatId = _currentChatId.value ?: return
        val now = System.currentTimeMillis()
        _chatSessions.value = _chatSessions.value
            .map { session ->
                if (session.id == chatId) {
                    val shouldRetitle = updateTitle && session.title == ChatTitles.DEFAULT_TITLE
                    session.copy(
                        title = if (shouldRetitle) firstUserTitle(messages) ?: session.title else session.title,
                        updatedAt = now,
                        modelId = _currentModel.value ?: session.modelId,
                        messageCount = messages.size,
                    )
                } else {
                    session
                }
            }
            .sortedByDescending { it.updatedAt }
        persistChatIndex()
    }

    private suspend fun cancelAndJoinGenerationLocked(reason: String) {
        val job = generationJob
        generationSession++
        generationJob = null
        if (job != null) {
            Log.d(TAG, "cancelAndJoinGeneration reason=$reason")
            job.cancelAndJoin()
            recordInterruptedBenchmarkRun(reason)
        }
        stopGenerationForeground()
        _isGenerating.value = false
        clearActiveGenerationMetrics()
        benchmarkQueue.clear()
        _benchmarkStatus.value = BenchmarkStatus()
        activeAssistantTranscriptId?.let { assistantMessageId ->
            updateTranscriptMessage(
                assistantMessageId,
                streamState.snapshotText(),
                persistImmediately = true,
            )
            activeAssistantTranscriptId = null
        }
        if (_runtimeStatus.value == RuntimeStatus.GENERATING || _runtimeStatus.value == RuntimeStatus.CANCELLING) {
            _runtimeStatus.value = RuntimeStatus.IDLE
        }
    }

    private fun publishUiEvent(message: String) {
        Log.d(TAG, "uiEvent=$message")
        _uiMessage.value = message
        _uiEvents.tryEmit(message)
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
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
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
        runBlocking {
            operationMutex.withLock {
                cancelAndJoinGenerationLocked("service destroy")
                engine.destroySafely()
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
