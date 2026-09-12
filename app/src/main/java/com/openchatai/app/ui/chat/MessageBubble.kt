package com.openchatai.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Role

/**
 * Satu unit pesan chat:
 *  - USER          → bubble kanan (primaryContainer), teks polos.
 *  - ASSISTANT     → bubble kiri (surface + outline) berisi MarkdownText.
 *  - ASSISTANT err → bubble error (surfaceVariant + border error) dengan tombol Retry.
 *  - Agent activity→ AgentActivityCard (isLive saat kartu terakhir & sedang generating).
 *
 * Long-press (combinedClickable) → DropdownMenu: Copy (semua pesan),
 * Edit (hanya user → dialog → onEdit(id, text)), Regenerate (assistant terakhir).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    isLastAssistant: Boolean,
    isGenerating: Boolean,
    fontScale: Float,
    onCopy: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onRegenerate: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == Role.USER
    val copyText = if (message.isAgentActivity) {
        message.steps.joinToString("\n") { step ->
            buildString {
                append("• ").append(step.label)
                step.detail?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            }
        }
    } else {
        message.content
    }

    var menuOpen by remember(message.id) { mutableStateOf(false) }
    var editOpen by remember(message.id) { mutableStateOf(false) }
    var editText by remember(message.id) { mutableStateOf(message.content) }

    val showEdit = isUser
    val showRegenerate = !isUser && !message.isError && !message.isAgentActivity &&
        isLastAssistant && !isGenerating

    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.85f).dp

    Box(modifier = modifier.fillMaxWidth()) {
        if (message.isAgentActivity) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
            ) {
                AgentActivityCard(
                    steps = message.steps,
                    isLive = isGenerating && isLastAssistant,
                    modifier = Modifier.fillMaxWidth()
                )
                MessageMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    showEdit = false,
                    showRegenerate = false,
                    onCopy = { onCopy(copyText) },
                    onEdit = {},
                    onRegenerate = {}
                )
            }
        } else {
            val shape = RoundedCornerShape(18.dp)
            val container = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                message.isError -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            }
            val border = when {
                isUser -> null
                message.isError -> BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f))
                else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
            Surface(
                modifier = Modifier
                    .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                    .widthIn(max = maxBubbleWidth)
                    .clip(shape)
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
                shape = shape,
                color = container,
                border = border
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    when {
                        message.isError -> {
                            Text(
                                text = message.content,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = onRetry) { Text("Retry") }
                        }
                        isUser -> {
                            Text(
                                text = message.content,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = (16f * fontScale).sp,
                                    lineHeight = (24f * fontScale).sp
                                )
                            )
                        }
                        else -> MarkdownText(content = message.content, fontScale = fontScale)
                    }
                    MessageMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        showEdit = showEdit,
                        showRegenerate = showRegenerate,
                        onCopy = { onCopy(copyText) },
                        onEdit = { editOpen = true },
                        onRegenerate = onRegenerate
                    )
                }
            }
        }
    }

    if (editOpen) {
        AlertDialog(
            onDismissRequest = { editOpen = false },
            title = { Text("Edit message") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editOpen = false
                        val newText = editText.trim()
                        if (newText.isNotEmpty() && newText != message.content) {
                            onEdit(message.id, newText)
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editOpen = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MessageMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    showEdit: Boolean,
    showRegenerate: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Copy") }, onClick = { onDismiss(); onCopy() })
        if (showEdit) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { onDismiss(); onEdit() })
        }
        if (showRegenerate) {
            DropdownMenuItem(
                text = { Text("Regenerate") },
                onClick = { onDismiss(); onRegenerate() }
            )
        }
    }
}
