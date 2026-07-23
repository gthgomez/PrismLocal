package com.prismai.llmhost.ui.chat

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
import com.prismai.llmhost.ui.components.GlassSurface
import com.prismai.llmhost.ui.components.PrismLogoTile
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.polishedModelName

@Composable
internal fun ChatTopBar(
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
