package com.openchatai.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openchatai.app.ui.theme.AppMotion
import com.openchai.core.model.AgentStep
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Role
import com.openchai.core.model.StepState

/*
 * Catatan warna: literal hijau lama (StepDoneGreen) dihapus — status langkah
 * agent kini memakai peran colorScheme M3 (CheckCircle = secondary, Warning =
 * error) sehingga ikut skema terang/gelap & dynamic color.
 */

/**
 * Satu unit pesan chat — Material Design 3:
 *  - USER          → bubble kanan (primaryContainer + onPrimaryContainer),
 *    bentuk shapes.large dengan sudut "ekor" asimetris di sisi pengirim.
 *  - ASSISTANT     → bubble kiri (surfaceContainerHigh + onSurface) berisi
 *    MarkdownText; aksi (copy/regenerate) lewat DropdownMenu berikon.
 *  - ASSISTANT err → bubble error (errorContainer + onErrorContainer) dengan
 *    tombol Retry.
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
                    .clip(MaterialTheme.shapes.medium)
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
            // Bentuk bubble: radius 20dp (= shapes.large) dengan sudut ekor 4dp
            // asimetris mengarah ke pengirim (user kanan, AI kiri) — pola M3.
            val bubbleShape = if (isUser) {
                RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp,
                    bottomEnd = 4.dp, bottomStart = 20.dp
                )
            } else {
                RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp,
                    bottomEnd = 20.dp, bottomStart = 4.dp
                )
            }
            // Warna bubble berbasis tonal M3 (tanpa border — hierarki dari warna).
            val container = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                message.isError -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            }
            Surface(
                modifier = Modifier
                    .align(if (isUser) Alignment.CenterEnd else Alignment.CenterStart)
                    .widthIn(max = maxBubbleWidth)
                    .clip(bubbleShape)
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
                shape = bubbleShape,
                color = container
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    when {
                        message.isError -> {
                            Text(
                                text = message.content,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = onRetry) {
                                Text("Retry", color = MaterialTheme.colorScheme.error)
                            }
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
            icon = {
                // Ikon tonal M3 di atas judul dialog.
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(22.dp)
                    )
                }
            },
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
 * streaming ChatScreen) — pola kartu collapsible M3:
 *  - Header: ikon Bolt dalam lingkaran secondaryContainer + judul titleSmall +
 *    spinner kecil saat live.
 *  - Toggle detail: ExpandMore yang berotasi (animateFloatAsState + AppMotion).
 *  - Konten expand: area surfaceContainerLow, muncul dengan fade + expand
 *    vertikal memakai token [AppMotion].
 *
 * Tampilan default RINGKAS: satu langkah = satu baris (ikon status ✓/⏳/✗ +
 * label). Bila ada langkah ber-detail, tombol "View execution details"
 * membuka area detail per kartu: daftar detail monospace dengan tinggi
 * maksimum + scroll.
 *
 * Catatan: menggantikan pemakaian langsung AgentActivityCard di chat tanpa
 * mengubah signature [MessageBubble].
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
    // Rotasi chevron ExpandMore: 0° (tertutup) → 180° (terbuka), easing M3.
    val chevronRotation by animateFloatAsState(
        targetValue = if (detailsOpen) 180f else 0f,
        animationSpec = AppMotion.standardTween(),
        label = "chevronRotation"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header kartu: badge tonal Bolt + judul + indikator live.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(16.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (hasRunning && isLive) "🤖 Working on your project…" else "Agent activity",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                if (hasRunning && isLive) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
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
                    // Chevron berotasi mengikuti status expand (motion M3).
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = chevronRotation }
                    )
                }
                // Konten expand: fade + expand vertikal dengan token AppMotion.
                AnimatedVisibility(
                    visible = detailsOpen,
                    enter = fadeIn(AppMotion.standardTween()) +
                        expandVertically(AppMotion.standardTween()),
                    exit = fadeOut(AppMotion.standardTween()) +
                        shrinkVertically(AppMotion.standardTween())
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
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

/** Ikon status satu langkah: DONE ✓ (secondary), RUNNING spinner, FAILED warning (error). */
@Composable
private fun StepStateIcon(state: StepState) {
    when (state) {
        StepState.DONE -> Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = "Done",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp)
        )
        StepState.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        StepState.FAILED -> Icon(
            imageVector = Icons.Rounded.Warning,
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
        DropdownMenuItem(
            text = { Text("Copy") },
            leadingIcon = {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            },
            onClick = { onDismiss(); onCopy() }
        )
        if (showEdit) {
            DropdownMenuItem(
                text = { Text("Edit") },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = { onDismiss(); onEdit() }
            )
        }
        if (showRegenerate) {
            DropdownMenuItem(
                text = { Text("Regenerate") },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = { onDismiss(); onRegenerate() }
            )
        }
    }
}
