package com.openchatai.app.ui.sessions

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchatai.app.background.SessionGenState
import com.openchatai.app.ui.chat.ChatViewModel
import com.openchatai.app.ui.theme.AppMotion
import com.openchai.core.model.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drawer "Chats" (daftar sesi, mirip UI ChatGPT) — komponen Material 3 penuh:
 *  - ModalDrawerSheet + header judul titleLarge dengan ikon tonal ChatBubble.
 *  - Tombol "+ New chat" FilledTonalButton (bentuk dari MaterialTheme.shapes).
 *  - Daftar sesi berupa ListItem M3: leading ikon ChatBubbleOutline tonal,
 *    sesi AKTIF diberi latar primaryContainer, trailing menu (MoreVert) untuk
 *    Rename/Delete (ikon Rounded/Outlined, delete memakai DeleteOutline).
 *  - Sesi yang sedang generating (SessionGenState.Running) menampilkan
 *    indikator hidup: spinner kecil + titik berdenyut + "generating…".
 *
 * Dipasang NavGraph sebagai drawerContent ModalNavigationDrawer. Sumber data
 * ChatViewModel; aksi (pilih/buat/rename/hapus) lewat method VM yang sudah ada.
 * Perubahan tinggi konten drawer dianimasikan animateContentSize dengan token
 * motion M3 (AppMotion.standardTween).
 */
@Composable
fun SessionsDrawer(
    chatViewModel: ChatViewModel,
    drawerState: DrawerState,
    scope: CoroutineScope,
    onNavigateChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val conversations by chatViewModel.conversations.collectAsStateWithLifecycle()
    val activeId by chatViewModel.activeConversationId.collectAsStateWithLifecycle()
    val genStates by chatViewModel.genStates.collectAsStateWithLifecycle()

    // State dialog di level drawer agar hanya satu dialog aktif pada satu waktu.
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    ModalDrawerSheet(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .animateContentSize(animationSpec = AppMotion.standardTween())
        ) {
            // ---------------- Header: ikon tonal + judul + tombol chat baru ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ikon tonal header: kotak primaryContainer berisi ikon ChatBubble.
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        Icons.Rounded.ChatBubble,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Chats",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            FilledTonalButton(
                onClick = {
                    chatViewModel.createNewConversation()
                    closeDrawer()
                    onNavigateChat()
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(44.dp)
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("New chat")
            }

            Spacer(Modifier.height(8.dp))

            // ---------------- Daftar sesi (updatedAt DESC) ----------------
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    conversations.sortedByDescending { it.updatedAt },
                    key = { it.id }
                ) { convo ->
                    SessionDrawerItem(
                        conversation = convo,
                        isActive = convo.id == activeId,
                        isRunning = genStates[convo.id] is SessionGenState.Running,
                        onClick = {
                            chatViewModel.selectConversation(convo.id)
                            closeDrawer()
                            onNavigateChat()
                        },
                        onRename = { renaming = convo },
                        onDelete = { deleting = convo }
                    )
                }
            }
        }
    }

    // ---------------- Dialog Rename ----------------
    renaming?.let { convo ->
        var title by remember(convo.id) { mutableStateOf(convo.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("Title") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        chatViewModel.renameConversation(convo.id, title)
                        renaming = null
                    },
                    enabled = title.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            }
        )
    }

    // ---------------- Dialog Delete (konfirmasi) ----------------
    deleting?.let { convo ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chat") },
            text = {
                Text("Delete \"${convo.title}\"? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        chatViewModel.deleteConversation(convo.id)
                        deleting = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Satu baris sesi di drawer memakai ListItem M3: ikon ChatBubbleOutline tonal,
 * judul + waktu relatif (+ indikator generating), menu Rename/Delete.
 * Sesi aktif diberi latar primaryContainer dengan teks onPrimaryContainer.
 */
@Composable
private fun SessionDrawerItem(
    conversation: Conversation,
    isActive: Boolean,
    isRunning: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember(conversation.id) { mutableStateOf(false) }

    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        // Latar baris: aktif = primaryContainer (peran M3), lainnya transparan.
        colors = ListItemDefaults.colors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            }
        ),
        leadingContent = {
            // Leading ikon tonal: kotak kecil berisi ChatBubbleOutline.
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isActive) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ) {
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .padding(6.dp)
                        .size(18.dp)
                )
            }
        },
        headlineContent = {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = relativeTime(conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (isRunning) {
                    Spacer(Modifier.width(8.dp))
                    GeneratingBadge()
                }
            }
        },
        trailingContent = {
            // Anchor DropdownMenu: aksi Rename/Delete per sesi dipertahankan.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Opsi sesi",
                        tint = if (isActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    )
}

/** Indikator hidup sesi generating: spinner 14dp + titik berdenyut + label. */
@Composable
private fun GeneratingBadge(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "generatingPulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Token motion M3 untuk denyut indikator.
            animation = AppMotion.standardTween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "generatingAlpha"
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(6.dp))
        // Titik berdenyut memakai peran primary dari colorScheme M3.
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                    CircleShape
                )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "generating…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Waktu relatif berbahasa Indonesia: "Baru saja", "x mnt", "x jam", "x hari". */
private fun relativeTime(updatedAt: Long): String {
    val diffMinutes = ((System.currentTimeMillis() - updatedAt) / 60_000L).coerceAtLeast(0)
    return when {
        diffMinutes < 1 -> "Baru saja"
        diffMinutes < 60 -> "$diffMinutes mnt"
        diffMinutes < 24 * 60 -> "${diffMinutes / 60} jam"
        else -> "${diffMinutes / (24 * 60)} hari"
    }
}
