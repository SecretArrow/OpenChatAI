package com.openchatai.app.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openchatai.app.ui.components.StatusDot
import com.openchatai.app.ui.theme.AppMotion
import com.openchai.core.agent.PermissionMode
import com.openchai.core.model.ProviderId
import com.openchai.core.settings.AppSettings
import com.openchai.core.settings.ChatDensity
import com.openchai.core.settings.ThemeMode
import kotlin.math.roundToInt

/**
 * Halaman Settings: provider AI, OpenCode engine, agent, terminal,
 * appearance, dan status runtime. Tanpa bottom bar sendiri.
 *
 * Overhaul Material 3 (visual-only — kontrak parameter & logika tidak berubah):
 * - Scaffold + TopAppBar "Pengaturan".
 * - Seksi dipisah judul headlineSmall + OutlinedCard berisi baris ListItem M3.
 * - Pemilih tema & density memakai SegmentedButton M3.
 * - Ikon Rounded, warna dari colorScheme, tipografi M3, motion token [AppMotion].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenRuntime: () -> Unit = {},
    onOpenLinuxSetup: () -> Unit = {}
) {
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Context untuk feedback Toast setiap perubahan konfigurasi berhasil disimpan.
    val context = LocalContext.current

    var ollamaTestResult by remember { mutableStateOf<Boolean?>(null) }
    var openCodeTestResult by remember { mutableStateOf<Boolean?>(null) }
    var runtimeRows by remember { mutableStateOf<List<Triple<String, Boolean, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        vm.runtimeStatus { runtimeRows = it }
    }

    // CATATAN INSET: layar ini hidup di dalam Scaffold NavGraph — padding status
    // bar & bottom bar sudah dipenuhi parent, jadi window insets di-nol-kan di
    // sini agar TopAppBar tidak menambah jarak ganda di bawah status bar.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan") },
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ------------------------------------------------------------
            item {
                SectionCard("AI Provider") {
                    val provider = settings.activeProvider
                    ProviderChips(provider) { selected ->
                        vm.update { it.copy(activeProvider = selected) }
                        // Aksi diskrit (pilih provider) → satu toast.
                        Toast.makeText(context, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
                    }
                    // Field teks menyimpan per ketukan huruf; toast cukup SEKALI
                    // saat field kehilangan fokus setelah isi benar-benar berubah.
                    var endpointEdited by remember(provider) { mutableStateOf(false) }
                    OutlinedTextField(
                        value = endpointOf(settings, provider),
                        onValueChange = { value ->
                            endpointEdited = true
                            vm.update { copyEndpoint(it, provider, value) }
                        },
                        label = { Text("Endpoint") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fieldSavedToast(context, endpointEdited, "URL tersimpan") {
                                endpointEdited = false
                            }
                    )
                    // Preset cepat AgentRouter (router multi-arsitektur OpenAI
                    // & Anthropic — cocok untuk uji model campuran).
                    if (provider == ProviderId.ANTHROPIC || provider == ProviderId.OPENAI ||
                        provider == ProviderId.CUSTOM
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = {
                                    vm.update { copyEndpoint(it, provider, agentRouterEndpoint(provider)) }
                                    Toast.makeText(context, "URL tersimpan", Toast.LENGTH_SHORT).show()
                                },
                                label = { Text("Preset: AgentRouter") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                    // Preset cepat Poolside (inference.poolside.ai — coding model
                    // laguna, API kompatibel OpenAI; key tersimpan di SecureStore).
                    if (provider == ProviderId.POOLSIDE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = {
                                    vm.update { copyEndpoint(it, provider, POOLSIDE_PRESET_ENDPOINT) }
                                    Toast.makeText(context, "URL tersimpan", Toast.LENGTH_SHORT).show()
                                },
                                label = { Text("Preset: Poolside") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                    var apiKeyInput by remember(provider) { mutableStateOf("") }
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("API key") },
                        placeholder = {
                            Text("API key stored securely (EncryptedSharedPreferences)")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                val key = apiKeyInput
                                if (key.isNotBlank()) {
                                    // Callback VM memastikan toast hanya muncul bila
                                    // key BENAR-BENAR tersimpan di SecureStore.
                                    vm.saveApiKey(provider, key) {
                                        Toast.makeText(context, "API key tersimpan", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    // Input kosong: perilaku lama dipertahankan (reset key),
                                    // tanpa toast agar tidak mengklaim "tersimpan".
                                    vm.saveApiKey(provider, key)
                                }
                                apiKeyInput = ""
                            }) {
                                Icon(Icons.Rounded.Done, contentDescription = "Save API key")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (provider == ProviderId.OLLAMA) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(onClick = {
                                vm.testOllama { ok -> ollamaTestResult = ok }
                            }) { Text("Test connection") }
                            TestResultText(ollamaTestResult)
                        }
                        Text(
                            "Local: http://127.0.0.1:11434 · " +
                                "Remote contoh: http://192.168.1.100:11434",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // ------------------------------------------------------------
            item {
                SectionCard("Runtime & Modul") {
                    // Baris navigasi M3 (ListItem) → buka layar Runtime & Modul.
                    NavRow(
                        icon = Icons.Rounded.Info,
                        title = "Runtime & Modul",
                        subtitle = "Kelola runtime llama.cpp & modul — pasang/hapus, jeda/lanjut unduhan",
                        onClick = onOpenRuntime
                    )
                }
            }
            // ------------------------------------------------------------
            item {
                SectionCard("Lingkungan Linux") {
                    // Baris navigasi M3 (ListItem) → buka layar setup Linux
                    // (proot + rootfs Ubuntu).
                    NavRow(
                        icon = Icons.Rounded.Terminal,
                        title = "Lingkungan Linux",
                        subtitle = "Pasang Ubuntu userspace (proot) — apt, nodejs, npm, " +
                            "python3 di dalam sandbox. Tanpa root.",
                        onClick = onOpenLinuxSetup
                    )
                }
            }
            // ------------------------------------------------------------
            item {
                SectionCard("OpenCode Engine") {
                    // Toast sekali saat selesai edit (pola sama dengan field Endpoint).
                    var openCodeUrlEdited by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = settings.openCodeServerUrl,
                        onValueChange = { value ->
                            openCodeUrlEdited = true
                            vm.update { it.copy(openCodeServerUrl = value) }
                        },
                        label = { Text("OpenCode server URL") },
                        placeholder = { Text("http://192.168.1.50:4096") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fieldSavedToast(context, openCodeUrlEdited, "URL tersimpan") {
                                openCodeUrlEdited = false
                            }
                    )
                    SwitchRow(
                        title = "Prefer OpenCode engine",
                        subtitle = "Route agent tasks to an OpenCode-compatible server " +
                            "instead of the built-in tool loop.",
                        checked = settings.preferOpenCodeEngine,
                        onCheckedChange = { value ->
                            vm.update { it.copy(preferOpenCodeEngine = value) }
                            Toast.makeText(context, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = {
                            vm.testOpenCode { ok -> openCodeTestResult = ok }
                        }) { Text("Test") }
                        TestResultText(openCodeTestResult)
                    }
                }
            }
            // ------------------------------------------------------------
            item {
                SectionCard("Agent") {
                    LabeledSlider(
                        label = "Max agent iterations",
                        display = settings.maxAgentIterations.toString(),
                        value = settings.maxAgentIterations.toFloat(),
                        range = 5f..30f,
                        steps = 24,
                        onChange = { v ->
                            vm.update { it.copy(maxAgentIterations = v.roundToInt()) }
                        }
                    )
                    Text("Permission mode", style = MaterialTheme.typography.titleMedium)
                    PermissionModeOptions(selected = settings.permissionMode) { mode ->
                        vm.update { it.copy(permissionMode = mode) }
                        Toast.makeText(context, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
                    }
                    // Peringatan Full Access muncul/hilang dengan motion M3
                    // (fade + expand vertikal, token AppMotion).
                    AnimatedVisibility(
                        visible = settings.permissionMode == PermissionMode.FULL_ACCESS,
                        enter = fadeIn(AppMotion.standardTween()) +
                            expandVertically(AppMotion.standardTween()),
                        exit = fadeOut(AppMotion.standardTween()) +
                            shrinkVertically(AppMotion.standardTween())
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Everything runs without confirmation — use only " +
                                    "in a trusted sandbox",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            // ------------------------------------------------------------
            item {
                SectionCard("Terminal") {
                    LabeledSlider(
                        label = "Terminal font size",
                        display = settings.terminalFontSize.toString(),
                        value = settings.terminalFontSize.toFloat(),
                        range = 9f..20f,
                        steps = 10,
                        onChange = { v ->
                            vm.update { it.copy(terminalFontSize = v.roundToInt()) }
                        }
                    )
                    SwitchRow(
                        title = "Auto-scroll terminal",
                        subtitle = "Follow new output while a command is running.",
                        checked = settings.terminalAutoScroll,
                        onCheckedChange = { value ->
                            vm.update { it.copy(terminalAutoScroll = value) }
                            Toast.makeText(context, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            // ------------------------------------------------------------
            item {
                SectionCard("Appearance") {
                    ThemeModeRow(settings.themeMode) { mode ->
                        vm.update { it.copy(themeMode = mode) }
                        Toast.makeText(context, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
                    }
                    LabeledSlider(
                        label = "Chat font scale",
                        display = "%.1f×".format(settings.chatFontScale),
                        value = settings.chatFontScale,
                        range = 0.8f..1.4f,
                        steps = 5,
                        onChange = { v ->
                            vm.update { it.copy(chatFontScale = (v * 10).roundToInt() / 10f) }
                        }
                    )
                    ChatDensityRow(settings.chatDensity) { density ->
                        vm.update { it.copy(chatDensity = density) }
                        Toast.makeText(context, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            // ------------------------------------------------------------
            item {
                SectionCard("Status Runtime") {
                    if (runtimeRows.isEmpty()) {
                        Text(
                            "Checking runtime…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        runtimeRows.forEachIndexed { index, (name, ok, detail) ->
                            // Pemisah baris status memakai warna outlineVariant M3.
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            RuntimeRow(name, ok, detail)
                        }
                    }
                    // Toast sekali saat selesai edit (pola sama dengan field Endpoint).
                    var extraPathEdited by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = settings.extraPathDirs,
                        onValueChange = { value ->
                            extraPathEdited = true
                            vm.update { it.copy(extraPathDirs = value) }
                        },
                        label = { Text("Extra PATH directories") },
                        placeholder = {
                            Text("Tambah PATH, mis. /data/data/com.termux/files/usr/bin")
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fieldSavedToast(context, extraPathEdited, "Pengaturan disimpan") {
                                extraPathEdited = false
                            }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            vm.pruneProcesses()
                            vm.runtimeStatus { runtimeRows = it }
                        }) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Prune finished processes")
                        }
                        TextButton(onClick = {
                            vm.restartRuntime {
                                vm.runtimeStatus { runtimeRows = it }
                            }
                        }) { Text("Restart runtime check") }
                    }
                }
            }
            // ------------------------------------------------------------
            item {
                McpSettingsSection()
            }
            item {
                SkillsSection()
            }
        }
    }
}

// ----------------------------------------------------------------------
// Building blocks
// ----------------------------------------------------------------------

/**
 * Kartu satu seksi M3: judul headlineSmall + OutlinedCard (shape large)
 * berisi baris-baris setting. Konten mengembang mulus via animateContentSize
 * dengan token motion [AppMotion].
 */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )
        OutlinedCard(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .animateContentSize(AppMotion.standardTween()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

/** Lingkaran tonal (surfaceContainerHigh) pembungkus ikon leading baris setting. */
@Composable
private fun TonalIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Baris navigasi setting M3: leading ikon dalam lingkaran tonal, judul
 * titleMedium, deskripsi bodyMedium, trailing ikon aksi.
 */
@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        leadingContent = { TonalIcon(icon) },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Icon(
                Icons.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

/**
 * Modifier feedback simpan untuk field teks konfigurasi (Endpoint, URL server,
 * Extra PATH).
 *
 * Mutasi DataStore pada field teks terjadi per ketukan huruf (onValueChange),
 * sehingga toast TIDAK ditampilkan per ketukan — cukup SEKALI saat field
 * kehilangan fokus setelah pengguna benar-benar mengubah isinya (satu toast
 * per sesi edit; padanan "onValueChangeFinished" untuk input teks).
 *
 * @param edited flag "sudah diedit sejak fokus terakhir"; di-set true oleh
 *   pemanggil di onValueChange dan di-reset lewat [onConsumed] setelah toast tampil.
 */
private fun Modifier.fieldSavedToast(
    context: Context,
    edited: Boolean,
    message: String,
    onConsumed: () -> Unit
): Modifier = onFocusChanged { state ->
    if (!state.isFocused && edited) {
        onConsumed()
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderChips(selected: ProviderId, onSelect: (ProviderId) -> Unit) {
    val options = listOf(
        ProviderId.OLLAMA to "Ollama",
        ProviderId.OPENAI to "OpenAI",
        ProviderId.ANTHROPIC to "Claude",
        ProviderId.GOOGLE to "Gemini",
        ProviderId.POOLSIDE to "Poolside",
        ProviderId.CUSTOM to "Custom"
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (provider, label) ->
            FilterChip(
                selected = selected == provider,
                onClick = { onSelect(provider) },
                label = { Text(label) },
                // Chip terpilih menampilkan tanda centang M3.
                leadingIcon = if (selected == provider) {
                    {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    null
                }
            )
        }
    }
}

/** Pemilih mode tema M3: tiga SegmentedButton dalam satu baris. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.SYSTEM to "System",
        ThemeMode.LIGHT to "Light",
        ThemeMode.DARK to "Dark"
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label, style = MaterialTheme.typography.labelLarge) }
            )
        }
    }
}

/** Pemilih kepadatan chat M3: label titleMedium + SegmentedButton 2 opsi. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDensityRow(selected: ChatDensity, onSelect: (ChatDensity) -> Unit) {
    val options = listOf(
        ChatDensity.COMPACT to "Compact",
        ChatDensity.COMFORTABLE to "Comfortable"
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Density", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (density, label) ->
                SegmentedButton(
                    selected = selected == density,
                    onClick = { onSelect(density) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(label, style = MaterialTheme.typography.labelLarge) }
                )
            }
        }
    }
}

/** Pemilih mode izin agent: 4 radio + deskripsi 1 baris per mode. */
private val PERMISSION_MODE_ITEMS = listOf(
    Triple(PermissionMode.ASK, "Ask", "Confirm every write, delete and command"),
    Triple(PermissionMode.PLAN, "Plan", "Read-only research, then a plan"),
    Triple(PermissionMode.AUTO_READ_EDIT, "Auto read-edit", "Reads and edits auto-approved"),
    Triple(PermissionMode.FULL_ACCESS, "Full access (YOLO)", "Everything auto-approved — dangerous")
)

@Composable
private fun PermissionModeOptions(selected: PermissionMode, onSelect: (PermissionMode) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PERMISSION_MODE_ITEMS.forEach { (mode, label, description) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onSelect(mode) }
            ) {
                RadioButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Baris setting dengan Switch M3 (ListItem): judul titleMedium, deskripsi
 * bodyMedium, trailing Switch. Menyentuh baris juga mengubah nilai.
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onCheckedChange(!checked) },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = if (subtitle != null) {
            {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            null
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}

/** Slider berlabel M3: judul titleMedium + nilai dalam pil tonal. */
@Composable
private fun LabeledSlider(
    label: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            // Nilai tampil dalam pil tonal (surfaceContainerHigh) ala M3.
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    display,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

/** Hasil tes koneksi: OK (primary, CheckCircle) / Failed (error, WarningAmber). */
@Composable
private fun TestResultText(result: Boolean?) {
    when (result) {
        null -> Unit
        true -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                "OK",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        false -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Text(
                "Failed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Satu baris status runtime: StatusDot + nama + detail. */
@Composable
private fun RuntimeRow(name: String, ok: Boolean, detail: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        StatusDot(
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Column {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ----------------------------------------------------------------------
// Helpers endpoint per provider
// ----------------------------------------------------------------------

/** Endpoint AgentRouter sesuai arsitektur provider (router mendukung keduanya). */
private fun agentRouterEndpoint(provider: ProviderId): String = when (provider) {
    ProviderId.ANTHROPIC -> "https://agentrouter.org"
    ProviderId.OPENAI, ProviderId.CUSTOM -> "https://agentrouter.org/v1"
    // POOLSIDE bukan target AgentRouter — chip ini tidak tampil untuk Poolside;
    // fallback ke endpoint-nya sendiri agar tidak pernah salah set endpoint.
    ProviderId.POOLSIDE -> POOLSIDE_PRESET_ENDPOINT
    else -> "https://agentrouter.org"
}

private fun endpointOf(settings: AppSettings, provider: ProviderId): String = when (provider) {
    ProviderId.OLLAMA -> settings.ollamaEndpoint
    ProviderId.OPENAI -> settings.openaiEndpoint
    ProviderId.ANTHROPIC -> settings.anthropicEndpoint
    ProviderId.GOOGLE -> settings.googleEndpoint
    ProviderId.POOLSIDE -> settings.poolsideEndpoint
    ProviderId.CUSTOM -> settings.customEndpoint
    // LOCAL tidak pakai endpoint — tampilkan path model GGUF aktif.
    ProviderId.LOCAL -> settings.localModelPath
}

private fun copyEndpoint(settings: AppSettings, provider: ProviderId, value: String): AppSettings =
    when (provider) {
        ProviderId.OLLAMA -> settings.copy(ollamaEndpoint = value)
        ProviderId.OPENAI -> settings.copy(openaiEndpoint = value)
        ProviderId.ANTHROPIC -> settings.copy(anthropicEndpoint = value)
        ProviderId.GOOGLE -> settings.copy(googleEndpoint = value)
        ProviderId.POOLSIDE -> settings.copy(poolsideEndpoint = value)
        ProviderId.CUSTOM -> settings.copy(customEndpoint = value)
        // LOCAL: edit manual path GGUF (alternatif tombol "Use" di screen Models).
        ProviderId.LOCAL -> settings.copy(localModelPath = value)
    }

/** Preset endpoint Poolside (sama dengan default poolsideEndpoint di AppSettings). */
private const val POOLSIDE_PRESET_ENDPOINT = "https://inference.poolside.ai/v1"
