package com.prismai.llmhost.ui.rag

import com.prismai.llmhost.storage.VectorChunk
import com.prismai.llmhost.ui.components.DashboardCard
import com.prismai.llmhost.ui.components.InfoBadge
import com.prismai.llmhost.ui.components.SectionHeader
import com.prismai.llmhost.ui.theme.PrismAmber
import com.prismai.llmhost.ui.theme.PrismBlue
import com.prismai.llmhost.ui.theme.PrismCyan
import com.prismai.llmhost.ui.theme.PrismGreen
import com.prismai.llmhost.ui.theme.PrismRed
import com.prismai.llmhost.ui.theme.PrismSlate
import com.prismai.llmhost.ui.theme.PrismViolet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DocumentBrowser(
    chunks: List<VectorChunk>,
    onIngestDocument: (id: String, title: String, text: String) -> Unit,
    onDeleteDocument: (documentId: String) -> Unit,
    onQueryVectorStore: suspend (query: String) -> List<Pair<VectorChunk, Float>>,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Pair<VectorChunk, Float>>?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    var showIngestDialog by remember { mutableStateOf(false) }
    var ingestDocId by remember { mutableStateOf("") }
    var ingestTitle by remember { mutableStateOf("") }
    var ingestText by remember { mutableStateOf("") }

    var deleteTargetDocId by remember { mutableStateOf<String?>(null) }

    // Group stored chunks by documentId
    val docSummaryMap by remember(chunks) {
        derivedStateOf {
            chunks.groupBy { it.documentId }
        }
    }

    DashboardCard(modifier = modifier) {
        SectionHeader(
            title = "Knowledge Base (RAG)",
            subtitle = "${docSummaryMap.size} documents • ${chunks.size} vector chunks",
            action = {
                Button(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    onClick = { showIngestDialog = true },
                ) {
                    Text("+ Ingest Doc", maxLines = 1, softWrap = false)
                }
            },
        )

        // ── Semantic Search Tester ──
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Vector Similarity Search",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = PrismBlue,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        if (it.isBlank()) searchResults = null
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Test semantic query...") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Button(
                    enabled = searchQuery.isNotBlank() && !isSearching,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    onClick = {
                        isSearching = true
                        coroutineScope.launch {
                            searchResults = onQueryVectorStore(searchQuery.trim())
                            isSearching = false
                        }
                    },
                ) {
                    Text(if (isSearching) "..." else "Search")
                }
            }
        }

        // ── Search Results View ──
        searchResults?.let { results ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Top Matches (${results.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (results.isEmpty()) {
                        Text(
                            text = "No matching vector chunks found.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(results) { (chunk, score) ->
                                VectorSearchResultRow(chunk = chunk, score = score)
                            }
                        }
                    }
                }
            }
        }

        // ── Stored Document List ──
        Text(
            text = "Stored Documents",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (docSummaryMap.isEmpty()) {
            Text(
                text = "No documents ingested into knowledge base yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(docSummaryMap.keys.toList(), key = { it }) { docId ->
                    val docChunks = docSummaryMap[docId] ?: emptyList()
                    DocumentSummaryRow(
                        docId = docId,
                        chunkCount = docChunks.size,
                        previewText = docChunks.firstOrNull()?.text ?: "",
                        onDelete = { deleteTargetDocId = docId },
                    )
                }
            }
        }

        TextButton(
            onClick = onRefresh,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Refresh Knowledge Base")
        }
    }

    // ── Ingest Document Dialog ──
    if (showIngestDialog) {
        AlertDialog(
            onDismissRequest = { showIngestDialog = false },
            title = { Text("Ingest Document") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Documents are split into chunks, embedded into float vectors, and stored in local SQLite VectorStore.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ingestDocId,
                        onValueChange = { ingestDocId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Document ID") },
                        placeholder = { Text("e.g. project_spec_v1") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = ingestTitle,
                        onValueChange = { ingestTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Title") },
                        placeholder = { Text("e.g. System Architecture Notes") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = ingestText,
                        onValueChange = { ingestText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp),
                        label = { Text("Document Content") },
                        placeholder = { Text("Paste document text here...") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = ingestDocId.isNotBlank() && ingestText.isNotBlank(),
                    onClick = {
                        onIngestDocument(
                            ingestDocId.trim(),
                            ingestTitle.ifBlank { ingestDocId }.trim(),
                            ingestText.trim(),
                        )
                        ingestDocId = ""
                        ingestTitle = ""
                        ingestText = ""
                        showIngestDialog = false
                    },
                ) {
                    Text("Ingest & Embed")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIngestDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Deletion Confirmation Dialog ──
    deleteTargetDocId?.let { docId ->
        AlertDialog(
            onDismissRequest = { deleteTargetDocId = null },
            title = { Text("Delete Document?") },
            text = {
                Text(
                    text = "Are you sure you want to remove document \"$docId\" and all associated vector chunks from local storage?",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        onDeleteDocument(docId)
                        deleteTargetDocId = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetDocId = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun VectorSearchResultRow(
    chunk: VectorChunk,
    score: Float,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${chunk.documentId} [chunk #${chunk.chunkIndex}]",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = PrismViolet,
                )
                InfoBadge(
                    text = "score ${String.format(Locale.US, "%.3f", score)}",
                    color = when {
                        score >= 0.7f -> PrismGreen
                        score >= 0.4f -> PrismAmber
                        else -> PrismSlate
                    },
                )
            }
            Text(
                text = chunk.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DocumentSummaryRow(
    docId: String,
    chunkCount: Int,
    previewText: String,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = docId,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InfoBadge(
                    text = "$chunkCount chunks",
                    color = PrismCyan,
                )
            }
            if (previewText.isNotBlank()) {
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(
            onClick = onDelete,
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                text = "Delete",
                color = PrismRed.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
