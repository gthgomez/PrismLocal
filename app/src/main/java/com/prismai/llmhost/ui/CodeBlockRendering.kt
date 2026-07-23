package com.prismai.llmhost.ui
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Color scheme for syntax-highlighted code blocks (dark theme)
// ---------------------------------------------------------------------------
private val CodeBg = Color(0xFF1E1E2E)
private val CodeText = Color(0xFFCDD6F4)
private val CodeKeyword = Color(0xFFCBA6F7)   // mauve — matches PrismViolet
private val CodeString = Color(0xFFA6E3A1)    // green  — matches PrismGreen
private val CodeComment = Color(0xFF6C7086)   // gray
private val CodeNumber = Color(0xFFFAB387)    // peach
private val CodeType   = Color(0xFF89B4FA)    // blue   — matches PrismBlue

private val InlineCodeBg   = Color(0xFF1E1E2E)
private val InlineCodeText = Color(0xFFCDD6F4)

// ---------------------------------------------------------------------------
// Language keyword sets
// ---------------------------------------------------------------------------
private val KOTLIN_KEYWORDS = setOf(
    "val", "var", "fun", "class", "object", "when", "if", "else", "return",
    "suspend", "data", "sealed", "interface", "override", "private", "public",
    "internal", "import", "package", "abstract", "open", "final", "enum",
    "companion", "init", "constructor", "super", "this", "null", "true",
    "false", "try", "catch", "finally", "throw", "for", "while", "do",
    "in", "is", "as", "typealias", "inline", "reified", "annotation",
    "infix", "operator", "tailrec", "crossinline", "noinline", "actual",
    "expect", "value",
)

private val PYTHON_KEYWORDS = setOf(
    "def", "class", "import", "from", "if", "elif", "else", "return",
    "for", "while", "try", "except", "with", "as", "yield", "lambda",
    "and", "or", "not", "in", "is", "None", "True", "False", "raise",
    "break", "continue", "pass", "global", "nonlocal", "assert", "del",
    "async", "await", "self",
)

private val JS_KEYWORDS = setOf(
    "const", "let", "var", "function", "class", "import", "export",
    "return", "if", "else", "for", "while", "async", "await", "typeof",
    "interface", "type", "extends", "implements", "new", "this", "super",
    "null", "undefined", "true", "false", "try", "catch", "finally",
    "throw", "switch", "case", "default", "break", "continue", "of",
    "in", "from", "yield", "static", "get", "set", "readonly", "enum",
    "namespace", "module", "declare", "abstract", "private", "protected",
    "public", "as", "any", "unknown", "never", "void",
)

private val JAVA_KEYWORDS = setOf(
    "class", "public", "private", "protected", "static", "final", "void",
    "int", "String", "boolean", "import", "package", "new", "return",
    "if", "else", "for", "while", "do", "switch", "case", "break",
    "continue", "try", "catch", "finally", "throw", "extends", "implements",
    "interface", "abstract", "enum", "volatile", "transient", "synchronized",
    "native", "strictfp", "assert", "this", "super", "null", "true",
    "false", "long", "double", "float", "char", "byte", "short",
)

private val SHELL_KEYWORDS = setOf(
    "echo", "export", "cd", "ls", "rm", "cp", "mv", "mkdir", "chmod",
    "grep", "sed", "awk", "curl", "wget", "cat", "find", "sort", "uniq",
    "tail", "head", "wc", "cut", "tr", "tee", "xargs", "source", "set",
    "alias", "unset", "exit", "return", "if", "then", "else", "elif",
    "fi", "for", "while", "do", "done", "case", "esac", "function",
    "local", "read", "printf", "test", "exec", "eval", "trap", "kill",
    "jobs", "fg", "bg", "wait", "sudo", "chown", "tar", "gzip", "gunzip",
    "unzip", "ssh", "scp", "rsync", "pip", "npm", "node", "python",
    "python3", "make", "cmake", "docker", "kubectl", "git", "diff",
    "patch", "env",
)

