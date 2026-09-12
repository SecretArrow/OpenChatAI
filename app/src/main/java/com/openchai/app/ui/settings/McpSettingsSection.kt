package com.openchai.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchai.app.ui.components.StatusDot
import com.openchai.core.mcp.McpHealth
import com.openchai.core.mcp.McpHealthStatus
import com.openchai.core.mcp.McpManagerProvider
import com.openchai.core.mcp.McpServerConfig
import com.openchai.core.mcp.McpTransport
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Section "MCP SERVERS" untuk Settings (standalone, di-embed oleh SettingsScreen):
 * daftar server (nama, transport badge, status dot, jumlah tools), toggle enable,
 * Test/Edit/Delete, dan dialog tambah/ubah server (HTTP streamable / STDIO).
 */
@Composable
fun McpSettingsSection(modifier: Modifier = Modifier) {
    val manager = remember { McpManagerProvider.get(LocalContext.current) }
    val configs by manager.configs.collectAsStateWithLifecycle()
    val health by manager.health.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // null = dialog tertutup; id kosong = tambah baru; id terisi = edit.
    var editing by remember { mutableStateOf<ServerFormState?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<Map<String, Pair<Boolean, String>>>(emptyMap()) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "MCP SERVERS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (configs.isEmpty()) {
                    Text(
                        "Belum ada server MCP. Tambahkan server HTTP (streamable) " +
                            "atau STDIO (proses lokal) agar agent bisa memakai tool eksternal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                configs.forEach { config ->
                    McpServerRow(
                        config = config,
                        health = health[config.id] ?: McpHealth(),
                        testing = testingId == config.id,
                        testResult = testResults[config.id],
                        onToggle = { enabled -> manager.update(config.copy(enabled = enabled)) },
                        onTest = {
                            scope.launch {
                                testingId = config.id
                                val result = manager.testServer(config)
                                val message = result.fold(
                                    onSuccess = { true to it },
                                    onFailure = { false to (it.message ?: "Failed") }
                                )
                                testResults = testResults + (config.id to message)
                                testingId = null
                            }
                        },
                        onEdit = { editing = fromConfig(config) },
                        onDelete = {
                            manager.remove(config.id)
                            testResults = testResults - config.id
                        }
                    )
                }
                TextButton(onClick = { editing = ServerFormState() }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add MCP server")
                }
            }
        }
    }

    editing?.let { form ->
        McpServerDialog(
            form = form,
            onDismiss = { editing = null },
            onSave = { saved ->
                if (saved.id.isBlank()) manager.add(saved.toConfig()) else manager.update(saved.toConfig())
                editing = null
            }
        )
    }
}

// ----------------------------------------------------------------------
// Baris satu server
// ----------------------------------------------------------------------

