package com.prismai.llmhost.ui.chat
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.RuntimeStatus
import com.prismai.llmhost.ImportState
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.ui.components.GlassSurface
import com.prismai.llmhost.ui.components.PrismLogoTile
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.polishedModelName
import com.prismai.llmhost.ui.formatTokensPerSecond

@Composable
internal fun ChatTopBar(
    runtimeStatus: RuntimeStatus,
    chatTitle: String?,
    modelName: String?,
    importStatus: String,
    importState: ImportState,
    collapsed: Boolean,
    thermalGovernorState: com.prismai.llmhost.util.ThermalGovernorState? = null,
    generationPerformance: GenerationPerformance? = null,
    onOpenChats: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenRag: (() -> Unit)? = null,
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
                    TopBarTitle(
                        modifier = Modifier.weight(1f),
                        runtimeStatus = runtimeStatus,
                        chatTitle = chatTitle,
                        modelName = modelName,
                        importStatus = importStatus,
                        importState = importState,
                        thermalGovernorState = thermalGovernorState,
                        generationPerformance = generationPerformance,
                    )
                    TopBarIconAction(label = "Chats", onClick = onOpenChats)
                    onOpenRag?.let { TopBarIconAction(label = "Docs", onClick = it) }
                    TopBarIconAction(label = "Memory", onClick = onOpenMemories)
                    TopBarIconAction(label = "Settings", onClick = onOpenControls)
                }
            } else if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            thermalGovernorState = thermalGovernorState,
                            generationPerformance = generationPerformance,
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
                        if (onOpenRag != null) {
                            TopBarAction(
                                modifier = Modifier.weight(1f),
                                label = "Docs",
                                compact = true,
                                onClick = onOpenRag,
                            )
                        }
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
                        thermalGovernorState = thermalGovernorState,
                        generationPerformance = generationPerformance,
                    )
                    TopBarAction(label = "Chats", onClick = onOpenChats)
                    onOpenRag?.let { TopBarAction(label = "Docs", onClick = it) }
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
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
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
internal fun ThermalBadge(
    state: com.prismai.llmhost.util.ThermalGovernorState? = null
) {
    val (color, text) = when {
        state?.isEmergency == true -> Pair(Color(0xFFDC2626), "EMERGENCY")
        state?.isThrottled == true -> Pair(Color(0xFFEF4444), "THROTTLED (${state.recommendedThreads}th)")
        state?.statusLabel == "MODERATE" -> Pair(Color(0xFFF59E0B), "MODERATE")
        else -> Pair(Color(0xFF10B981), "NORMAL")
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Canvas(modifier = Modifier.size(5.dp)) {
                drawCircle(color = color)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
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
    thermalGovernorState: com.prismai.llmhost.util.ThermalGovernorState? = null,
    generationPerformance: GenerationPerformance? = null,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
            ThermalBadge(state = thermalGovernorState)
        }
        Text(
            text = topBarSubtitle(runtimeStatus, modelName, importStatus, importState, generationPerformance),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
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
        }.semantics { contentDescription = label },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
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
                    color = MaterialTheme.colorScheme.onSurface,
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
                    color = MaterialTheme.colorScheme.onSurface,
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
    val iconColor = when (label) {
        "Chats" -> PrismBlue
        "Memory" -> PrismViolet
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Canvas(modifier = Modifier.size(25.dp)) {
        val stroke = Stroke(width = 2.5f, cap = StrokeCap.Round)
        when (label) {
            "Chats" -> {
                drawRoundRect(
                    color = iconColor,
                    topLeft = Offset(size.width * 0.16f, size.height * 0.18f),
                    size = Size(size.width * 0.68f, size.height * 0.52f),
                    cornerRadius = CornerRadius(6f, 6f),
                    style = stroke,
                )
                drawLine(
                    color = iconColor,
                    start = Offset(size.width * 0.34f, size.height * 0.38f),
                    end = Offset(size.width * 0.66f, size.height * 0.38f),
                    strokeWidth = 2.2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = iconColor,
                    start = Offset(size.width * 0.34f, size.height * 0.52f),
                    end = Offset(size.width * 0.56f, size.height * 0.52f),
                    strokeWidth = 2.2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = iconColor,
                    start = Offset(size.width * 0.36f, size.height * 0.70f),
                    end = Offset(size.width * 0.26f, size.height * 0.84f),
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round,
                )
            }
            "Memory" -> {
                drawRoundRect(
                    color = iconColor,
                    topLeft = Offset(size.width * 0.24f, size.height * 0.24f),
                    size = Size(size.width * 0.52f, size.height * 0.52f),
                    cornerRadius = CornerRadius(4f, 4f),
                    style = stroke,
                )
                drawRect(
                    color = iconColor.copy(alpha = 0.30f),
                    topLeft = Offset(size.width * 0.38f, size.height * 0.38f),
                    size = Size(size.width * 0.24f, size.height * 0.24f),
                )
                val pinLength = size.height * 0.14f
                listOf(0.36f, 0.50f, 0.64f).forEach { xRatio ->
                    val x = size.width * xRatio
                    drawLine(
                        color = iconColor,
                        start = Offset(x, size.height * 0.10f),
                        end = Offset(x, size.height * 0.24f),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = iconColor,
                        start = Offset(x, size.height * 0.76f),
                        end = Offset(x, size.height * 0.76f + pinLength),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                }
            }
            else -> {
                drawCircle(
                    color = iconColor,
                    radius = size.minDimension * 0.18f,
                    center = center,
                    style = stroke,
                )
                repeat(8) { index ->
                    val angle = (index * 45.0) * Math.PI / 180.0
                    val inner = size.minDimension * 0.34f
                    val outer = size.minDimension * 0.44f
                    drawLine(
                        color = iconColor,
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
}

private fun topBarSubtitle(
    status: RuntimeStatus,
    modelName: String?,
    importStatus: String,
    importState: ImportState,
    performance: GenerationPerformance? = null,
): String {
    return if (importState is ImportState.Running && importStatus.isNotBlank()) {
        importStatus
    } else if (status == RuntimeStatus.GENERATING) {
        if (performance != null && performance.tokensPerSecond > 0f) {
            val tpsStr = formatTokensPerSecond(performance.tokensPerSecond)
            val threads = if (performance.activeThreads > 0) performance.activeThreads else performance.settings.threadCount
            "Generating · $tpsStr t/s · ${threads}th"
        } else {
            status.label()
        }
    } else if (status == RuntimeStatus.LOADING_MODEL || status == RuntimeStatus.ERROR) {
        status.label()
    } else if (!modelName.isNullOrBlank()) {
        "${polishedModelName(modelName)} • Offline"
    } else {
        "Offline AI workspace"
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
