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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
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
        userBubbleColor()
    } else {
        assistantBubbleColor()
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val labelColor = if (isUser) PrismBlue else PrismViolet
    val context = LocalContext.current
    val view = LocalView.current
    val copyLabel = if (isUser) "prompt" else "response"
    var showReportDialog by remember { mutableStateOf(false) }

    if (showReportDialog) {
        ReportAiContentDialog(
            onDismiss = { showReportDialog = false },
            onSubmitReport = { reason ->
                Toast.makeText(context, "Report saved locally: $reason", Toast.LENGTH_SHORT).show()
            }
        )
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.82f else 0.94f)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(16.dp)
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
                if (!isUser && text.isNotBlank()) {
                    TextButton(
                        modifier = Modifier.widthIn(min = 52.dp),
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            showReportDialog = true
                        },
                    ) {
                        Text("Report", maxLines = 1, softWrap = false)
                    }
                }
                TextButton(
                    modifier = Modifier.widthIn(min = 56.dp),
                    enabled = text.isNotBlank(),
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        copyTextToClipboard(context, label, text)
                        Toast.makeText(context, "Copied $copyLabel", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Copy", maxLines = 1, softWrap = false)
                }
            }
            Spacer(modifier = Modifier.height(if (isUser) 10.dp else 12.dp))
            SelectionContainer {
                if (isUser) {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    EnhancedMarkdownText(text = text)
                }
            }
        }
    }
}

@Composable
private fun ReportAiContentDialog(
    onDismiss: () -> Unit,
    onSubmitReport: (reason: String) -> Unit,
) {
    val reportReasons = listOf(
        "Offensive or hateful content",
        "Sexually explicit content",
        "Dangerous or harmful instructions",
        "Inaccurate or hallucinated response",
        "Other policy violation"
    )
    var selectedReasonIndex by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report AI Generated Response") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Google Play AI policy requires in-app user reporting for generated content. Select the issue with this response:",
                    style = MaterialTheme.typography.bodySmall
                )
                reportReasons.forEachIndexed { index, reason ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedReasonIndex = index }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedReasonIndex == index),
                            onClick = { selectedReasonIndex = index }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSubmitReport(reportReasons[selectedReasonIndex])
                    onDismiss()
                }
            ) {
                Text("Submit Report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AssistantBadge() {
    Surface(
        modifier = Modifier.size(32.dp),
        shape = RoundedCornerShape(16.dp),
        color = PrismViolet.copy(alpha = 0.14f),
        contentColor = PrismViolet,
        border = BorderStroke(1.dp, PrismViolet.copy(alpha = 0.36f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(16.dp)) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    val w = size.width
                    val h = size.height
                    moveTo(w * 0.5f, 0f)
                    quadraticTo(w * 0.5f, h * 0.5f, w, h * 0.5f)
                    quadraticTo(w * 0.5f, h * 0.5f, w * 0.5f, h)
                    quadraticTo(w * 0.5f, h * 0.5f, 0f, h * 0.5f)
                    quadraticTo(w * 0.5f, h * 0.5f, w * 0.5f, 0f)
                    close()
                }
                drawPath(path = path, color = PrismViolet)
            }
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
        modifier = modifier.widthIn(min = 80.dp, max = 160.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, prismGlassBorderColor()),
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
                    performance != null -> {
                        val tpsStr = "${formatTokensPerSecond(performance.tokensPerSecond)} t/s"
                        val ttftStr = if (performance.ttftMs > 0) " · ${performance.ttftMs}ms" else ""
                        val threadsStr = if (performance.activeThreads > 0) " · ${performance.activeThreads}th" else ""
                        "$tpsStr$ttftStr$threadsStr"
                    }
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
    val statusStr = event?.optString("status")?.takeIf { it.isNotBlank() } ?: "done"
    val toolName = event?.optString("tool")?.takeIf { it.isNotBlank() } ?: "tool"
    val summary = event?.optString("summary")?.takeIf { it.isNotBlank() } ?: rawText
    val latencyMs = event?.optLong("latency_ms")?.takeIf { it > 0 }
    val argsJson = event?.optString("arguments")?.takeIf { it.isNotBlank() }
    val error = event?.optString("error")?.takeIf { it.isNotBlank() }

    val status = when (statusStr) {
        "pending", "running" -> com.prismai.llmhost.ui.components.ToolStatus.RUNNING
        "failed" -> com.prismai.llmhost.ui.components.ToolStatus.FAILED
        else -> com.prismai.llmhost.ui.components.ToolStatus.SUCCESS
    }

    val item = com.prismai.llmhost.ui.components.ToolExecutionItem(
        toolName = toolName,
        status = status,
        latencyMs = latencyMs,
        argumentsJson = argsJson,
        resultPreview = summary,
        errorMessage = error
    )

    com.prismai.llmhost.ui.components.ToolExecutionCard(item = item)
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
