package com.example.llmhost

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        markdownBlocks(text).forEach { block ->
            when (block.kind) {
                MarkdownBlockKind.Blank -> Spacer(modifier = Modifier.height(8.dp))
                MarkdownBlockKind.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MarkdownBlockKind.Heading -> Text(
                    text = parseInlineMarkdown(block.text),
                    color = color,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                MarkdownBlockKind.Quote -> Text(
                    text = parseInlineMarkdown(block.text),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
                MarkdownBlockKind.Text -> Text(
                    text = parseInlineMarkdown(block.text),
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
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
}

internal data class MarkdownBlock(
    val kind: MarkdownBlockKind,
    val text: String,
)

internal fun markdownBlocks(text: String): List<MarkdownBlock> =
    text.lines().map { rawLine ->
        val line = rawLine.trimEnd()
        val trimmed = line.trim()
        when {
            trimmed.isBlank() -> MarkdownBlock(MarkdownBlockKind.Blank, "")
            trimmed == "---" || trimmed == "***" -> MarkdownBlock(MarkdownBlockKind.Divider, "")
            trimmed.startsWith("### ") -> MarkdownBlock(MarkdownBlockKind.Heading, trimmed.removePrefix("### ").trim())
            trimmed.startsWith("## ") -> MarkdownBlock(MarkdownBlockKind.Heading, trimmed.removePrefix("## ").trim())
            trimmed.startsWith("# ") -> MarkdownBlock(MarkdownBlockKind.Heading, trimmed.removePrefix("# ").trim())
            trimmed.startsWith("> ") -> MarkdownBlock(MarkdownBlockKind.Quote, trimmed.removePrefix("> ").trim())
            trimmed.startsWith("- ") -> MarkdownBlock(MarkdownBlockKind.Text, "• ${trimmed.removePrefix("- ").trim()}")
            trimmed.startsWith("* ") -> MarkdownBlock(MarkdownBlockKind.Text, "• ${trimmed.removePrefix("* ").trim()}")
            else -> MarkdownBlock(MarkdownBlockKind.Text, line)
        }
    }

internal fun markdownPlainLinesForTesting(text: String): List<String> =
    markdownBlocks(text)
        .filter { it.kind != MarkdownBlockKind.Blank && it.kind != MarkdownBlockKind.Divider }
        .map { parseInlineMarkdown(it.text).text }

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
