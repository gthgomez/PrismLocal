package com.prismai.llmhost.ui.chat
import com.prismai.llmhost.*
import com.prismai.llmhost.bridge.*
import com.prismai.llmhost.service.*
import com.prismai.llmhost.storage.*
import com.prismai.llmhost.tools.*
import com.prismai.llmhost.ui.*
import com.prismai.llmhost.model.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.ui.theme.*
import com.prismai.llmhost.ui.polishedChatTitle
import com.prismai.llmhost.ui.polishedModelName
import com.prismai.llmhost.ui.formatChatTimestamp

@Composable
internal fun ChatListSheet(
    sessions: List<ChatSession>,
    currentChatId: String?,
    isGenerating: Boolean,
    hasCurrentTranscript: Boolean,
    onNewChat: () -> Unit,
    onSwitchChat: (String) -> Unit,
    onRenameChat: (String, String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onClearCurrentChat: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    var clearCurrentRequested by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredSessions = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions
        else sessions.filter { session ->
            session.title.contains(searchQuery, ignoreCase = true) ||
                (session.modelId?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Chats",
                    style = MaterialTheme.typography.titleMedium,
                    color = PrismBlue,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${sessions.size} ${if (sessions.size == 1) "conversation" else "conversations"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Button(
                enabled = !isGenerating,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                onClick = onNewChat,
            ) {
                Text("+ New", maxLines = 1, softWrap = false)
            }
        }

        if (sessions.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search conversations...") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }

        if (filteredSessions.isEmpty()) {
            Text(
                text = if (sessions.isEmpty()) "No chats yet" else "No matching conversations",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filteredSessions, key = { it.id }) { session ->
                    ChatSessionRow(
                        session = session,
                        selected = session.id == currentChatId,
                        isGenerating = isGenerating,
                        onOpen = { onSwitchChat(session.id) },
                        onRename = {
                            renameTarget = session
                            renameTitle = session.title
                        },
                        onDelete = { deleteTarget = session },
                        onClearCurrent = { clearCurrentRequested = true },
                    )
                }
            }
        }
    }

    renameTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = renameTitle,
                    onValueChange = { renameTitle = it },
                    singleLine = true,
                    label = { Text("Title") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameTitle.isNotBlank(),
                    onClick = {
                        onRenameChat(session.id, renameTitle)
                        renameTarget = null
                    },
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete chat?") },
            text = {
                Text(
                    text = "This removes the local transcript for \"${session.title}\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isGenerating,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        onDeleteChat(session.id)
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

    if (clearCurrentRequested) {
        AlertDialog(
            onDismissRequest = { clearCurrentRequested = false },
            title = { Text("Clear current chat?") },
            text = {
                Text(
                    text = "This removes the messages in the active chat but keeps the chat itself.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = hasCurrentTranscript && !isGenerating,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        onClearCurrentChat()
                        clearCurrentRequested = false
                    },
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearCurrentRequested = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ChatSessionRow(
    session: ChatSession,
    selected: Boolean,
    isGenerating: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClearCurrent: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) PrismViolet.copy(alpha = 0.50f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        shadowElevation = 0.dp,
        enabled = !isGenerating,
        onClick = {
            if (!selected) {
                onOpen()
            }
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            brush = Brush.verticalGradient(listOf(PrismCyan, PrismViolet)),
                            shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                        ),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = if (selected) 12.dp else 14.dp,
                        top = 12.dp,
                        end = 10.dp,
                        bottom = 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = polishedChatTitle(session.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        ActiveBadge()
                    }
                    Box {
                        ChatOverflowButton(
                            enabled = !isGenerating,
                            onClick = { menuExpanded = true },
                        )
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                enabled = !isGenerating,
                                onClick = {
                                    menuExpanded = false
                                    onRename()
                                },
                            )
                            if (selected) {
                                DropdownMenuItem(
                                    text = { Text("Clear messages") },
                                    enabled = !isGenerating,
                                    onClick = {
                                        menuExpanded = false
                                        onClearCurrent()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                enabled = !isGenerating,
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
                ModelBadge(modelName = polishedModelName(session.modelId))
                Text(
                    text = "${session.messageCount} ${if (session.messageCount == 1) "message" else "messages"} • ${formatChatTimestamp(session.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActiveBadge() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = PrismViolet.copy(alpha = 0.10f),
        contentColor = PrismViolet,
        border = BorderStroke(1.dp, PrismViolet.copy(alpha = 0.24f)),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Canvas(modifier = Modifier.size(6.dp)) {
                drawCircle(color = PrismGreen)
            }
            Text(
                text = "Active",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ModelBadge(modelName: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = PrismBlue.copy(alpha = 0.08f),
        contentColor = PrismBlue,
        border = BorderStroke(1.dp, PrismBlue.copy(alpha = 0.14f)),
        shadowElevation = 0.dp,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            text = modelName,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ChatOverflowButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                contentDescription = "Chat options"
                role = Role.Button
            },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        enabled = enabled,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(18.dp)) {
                repeat(3) { index ->
                    drawCircle(
                        color = dotColor,
                        radius = size.minDimension * 0.08f,
                        center = Offset(center.x, size.height * (0.28f + index * 0.22f)),
                    )
                }
            }
        }
    }
}
