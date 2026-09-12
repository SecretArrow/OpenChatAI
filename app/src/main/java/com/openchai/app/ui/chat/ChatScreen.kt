package com.openchai.app.ui.chat

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchai.app.ui.components.StatusDot
import com.openchai.app.ui.theme.TerminalYellow
import com.openchai.core.model.Role
import com.openchai.app.ui.terminal.TerminalPanel

/** Slash command cepat: chip di atas input bar, mengisi prompt yang lebih lengkap. */
private val SLASH_COMMANDS = listOf(
    "/fix" to "Find the error in this project, fix it, and verify the result.",
    "/test" to "Run the project tests, summarize the failures, and propose fixes.",
    "/commit" to "Create a clean git commit for the current changes with a good message.",
    "/explain" to "Explain the structure of this project in short bullets.",
    "/review" to "Review the recent changes in this project and suggest improvements."
)

/**
 * Pusat pengalaman aplikasi: chat AI coding agent.
 *
 * Struktur (Column):
 *  - Header: judul "Open Chat AI" + tombol Settings.
 *  - Chip selector: Project & Model (buka ModalBottomSheet).
 *  - Banner status kecil (offline / engine fallback) — hanya saat perlu.
 *  - LazyColumn pesan (weight 1f) + EmptyChatState saat kosong + TypingIndicator saat menunggu.
 *  - ChatInputBar.
 *  - Toggle bar Terminal (slim) + TerminalPanel.
 *  - Sheets: ModelSelectorSheet & ProjectSelectorSheet.
 */
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by chatViewModel.messages.collectAsStateWithLifecycle()
    val isGenerating by chatViewModel.isGenerating.collectAsStateWithLifecycle()
    val networkAvailable by chatViewModel.networkAvailable.collectAsStateWithLifecycle()
    val engineStatus by chatViewModel.engineStatus.collectAsStateWithLifecycle()
    val settings by chatViewModel.settings.collectAsStateWithLifecycle()
    val activeProject by chatViewModel.activeProject.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var showModelSheet by remember { mutableStateOf(false) }
    var showProjectSheet by remember { mutableStateOf(false) }
    var terminalVisible by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    val lastMessage = messages.lastOrNull()
    val lastAssistantId = messages.lastOrNull { it.role == Role.ASSISTANT }?.id
    val context = LocalContext.current

    fun exportConversationMarkdown() {
        if (messages.isEmpty()) return
        val md = buildString {
            appendLine("# Open Chat AI — conversation export")
            appendLine()
            val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            appendLine(
                "_Project: ${activeProject?.name?.takeIf { it.isNotBlank() } ?: "-"} · " +
                    "Model: ${settings.selectedModel.ifBlank { "auto" }} · ${now}_"
            )
            appendLine()
            messages.forEach { m ->
                when {
                    m.isAgentActivity -> m.steps.forEach { s -> appendLine("- ${s.label}") }
                    m.role == Role.USER -> {
                        appendLine("**You:**")
                        appendLine()
                        appendLine(m.content)
                    }
                    else -> {
                        appendLine("**Assistant:**")
                        appendLine()
                        appendLine(m.content)
                    }
                }
                appendLine()
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Open Chat AI — conversation export")
            putExtra(Intent.EXTRA_TEXT, md)
        }
        context.startActivity(Intent.createChooser(send, "Export conversation"))
    }

    // Typing indicator hanya saat sedang generate dan belum ada delta jawaban terlihat
    // (pesan terakhir masih milik user, kartu aktivitas agent, atau konten masih kosong).
    val showTyping = isGenerating && (
        lastMessage == null ||
            lastMessage.role == Role.USER ||
            lastMessage.isAgentActivity ||
            lastMessage.content.isBlank()
        )
    val itemCount = if (messages.isEmpty()) 1 else messages.size + (if (showTyping) 1 else 0)
    val lastContentLength = lastMessage?.content?.length ?: 0

    // Auto-scroll pintar: ikuti bawah saat generating, atau bila user sudah dekat bawah.
    LaunchedEffect(messages.size, lastContentLength, isGenerating) {
        if (messages.isEmpty()) return@LaunchedEffect
        val target = itemCount - 1
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible < 0 || lastVisible >= target - 1
        if (isGenerating || nearBottom) {
            listState.animateScrollToItem(target)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- Header ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Open Chat AI",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { exportConversationMarkdown() }) {
                Icon(Icons.Filled.Share, contentDescription = "Export chat as Markdown")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        // ---------------- Selector chips ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { showProjectSheet = true },
                label = {
                    Text(
                        text = activeProject?.name?.takeIf { it.isNotBlank() }
                            ?.let { "Project: $it" } ?: "Select project",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            AssistChip(
                onClick = { showModelSheet = true },
                label = {
                    Text(
                        text = "Model: ${settings.selectedModel.ifBlank { "auto" }}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }

        // ---------------- Status banners (hanya saat perlu) ----------------
        if (!networkAvailable) {
            StatusBanner(text = "⚠ Offline", dotColor = MaterialTheme.colorScheme.error)
        }
        val engineMessage = engineStatus.message
        if (engineMessage.contains("unavailable", ignoreCase = true) ||
            engineMessage.contains("fallback", ignoreCase = true)
        ) {
            StatusBanner(text = engineMessage, dotColor = TerminalYellow)
        }

        // ---------------- Messages ----------------
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item(key = "empty") {
                    EmptyChatState(onSuggestion = chatViewModel::send)
                }
            } else {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        isLastAssistant = message.id == lastAssistantId,
                        isGenerating = isGenerating,
                        fontScale = settings.chatFontScale,
                        onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                        onEdit = { id, newText -> chatViewModel.editMessage(id, newText) },
                        onRegenerate = chatViewModel::regenerate,
                        onRetry = chatViewModel::retryLast
                    )
                }
                if (showTyping) {
                    item(key = "typing") {
                        TypingIndicator()
                    }
                }
            }
        }

        // ---------------- Slash command chips ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SLASH_COMMANDS.forEach { (cmd, prompt) ->
                AssistChip(
                    onClick = { inputText = prompt },
                    label = {
                        Text(
                            text = cmd,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
        }

        // ---------------- Input ----------------
        ChatInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            onSend = {
                if (inputText.isNotBlank() && !isGenerating) {
                    chatViewModel.send(inputText)
                    inputText = ""
                }
            },
            isGenerating = isGenerating,
            onStop = chatViewModel::stopGeneration,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        // ---------------- Terminal toggle ----------------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clickable { terminalVisible = !terminalVisible }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Terminal",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (terminalVisible) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = if (terminalVisible) "Hide terminal" else "Show terminal"
                )
            }
        }
        TerminalPanel(visible = terminalVisible, onCollapse = { terminalVisible = false })

        // ---------------- Sheets ----------------
        ModelSelectorSheet(
            visible = showModelSheet,
            onDismiss = { showModelSheet = false },
            vm = chatViewModel
        )
        ProjectSelectorSheet(
            visible = showProjectSheet,
            onDismiss = { showProjectSheet = false },
            onManageProjects = onOpenProjects,
            vm = chatViewModel
        )
    }
}

/** Banner status kecil: Surface + StatusDot + teks. */
@Composable
private fun StatusBanner(text: String, dotColor: Color) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(dotColor)
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
