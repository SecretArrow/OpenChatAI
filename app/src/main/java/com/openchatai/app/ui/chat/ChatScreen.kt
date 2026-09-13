package com.openchatai.app.ui.chat

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.openchatai.app.background.SessionGenState
import com.openchatai.app.ui.components.StatusDot
import com.openchatai.app.ui.theme.AppMotion
import com.openchatai.app.ui.theme.TerminalYellow
import com.openchatai.app.ui.terminal.TerminalActivity
import com.openchai.core.agent.PermissionMode
import com.openchai.core.model.Role
import com.openchai.core.settings.EngineMode
import kotlinx.coroutines.launch

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

/**
 * Aksi cepat agent di header (menu ikon tools). Nama persis dikirim ke
 * [ChatViewModel.runAgentAction] (implementasi MAIN — kontrak Task 11).
 */
private val AGENT_MENU_ACTIONS = listOf(
    "Fix", "Test", "Build", "Run", "Debug", "Explain", "Review", "Commit"
)

private fun PermissionMode.chatLabel(): String =
    PERMISSION_MODE_OPTIONS.firstOrNull { it.first == this }?.second ?: name

/**
 * Pusat pengalaman aplikasi: chat AI coding agent (multi-sesi paralel).
 *
 * Struktur (Column) — Material Design 3:
 *  - TopAppBar (containerColor = surface): tombol menu (drawer "Chats") +
 *    ModeSwitcherChip + judul "Workspace: <nama>" (fallback "Open Chat AI") +
 *    aksi agent (Bolt) + Terminal (Activity layar penuh terpisah) + menu
 *    MoreVert (Export as Markdown / Settings).
 *  - Chip selector: Project & Model (buka ModalBottomSheet).
 *  - Banner status kecil (offline / engine fallback) — hanya saat perlu.
 *  - LazyColumn pesan (weight 1f) + EmptyChatState saat kosong + blok streaming
 *    (kartu aktivitas agent + partial answer + TypingIndicator) saat sesi aktif
 *    masih Running di GenerationManager.
 *  - Chip "New messages" (AnimatedVisibility fade+expand) saat streaming & user
 *    tidak di dekat bawah.
 *  - ChatInputBar.
 *  - Sheets: ModelSelectorSheet & ProjectSelectorSheet.
 *
 * Catatan: terminal tidak lagi embedded (dipindah ke TerminalActivity);
 * [onOpenLinuxSetup] dipertahankan demi kompatibilitas call site NavGraph.
 * TopAppBar dipakai dengan windowInsets nol karena Scaffold di NavGraph sudah
 * memberikan padding status bar ke konten (mencegah insets dobel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel,
    onOpenSessions: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWorkspaceSetup: () -> Unit = {},
    modifier: Modifier = Modifier,
    onOpenLinuxSetup: () -> Unit = {}
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

    // Banner workspace (NON-blocking): mode AGENT tanpa proyek aktif tetap
    // menampilkan chat — aksi setup disediakan sebagai tombol di banner.
    val needsWorkspace = settings.engineMode == EngineMode.AGENT && activeProject == null

    // State generasi sesi AKTIF saja (sesi lain tetap jalan di background).
    val activeRunning = activeId?.let { genStates[it] } as? SessionGenState.Running
    val isGenerating = activeRunning != null

    val listState = rememberLazyListState()
    var showModelSheet by remember { mutableStateOf(false) }
    var showProjectSheet by remember { mutableStateOf(false) }
    var agentMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

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

    // "Dekat bawah" dihitung tiap frame dari layoutInfo (derivedStateOf):
    // dipakai untuk auto-follow DAN untuk menampilkan chip "New messages".
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            // Belum ada item ter-layout (mis. baru masuk layar) → anggap di bawah.
            lastVisible < 0 || lastVisible >= info.totalItemsCount - 2
        }
    }

    // Auto-scroll pintar: ikuti bawah HANYA bila user sudah dekat bawah.
    // Saat streaming dan user membaca ke atas → JANGAN paksa scroll; chip
    // "New messages" (di bawah) yang menawarkan lompatan manual.
    LaunchedEffect(messages.size, lastContentLength, streamProgress, isGenerating) {
        if (messages.isEmpty()) return@LaunchedEffect
        val target = itemCount - 1
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible < 0 || lastVisible >= target - 1
        if (nearBottom) {
            listState.animateScrollToItem(target)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ---------------- TopAppBar (header M3 di surface) ----------------
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Mode switcher izin (gaya Claude Code): chip + dropdown radio.
                    ModeSwitcherChip(
                        current = settings.permissionMode,
                        onSelect = chatViewModel::setPermissionMode
                    )
                    // Judul workspace aktif; fallback nama app bila belum ada workspace.
                    Text(
                        text = activeProject?.name?.takeIf { it.isNotBlank() }
                            ?.let { "Workspace: $it" } ?: "Open Chat AI",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                }
            },
            navigationIcon = {
                // Buka drawer "Chats" (daftar sesi).
                IconButton(onClick = onOpenSessions) {
                    Icon(Icons.Rounded.Menu, contentDescription = "Open chats list")
                }
            },
            actions = {
                // Aksi cepat agent (Fix/Test/Build/...) → runAgentAction.
                Box {
                    IconButton(onClick = { agentMenuOpen = true }) {
                        Icon(
                            Icons.Rounded.Bolt,
                            contentDescription = "Agent actions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AgentActionsMenu(
                        expanded = agentMenuOpen,
                        onDismiss = { agentMenuOpen = false },
                        onAction = { name ->
                            agentMenuOpen = false
                            chatViewModel.runAgentAction(name)
                        }
                    )
                }
                // Terminal layar penuh (Activity terpisah, bukan lagi panel embedded).
                IconButton(onClick = { context.startActivity(TerminalActivity.intent(context)) }) {
                    Icon(
                        Icons.Rounded.Terminal,
                        contentDescription = "Open terminal",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Menu lainnya: export markdown + settings.
                Box {
                    IconButton(onClick = { moreMenuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = moreMenuOpen,
                        onDismissRequest = { moreMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Export as Markdown") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Upload,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                moreMenuOpen = false
                                exportConversationMarkdown()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                moreMenuOpen = false
                                onOpenSettings()
                            }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            // Scaffold di NavGraph sudah memberi padding status bar → insets nol.
            windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
        )

        // ---------------- Selector chips ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
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
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Rounded.ArrowDropDown,
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
                leadingIcon = {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Rounded.ArrowDropDown,
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
            // Warna kuning terminal dipertahankan untuk banner engine (kontrak warna).
            StatusBanner(text = engineMessage, dotColor = TerminalYellow)
        }
        // Banner workspace: chat TETAP tampil (pesan & percakapan selalu bisa
        // dibuka); setup workspace hanya satu tap lewat banner ini.
        if (needsWorkspace) {
            WorkspaceBanner(
                onPickFolder = onOpenWorkspaceSetup,
                onUseAppWorkspace = chatViewModel::createNewAppWorkspace
            )
        }

        // ---------------- Messages (SELALU tampil — tidak ada gate) ----------------
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
                            CollapsibleAgentActivityCard(
                                steps = activeRunning.steps,
                                isLive = true
                            )
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

        // ---------------- Slash command chips ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SLASH_COMMANDS.forEach { (cmd, prompt) ->
                // SuggestionChip M3 — chip menyarankan prompt yang mengisi input.
                SuggestionChip(
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

        // ---------------- Chip "New messages" (align end, di atas input) ----------------
        // Muncul hanya saat streaming berjalan dan user TIDAK di dekat bawah
        // (auto-follow sengaja tidak memaksa scroll). Tap → lompat ke item
        // terakhir; karena posisi kembali di bawah, follow aktif lagi otomatis.
        // Muncul/hilang dengan motion M3: fade + expand/shrink vertikal.
        AnimatedVisibility(
            visible = streamingVisible && !atBottom,
            enter = fadeIn(AppMotion.standardTween()) +
                expandVertically(AppMotion.standardTween()),
            exit = fadeOut(AppMotion.standardTween()) +
                shrinkVertically(AppMotion.standardTween())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                AssistChip(
                    onClick = {
                        scope.launch { listState.animateScrollToItem(itemCount - 1) }
                    },
                    label = {
                        Text("New messages", style = MaterialTheme.typography.labelMedium)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
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

/** Banner status kecil: Surface tonal (shapes.small) + StatusDot + teks. */
@Composable
private fun StatusBanner(text: String, dotColor: Color) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
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
 * Mode switcher izin di TopAppBar: AssistChip berlabel mode saat ini + panah
 * → DropdownMenu 4 opsi dengan leading radio. Pilihan diteruskan ke
 * [ChatViewModel.setPermissionMode] (persist di AppSettings).
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
                    Icons.Rounded.ArrowDropDown,
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
 * Menu aksi cepat agent (anchor: ikon Bolt di TopAppBar). Setiap item meneruskan
 * NAMA aksi persis ("Fix", "Test", dst.) ke [ChatViewModel.runAgentAction] —
 * eksekusinya (prompt agent + sesi) diimplementasi MAIN (kontrak Task 11).
 */
