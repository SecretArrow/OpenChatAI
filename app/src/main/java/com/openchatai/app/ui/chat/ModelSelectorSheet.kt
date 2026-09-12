package com.openchatai.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchatai.app.ui.components.StatusDot
import com.openchai.core.model.ModelInfo
import com.openchai.core.model.ProviderId

private data class ProviderRow(
    val id: ProviderId,
    val name: String,
    val kind: String
)

private val PROVIDER_ORDER = listOf(
    ProviderRow(ProviderId.LOCAL, "On-device (llama.cpp)", "Local"),
    ProviderRow(ProviderId.OLLAMA, "Ollama", "Local"),
    ProviderRow(ProviderId.OPENAI, "OpenAI", "Cloud"),
    ProviderRow(ProviderId.ANTHROPIC, "Claude", "Cloud"),
    ProviderRow(ProviderId.GOOGLE, "Google Gemini", "Cloud"),
    ProviderRow(ProviderId.POOLSIDE, "Poolside", "Cloud"),
    ProviderRow(ProviderId.CUSTOM, "Custom (OpenAI-compatible)", "Cloud")
)

/** True bila pesan status Ollama menunjukkan server tidak terjangkau (refused). */
private fun isOllamaUnreachable(message: String): Boolean =
    message.contains("not reachable", ignoreCase = true) ||
        message.contains("refused", ignoreCase = true)

/** Durasi uji model dalam bentuk manusiawi (ms / detik). */
private fun formatTestDuration(ms: Long): String =
    if (ms >= 1000) "%.1f s".format(ms / 1000.0) else "$ms ms"

