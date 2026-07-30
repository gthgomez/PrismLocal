package com.prismai.llmhost.ui.components

import com.prismai.llmhost.ui.*
import com.prismai.llmhost.ui.theme.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

enum class ToolStatus {
    RUNNING,
    SUCCESS,
    FAILED
}

data class ToolExecutionItem(
    val toolName: String,
    val status: ToolStatus,
    val latencyMs: Long? = null,
    val argumentsJson: String? = null,
    val resultPreview: String? = null,
    val errorMessage: String? = null
)

@Composable
fun ToolExecutionCard(
    item: ToolExecutionItem,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val borderColor = when (item.status) {
        ToolStatus.RUNNING -> PrismCyan.copy(alpha = 0.5f)
        ToolStatus.SUCCESS -> Color(0xFF10B981).copy(alpha = 0.5f)
        ToolStatus.FAILED -> Color(0xFFEF4444).copy(alpha = 0.5f)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = getToolIcon(item.toolName),
                        fontSize = 14.sp
                    )
                    Text(
                        text = formatToolDisplayName(item.toolName),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ToolStatusBadge(status = item.status, latencyMs = item.latencyMs)
                    Text(
                        text = if (expanded) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item.argumentsJson?.takeIf { it.isNotBlank() }?.let { args ->
                        Text(
                            text = "ARGUMENTS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PrismBlue,
                            fontSize = 9.sp
                        )
                        PayloadPreviewBox(content = formatDefensiveJson(args))
                    }

                    item.resultPreview?.takeIf { it.isNotBlank() }?.let { res ->
                        Text(
                            text = "OUTPUT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981),
                            fontSize = 9.sp
                        )
                        PayloadPreviewBox(content = res)
                    }

                    item.errorMessage?.takeIf { it.isNotBlank() }?.let { err ->
                        Text(
                            text = "ERROR",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444),
                            fontSize = 9.sp
                        )
                        PayloadPreviewBox(content = err, isError = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStatusBadge(status: ToolStatus, latencyMs: Long?) {
    val (bgColor, textColor, label) = when (status) {
        ToolStatus.RUNNING -> Triple(
            PrismCyan.copy(alpha = 0.15f),
            PrismCyan,
            "RUNNING"
        )
        ToolStatus.SUCCESS -> Triple(
            Color(0xFF10B981).copy(alpha = 0.15f),
            Color(0xFF059669),
            if (latencyMs != null) "${latencyMs}ms" else "SUCCESS"
        )
        ToolStatus.FAILED -> Triple(
            Color(0xFFEF4444).copy(alpha = 0.15f),
            Color(0xFFDC2626),
            "FAILED"
        )
    }

    val alphaAnim = if (status == ToolStatus.RUNNING) {
        val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shimmerAlpha"
        )
        alpha
    } else 1.0f

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bgColor,
        contentColor = textColor,
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f)),
        modifier = Modifier.alpha(alphaAnim)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun PayloadPreviewBox(content: String, isError: Boolean = false) {
    val bgColor = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val borderColor = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = content,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun formatToolDisplayName(toolName: String): String {
    return when (toolName.lowercase()) {
        "web_search" -> "Web Search"
        "vector_store_query" -> "Memory Retrieval"
        "read_contacts" -> "Contacts Lookup"
        "memory_extract" -> "Knowledge Extract"
        else -> toolName.replace("_", " ").split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

private fun getToolIcon(toolName: String): String {
    return when (toolName.lowercase()) {
        "web_search" -> "🌐"
        "vector_store_query" -> "📚"
        "read_contacts" -> "📇"
        "memory_extract" -> "🧠"
        else -> "🔧"
    }
}

private fun formatDefensiveJson(input: String): String {
    return try {
        val trimmed = input.trim()
        if (trimmed.startsWith("{")) {
            JSONObject(trimmed).toString(2)
        } else {
            input
        }
    } catch (_: Exception) {
        input
    }
}