@Composable
internal fun AgentActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        AGENT_MENU_ACTIONS.forEach { name ->
            DropdownMenuItem(
                text = { Text(name) },
                onClick = { onAction(name) }
            )
        }
    }
}

/**
 * Kartu plan approval: tampil setelah run PLAN mode selesai (vm.planApproval).
 * Approve menaikkan mode ke AUTO_READ_EDIT lalu run ulang sesi aktif.
 * ElevatedCard M3 + tombol konfirmasi berikon Check.
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
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
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
 * Banner workspace (engineMode AGENT tanpa proyek aktif) — NON-blocking:
 * satu baris di atas pesan, chat & percakapan TETAP tampil penuh. Aksi:
 * buat app-private sekali tap, atau buka layar setup (folder device/SAF).
 * Banner hilang otomatis begitu activeProject terisi.
 */
@Composable
private fun WorkspaceBanner(
    onPickFolder: () -> Unit,
    onUseAppWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Agent mode works in a workspace",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onUseAppWorkspace,
                contentPadding = PaddingValues(horizontal = 6.dp)
            ) {
                Text(
                    "Use app-private",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
            TextButton(
                onClick = onPickFolder,
                contentPadding = PaddingValues(horizontal = 6.dp)
            ) {
                Text(
                    "Set up",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}
