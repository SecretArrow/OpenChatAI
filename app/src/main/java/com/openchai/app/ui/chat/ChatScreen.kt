package com.openchai.app.ui.chat

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchai.app.background.SessionGenState
import com.openchai.app.ui.components.StatusDot
import com.openchai.app.ui.theme.TerminalYellow
import com.openchai.core.agent.PermissionMode
import com.openchai.core.model.Role
import com.openchai.core.settings.EngineMode
import com.openchai.app.ui.terminal.TerminalPanel

/** Slash command cepat: chip di atas input bar, mengisi prompt yang lebih lengkap. */
private val SLASH_COMMANDS = listOf(
    "/fix" to "Find the error in this project, fix it, and verify the result.",
    "/test" to "Run the project tests, summarize the failures, and propose fixes.",
    "/commit" to "Create a clean git commit for the current changes with a good message.",
    "/explain" to "Explain the structure of this project in short bullets.",
    "/review" to "Review the recent changes in this project and suggest improvements."
)

/** Opsi mode izin untuk ModeSwitcherChip (urutan tampil di dropdown). */
private val PERMISSION_MODE_OPTIONS = listOf(
    PermissionMode.ASK to "Ask",
    PermissionMode.PLAN to "Plan",
    PermissionMode.AUTO_READ_EDIT to "Edit",
    PermissionMode.FULL_ACCESS to "YOLO"
)

private fun PermissionMode.chatLabel(): String =
    PERMISSION_MODE_OPTIONS.firstOrNull { it.first == this }?.second ?: name