// ---------------------------------------------------------------------------
// Syntax highlighter — keyword-based, no external libs
// ---------------------------------------------------------------------------

private fun keywordsForLanguage(language: String?): Set<String> = when (language?.lowercase()) {
    "kotlin", "kt", "kts" -> KOTLIN_KEYWORDS
    "python", "py", "python3" -> PYTHON_KEYWORDS
    "javascript", "js", "typescript", "ts", "jsx", "tsx", "node", "nodejs" -> JS_KEYWORDS
    "java" -> JAVA_KEYWORDS
    "bash", "sh", "shell", "zsh", "fish", "powershell", "pwsh" -> SHELL_KEYWORDS
    else -> emptySet()
}

private fun commentMarkerFor(language: String?): String = when (language?.lowercase()) {
    "kotlin", "kt", "kts", "java", "javascript", "js", "typescript", "ts",
    "jsx", "tsx", "css", "scss" -> "//"
    "python", "py", "bash", "sh", "shell", "zsh", "fish", "yaml", "yml",
    "toml", "makefile", "make" -> "#"
    "xml", "html", "htm", "svg" -> "<!--"
    else -> "//"
}

private val STRING_PATTERN = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
private val NUMBER_PATTERN = Regex("\\b\\d+(?:\\.\\d+)?[fFlLdD]?\\b")
private val UPPER_WORD_PATTERN = Regex("\\b[A-Z][a-zA-Z0-9_]*\\b")
private val IDENTIFIER_PATTERN = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

/**
 * Build an [AnnotatedString] with syntax highlighting for the given code and language.
 */
internal fun highlightCode(code: String, language: String?): AnnotatedString {
    val keywords = keywordsForLanguage(language)
    val commentMarker = commentMarkerFor(language)

    return buildAnnotatedString {
        val lines = code.split("\n")
        for ((lineIndex, rawLine) in lines.withIndex()) {
            if (lineIndex > 0) append("\n")
            if (rawLine.isEmpty()) continue

            val trimmedStart = rawLine.trimStart()
            val leadingWhitespace = rawLine.length - trimmedStart.length
            if (leadingWhitespace > 0) {
                append(rawLine.substring(0, leadingWhitespace))
            }
            val line = trimmedStart

            // Single-line comment
            val commentIdx = line.indexOf(commentMarker)
            if (commentIdx >= 0 && (commentMarker != "//" || isNotInString(line, commentIdx))) {
                highlightLine(line.substring(0, commentIdx), keywords)
                withStyle(SpanStyle(color = CodeComment)) {
                    append(line.substring(commentIdx))
                }
            } else {
                highlightLine(line, keywords)
            }
        }
    }
}

private fun isNotInString(line: String, idx: Int): Boolean {
    var inDouble = false
    var inSingle = false
    var i = 0
    while (i < idx) {
        when (line[i]) {
            '"' -> if (!inSingle) inDouble = !inDouble
            '\'' -> if (!inDouble) inSingle = !inSingle
            '\\' -> i++
        }
        i++
    }
    return !inDouble && !inSingle
}

/**
 * Extension on [AnnotatedString.Builder] to highlight a single line of code.
 */
