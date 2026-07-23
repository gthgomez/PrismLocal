package com.prismai.llmhost.ui.chat
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.GenerationPerformance
import com.prismai.llmhost.ui.components.InfinityLoadingIndicator
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.formatTokensPerSecond

@Composable
internal fun MessageBubble(
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
                    EnhancedMarkdownText(text = text)
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
internal fun ToolEventCard(rawText: String) {
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
internal fun AgentToolConfirmationDialog(
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

internal fun GenerationPerformance.terminalSuffix(): String =
    terminalReason?.let { reason ->
        " | " + when (reason) {
            "MAX_TOKENS" -> "token limit"
            else -> reason
        }
    }.orEmpty()
