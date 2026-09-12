package com.openchai.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchai.app.ui.components.StatusDot
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
    ProviderRow(ProviderId.CUSTOM, "Custom (OpenAI-compatible)", "Cloud")
)

/**
 * Model selector: daftar provider + model, status koneksi, pilihan model aktif.
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
                    val status = providerStatus[row.id]
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
                                val rawMessage = status?.message ?: "Not checked"
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
                        if (expanded == row.id) {
                            Spacer(Modifier.height(6.dp))
                            if (providerModels.isEmpty()) {
                                // Empty-state informatif: pesan status ASLI dari provider
                                // (bukan generik) + hint retry bila gagal / belum dicek.
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
                                        Column {
                                            Text(model.name, style = MaterialTheme.typography.bodyLarge)
                                            model.details?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
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
}
