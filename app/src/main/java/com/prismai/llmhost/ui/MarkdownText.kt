package com.prismai.llmhost.ui
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { markdownBlocks(text) }
    SideEffect {
        android.util.Log.d("MarkdownText", "recomposed text_length=${text.length}")
    }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block.kind) {
                MarkdownBlockKind.Blank -> Spacer(modifier = Modifier.height(8.dp))
                MarkdownBlockKind.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MarkdownBlockKind.Heading -> Text(
                    text = block.text,
                    color = color,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                MarkdownBlockKind.Quote -> Text(
                    text = block.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
                MarkdownBlockKind.Text -> Text(
                    text = block.text,
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                )
                MarkdownBlockKind.Code -> CodeBlock(
                    code = block.text.text,
                    language = block.language,
                    color = color,
                )
            }
        }
    }
}

internal enum class MarkdownBlockKind {
    Blank,
    Divider,
    Heading,
    Quote,
    Text,
    Code,
}

internal data class MarkdownBlock(
    val kind: MarkdownBlockKind,
    val text: AnnotatedString,
    val language: String? = null,
)

internal fun markdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var index = 0
    while (index < lines.size) {
        val rawLine = lines[index]
        val line = rawLine.trimEnd()
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim().takeIf { it.isNotBlank() }
            val codeLines = mutableListOf<String>()
            index += 1
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                codeLines += lines[index].trimEnd()
                index += 1
            }
            if (index < lines.size) {
                index += 1
            }
            blocks += MarkdownBlock(
                kind = MarkdownBlockKind.Code,
                text = AnnotatedString(codeLines.joinToString("\n").trimEnd()),
                language = language,
            )
        } else {
            blocks += when {
                trimmed.isBlank() -> MarkdownBlock(MarkdownBlockKind.Blank, AnnotatedString(""))
                trimmed == "---" || trimmed == "***" -> MarkdownBlock(MarkdownBlockKind.Divider, AnnotatedString(""))
                trimmed.startsWith("### ") -> MarkdownBlock(MarkdownBlockKind.Heading, parseInlineMarkdown(trimmed.removePrefix("### ").trim()))
                trimmed.startsWith("## ") -> MarkdownBlock(MarkdownBlockKind.Heading, parseInlineMarkdown(trimmed.removePrefix("## ").trim()))
                trimmed.startsWith("# ") -> MarkdownBlock(MarkdownBlockKind.Heading, parseInlineMarkdown(trimmed.removePrefix("# ").trim()))
                trimmed.startsWith("> ") -> MarkdownBlock(MarkdownBlockKind.Quote, parseInlineMarkdown(trimmed.removePrefix("> ").trim()))
                trimmed.startsWith("- ") -> MarkdownBlock(MarkdownBlockKind.Text, parseInlineMarkdown("• ${trimmed.removePrefix("- ").trim()}"))
                trimmed.startsWith("* ") -> MarkdownBlock(MarkdownBlockKind.Text, parseInlineMarkdown("• ${trimmed.removePrefix("* ").trim()}"))
                else -> MarkdownBlock(MarkdownBlockKind.Text, parseInlineMarkdown(line))
            }
            index += 1
        }
    }
    return blocks
}

@Composable
private fun CodeBlock(
    code: String,
    language: String?,
    color: Color,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f))
                .padding(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = language ?: "code",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText(language ?: "code", code))
                        Toast.makeText(context, "Copied code", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Copy code", maxLines = 1, softWrap = false)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                text = code,
                color = color,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

internal fun markdownPlainLinesForTesting(text: String): List<String> =
    markdownBlocks(text)
        .filter { it.kind != MarkdownBlockKind.Blank && it.kind != MarkdownBlockKind.Divider }
        .map { it.text.text }

private fun parseInlineMarkdown(text: String): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val start = text.indexOf("**", startIndex = index)
            val italicStart = findSingleAsterisk(text, index)
            if (start < 0 && italicStart < 0) {
                append(text.substring(index))
                break
            }
            if (start >= 0 && (italicStart < 0 || start < italicStart)) {
                val end = text.indexOf("**", startIndex = start + 2)
                if (end < 0) {
                    append(text.substring(index))
                    break
                }
                append(text.substring(index, start))
                val boldStart = length
                append(text.substring(start + 2, end))
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), boldStart, length)
                index = end + 2
            } else {
                val end = findSingleAsterisk(text, italicStart + 1)
                if (end < 0) {
                    append(text.substring(index))
                    break
                }
                append(text.substring(index, italicStart))
                val styledStart = length
                append(text.substring(italicStart + 1, end))
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), styledStart, length)
                index = end + 1
            }
        }
    }

private fun findSingleAsterisk(text: String, startIndex: Int): Int {
    var index = text.indexOf('*', startIndex = startIndex)
    while (index >= 0) {
        val previousIsAsterisk = index > 0 && text[index - 1] == '*'
        val nextIsAsterisk = index + 1 < text.length && text[index + 1] == '*'
        if (!previousIsAsterisk && !nextIsAsterisk) {
            return index
        }
        index = text.indexOf('*', startIndex = index + 1)
    }
    return -1
}
