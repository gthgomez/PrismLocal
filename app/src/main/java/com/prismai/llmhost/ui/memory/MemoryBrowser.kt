package com.prismai.llmhost.ui.memory
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.prismai.llmhost.MemoryFact
import com.prismai.llmhost.MemoryCategory
import com.prismai.llmhost.ui.components.*
import com.prismai.llmhost.ui.theme.*
import kotlin.math.roundToInt

@Composable
internal fun MemoryBrowser(
    memories: List<MemoryFact>,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
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
                text = "No memories stored yet. Memories are extracted from conversations automatically.",
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
                        onDelete = { onDelete(memory.id) },
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
        MemoryCategory.GENERAL -> PrismSlate
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
                        else -> PrismSlate
                    },
                )
            }
            Text(
                text = fact.fact,
                style = MaterialTheme.typography.bodySmall,
                color = PrismText,
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