/**
 * Model selector: daftar provider + model, status koneksi, pilihan model aktif.
 *
 * Tambahan kontrak Task 11:
 *  - Row OLLAMA: saat tidak terjangkau → tombol [Start Ollama] (vm.startOllama)
 *    + [Retry] (vm.refreshProvider) + spinner saat proses start berjalan
 *    (vm.ollamaStarting); saat connected → "Connected · N models".
 *  - Setiap model row punya tombol [Test] → vm.testModel(provider, model).
 *  - Hasil uji (vm.modelTest) ditampilkan lewat [ModelTestDialog].
 *  - Provider LOCAL tanpa model → empty-state ajakan buka tab Models.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    vm: ChatViewModel
) {
    if (!visible) return
    val settings by vm.settings.collectAsStateWithLifecycle()
    val providerStatus by vm.providerStatus.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    // Status khusus Ollama (dari VM, kontrak MAIN) + state start/test.
    val ollamaStatus by vm.ollamaStatus.collectAsStateWithLifecycle()
    val ollamaStarting by vm.ollamaStarting.collectAsStateWithLifecycle()
    val modelTest by vm.modelTest.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<ProviderId?>(settings.activeProvider) }

    LaunchedEffect(Unit) {
        vm.refreshProvider(settings.activeProvider)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Model", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                items(PROVIDER_ORDER, key = { it.id.name }) { row ->
                    // Ollama memakai StateFlow khusus dari VM; provider lain tetap
                    // membaca map providerStatus lama.
                    val status = if (row.id == ProviderId.OLLAMA) {
                        ollamaStatus
                    } else {
                        providerStatus[row.id]
                    }
                    val providerModels = models[row.id] ?: emptyList<ModelInfo>()
                    val dotColor = when (status?.connected) {
                        true -> Color(0xFF3FB950)
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.outline
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = if (expanded == row.id) null else row.id
                                // Refetch bila list kosong/belum ada — provider yang
                                // gagal sebelumnya dicoba ulang tiap kali dibuka.
                                if ((models[row.id] ?: emptyList()).isEmpty()) {
                                    vm.refreshProvider(row.id)
                                }
                            }
                            .padding(vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(dotColor)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.titleMedium)
                                // Pesan status asli dari provider; bila list yang tampil
                                // adalah cache lama, tandai dengan suffix " · cached".
                                // Ollama connected → ringkasan jumlah model.
                                val rawMessage = when {
                                    row.id == ProviderId.OLLAMA && status?.connected == true ->
                                        "Connected · ${providerModels.size} models"
                                    else -> status?.message ?: "Not checked"
                                }
                                val statusMessage =
                                    if (status?.stale == true) "$rawMessage · cached" else rawMessage
                                Text(
                                    "${row.kind} · $statusMessage",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Spinner saat mengecek; selain itu tombol refresh manual
                            // per-provider (fetch ulang list model dari base URL).
                            if (status?.connected == null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                IconButton(onClick = { vm.refreshProvider(row.id) }) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Refresh",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (settings.activeProvider == row.id) {
                                Text("●", color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        // ---- Aksi khusus Ollama saat server tidak terjangkau ----
                        // [Start Ollama] menjalankan `ollama serve` di sandbox Linux;
                        // selama proses start, spinner menggantikan tombol.
                        val ollamaUnreachable = row.id == ProviderId.OLLAMA &&
                            status?.connected == false &&
                            isOllamaUnreachable(status?.message ?: "")
                        if (ollamaUnreachable) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                if (ollamaStarting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        "Starting Ollama…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    TextButton(onClick = { vm.startOllama() }) {
                                        Text("Start Ollama")
                                    }
                                    TextButton(onClick = {
                                        vm.refreshProvider(ProviderId.OLLAMA)
                                    }) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }

                        if (expanded == row.id) {
                            Spacer(Modifier.height(6.dp))
                            if (providerModels.isEmpty()) {
                                if (row.id == ProviderId.LOCAL) {
                                    // Empty-state khusus LOCAL: arahkan ke tab Models
                                    // untuk mengunduh model GGUF on-device.
                                    Text(
                                        "No on-device model yet — open the Models tab " +
                                            "to download a GGUF model.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 18.dp)
                                    )
                                } else {
                                    // Empty-state informatif: pesan status ASLI dari
                                    // provider (bukan generik) + hint retry bila gagal.
                                    Column(modifier = Modifier.padding(start = 18.dp)) {
                                        Text(
                                            status?.message ?: "Not checked yet",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (status?.connected == false) {
                                            Text(
                                                "Tap ↻ to retry",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else if (status == null) {
                                            Text(
                                                "Tap ↻ to load models",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                providerModels.forEach { model: ModelInfo ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                vm.setActiveModel(row.id, model.id)
                                            }
                                            .padding(start = 18.dp, top = 4.dp, bottom = 4.dp)
                                    ) {
                                        Checkbox(
                                            checked = settings.activeProvider == row.id &&
                                                settings.selectedModel == model.id,
                                            onCheckedChange = {
                                                vm.setActiveModel(row.id, model.id)
                                            }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(model.name, style = MaterialTheme.typography.bodyLarge)
                                            model.details?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        // Uji cepat model: 1 prompt singkat via
                                        // ModelTester (hasilnya muncul di dialog bawah).
                                        TextButton(onClick = { vm.testModel(row.id, model.id) }) {
                                            Text("Test")
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- Dialog hasil uji model (vm.modelTest) ----
    // Tutup dialog → clear state di VM agar tidak muncul lagi saat sheet dibuka.
    modelTest?.let { state ->
        ModelTestDialog(
            state = state,
            onRetry = { providerId, modelId ->
                vm.clearModelTest()
                vm.testModel(providerId, modelId)
            },
            onDismiss = { vm.clearModelTest() }
        )
    }
}

/**
 * Dialog hasil uji satu model (kontrak Task 11):
 *  - Running → spinner "Testing model…".
 *  - Result  → "Model works ✓" + balasan (monospace, maks 8 baris) + durasi.
 *  - Failed  → "Model test failed" + reason + [View details] (dialog kedua
 *    berisi detail penuh) + [Retry] + [Close].
 */
@Composable
private fun ModelTestDialog(
    state: ChatViewModel.ModelTestUi,
    onRetry: (ProviderId, String) -> Unit,
    onDismiss: () -> Unit
) {
    var showDetail by remember(state) { mutableStateOf(false) }
    when (state) {
        is ChatViewModel.ModelTestUi.Running -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Model test") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Testing model…")
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        )

        is ChatViewModel.ModelTestUi.Result -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Model works ✓") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = state.model,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = state.reply,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatTestDuration(state.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        )

        is ChatViewModel.ModelTestUi.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Model test failed") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = state.model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Reason: ${state.error}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { showDetail = true }) {
                            Text("View details")
                        }
                        TextButton(onClick = { onRetry(state.providerId, state.model) }) {
                            Text("Retry")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }
            )
            // Dialog kedua: detail error penuh (monospace, scrollable).
            if (showDetail) {
                AlertDialog(
                    onDismissRequest = { showDetail = false },
                    title = { Text("Error details") },
                    text = {
                        Text(
                            text = state.detail ?: "-",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showDetail = false }) { Text("Close") }
                    }
                )
            }
        }
    }
}
