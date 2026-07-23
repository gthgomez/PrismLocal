package com.prismai.llmhost.ui.controlplane

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.DeviceCapabilityProfile
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.GenerationSettings
import com.prismai.llmhost.ui.components.*
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.formatTokensPerSecond
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun RuntimeControls(
    settings: GenerationSettings,
    performance: GenerationPerformance?,
    enabled: Boolean,
    deviceCapabilityProfile: DeviceCapabilityProfile?,
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
            val maxContextLimit = when {
                deviceCapabilityProfile == null -> GenerationSettings.MAX_CONTEXT_LENGTH
                deviceCapabilityProfile.totalRamBytes < 6L * 1024L * 1024L * 1024L -> 4096
                deviceCapabilityProfile.totalRamBytes < 8L * 1024L * 1024L * 1024L -> 8192
                else -> GenerationSettings.MAX_CONTEXT_LENGTH
            }
            SettingSlider(
                label = "Context",
                valueText = settings.contextLength.toString(),
                value = minOf(settings.contextLength, maxContextLimit).toFloat(),
                valueRange = GenerationSettings.MIN_CONTEXT_LENGTH.toFloat()..maxContextLimit.toFloat(),
                steps = ((maxContextLimit - GenerationSettings.MIN_CONTEXT_LENGTH) / GenerationSettings.CONTEXT_LENGTH_STEP) - 1,
                enabled = enabled,
                onValueChange = { value ->
                    onSettingsChange(settings.copy(contextLength = snapStep(value, GenerationSettings.CONTEXT_LENGTH_STEP).coerceAtMost(maxContextLimit)))
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

private fun snapTokens(value: Float): Int {
    val step = GenerationSettings.MAX_TOKEN_STEP
    val snapped = (value / step).roundToInt() * step
    return snapped.coerceIn(GenerationSettings.MIN_MAX_TOKENS, GenerationSettings.MAX_MAX_TOKENS)
}

private fun snapStep(value: Float, step: Int): Int =
    ((value / step).roundToInt() * step)

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

private fun GenerationPerformance.terminalSuffix(): String =
    terminalReason?.let { " | $it" }.orEmpty()

