package com.openchatai.app.ui.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openchatai.app.R
import com.openchatai.app.ui.components.StatusDot
import com.openchatai.app.ui.theme.AppMotion
import com.openchatai.app.ui.theme.TerminalBackground
import com.openchatai.app.ui.theme.TerminalForeground
import com.openchai.core.linux.LinuxEnvPhase
import com.openchai.core.runtime.ManagedProcess
import com.openchai.core.runtime.ProcState
import com.openchai.terminal.Ansi

private enum class PanelTab { SHELL, PROCESSES }

/** Command cepat yang sering dipakai di tab shell. */
private val QUICK_COMMANDS = listOf(
    "ls", "pwd", "git status", "node -v", "python3 --version", "df -h .", "ps | head"
)

/**
 * Bentuk panel embedded (hanya sudut atas membulat, sesuai posisi panel yang
 * menempel di dasar layar) — hanya dipakai wrapper. Bentuk asimetris ini
 * tidak bisa diekspresikan lewat skala MaterialTheme.shapes sehingga
 * didefinisikan eksplisit di sini.
 */
private val PanelShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

/**
 * Panel terminal bawah (300dp) — WRAPPER kompatibilitas.
 *
 * Konten penuh sudah diekstrak ke [TerminalContent] agar bisa dipakai
 * [TerminalActivity] (layar penuh). Wrapper dipertahankan agar pemakai lama
 * tidak pecah; [onOpenLinuxSetup] tetap ada di signature demi kompatibilitas
 * call site, tetapi banner status Linux di dalam konten kini informatif
 * (tanpa navigasi) karena konten dipakai juga di Activity tanpa rute setup.
 *
 * Buka/tutup panel memakai AnimatedVisibility dengan token motion M3
 * (AppMotion): masuk dari bawah (expand + fade), keluar ke atas (shrink + fade).
 * Tidak menampilkan apa pun bila [visible] == false.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalPanel(
    visible: Boolean,
    onCollapse: () -> Unit,
    onOpenLinuxSetup: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(AppMotion.standardTween()) + expandVertically(
            animationSpec = AppMotion.standardTween(),
            expandFrom = Alignment.Bottom
        ),
        exit = fadeOut(AppMotion.standardTween()) + shrinkVertically(
            animationSpec = AppMotion.standardTween(),
            shrinkTowards = Alignment.Top
        ),
        modifier = modifier
    ) {
        // ViewModel dibuat di dalam konten (sama seperti perilaku lama: hanya
        // saat panel tampil).
        val vm: TerminalViewModel = viewModel()
        TerminalContent(
            vm = vm,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .shadow(elevation = 8.dp, shape = PanelShape, clip = true),
            headerSlot = {
                // Tombol collapse hanya relevan di mode panel embedded.
                IconButton(onClick = onCollapse) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Tutup terminal"
                    )
                }
            }
        )
    }
}

/**
 * Konten terminal penuh (tanpa bingkai/ukuran tetap) — dipakai [TerminalPanel]
 * (embedded) dan [TerminalActivity] (layar penuh). [vm] disuplai pemanggil
 * agar satu instance [TerminalViewModel] bisa dipakai bersama.
 *
 * Struktur (chrome di luar area emulator memakai peran warna Material 3):
 *  - Toolbar: Surface surfaceContainer dengan ikon Terminal tonal + judul +
 *    font +/− + Ctrl+C + Clear + [headerSlot] (opsional).
 *  - Bar tab: pill status Linux (informatif) + chip sesi shell (FilterChip)
 *    + "+ New" + tab Processes.
 *  - Banner status lingkungan Linux bila belum terpasang/tak didukung.
 *  - Konten: shell interaktif (quick commands + output + input) atau daftar
 *    proses terkelola (Stop/Restart + stdin REPL).
 *
 * Area output emulator tetap memakai palet khusus terminal
 * (TerminalBackground/TerminalForeground) — identitas visual emulator.
 * Modifier [modifier] menentukan ukuran/bentuk akhir (wrapper: 300dp dengan
 * sudut membulat + shadow; activity: fillMaxSize polos).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalContent(
    vm: TerminalViewModel,
    modifier: Modifier = Modifier,
    headerSlot: (@Composable () -> Unit)? = null
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val processes by vm.processes.collectAsStateWithLifecycle()
    // Status lingkungan Linux: ikut menentukan rute delegating TerminalHost
    // (READY → proot, else shell Android) + teks pill & banner di konten.
    val linuxPhase by vm.linuxStatus.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(PanelTab.SHELL) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var processOutputId by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }

    // Buat satu sesi shell otomatis saat konten pertama kali tampil.
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
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Perubahan tinggi konten (banner/stdin muncul-hilang)
                // dianimasikan lembut dengan token motion M3.
                .animateContentSize(animationSpec = AppMotion.standardTween())
        ) {
            // ---------------- Toolbar M3 (surfaceContainer) ----------------
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Ikon tab terminal tonal — identitas toolbar.
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(6.dp)
                                .size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
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
                    // Ctrl+C: kirim byte SIGINT (ETX) TANPA newline ke sesi aktif.
                    // Catatan: host legacy menambahkan newline otomatis (baris kosong
                    // setelah interrupt — tidak berbahaya); host Linux menulis
                    // byte mentah. Posisi: dekat font size / Clear / slot header.
                    TextButton(
                        onClick = { selectedSessionId?.let { vm.write(it, "\u0003") } },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Ctrl+C", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { selectedSessionId?.let { vm.clear(it) } }) {
                        Icon(
                            Icons.Rounded.Clear,
                            contentDescription = "Bersihkan output"
                        )
                    }
                    // Slot header tambahan dari pemanggil (wrapper: tombol collapse).
                    headerSlot?.invoke()
                }
            }

            // Tab sesi + aksi (+ pill status Linux di bar ini — dipilih agar
            // tidak overflow pada layar sempit; bar ini sudah scrollable).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val linuxReady = linuxPhase == LinuxEnvPhase.READY
                // Pill status Linux: informatif — konten ini juga dipakai
                // TerminalActivity yang tidak punya rute layar setup.
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (linuxReady) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ) {
                    Text(
                        text = if (linuxReady) "Linux: aktif" else "Linux: shell Android",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (linuxReady) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
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
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = { Text("New") }
                )
                FilterChip(
                    selected = selectedTab == PanelTab.PROCESSES,
                    onClick = {
                        selectedTab =
                            if (selectedTab == PanelTab.PROCESSES) PanelTab.SHELL
                            else PanelTab.PROCESSES
                        processOutputId = null
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.List,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = { Text("Processes (${processes.size})", maxLines = 1) }
                )
            }

            // Banner status lingkungan Linux (di atas konten, hanya bila belum
            // terpasang / perangkat tidak didukung) — informatif, tanpa klik.
            // Warna banner memakai peran colorScheme M3 (chrome di luar emulator).
            when (linuxPhase) {
                LinuxEnvPhase.NOT_INSTALLED -> LinuxSetupBanner(
                    text = "Lingkungan Linux belum terpasang — terminal memakai shell " +
                        "Android terbatas. Buka Settings → Lingkungan Linux untuk pasang " +
                        "Ubuntu (apt/node/python).",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    icon = Icons.Rounded.Info,
                    iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                LinuxEnvPhase.NOT_SUPPORTED -> LinuxSetupBanner(
                    text = "Perangkat tidak mendukung lingkungan Linux: " +
                        vm.linuxCapability.reason,
                    container = MaterialTheme.colorScheme.errorContainer,
                    icon = Icons.Rounded.WarningAmber,
                    iconTint = MaterialTheme.colorScheme.onErrorContainer,
                    textColor = MaterialTheme.colorScheme.onErrorContainer
                )
                else -> Unit
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
                                Icon(Icons.Rounded.Send, contentDescription = "Kirim")
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
                                    Icon(
                                        Icons.Rounded.ArrowBack,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Proses")
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
                            // Baris stdin muncul/hilang dengan transisi M3.
                            AnimatedVisibility(
                                visible = procActive,
                                enter = fadeIn(AppMotion.standardTween()) + expandVertically(
                                    animationSpec = AppMotion.standardTween(),
                                    expandFrom = Alignment.Bottom
                                ),
                                exit = fadeOut(AppMotion.standardTween()) + shrinkVertically(
                                    animationSpec = AppMotion.standardTween(),
                                    shrinkTowards = Alignment.Top
                                )
                            ) {
                                var stdinText by remember(showingId) {
                                    mutableStateOf("")
                                }
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
                                            Text(
                                                "stdin (REPL)…",
                                                fontFamily = FontFamily.Monospace
                                            )
                                        },
                                        textStyle = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = settings.terminalFontSize.sp
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Send
                                        ),
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
                                        Icon(
                                            Icons.Rounded.Send,
                                            contentDescription = "Kirim ke stdin"
                                        )
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

/**
 * Banner tipis status lingkungan Linux. [onClick] opsional: bila null (konteks
 * tanpa rute setup, mis. TerminalActivity) banner murni informatif. Warna
 * container/icon/text disuplai pemanggil (peran colorScheme M3).
 */
@Composable
private fun LinuxSetupBanner(
    text: String,
    container: Color,
    icon: ImageVector,
    iconTint: Color,
    textColor: Color,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        shape = MaterialTheme.shapes.small,
        color = container
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
    }
}

/**
 * Area output monospace gelap dengan auto-scroll opsional. Palet khusus
 * Terminal* (dari theme/Color.kt) dipertahankan HANYA di sini — area emulator.
 */
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

/**
 * Warna status dot proses — daftar proses adalah chrome UI, jadi memakai
 * peran colorScheme M3 (bukan palet terminal): RUNNING = primary,
 * STARTING/RESTARTING = tertiary, FAILED = error, EXITED/STOPPED = outline.
 */
@Composable
private fun stateColor(state: ProcState): Color = when (state) {
    ProcState.RUNNING -> MaterialTheme.colorScheme.primary
    ProcState.RESTARTING, ProcState.STARTING -> MaterialTheme.colorScheme.tertiary
    ProcState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline // EXITED / STOPPED — abu netral
}
