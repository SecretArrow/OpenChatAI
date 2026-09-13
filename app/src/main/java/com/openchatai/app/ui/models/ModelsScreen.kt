package com.openchatai.app.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openchatai.app.ui.components.StatusDot
import com.openchatai.app.ui.theme.AppMotion
import com.openchai.core.llm.ModelManager
import com.openchai.core.llm.humanBytes
import java.io.File

/**
 * Screen Models: kelola AI on-device (llama.cpp).
 *
 *  - Banner rekomendasi model sesuai RAM perangkat.
 *  - Impor file GGUF dari penyimpanan (SAF OpenDocument) + indikator transfer.
 *  - Daftar model terpasang: tombol "Use" / "Export" (SAF CreateDocument) / "Delete".
 *  - Katalog GGUF: tombol Download; saat berjalan progress MB + Pause; saat
 *    dijeda Resume/Discard; Retry otomatis melanjutkan dari part tersisa;
 *    part tertinggal setelah proses mati ditawarkan "Resume (ukuran)".
 *
 * Visual Material 3: Scaffold + CenterAlignedTopAppBar, kartu model dengan
 * ikon SmartToy tonal, meta model sebagai AssistChip, status terpasang
 * CheckCircle, tombol unduh FilledTonalButton, motion AppMotion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ModelsViewModel = viewModel()
) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val downloads by vm.downloadStates.collectAsStateWithLifecycle()
    val resumable by vm.resumable.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val transfer by vm.transferState.collectAsStateWithLifecycle()

    // Model yang menunggu konfirmasi hapus.
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    // Id model yang menunggu pemilihan tujuan ekspor (SAF CreateDocument).
    var pendingExportId by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Launcher impor: SAF OpenDocument tanpa filter MIME (file .gguf jarang
    // terdaftar dengan MIME type resmi).
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importModel(uri) }

    // Launcher ekspor: SAF CreateDocument, saran nama = nama file asli.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) vm.exportModel(id, uri)
    }

    // Snackbar hasil transfer (snapshot agar konsisten selama koroutine jalan):
    // DONE → info lalu auto reset; FAILED → error dengan aksi "Dismiss".
    val reported = transfer
    LaunchedEffect(reported) {
        when (reported?.phase) {
            ModelManager.TransferPhase.DONE -> {
                snackbarHostState.showSnackbar(
                    message = reported.message ?: "Transfer completed",
                    duration = SnackbarDuration.Short
                )
                vm.clearTransfer()
            }
            ModelManager.TransferPhase.FAILED -> {
                snackbarHostState.showSnackbar(
                    message = reported.message ?: "Transfer failed",
                    actionLabel = "Dismiss",
                    duration = SnackbarDuration.Indefinite
                )
                // Reset status apapun cara snackbar ditutup (aksi maupun swipe).
                vm.clearTransfer()
            }
            else -> Unit
        }
    }

    Scaffold(
        modifier = modifier,
        // Inset ditangani Scaffold luar (NavGraph) — cegah padding ganda.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // App bar M3 terpusat — layar tab top-level.
            CenterAlignedTopAppBar(
                title = { Text("Models") }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Text(
                "On-device AI (llama.cpp) — download a GGUF model once, chat offline after that.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            RecommendationBanner(vm.recommendationText())

            // Indikator transfer (impor/ekspor) sedang berjalan — muncul/hilang
            // dengan motion M3 (fade + expand via token AppMotion).
            AnimatedVisibility(
                visible = reported?.phase == ModelManager.TransferPhase.RUNNING,
                enter = fadeIn(AppMotion.standardTween()) +
                    expandVertically(AppMotion.standardTween()),
                exit = fadeOut(AppMotion.standardTween()) +
                    shrinkVertically(AppMotion.standardTween())
            ) {
                TransferIndicator(reported?.message)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---------------- Terpasang ----------------
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionTitle("INSTALLED")
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) }
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Import GGUF")
                        }
                    }
                }
                if (installed.isEmpty()) {
                    item {
                        Text(
                            "No models installed yet — download from the catalog below or import a GGUF file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    item {
                        ElevatedCard(
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                installed.forEachIndexed { index, file ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            modifier = Modifier.padding(vertical = 10.dp)
                                        )
                                    }
                                    InstalledRow(
                                        file = file,
                                        isActive = settings.localModelPath == file.absolutePath,
                                        onUse = { vm.useModel(file) { onOpenChat() } },
                                        onExport = {
                                            // Id = nama file tanpa suffix ".gguf";
                                            // saran nama dokumen = nama file asli.
                                            pendingExportId = file.name.removeSuffix(".gguf")
                                            exportLauncher.launch(file.name)
                                        },
                                        onDelete = { pendingDelete = file }
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------------- Katalog ----------------
                item { SectionTitle("CATALOG") }
                items(catalog, key = { it.id }) { model ->
                    CatalogRow(
                        model = model,
                        state = downloads[model.id],
                        isInstalled = installed.any { it.name == "${model.id}.gguf" },
                        resumableBytes = resumable[model.id],
                        onDownload = { vm.download(model) },
                        onPause = { vm.pauseDownload(model.id) },
                        onCancel = { vm.cancelDownload(model.id) }
                    )
                }
            }
        }
    }

    // ---------------- Konfirmasi hapus ----------------
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete model?") },
            text = { Text("${file.name} (${humanBytes(file.length())}) will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(file.name.removeSuffix(".gguf"))
                    pendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** Judul section kecil berwarna primary (uppercase) — pola label M3. */
