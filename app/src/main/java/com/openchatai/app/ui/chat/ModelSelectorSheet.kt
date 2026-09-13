package com.openchatai.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchatai.app.ui.theme.AppMotion
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

/*
 * Catatan warna: literal hijau lama untuk "connected" dihapus — status koneksi
 * kini murni peran colorScheme M3: CheckCircle = secondary (tersambung),
 * Cancel = error (putus), Hub outlined = outline (belum dicek).
 */

/**
 * Model selector — Material Design 3: ModalBottomSheet (dragHandle default)
 * berisi ListItem per provider; status koneksi lewat ikon (CheckCircle
 * secondary / Cancel error / Hub outline), provider aktif ditandai CheckCircle
 * primary, model dipilih lewat FilterChip.
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
                            .padding(vertical = 4.dp)
                    ) {
                        // Baris provider (ListItem M3): leading = ikon status
                        // koneksi, trailing = spinner saat mengecek / refresh manual.
                        ListItem(
                            headlineContent = {
                                Text(row.name, style = MaterialTheme.typography.titleMedium)
                            },
                            supportingContent = {
                                Text(
                                    "${row.kind} · $statusMessage",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingContent = {
                                when (status?.connected) {
                                    true -> Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Connected",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    false -> Icon(
                                        imageVector = Icons.Rounded.Cancel,
                                        contentDescription = "Disconnected",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    null -> Icon(
                                        imageVector = Icons.Outlined.Hub,
                                        contentDescription = "Not checked",
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (settings.activeProvider == row.id) {
                                        // Penanda provider aktif (dulu teks "●").
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = "Active provider",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .size(20.dp)
                                        )
                                    }
                                    if (status?.connected == null) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        // Refresh manual per-provider (fetch ulang
                                        // list model dari base URL).
                                        IconButton(onClick = { vm.refreshProvider(row.id) }) {
                                            Icon(
                                                Icons.Rounded.Refresh,
                                                contentDescription = "Refresh",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        )

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
                                    FilledTonalButton(onClick = { vm.startOllama() }) {
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

                        // Daftar model expand/collapse dengan motion M3.
                        AnimatedVisibility(
                            visible = expanded == row.id,
                            enter = fadeIn(AppMotion.standardTween()) +
                                expandVertically(AppMotion.standardTween()),
                            exit = fadeOut(AppMotion.standardTween()) +
                                shrinkVertically(AppMotion.standardTween())
                        ) {
                            Column {
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
                                        val isActiveModel = settings.activeProvider == row.id &&
                                            settings.selectedModel == model.id
                                        // Baris model: FilterChip M3 (seleksi tunggal)
                                        // + tombol uji cepat.
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 18.dp, top = 2.dp, bottom = 2.dp)
                                        ) {
                                            FilterChip(
                                                selected = isActiveModel,
                                                onClick = { vm.setActiveModel(row.id, model.id) },
                                                label = {
                                                    Text(
                                                        text = model.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                },
                                                leadingIcon = {
                                                    if (isActiveModel) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Check,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                            // Uji cepat model: 1 prompt singkat via
                                            // ModelTester (hasilnya muncul di dialog bawah).
                                            TextButton(onClick = { vm.testModel(row.id, model.id) }) {
                                                Text("Test")
                                            }
                                        }
                                        model.details?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(start = 18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
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
 * Dialog hasil uji satu model (kontrak Task 11) — AlertDialog M3 dengan ikon
 * status (CheckCircle secondary saat sukses, Warning error saat gagal):
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
            icon = {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(28.dp)
                )
            },
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
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                },
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
