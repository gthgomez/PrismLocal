package com.prismai.llmhost.ui.controlplane

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.BenchmarkRun
import com.prismai.llmhost.BenchmarkPresets
import com.prismai.llmhost.BenchmarkStatus
import com.prismai.llmhost.BuildConfig
import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.HuggingFaceModelEntry
import com.prismai.llmhost.ImportState
import com.prismai.llmhost.ModelDownloadState
import com.prismai.llmhost.ModelFitRating
import com.prismai.llmhost.ModelLoadDiagnostics
import com.prismai.llmhost.ModelReadiness
import com.prismai.llmhost.ModelStorageManager
import com.prismai.llmhost.RuntimeStatus
import com.prismai.llmhost.ui.components.*
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.benchmark.*
import com.prismai.llmhost.ui.*
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ControlPlaneSheet(
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
            deviceCapabilityProfile = deviceCapabilityProfile,
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
internal fun DownloadProgressBar(bytesDone: Long, totalBytes: Long?) {
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

internal fun ModelDownloadState.Running.Stage.label(): String = when (this) {
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

private fun ImportState.Running.progressFraction(): Float? =
    totalBytes
        ?.takeIf { it > 0L }
        ?.let { total -> (bytesCopied.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat() }

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