/**
 * Pusat pengalaman aplikasi: chat AI coding agent (multi-sesi paralel).
 *
 * Struktur (Column):
 *  - Header: tombol menu (buka drawer "Chats") + judul + tombol Settings.
 *  - Chip selector: Project & Model (buka ModalBottomSheet).
 *  - Banner status kecil (offline / engine fallback) — hanya saat perlu.
 *  - LazyColumn pesan (weight 1f) + EmptyChatState saat kosong + blok streaming
 *    (kartu aktivitas agent + partial answer + TypingIndicator) saat sesi aktif
 *    masih Running di GenerationManager.
 *  - ChatInputBar.
 *  - Toggle bar Terminal (slim) + TerminalPanel.
 *  - Sheets: ModelSelectorSheet & ProjectSelectorSheet.
 */
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    onOpenSessions: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkspaceSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val messages by chatViewModel.messages.collectAsStateWithLifecycle()
    val activeId by chatViewModel.activeConversationId.collectAsStateWithLifecycle()
    val genStates by chatViewModel.genStates.collectAsStateWithLifecycle()
    val networkAvailable by chatViewModel.networkAvailable.collectAsStateWithLifecycle()
    val engineStatus by chatViewModel.engineStatus.collectAsStateWithLifecycle()
    val settings by chatViewModel.settings.collectAsStateWithLifecycle()
    val activeProject by chatViewModel.activeProject.collectAsStateWithLifecycle()
    val pendingPermission by chatViewModel.pendingPermission.collectAsStateWithLifecycle()
    val planApproval by chatViewModel.planApproval.collectAsStateWithLifecycle()

    // Gating workspace: engine AGENT butuh folder proyek aktif.
    val workspaceGate = settings.engineMode == EngineMode.AGENT && activeProject == null

    // State generasi sesi AKTIF saja (sesi lain tetap jalan di background).
    val activeRunning = activeId?.let { genStates[it] } as? SessionGenState.Running
    val isGenerating = activeRunning != null

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

    // Blok streaming overlay: tampil hanya saat sesi aktif masih Running.
    // Pesan permanen tetap datang dari _messages setelah manager mempersist.
    val streamingVisible = activeRunning != null
    val streamProgress = (activeRunning?.steps?.size ?: 0) +
        (activeRunning?.partialText?.length ?: 0)
    val itemCount = messages.size +
        (if (messages.isEmpty()) 1 else 0) +
        (if (streamingVisible) 1 else 0)
    val lastContentLength = lastMessage?.content?.length ?: 0

    // Auto-scroll pintar: ikuti bawah saat generating, atau bila user sudah dekat bawah.
    LaunchedEffect(messages.size, lastContentLength, streamProgress, isGenerating) {
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
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Buka drawer "Chats" (daftar sesi).
            IconButton(onClick = onOpenSessions) {
                Icon(Icons.Filled.Menu, contentDescription = "Open chats list")
            }
            // Mode switcher izin (gaya Claude Code): chip + dropdown radio.
            ModeSwitcherChip(
                current = settings.permissionMode,
                onSelect = chatViewModel::setPermissionMode
            )
            Text(
                text = "Open Chat AI",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

        // ---------------- Messages / workspace gate ----------------
        if (workspaceGate) {
            // Overlay full-column: agent butuh workspace sebelum bisa jalan
            // (menggantikan EmptyChatState).
            WorkspaceGate(
                onPickFolder = onOpenWorkspaceSetup,
                onUseAppWorkspace = chatViewModel::createNewAppWorkspace,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
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
                }
                // Streaming overlay sesi aktif (Running): steps + partial + typing.
                // TIDAK menambah pesan permanen — pesan final dimuat VM dari store
                // saat state terminal (Done/Failed/Cancelled).
                if (streamingVisible && activeRunning != null) {
                    item(key = "streaming") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (activeRunning.steps.isNotEmpty()) {
                                AgentActivityCard(steps = activeRunning.steps, isLive = true)
                            }
                            if (activeRunning.partialText.isNotBlank()) {
                                MarkdownText(
                                    content = activeRunning.partialText,
                                    fontScale = settings.chatFontScale
                                )
                            }
                            TypingIndicator()
                        }
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

        // ---------------- Plan approval (mode PLAN selesai) ----------------
        if (planApproval) {
            PlanApprovalCard(
                onApprove = chatViewModel::approvePlan,
                onDismiss = chatViewModel::dismissPlanApproval,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
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

        // ---------------- Permission dialog (di atas semua content) ----------------
        pendingPermission?.let { request ->
            PermissionDialog(
                request = request,
                onDecision = { decision ->
                    chatViewModel.respondPermission(request.id, decision)
                }
            )
        }
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

/**
 * Mode switcher izin di header (dekat tombol menu): AssistChip berlabel mode
 * saat ini + panah → DropdownMenu 4 opsi dengan leading radio. Pilihan
 * diteruskan ke [ChatViewModel.setPermissionMode] (persist di AppSettings).
 * Tidak memakai SegmentedButton (kompatibilitas material3).
 */
@Composable
private fun ModeSwitcherChip(current: PermissionMode, onSelect: (PermissionMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = {
                Text(
                    text = current.chatLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PERMISSION_MODE_OPTIONS.forEach { (mode, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = {
                        RadioButton(
                            selected = mode == current,
                            onClick = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    }
                )
            }
        }
    }
}

/**
 * Kartu plan approval: tampil setelah run PLAN mode selesai (vm.planApproval).
 * Approve menaikkan mode ke AUTO_READ_EDIT lalu run ulang sesi aktif.
 */
@Composable
private fun PlanApprovalCard(
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Plan ready — approve to execute with edits auto-accepted?",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onApprove) {
                    Text("Approve & Execute")
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

/**
 * Workspace gate (engineMode AGENT tanpa proyek aktif): overlay full-column
 * pengganti EmptyChatState — agent menolak jalan tanpa folder proyek.
 */
@Composable
private fun WorkspaceGate(
    onPickFolder: () -> Unit,
    onUseAppWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(modifier = Modifier.padding(18.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Create a workspace first",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Agent mode works inside a project folder so it can read and " +
                "edit real files. Pick a device folder, or create an app-private " +
                "workspace now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickFolder) {
            Text("Pick a device folder")
        }
        TextButton(onClick = onUseAppWorkspace) {
            Text("Use app-private workspace")
        }
    }
}