private fun AnnotatedString.Builder.highlightLine(line: String, keywords: Set<String>) {
    if (line.isEmpty()) return

    val isXmlLike = keywords.isEmpty() && line.any { it == '<' || it == '>' }

    if (isXmlLike) {
        highlightXmlLine(line)
        return
    }

    var i = 0
    while (i < line.length) {
        // String literal
        val strMatch = STRING_PATTERN.find(line, i)
        if (strMatch != null && strMatch.range.first == i) {
            withStyle(SpanStyle(color = CodeString)) {
                append(strMatch.value)
            }
            i = strMatch.range.last + 1
            continue
        }

        // Number literal
        if (line[i].isDigit() || (line[i] == '.' && i + 1 < line.length && line[i + 1].isDigit())) {
            val numMatch = NUMBER_PATTERN.find(line, i)
            if (numMatch != null && numMatch.range.first == i) {
                withStyle(SpanStyle(color = CodeNumber)) {
                    append(numMatch.value)
                }
                i = numMatch.range.last + 1
                continue
            }
        }

        // Identifier / keyword
        val idMatch = IDENTIFIER_PATTERN.find(line, i)
        if (idMatch != null && idMatch.range.first == i) {
            val word = idMatch.value
            val color = when {
                word in keywords -> CodeKeyword
                UPPER_WORD_PATTERN.matches(word) -> CodeType
                else -> CodeText
            }
            withStyle(SpanStyle(color = color)) {
                append(word)
            }
            i = idMatch.range.last + 1
            continue
        }

        // Plain character
        append(line[i])
        i++
    }
}

/**
 * Extension on [AnnotatedString.Builder] to highlight an XML/HTML line.
 */
