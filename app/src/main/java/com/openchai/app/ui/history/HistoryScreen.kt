package com.openchai.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchai.app.background.SessionGenState
import com.openchai.app.ui.chat.ChatViewModel
import com.openchai.core.model.Conversation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Riwayat percakapan: pencarian, pengelompokan Today / Yesterday /
 * Previous 7 days / Older, membuka percakapan, dan menghapusnya.
 */
@Composable
fun HistoryScreen(
    chatViewModel: ChatViewModel,
    onOpenConversation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val conversations by chatViewModel.conversations.collectAsStateWithLifecycle()
    val activeId by chatViewModel.activeConversationId.collectAsStateWithLifecycle()
    val genStates by chatViewModel.genStates.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        chatViewModel.refreshHistory()
    }

    val filtered = conversations
        .filter { convo ->
            query.isBlank() || convo.title.contains(query, ignoreCase = true)
        }
        .sortedByDescending { it.updatedAt }

    val groups: List<Pair<String, List<Conversation>>> = remember(filtered) {
        val buckets = linkedMapOf(
            "Today" to mutableListOf<Conversation>(),
            "Yesterday" to mutableListOf<Conversation>(),
            "Previous 7 days" to mutableListOf<Conversation>(),
            "Older" to mutableListOf<Conversation>()
        )
        filtered.forEach { convo ->
            buckets[groupLabel(convo.updatedAt)]?.add(convo)
        }
        buckets.filter { it.value.isNotEmpty() }.map { it.key to it.value.toList() }
    }

    Column(modifier = modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "History",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                chatViewModel.createNewConversation()
                onOpenConversation()
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("New chat")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            singleLine = true,
            placeholder = { Text("Search conversations…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
        )

        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (query.isBlank()) {
                        "Belum ada percakapan.\nMulai chat baru — agent siap membantu."
                    } else {
                        "Tidak ada hasil untuk \"$query\"."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                groups.forEach { (label, groupItems) ->
                    item(key = "header_$label") {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(groupItems, key = { it.id }) { convo ->
                        ConversationRow(
                            conversation = convo,
                            isActive = convo.id == activeId,
                            isGenerating = genStates[convo.id] is SessionGenState.Running,
                            onOpen = {
                                chatViewModel.selectConversation(convo.id)
                                onOpenConversation()
                            },
                            onDelete = { chatViewModel.deleteConversation(convo.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    isActive: Boolean,
    isGenerating: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.DateRange,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Updated " + formatTime(conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Indikator kecil sesi yang sedang generating di background.
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(12.dp),
                    strokeWidth = 2.dp
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete conversation",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun groupLabel(updatedAt: Long): String {
    val a = Calendar.getInstance().apply { timeInMillis = updatedAt }
    val b = Calendar.getInstance()
    val dayA = a.get(Calendar.DAY_OF_YEAR) + a.get(Calendar.YEAR) * 1000
    val dayB = b.get(Calendar.DAY_OF_YEAR) + b.get(Calendar.YEAR) * 1000
    val diff = (dayB - dayA).coerceAtLeast(0)
    return when {
        diff < 1 -> "Today"
        diff < 2 -> "Yesterday"
        diff < 7 -> "Previous 7 days"
        else -> "Older"
    }
}

private fun formatTime(timestamp: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    val sameDay = cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) &&
        cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    return if (sameDay) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    } else {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