@Composable
private fun McpServerRow(
    config: McpServerConfig,
    health: McpHealth,
    testing: Boolean,
    testResult: Pair<Boolean, String>?,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(config.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    summaryOf(config),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = config.enabled, onCheckedChange = onToggle)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusDot(color = healthColor(health.status, MaterialTheme.colorScheme.error))
            Text(
                statusText(health, config),
                style = MaterialTheme.typography.bodySmall,
                color = if (health.status == McpHealthStatus.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (health.status == McpHealthStatus.ERROR && !health.message.isNullOrBlank()) {
            Text(
                health.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onTest, enabled = !testing) {
                Text(if (testing) "Testing…" else "Test")
            }
            Box(modifier = Modifier.weight(1f)) {
                testResult?.let { (ok, message) ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${config.name}")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete ${config.name}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// Dialog tambah / ubah server
// ----------------------------------------------------------------------

@Composable
private fun McpServerDialog(
    form: ServerFormState,
    onDismiss: () -> Unit,
    onSave: (ServerFormState) -> Unit
) {
    var name by remember(form) { mutableStateOf(form.name) }
    var transport by remember(form) { mutableStateOf(form.transport) }
    var url by remember(form) { mutableStateOf(form.url) }
    var command by remember(form) { mutableStateOf(form.command) }
    var argsCsv by remember(form) { mutableStateOf(form.argsCsv) }
    var headersText by remember(form) { mutableStateOf(form.headersText) }
    var enabled by remember(form) { mutableStateOf(form.enabled) }

    val state = ServerFormState(form.id, name, transport, url, command, argsCsv, headersText, enabled)
    val errors = validateForm(state)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.id.isBlank()) "Add MCP server" else "Edit MCP server") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = errors.containsKey("name"),
                    supportingText = { errors["name"]?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Column {
                    Text(
                        "Transport",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TransportDropdown(
                        selected = transport,
                        onSelect = { transport = it }
                    )
                }
                if (transport == McpTransport.HTTP) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://example.com/mcp") },
                        singleLine = true,
                        isError = errors.containsKey("url"),
                        supportingText = { errors["url"]?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Command") },
                        placeholder = { Text("node /path/to/server.js") },
                        singleLine = true,
                        isError = errors.containsKey("command"),
                        supportingText = { errors["command"]?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = argsCsv,
                        onValueChange = { argsCsv = it },
                        label = { Text("Arguments (dipisah koma)") },
                        placeholder = { Text("--port 3000, --verbose") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = headersText,
                    onValueChange = { headersText = it },
                    label = { Text("Headers (per baris \"Key: Value\")") },
                    placeholder = { Text("Authorization: Bearer sk-...") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { enabled = !enabled }
                ) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = errors.isEmpty(), onClick = { onSave(state) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun TransportDropdown(selected: McpTransport, onSelect: (McpTransport) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(transportLabel(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            McpTransport.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(transportLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// Form state & helper
// ----------------------------------------------------------------------

/** State form dialog; [id] kosong berarti mode tambah server baru. */
private data class ServerFormState(
    val id: String = "",
    val name: String = "",
    val transport: McpTransport = McpTransport.HTTP,
    val url: String = "",
    val command: String = "",
    val argsCsv: String = "",
    val headersText: String = "",
    val enabled: Boolean = true
)

private fun fromConfig(config: McpServerConfig) = ServerFormState(
    id = config.id,
    name = config.name,
    transport = config.transport,
    url = config.url,
    command = config.command,
    argsCsv = config.args.joinToString(", "),
    headersText = config.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" },
    enabled = config.enabled
)

private fun ServerFormState.toConfig(): McpServerConfig = McpServerConfig(
    id = id.ifBlank { UUID.randomUUID().toString() },
    name = name.trim(),
    transport = transport,
    url = url.trim(),
    command = command.trim(),
    args = parseArgsCsv(argsCsv),
    headers = parseHeaders(headersText),
    enabled = enabled
)

/** Validasi minimal: nama wajib; HTTP → url http(s); STDIO → command wajib. */
private fun validateForm(form: ServerFormState): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (form.name.isBlank()) errors["name"] = "Name is required"
    when (form.transport) {
        McpTransport.HTTP -> {
            val value = form.url.trim()
            when {
                value.isEmpty() -> errors["url"] = "URL is required"
                !value.startsWith("http://") && !value.startsWith("https://") ->
                    errors["url"] = "URL must start with http:// or https://"
            }
        }
        McpTransport.STDIO -> {
            if (form.command.isBlank()) errors["command"] = "Command is required"
        }
    }
    return errors
}

/** "Key: Value" per baris → Map; baris tanpa ':' / kosong dilewati. */
private fun parseHeaders(text: String): Map<String, String> = text.lineSequence()
    .mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) return@mapNotNull null
        val key = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim()
        if (key.isEmpty() || value.isEmpty()) null else key to value
    }
    .toMap()

/** CSV argumen sederhana: dipisah koma, trim, kosong dihapus. */
private fun parseArgsCsv(text: String): List<String> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun transportLabel(transport: McpTransport): String = when (transport) {
    McpTransport.HTTP -> "HTTP (streamable)"
    McpTransport.STDIO -> "STDIO (proses lokal)"
}

private fun summaryOf(config: McpServerConfig): String = when (config.transport) {
    McpTransport.HTTP -> "HTTP · ${config.url.ifBlank { "(url belum diisi)" }}"
    McpTransport.STDIO ->
        "STDIO · " + (listOf(config.command) + config.args)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "(command belum diisi)" }
}

private fun statusText(health: McpHealth, config: McpServerConfig): String {
    val base = when (health.status) {
        McpHealthStatus.CONNECTED ->
            if (health.toolCount > 0) "Connected · ${health.toolCount} tools" else "Connected"
        McpHealthStatus.CONNECTING -> "Connecting…"
        McpHealthStatus.ERROR -> "Error"
        McpHealthStatus.DISCONNECTED -> if (config.enabled) "Disconnected" else "Disabled"
    }
    return "${if (config.transport == McpTransport.HTTP) "HTTP" else "STDIO"} · $base"
}

/** Warna status dot: hijau konek, kuning nyambung, abu putus, merah error. */
private fun healthColor(status: McpHealthStatus, error: Color): Color = when (status) {
    McpHealthStatus.CONNECTED -> Color(0xFF4CAF50)
    McpHealthStatus.CONNECTING -> Color(0xFFFFB300)
    McpHealthStatus.DISCONNECTED -> Color(0xFF9E9E9E)
    McpHealthStatus.ERROR -> error
}
