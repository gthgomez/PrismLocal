package com.prismai.llmhost.ui.memory

import com.prismai.llmhost.MemoryCategory
import com.prismai.llmhost.MemoryFact
import com.prismai.llmhost.ui.components.DashboardCard
import com.prismai.llmhost.ui.components.InfoBadge
import com.prismai.llmhost.ui.components.SectionHeader
import com.prismai.llmhost.ui.theme.PrismAmber
import com.prismai.llmhost.ui.theme.PrismBlue
import com.prismai.llmhost.ui.theme.PrismCyan
import com.prismai.llmhost.ui.theme.PrismGreen
import com.prismai.llmhost.ui.theme.PrismRed
import com.prismai.llmhost.ui.theme.PrismViolet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun MemoryBrowser(
    memories: List<MemoryFact>,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onAddMemory: ((String, MemoryCategory) -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<MemoryFact?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newFactText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(MemoryCategory.GENERAL) }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }

    val filteredMemories by remember {
        derivedStateOf {
            if (searchQuery.isBlank()) memories
            else memories.filter { it.fact.contains(searchQuery, ignoreCase = true) }
        }
    }

    DashboardCard {
        SectionHeader(
            title = "Memory",
            subtitle = "${memories.size} facts stored",
            action = {
                Button(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    onClick = { showAddDialog = true },
                ) {
                    Text("+ Add Fact", maxLines = 1, softWrap = false)
                }
            },
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search memories...") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        if (filteredMemories.isEmpty()) {
            Text(
                text = "No memories stored yet. Memories are extracted automatically or can be added manually above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredMemories, key = { it.id }) { memory ->
                    MemoryFactRow(
                        fact = memory,
                        onDelete = { deleteTarget = memory },
                    )
                }
            }
        }

        TextButton(
            onClick = onRefresh,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Refresh")
        }
    }

    deleteTarget?.let { fact ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete memory fact?") },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${fact.fact}\"?",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        onDelete(fact.id)
                        deleteTarget = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Memory Fact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Manual memories initialize at 85% confidence until reinforced.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = newFactText,
                        onValueChange = { newFactText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Fact statement") },
                        placeholder = { Text("e.g. Prefers Kotlin over Java") },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Category:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(modifier = Modifier.weight(1f)) {
                            TextButton(onClick = { categoryDropdownExpanded = true }) {
                                Text(selectedCategory.name)
                            }
                            DropdownMenu(
                                expanded = categoryDropdownExpanded,
                                onDismissRequest = { categoryDropdownExpanded = false },
                            ) {
                                MemoryCategory.values().forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat.name) },
                                        onClick = {
                                            selectedCategory = cat
                                            categoryDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newFactText.isNotBlank(),
                    onClick = {
                        onAddMemory?.invoke(newFactText.trim(), selectedCategory)
                        newFactText = ""
                        showAddDialog = false
                    },
                ) {
                    Text("Save Fact")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun MemoryFactRow(
    fact: MemoryFact,
    onDelete: () -> Unit,
) {
    val categoryColor = when (fact.category) {
        MemoryCategory.PERSONAL -> PrismBlue
        MemoryCategory.PREFERENCE -> PrismViolet
        MemoryCategory.PROJECT -> PrismGreen
        MemoryCategory.RELATIONSHIP -> PrismAmber
        MemoryCategory.KNOWLEDGE -> PrismCyan
        MemoryCategory.GENERAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = categoryColor.copy(alpha = 0.12f),
                    contentColor = categoryColor,
                ) {
                    Text(
                        text = fact.category.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                InfoBadge(
                    text = "${(fact.confidence * 100).roundToInt()}%",
                    color = when {
                        fact.confidence >= 0.7f -> PrismGreen
                        fact.confidence >= 0.4f -> PrismAmber
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = fact.fact,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        TextButton(
            onClick = onDelete,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(32.dp).height(32.dp),
        ) {
            Text(
                text = "✕",
                color = PrismRed.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
