package com.example.llmhost

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Table color tokens
// ---------------------------------------------------------------------------
private val TableBorderColor = Color(0xFF334155)     // PrismSlate-ish
private val TableHeaderBg   = Color(0xFF1E293B)      // dark slate background for header
private val TableHeaderText = Color(0xFFF8FAFC)      // white-ish
private val TableRowEvenBg  = Color(0xFFF8FAFC)      // very light (matches PrismCanvas)
private val TableRowOddBg   = Color(0xFFF1F5F9)      // slightly darker
private val TableCellText   = Color(0xFF0F172A)      // PrismText
private val TableContainerBg = Color(0xDFFFFFFF)     // PrismGlass

// ---------------------------------------------------------------------------
// ParsedTable data class
// ---------------------------------------------------------------------------

/**
 * Structured representation of a parsed markdown table.
 *
 * @property headers  Column header values.
 * @property alignments Detected text alignment per column (derived from separator row).
 * @property rows     Data rows, each a list of cell values.
 */
data class ParsedTable(
    val headers: List<String>,
    val alignments: List<TextAlign>,
    val rows: List<List<String>>,
)

// ---------------------------------------------------------------------------
// parseMarkdownTable
// ---------------------------------------------------------------------------

/**
 * Parse a pipe-delimited markdown table into structured data.
 *
 * Input format (standard GFM):
 * ```
 * | Header 1 | Header 2 | Header 3 |
 * | :--- | :---: | ---: |
 * | Cell 1 | Cell 2 | Cell 3 |
 * ```
 *
 * Returns `null` if the input does not match a valid table structure.
 */
fun parseMarkdownTable(raw: String): ParsedTable? {
    if (raw.isBlank()) return null

    val lines = raw.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (lines.size < 2) return null

    // Every line must start and end with |
    if (lines.any { !it.startsWith("|") || !it.endsWith("|") }) return null

    // Split cells: strip outer pipes, split by |
    fun splitRow(line: String): List<String> =
        line
            .removeSurrounding("|")
            .split("|")
            .map { it.trim() }

    val headerCells = splitRow(lines[0])
    if (headerCells.isEmpty()) return null

    // Parse separator row for alignment
    val separatorCells = splitRow(lines[1])
    val alignments = separatorCells.map { cell ->
        val trimmed = cell.trim()
        when {
            trimmed.startsWith(":") && trimmed.endsWith(":") -> TextAlign.Center
            trimmed.endsWith(":") -> TextAlign.End
            trimmed.startsWith(":") -> TextAlign.Start
            trimmed.contains("---") -> TextAlign.Start
            else -> TextAlign.Start
        }
    }

    // Parse data rows
    val dataRows = mutableListOf<List<String>>()
    for (i in 2 until lines.size) {
        val cells = splitRow(lines[i])
        // Pad or trim to match header count
        val normalized = if (cells.size < headerCells.size) {
            cells + List(headerCells.size - cells.size) { "" }
        } else {
            cells.take(headerCells.size)
        }
        dataRows += normalized
    }

    if (headerCells.isEmpty()) return null

    return ParsedTable(
        headers = headerCells,
        alignments = alignments.ifEmpty { List(headerCells.size) { TextAlign.Start } },
        rows = dataRows,
    )
}

// ---------------------------------------------------------------------------
// Column width calculation helper
// ---------------------------------------------------------------------------

private data class ColumnWidthInfo(
    val widths: List<Int>,
    val totalWidth: Int,
)

private fun calculateColumnWidths(table: ParsedTable): ColumnWidthInfo {
    val colCount = table.headers.size
    val widths = IntArray(colCount) { 0 }

    // Measure header widths
    table.headers.forEachIndexed { i, header ->
        widths[i] = kotlin.math.max(widths[i], visibleLength(header))
    }

    // Measure cell widths
    table.rows.forEach { row ->
        row.forEachIndexed { i, cell ->
            if (i < colCount) {
                widths[i] = kotlin.math.max(widths[i], visibleLength(cell))
            }
        }
    }

    // Cap minimum at 8 chars for readability, cap max at 40
    val capped = widths.map { it.coerceIn(8, 40) }
    val total = capped.sum() + (colCount - 1) * 3 + 2 // account for padding and borders
    return ColumnWidthInfo(capped, total)
}

private fun visibleLength(text: String): Int {
    // Simple approximation: count non-ansi chars
    return text.length
}

// ---------------------------------------------------------------------------
// MarkdownTable composable
// ---------------------------------------------------------------------------

/**
 * Renders a markdown table as a Compose layout with:
 * - Alternating row colors
 * - Bold header row with darker background
 * - Alignment detection (left/center/right)
 * - Horizontal scroll for wide tables
 * - Rounded corners on container
 * - Max height 300.dp with vertical scroll
 *
 * If the table cannot be parsed, falls back to rendering as plain text.
 */
@Composable
fun MarkdownTable(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    if (markdown.isBlank()) return

    val table = remember(markdown) { parseMarkdownTable(markdown) }

    if (table == null) {
        // Fallback: render as plain text
        Text(
            text = markdown,
            style = MaterialTheme.typography.bodyMedium,
            color = TableCellText,
        )
        return
    }

    Surface(
        modifier = modifier.padding(vertical = 6.dp),
        shape = RoundedCornerShape(8.dp),
        color = TableContainerBg,
        contentColor = TableCellText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(0.dp),
        ) {
            // Header row
            TableRow(
                cells = table.headers,
                alignments = table.alignments,
                isHeader = true,
                rowIndex = -1,
            )

            // Data rows
            table.rows.forEachIndexed { index, row ->
                TableRow(
                    cells = row,
                    alignments = table.alignments,
                    isHeader = false,
                    rowIndex = index,
                )
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    alignments: List<TextAlign>,
    isHeader: Boolean,
    rowIndex: Int,
) {
    val rowBg = if (isHeader) {
        TableHeaderBg
    } else {
        if (rowIndex % 2 == 0) TableRowEvenBg else TableRowOddBg
    }
    val textColor = if (isHeader) TableHeaderText else TableCellText

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = rowBg)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { colIndex, cell ->
            val alignment = alignments.getOrElse(colIndex) { TextAlign.Start }
            val isLast = colIndex == cells.lastIndex

            Text(
                modifier = Modifier
                    .widthIn(min = 80.dp, max = 200.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .then(
                        if (!isLast) {
                            Modifier.border(
                                width = 0.5.dp,
                                color = TableBorderColor.copy(alpha = 0.20f),
                            )
                        } else {
                            Modifier
                        }
                    ),
                text = cell,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = if (isHeader) 12.5.sp else 12.sp,
                ),
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
                textAlign = alignment,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                softWrap = true,
            )
        }
    }
}