@Composable
private fun SectionTitle(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

/**
 * Ikon dalam wadah tonal (surfaceContainerHigh, bentuk membulat) — pola kartu
 * model M3: setiap model punya anchor visual SmartToy.
 */
@Composable
private fun TonalIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(8.dp)
                .size(20.dp)
        )
    }
}

/** Banner rekomendasi RAM — gaya sama dengan StatusBanner di ChatScreen. */
@Composable
private fun RecommendationBanner(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Indikator transfer (impor/ekspor) berjalan — pola visual RecommendationBanner. */
@Composable
private fun TransferIndicator(message: String?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .animateContentSize(AppMotion.standardTween())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message ?: "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            // Indeterminate memakai gaya default M3.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Baris model terpasang: ikon tonal, nama, ukuran, badge aktif, aksi model. */
@Composable
private fun InstalledRow(
    file: File,
    isActive: Boolean,
    onUse: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TonalIcon(Icons.Rounded.SmartToy)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    humanBytes(file.length()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    // Status aktif: ikon CheckCircle (M3) + label kecil.
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Model aktif",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        if (!isActive) {
            FilledTonalButton(onClick = onUse) { Text("Use") }
        }
        TextButton(onClick = onExport) { Text("Export") }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "Delete model",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * Kartu katalog model: ikon SmartToy tonal + nama + meta (quant/ukuran/family
 * sebagai AssistChip) + deskripsi + matriks aksi unduhan:
 *  - DOWNLOADING → progress bar + "x / y" + Pause,
 *  - PAUSED → Resume + "Paused · x / y" + Discard (buang part),
 *  - FAILED → Retry (auto-resume dari part) + blok error,
 *  - ada part tapi tanpa state aktif (proses mati) → Resume (ukuran) + Discard,
 *  - terpasang → status CheckCircle; selain itu → Download.
 */
@Composable
private fun CatalogRow(
    model: ModelManager.CatalogModel,
    state: ModelManager.DownloadState?,
    isInstalled: Boolean,
    resumableBytes: Long?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(AppMotion.standardTween())
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TonalIcon(Icons.Rounded.SmartToy)
                Spacer(Modifier.width(12.dp))
                Text(
                    model.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            // Meta model sebagai AssistChip M3 (quant, ukuran, family) —
            // bisa digulir horizontal bila layar sempit.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { /* chip meta tanpa aksi */ },
                    label = {
                        Text(model.quant, style = MaterialTheme.typography.labelMedium)
                    }
                )
                AssistChip(
                    onClick = { /* chip meta tanpa aksi */ },
                    label = {
                        Text(
                            humanBytes(model.sizeBytes),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                )
                AssistChip(
                    onClick = { /* chip meta tanpa aksi */ },
                    label = {
                        Text(model.family, style = MaterialTheme.typography.labelMedium)
                    }
                )
            }
            Text(
                model.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val st = state
            when {
                st != null && st.state == ModelManager.DownloadPhase.DOWNLOADING -> {
                    val fraction = if (st.totalBytes > 0) {
                        (st.progressBytes.toFloat() / st.totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else null
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    } else {
                        // Indeterminate memakai gaya default M3.
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${humanBytes(st.progressBytes)} / ${humanBytes(st.totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onPause) { Text("Pause") }
                    }
                }

                st != null && st.state == ModelManager.DownloadPhase.PAUSED -> {
                    Text(
                        "Paused · ${humanBytes(st.progressBytes)} / ${humanBytes(st.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = onDownload) { Text("Resume") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onCancel) { Text("Discard") }
                    }
                }

                st != null && st.state == ModelManager.DownloadPhase.FAILED -> {
                    // Blok error M3: errorContainer + WarningAmber + onErrorContainer.
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                st.error ?: "Download failed",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    // Retry memanggil download lagi — part tersisa dipakai resume.
                    FilledTonalButton(onClick = onDownload) { Text("Retry") }
                }

                isInstalled -> {
                    // Status terpasang: ikon CheckCircle + teks primary.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Installed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Proses mati saat mengunduh: state hilang tapi part masih ada
                // → tawarkan lanjutkan unduhan (ukuran part dalam kurung).
                resumableBytes != null && resumableBytes > 0L -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = onDownload) {
                            Text("Resume (${humanBytes(resumableBytes)})")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onCancel) { Text("Discard") }
                    }
                }

                else -> {
                    FilledTonalButton(onClick = onDownload) { Text("Download") }
                }
            }
        }
    }
}