private fun AnnotatedString.Builder.highlightXmlLine(line: String) {
    var i = 0
    while (i < line.length) {
        when (line[i]) {
            '<' -> {
                val closeSlash = if (i + 1 < line.length && line[i + 1] == '/') 1 else 0
                val tagStart = i + 1 + closeSlash
                val tagEnd = line.indexOfAny(charArrayOf(' ', '>', '/'), tagStart)
                if (tagEnd > tagStart) {
                    withStyle(SpanStyle(color = CodeType)) {
                        append(line.substring(i, tagEnd))
                    }
                    i = tagEnd
                } else {
                    append(line[i])
                    i++
                }
            }
            '>' -> {
                withStyle(SpanStyle(color = CodeType)) {
                    append(">")
                }
                i++
            }
            '"', '\'' -> {
                val quote = line[i]
                val end = line.indexOf(quote, i + 1)
                if (end > i) {
                    withStyle(SpanStyle(color = CodeString)) {
                        append(line.substring(i, end + 1))
                    }
                    i = end + 1
                } else {
                    append(line[i])
                    i++
                }
            }
            else -> {
                val eqIdx = line.indexOf('=', i)
                if (eqIdx > i && line.substring(i, eqIdx).all { it.isLetterOrDigit() || it == ':' || it == '-' }) {
                    withStyle(SpanStyle(color = CodeKeyword)) {
                        append(line.substring(i, eqIdx))
                    }
                    i = eqIdx
                } else {
                    append(line[i])
                    i++
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// CodeBlock — syntax-highlighted code with language label and copy button
// ---------------------------------------------------------------------------

/**
 * Renders a syntax-highlighted code block with:
 * - Dark background + monospace font
 * - Language label chip
 * - Copy-to-clipboard button with "Copied!" feedback
 * - Horizontal scroll for long lines
 * - Max height 400.dp with vertical scroll
 */
@Composable
fun CodeBlock(
    code: String,
    language: String? = null,
    modifier: Modifier = Modifier,
) {
    if (code.isBlank()) return

    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val highlighted = remember(code, language) { highlightCode(code, language) }

    Surface(
        modifier = modifier.padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = CodeBg,
        contentColor = CodeText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Title bar with language label and copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181825))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Language label chip
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFF313244),
                    contentColor = CodeText.copy(alpha = 0.72f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        text = language?.takeIf { it.isNotBlank() } ?: "code",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = CodeText.copy(alpha = 0.72f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Copy button
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Transparent,
                    contentColor = CodeText.copy(alpha = 0.72f),
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                    },
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (copied) "✓" else "⎘",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                            color = if (copied) CodeString else CodeText.copy(alpha = 0.72f),
                            maxLines = 1,
                            softWrap = false,
                        )
                        AnimatedVisibility(
                            visible = copied,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            Text(
                                text = "Copied!",
                                style = MaterialTheme.typography.labelSmall,
                                color = CodeString,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        AnimatedVisibility(
                            visible = !copied,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            Text(
                                text = "Copy",
                                style = MaterialTheme.typography.labelSmall,
                                color = CodeText.copy(alpha = 0.72f),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            // Code content
            SelectionContainer {
                Text(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    text = highlighted,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// InlineCode — backtick-wrapped inline code
// ---------------------------------------------------------------------------

/**
 * Renders inline code (backtick-wrapped) with monospace styling on a dark pill.
 */
@Composable
fun InlineCode(
    code: String,
    modifier: Modifier = Modifier,
) {
    if (code.isBlank()) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = InlineCodeBg,
        contentColor = InlineCodeText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Enhanced block types
// ---------------------------------------------------------------------------

internal sealed class EnhancedBlock {
    data class Code(val code: String, val language: String?) : EnhancedBlock()
    data class Table(val raw: String) : EnhancedBlock()
    data class Text(val annotated: AnnotatedString) : EnhancedBlock()
    data class Heading(val annotated: AnnotatedString, val level: Int) : EnhancedBlock()
    object Blank : EnhancedBlock()
    object Divider : EnhancedBlock()
    data class Quote(val annotated: AnnotatedString) : EnhancedBlock()
}

// ---------------------------------------------------------------------------
// Block parser — enhanced version of markdownBlocks()
// ---------------------------------------------------------------------------

internal fun enhancedMarkdownBlocks(text: String): List<EnhancedBlock> {
    val blocks = mutableListOf<EnhancedBlock>()
    val lines = text.lines()
    var index = 0

    while (index < lines.size) {
        val rawLine = lines[index]
        val line = rawLine.trimEnd()
        val trimmed = line.trim()

        // Code block fence
        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim().takeIf { it.isNotBlank() }
            val codeLines = mutableListOf<String>()
            index += 1
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                codeLines += lines[index]
                index += 1
            }
            if (index < lines.size) {
                index += 1 // skip closing ```
            }
            blocks += EnhancedBlock.Code(
                code = codeLines.joinToString("\n").trimEnd(),
                language = language,
            )
            continue
        }

        // Table: detect pipe-delimited rows with separator
        if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2) {
            val tableLines = mutableListOf<String>()
            while (index < lines.size) {
                val tl = lines[index].trim()
                if (tl.startsWith("|") && tl.endsWith("|") && tl.count { it == '|' } >= 2) {
                    tableLines += tl
                    index++
                } else {
                    break
                }
            }
            // Require at least header + separator
            if (tableLines.size >= 2) {
                val sepLine = tableLines[1].trim()
                if (sepLine.contains("---") || sepLine.contains("---")) {
                    blocks += EnhancedBlock.Table(tableLines.joinToString("\n"))
                    continue
                }
            }
            // Not a valid table — treat as regular text
            tableLines.forEach { tableLine ->
                blocks += EnhancedBlock.Text(
                    parseInlineMarkdownEnhanced(tableLine)
                )
            }
            continue
        }

        // Non-code/non-table block
        blocks += when {
            trimmed.isBlank() -> EnhancedBlock.Blank
            trimmed == "---" || trimmed == "***" -> EnhancedBlock.Divider
            trimmed.startsWith("### ") -> EnhancedBlock.Heading(
                parseInlineMarkdownEnhanced(trimmed.removePrefix("### ").trim()),
                level = 3,
            )
            trimmed.startsWith("## ") -> EnhancedBlock.Heading(
                parseInlineMarkdownEnhanced(trimmed.removePrefix("## ").trim()),
                level = 2,
            )
            trimmed.startsWith("# ") -> EnhancedBlock.Heading(
                parseInlineMarkdownEnhanced(trimmed.removePrefix("# ").trim()),
                level = 1,
            )
            trimmed.startsWith("> ") -> EnhancedBlock.Quote(
                parseInlineMarkdownEnhanced(trimmed.removePrefix("> ").trim())
            )
            trimmed.startsWith("- ") -> EnhancedBlock.Text(
                parseInlineMarkdownEnhanced("• ${trimmed.removePrefix("- ").trim()}")
            )
            trimmed.startsWith("* ") -> EnhancedBlock.Text(
                parseInlineMarkdownEnhanced("• ${trimmed.removePrefix("* ").trim()}")
            )
            else -> EnhancedBlock.Text(parseInlineMarkdownEnhanced(line))
        }
        index++
    }
    return blocks
}

// ---------------------------------------------------------------------------
// Enhanced inline markdown parser — bold, italic, inline code
// ---------------------------------------------------------------------------

private val INLINE_CODE_PATTERN = Regex("`([^`]+)`")

internal fun parseInlineMarkdownEnhanced(text: String): AnnotatedString {
    return buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val codeMatch = INLINE_CODE_PATTERN.find(text, index)
            val boldStart = text.indexOf("**", startIndex = index)
            val italicStart = findSingleAsterisk(text, index)

            if (codeMatch != null) {
                val codeRange = codeMatch.range
                if (boldStart < 0 || codeRange.first <= boldStart) {
                    if (italicStart < 0 || codeRange.first <= italicStart) {
                        append(text.substring(index, codeRange.first))
                        val codeContent = codeMatch.groupValues[1]
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = InlineCodeText,
                            background = InlineCodeBg,
                        )) {
                            append(codeContent)
                        }
                        index = codeRange.last + 1
                        continue
                    }
                }
            }

            if (boldStart >= 0 && (italicStart < 0 || boldStart < italicStart)) {
                val end = text.indexOf("**", startIndex = boldStart + 2)
                if (end < 0) {
                    append(text.substring(index))
                    break
                }
                append(text.substring(index, boldStart))
                val boldContentStart = length
                append(text.substring(boldStart + 2, end))
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), boldContentStart, length)
                index = end + 2
            } else if (italicStart >= 0) {
                val end = findSingleAsterisk(text, italicStart + 1)
                if (end < 0) {
                    append(text.substring(index))
                    break
                }
                append(text.substring(index, italicStart))
                val italicContentStart = length
                append(text.substring(italicStart + 1, end))
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), italicContentStart, length)
                index = end + 1
            } else {
                append(text.substring(index))
                break
            }
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

// ---------------------------------------------------------------------------
// EnhancedMarkdownText — full markdown renderer
// ---------------------------------------------------------------------------

/**
 * Enhanced markdown renderer that handles:
 * - Code blocks with syntax highlighting (``` ... ```)
 * - Inline code (`code`)
 * - Tables (pipe-delimited markdown tables)
 * - Standard markdown: headings, bold, italic, blockquotes, lists, dividers
 *
 * Drop-in replacement for [MarkdownText] in MessageBubble.
 */
@Composable
fun EnhancedMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return

    val blocks = remember(text) { enhancedMarkdownBlocks(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        blocks.forEach { block ->
            when (block) {
                is EnhancedBlock.Blank -> Spacer(modifier = Modifier.height(8.dp))
                is EnhancedBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                is EnhancedBlock.Heading -> Text(
                    text = block.annotated,
                    color = Color(0xFF0F172A), // PrismText
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                is EnhancedBlock.Quote -> Text(
                    text = block.annotated,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                )
                is EnhancedBlock.Text -> Text(
                    text = block.annotated,
                    color = Color(0xFF0F172A), // PrismText
                    style = MaterialTheme.typography.bodyMedium,
                )
                is EnhancedBlock.Code -> CodeBlock(
                    code = block.code,
                    language = block.language,
                    modifier = Modifier.fillMaxWidth(),
                )
                is EnhancedBlock.Table -> MarkdownTable(
                    markdown = block.raw,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
