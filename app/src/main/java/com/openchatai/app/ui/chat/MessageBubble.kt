package com.openchatai.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openchai.core.model.AgentStep
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Role
import com.openchai.core.model.StepState

private val StepDoneGreen = Color(0xFF3FB950)

/**
 * Satu unit pesan chat:
 *  - USER          → bubble kanan (primaryContainer), teks polos.
 *  - ASSISTANT     → bubble kiri (surface + outline) berisi MarkdownText.
 *  - ASSISTANT err → bubble error (surfaceVariant + border error) dengan tombol Retry.
 *  - Agent activity→ CollapsibleAgentActivityCard (isLive saat kartu terakhir &
 *    sedang generating) — langkah ringkas + detail collapsible.
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
                CollapsibleAgentActivityCard(
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

/**
 * Kartu aktivitas agent versi collapsible (pemakai: MessageBubble & blok
 * streaming ChatScreen). Tampilan default RINGKAS: satu langkah = satu baris
 * (ikon status ✓/⏳/✗ + label). Bila ada langkah ber-detail, tombol
 * "View execution details" membuka area detail per kartu: daftar detail
 * monospace dengan tinggi maksimum + scroll.
 *
 * Catatan: menggantikan pemakaian langsung AgentActivityCard di chat tanpa
 * mengubah file tersebut (bukan milik 11-d) dan tanpa mengubah signature
 * [MessageBubble].
 */
@Composable
internal fun CollapsibleAgentActivityCard(
    steps: List<AgentStep>,
    isLive: Boolean,
    modifier: Modifier = Modifier
) {
    val hasRunning = steps.any { it.state == StepState.RUNNING }
    val detailSteps = remember(steps) { steps.filter { !it.detail.isNullOrBlank() } }
    var detailsOpen by remember(steps) { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (hasRunning && isLive) "🤖 Working on your project…" else "Agent activity",
                style = MaterialTheme.typography.titleMedium
            )
            // Baris ringkas: satu langkah = satu baris (ikon status + label).
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepStateIcon(step.state)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = step.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // Detail eksekusi: hanya bila ada langkah dengan detail non-blank.
            if (detailSteps.isNotEmpty()) {
                TextButton(
                    onClick = { detailsOpen = !detailsOpen },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = if (detailsOpen) "Hide execution details" else "View execution details",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (detailsOpen) {
                            Icons.Filled.KeyboardArrowUp
                        } else {
                            Icons.Filled.KeyboardArrowDown
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (detailsOpen) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            detailSteps.forEach { step ->
                                Text(
                                    text = "• ${step.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = step.detail.orEmpty(),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Ikon status satu langkah: DONE ✓ hijau, RUNNING spinner, FAILED warning. */
@Composable
private fun StepStateIcon(state: StepState) {
    when (state) {
        StepState.DONE -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Done",
            tint = StepDoneGreen,
            modifier = Modifier.size(16.dp)
        )
        StepState.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        StepState.FAILED -> Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
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
