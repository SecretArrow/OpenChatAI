package com.openchai.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openchai.app.ui.components.StatusDot
import com.openchai.core.model.ProviderId
import com.openchai.core.settings.AppSettings
import com.openchai.core.settings.ChatDensity
import com.openchai.core.settings.ThemeMode
import kotlin.math.roundToInt

/**
 * Halaman Settings: provider AI, OpenCode engine, agent, terminal,
 * appearance, dan status runtime. Tanpa bottom bar sendiri.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var ollamaTestResult by remember { mutableStateOf<Boolean?>(null) }
    var openCodeTestResult by remember { mutableStateOf<Boolean?>(null) }
    var runtimeRows by remember { mutableStateOf<List<Triple<String, Boolean, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        vm.runtimeStatus { runtimeRows = it }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ------------------------------------------------------------
            item {
                SectionCard("AI PROVIDER") {
                    val provider = settings.activeProvider
                    ProviderChips(provider) { selected ->
                        vm.update { it.copy(activeProvider = selected) }
                    }
                    OutlinedTextField(
                        value = endpointOf(settings, provider),
                        onValueChange = { value ->
                            vm.update { copyEndpoint(it, provider, value) }
                        },
                        label = { Text("Endpoint") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                                vm.saveApiKey(provider, apiKeyInput)
                                apiKeyInput = ""
                            }) {
                                Icon(Icons.Filled.Done, contentDescription = "Save API key")
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
                SectionCard("OPENCODE ENGINE") {
                    OutlinedTextField(
                        value = settings.openCodeServerUrl,
                        onValueChange = { value ->
                            vm.update { it.copy(openCodeServerUrl = value) }
                        },
                        label = { Text("OpenCode server URL") },
                        placeholder = { Text("http://192.168.1.50:4096") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SwitchRow(
                        title = "Prefer OpenCode engine",
                        subtitle = "Route agent tasks to an OpenCode-compatible server " +
                            "instead of the built-in tool loop.",
                        checked = settings.preferOpenCodeEngine,
                        onCheckedChange = { value ->
                            vm.update { it.copy(preferOpenCodeEngine = value) }
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
                SectionCard("AGENT") {
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
                    SwitchRow(
                        title = "Auto-approve commands",
                        subtitle = "Run agent shell commands without asking first.",
                        checked = settings.autoApproveCommands,
                        onCheckedChange = { value ->
                            vm.update { it.copy(autoApproveCommands = value) }
                        }
                    )
                    if (settings.autoApproveCommands) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "Commands run inside your workspace sandbox",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            // ------------------------------------------------------------
            item {
                SectionCard("TERMINAL") {
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
                        }
                    )
                }
            }
            // ------------------------------------------------------------
            item {
                SectionCard("APPEARANCE") {
                    ThemeModeRow(settings.themeMode) { mode ->
                        vm.update { it.copy(themeMode = mode) }
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
                    }
                }
            }
            // ------------------------------------------------------------
            item {
                SectionCard("RUNTIME") {
                    if (runtimeRows.isEmpty()) {
                        Text(
                            "Checking runtime…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        runtimeRows.forEach { (name, ok, detail) ->
                            RuntimeRow(name, ok, detail)
                        }
                    }
                    OutlinedTextField(
                        value = settings.extraPathDirs,
                        onValueChange = { value ->
                            vm.update { it.copy(extraPathDirs = value) }
                        },
                        label = { Text("Extra PATH directories") },
                        placeholder = {
                            Text("Tambah PATH, mis. /data/data/com.termux/files/usr/bin")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            vm.pruneProcesses()
                            vm.runtimeStatus { runtimeRows = it }
                        }) {
                            Icon(
                                Icons.Filled.Refresh,
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

/** Kartu satu section dengan header label kecil berwarna primary (uppercase). */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
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
        ProviderId.CUSTOM to "Custom"
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (provider, label) ->
            FilterChip(
                selected = selected == provider,
                onClick = { onSelect(provider) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun ThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        listOf(
            ThemeMode.SYSTEM to "System",
            ThemeMode.LIGHT to "Light",
            ThemeMode.DARK to "Dark"
        ).forEach { (mode, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelect(mode) }
            ) {
                RadioButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) }
                )
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ChatDensityRow(selected: ChatDensity, onSelect: (ChatDensity) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Density", style = MaterialTheme.typography.bodyLarge)
        FilterChip(
            selected = selected == ChatDensity.COMPACT,
            onClick = { onSelect(ChatDensity.COMPACT) },
            label = { Text("Compact") }
        )
        FilterChip(
            selected = selected == ChatDensity.COMFORTABLE,
            onClick = { onSelect(ChatDensity.COMFORTABLE) },
            label = { Text("Comfortable") }
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

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
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                display,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun TestResultText(result: Boolean?) {
    when (result) {
        null -> Unit
        true -> Text(
            "OK",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        false -> Text(
            "Failed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun RuntimeRow(name: String, ok: Boolean, detail: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatusDot(
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium)
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

private fun endpointOf(settings: AppSettings, provider: ProviderId): String = when (provider) {
    ProviderId.OLLAMA -> settings.ollamaEndpoint
    ProviderId.OPENAI -> settings.openaiEndpoint
    ProviderId.ANTHROPIC -> settings.anthropicEndpoint
    ProviderId.GOOGLE -> settings.googleEndpoint
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
        ProviderId.CUSTOM -> settings.copy(customEndpoint = value)
        // LOCAL: edit manual path GGUF (alternatif tombol "Use" di screen Models).
        ProviderId.LOCAL -> settings.copy(localModelPath = value)
    }
