package com.openchai.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openchai.app.R
import com.openchai.app.ui.components.StatusDot
import com.openchai.app.ui.theme.TerminalBackground
import com.openchai.app.ui.theme.TerminalForeground
import com.openchai.app.ui.theme.TerminalGreen
import com.openchai.app.ui.theme.TerminalRed
import com.openchai.app.ui.theme.TerminalYellow
import com.openchai.core.runtime.ManagedProcess
import com.openchai.core.runtime.ProcState
import com.openchai.terminal.Ansi

private enum class PanelTab { SHELL, PROCESSES }

/** Command cepat yang sering dipakai di tab shell. */
private val QUICK_COMMANDS = listOf(
    "ls", "pwd", "git status", "node -v", "python3 --version", "df -h .", "ps | head"
)

/**
 * Panel terminal bawah (300dp): sesi shell interaktif + daftar proses
 * yang dikelola [com.openchai.runtime.AndroidProcessManager].
 * Tidak menampilkan apa pun bila [visible] == false.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalPanel(
    visible: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val vm: TerminalViewModel = viewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val processes by vm.processes.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(PanelTab.SHELL) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var processOutputId by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }

    // Buat satu sesi shell otomatis saat panel pertama kali tampil.
    LaunchedEffect(Unit) {
        if (vm.sessions.value.isEmpty()) {
            selectedSessionId = vm.newSession()
        }
    }
    // Jaga agar sesi terpilih selalu valid.
    LaunchedEffect(sessions) {
        if (sessions.none { it.id == selectedSessionId }) {
            selectedSessionId = sessions.lastOrNull()?.id
        }
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab == PanelTab.PROCESSES) vm.refreshProcesses()
    }

    fun sendInput() {
        val id = selectedSessionId ?: return
        if (input.isBlank()) return
        vm.write(id, input)
        input = ""
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Border atas subtle.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            )

            // Header.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.terminal_tab),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.changeFontSize(1) }) {
                    Text("+", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = { vm.changeFontSize(-1) }) {
                    Text("-", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = { selectedSessionId?.let { vm.clear(it) } }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Bersihkan output")
                }
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Tutup terminal")
                }
            }

            // Tab sesi + aksi.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                sessions.forEachIndexed { index, session ->
                    FilterChip(
                        selected = selectedTab == PanelTab.SHELL &&
                            selectedSessionId == session.id,
                        onClick = {
                            selectedTab = PanelTab.SHELL
                            selectedSessionId = session.id
                            processOutputId = null
                        },
                        label = { Text("shell ${index + 1}", maxLines = 1) }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = {
                        val id = vm.newSession()
                        selectedSessionId = id
                        selectedTab = PanelTab.SHELL
                        processOutputId = null
                    },
                    label = { Text("+ New") }
                )
                FilterChip(
                    selected = selectedTab == PanelTab.PROCESSES,
                    onClick = {
                        selectedTab =
                            if (selectedTab == PanelTab.PROCESSES) PanelTab.SHELL
                            else PanelTab.PROCESSES
                        processOutputId = null
                    },
                    label = { Text("Processes (${processes.size})", maxLines = 1) }
                )
            }

            when (selectedTab) {
                PanelTab.SHELL -> {
                    val sessionId = selectedSessionId
                    if (sessionId == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Tidak ada sesi terminal",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val output by vm.outputFlow(sessionId).collectAsStateWithLifecycle()
                        // Baris command cepat (ketik satu per satu ke sesi shell aktif).
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            QUICK_COMMANDS.forEach { cmd ->
                                AssistChip(
                                    onClick = {
                                        val sid = selectedSessionId
                                        if (sid != null) vm.write(sid, cmd)
                                    },
                                    label = {
                                        Text(
                                            text = cmd,
                                            fontFamily = FontFamily.Monospace,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                        TerminalOutputSurface(
                            text = Ansi.strip(output),
                            fontSize = settings.terminalFontSize,
                            autoScroll = settings.terminalAutoScroll,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = {
                                    Text("$ ", fontFamily = FontFamily.Monospace)
                                },
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = settings.terminalFontSize.sp
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { sendInput() })
                            )
                            IconButton(
                                onClick = { sendInput() },
                                enabled = true
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = "Kirim")
                            }
                        }
                    }
                }

                PanelTab.PROCESSES -> {
                    val showingId = processOutputId
                    if (showingId == null) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(processes, key = { it.id }) { process ->
                                ProcessRow(
                                    process = process,
                                    onShowOutput = { processOutputId = process.id },
                                    onStop = { vm.stopProcess(process.id) },
                                    onRestart = { vm.restartProcess(process.id) }
                                )
                            }
                            if (processes.isEmpty()) {
                                item {
                                    Text(
                                        text = "Belum ada proses. Minta agent menjalankan " +
                                            "command dev/server dari chat.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { processOutputId = null }) {
                                    Text("← Proses")
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = processes.firstOrNull { it.id == showingId }
                                        ?.command?.take(40) ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            val revision by vm.processOutputRevision(showingId)
                                .collectAsStateWithLifecycle()
                            val procOutput = remember(showingId, revision) {
                                vm.processOutputFor(showingId) ?: ""
                            }
                            TerminalOutputSurface(
                                text = Ansi.strip(procOutput),
                                fontSize = settings.terminalFontSize,
                                autoScroll = settings.terminalAutoScroll,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                            // Input stdin untuk proses interaktif (REPL python3 -i, node, dsb.).
                            val proc = processes.firstOrNull { it.id == showingId }
                            val procActive = proc != null && (
                                proc.state == ProcState.RUNNING ||
                                    proc.state == ProcState.STARTING ||
                                    proc.state == ProcState.RESTARTING
                                )
                            if (procActive) {
                                var stdinText by remember(showingId) { mutableStateOf("") }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = stdinText,
                                        onValueChange = { stdinText = it },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        placeholder = {
                                            Text("stdin (REPL)…", fontFamily = FontFamily.Monospace)
                                        },
                                        textStyle = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = settings.terminalFontSize.sp
                                        ),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                        keyboardActions = KeyboardActions(
                                            onSend = {
                                                if (stdinText.isNotBlank()) {
                                                    vm.writeProcessStdin(showingId, stdinText)
                                                    stdinText = ""
                                                }
                                            }
                                        )
                                    )
                                    IconButton(
                                        onClick = {
                                            if (stdinText.isNotBlank()) {
                                                vm.writeProcessStdin(showingId, stdinText)
                                                stdinText = ""
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Filled.Send, contentDescription = "Kirim ke stdin")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Area output monospace gelap dengan auto-scroll opsional. */
@Composable
private fun TerminalOutputSurface(
    text: String,
    fontSize: Int,
    autoScroll: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Surface(modifier = modifier, color = TerminalBackground) {
        SelectionContainer(modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.35).sp,
                color = TerminalForeground,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        LaunchedEffect(text, autoScroll) {
            if (autoScroll) scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
}

/** Satu baris proses: status dot, command, meta, aksi Stop/Restart. */
@Composable
private fun ProcessRow(
    process: ManagedProcess,
    onShowOutput: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onShowOutput)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color = stateColor(process.state), size = 8.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = process.command.take(40),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = processMeta(process),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val active = process.state == ProcState.RUNNING ||
            process.state == ProcState.STARTING ||
            process.state == ProcState.RESTARTING
        if (active) {
            TextButton(onClick = onStop) { Text("Stop") }
        } else {
            TextButton(onClick = onRestart) { Text("Restart") }
        }
    }
}

private fun processMeta(process: ManagedProcess): String {
    val pid = "PID " + (process.pid?.toString() ?: "-")
    val port = process.detectedPort?.let { "Port $it" }
    val duration = formatDuration(process.startedAt, process.endedAt)
    return listOfNotNull(pid, port, duration).joinToString(" · ")
}

private fun formatDuration(startedAt: Long, endedAt: Long?): String {
    val end = endedAt ?: System.currentTimeMillis()
    val totalSeconds = (end - startedAt).coerceAtLeast(0) / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun stateColor(state: ProcState): Color = when (state) {
    ProcState.RUNNING -> TerminalGreen
    ProcState.RESTARTING, ProcState.STARTING -> TerminalYellow
    ProcState.FAILED -> TerminalRed
    else -> Color(0xFF8B949E) // EXITED / STOPPED — abu
}
