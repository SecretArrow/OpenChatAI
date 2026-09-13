package com.openchatai.app.ui.runtime

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.openchatai.app.ui.theme.AppMotion
import com.openchai.core.llm.InstallPhase
import com.openchai.core.llm.PackStatus
import com.openchai.core.llm.PackView
import com.openchai.core.llm.humanBytes

/**
 * Screen Runtime & Modul: kelola runtime AI lokal (llama.cpp) dan modul
 * pendukung dari manifest.
 *
 *  - TopAppBar dengan aksi refresh manifest (info manifest tampil saat ada).
 *  - Kartu runtime aktif + penjelasan fallback bawaan APK.
 *  - Switch pasang otomatis (hanya update pack terpasang & modul wajib).
 *  - Kartu per pack RUNTIME & MODULE (icon tonal, chip status FilterChip):
 *    Pasang/Perbarui, Jeda/Lanjut/Batal (unduhan resume dari part), Hapus
 *    dengan dialog konfirmasi.
 *  - Error unduhan/pemasangan ditampilkan sebagai snackbar (sekali per pesan).
 *
 * Visual Material 3: Scaffold + TopAppBar, kartu ElevatedCard per pack,
 * tombol FilledTonalButton (pasang) / OutlinedButton (hapus), motion
 * AppMotion pada perubahan isi kartu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeScreen(modifier: Modifier = Modifier) {
    val vm: RuntimeViewModel = viewModel()

    val packs by vm.packs.collectAsStateWithLifecycle()
    val installStates by vm.installStates.collectAsStateWithLifecycle()
    val autoInstall by vm.autoInstall.collectAsStateWithLifecycle()
    val manifestInfo by vm.manifestInfo.collectAsStateWithLifecycle()

    // Pack yang menunggu konfirmasi hapus (AlertDialog di bawah).
    var pendingRemove by remember { mutableStateOf<PackView?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Mekanisme clear sederhana: id pesan error terakhir yang sudah tampil per
    // pack disimpan di remember — snackbar hanya muncul sekali per pesan baru.
    val lastShownError = remember { mutableStateMapOf<String, String>() }

    // Signature gabungan seluruh error aktif — hanya berubah bila SET error
    // berubah, sehingga efek ini tidak di-restart tiap tick progress unduhan.
    val errorSignature = installStates.entries
        .mapNotNull { (id, st) -> st.error?.let { "$id:$it" } }
        .sorted()
        .joinToString("|")
    LaunchedEffect(errorSignature) {
        if (errorSignature.isEmpty()) return@LaunchedEffect
        installStates.forEach { (packId, state) ->
            val err = state.error ?: return@forEach
            if (lastShownError[packId] != err) {
                lastShownError[packId] = err
                snackbarHostState.showSnackbar(
                    message = err,
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    val runtimePacks = packs.filter { it.kind == "RUNTIME" }
    val modulePacks = packs.filter { it.kind == "MODULE" }

    Scaffold(
        modifier = modifier,
        // Inset ditangani Scaffold luar (NavGraph) — cegah padding ganda.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // App bar M3 dengan aksi refresh manifest.
            TopAppBar(
                title = { Text("Runtime & Modul") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Muat ulang manifest"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------- Intro + info manifest ----------------
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Kelola runtime AI lokal (llama.cpp) dan modul pendukung. " +
                            "Unduhan mendukung jeda/lanjut.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    manifestInfo?.let { info ->
                        Text(
                            info,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            // ---------------- Runtime aktif ----------------
            item {
                SectionCard("RUNTIME AKTIF") {
                    Text(
                        vm.activeRuntimeLabel(),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Runtime bawaan APK selalu tersedia sebagai fallback. Runtime pack " +
                            "terpasang dipakai setelah aplikasi dimulai ulang; menghapus pack " +
                            "langsung mengembalikan ke bawaan pada mulai ulang berikutnya.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // ---------------- Pengaturan ----------------
            item {
                SectionCard("PENGATURAN") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Pasang otomatis",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Perbarui runtime terpasang & pasang modul wajib saat manifest " +
                                    "baru (tidak pernah mengunduh pack opsional yang belum kamu pasang)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = autoInstall, onCheckedChange = { vm.setAuto(it) })
                    }
                }
            }
            // ---------------- Pack runtime — satu kartu per pack ----------------
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionTitle("RUNTIME")
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        runtimePacks.forEach { pack ->
                            PackRow(
                                pack = pack,
                                onInstall = { vm.install(pack.id) },
                                onPause = { vm.pause(pack.id) },
                                onCancel = { vm.cancel(pack.id) },
                                onRemove = { pendingRemove = pack }
                            )
                        }
                    }
                }
            }
            // ---------------- Modul & pustaka — satu kartu per pack ----------------
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionTitle("MODUL & PUSTAKA")
                    if (modulePacks.isEmpty()) {
                        Text(
                            "Belum ada modul di manifest.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            modulePacks.forEach { pack ->
                                PackRow(
                                    pack = pack,
                                    onInstall = { vm.install(pack.id) },
                                    onPause = { vm.pause(pack.id) },
                                    onCancel = { vm.cancel(pack.id) },
                                    onRemove = { pendingRemove = pack }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- Konfirmasi hapus pack ----------------
    pendingRemove?.let { pack ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Hapus pack ${pack.name}?") },
            text = { Text("Runtime bawaan tetap tersedia sebagai fallback.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(pack.id)
                    pendingRemove = null
                }) {
                    Text("Hapus", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text("Batal")
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
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** Kartu satu section dengan header label kecil berwarna primary (uppercase). */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(title)
        ElevatedCard(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

/**
 * Ikon dalam wadah tonal (surfaceContainerHigh, bentuk membulat) — anchor
 * visual tiap pack: runtime → Memory, modul → Storage.
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

/** Teks chip status sesuai status pack (versi fallback "?" bila versi null). */
private fun statusText(pack: PackView): String = when (pack.status) {
    PackStatus.BUNDLED -> "Bawaan APK"
    PackStatus.INSTALLED -> "Terpasang v${pack.installedVersion ?: "?"}"
    PackStatus.UPDATABLE ->
        "Tersedia pembaruan v${pack.installedVersion ?: "?"} → v${pack.availableVersion ?: "?"}"
    PackStatus.AVAILABLE -> "Tersedia v${pack.availableVersion ?: "?"}"
}

/**
 * Warna FilterChip status (pola M3): terpasang → primaryContainer,
 * ada pembaruan → tertiaryContainer, lainnya → netral surfaceContainerHigh.
 */
@Composable
private fun statusChipColors(pack: PackView) = when (pack.status) {
    PackStatus.UPDATABLE -> FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
    )
    PackStatus.INSTALLED -> FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
    else -> FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Kartu satu pack: ikon tonal per jenis + nama + ukuran + chip status
 * (FilterChip) + deskripsi + progress unduhan/ekstraksi + tombol aksi.
 * Kartu bundled (removable=false) hanya menampilkan status tanpa tombol.
 * Selama fase EXTRACTING tidak ada tombol aksi yang tampil (implisit
 * disabled) — ekstraksi tidak dapat dijeda.
 */
@Composable
private fun PackRow(
    pack: PackView,
    onInstall: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
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
                TonalIcon(
                    if (pack.kind == "RUNTIME") Icons.Rounded.Memory else Icons.Rounded.Storage
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium)
                    if (pack.sizeBytes > 0) {
                        Text(
                            humanBytes(pack.sizeBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // Chip status M3 — FilterChip non-interaktif (murni indikator).
            FilterChip(
                selected = pack.status == PackStatus.INSTALLED ||
                    pack.status == PackStatus.UPDATABLE,
                onClick = { /* chip status tanpa aksi */ },
                label = {
                    Text(statusText(pack), style = MaterialTheme.typography.labelMedium)
                },
                colors = statusChipColors(pack)
            )
            if (pack.description.isNotBlank()) {
                Text(
                    pack.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Progress saat fase aktif (bentuk lambda — overload non-lambda deprecated).
            when (pack.installPhase) {
                InstallPhase.DOWNLOADING, InstallPhase.PAUSED, InstallPhase.EXTRACTING -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = {
                                if (pack.totalBytes > 0) {
                                    pack.progressBytes.toFloat() / pack.totalBytes
                                } else {
                                    0f
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Text(
                            "${humanBytes(pack.progressBytes)} / ${humanBytes(pack.totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            when (pack.installPhase) {
                                InstallPhase.DOWNLOADING -> "Mengunduh…"
                                InstallPhase.PAUSED -> "Dijeda"
                                else -> "Memasang…"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                else -> Unit
            }

            // Error terakhir saat fase FAILED — blok errorContainer + WarningAmber.
            if (pack.installPhase == InstallPhase.FAILED && pack.error != null) {
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
                            pack.error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Baris tombol aksi (semua kondisi dievaluasi dulu agar Row kosong
            // tidak dirender; fase EXTRACTING menghasilkan semua flag false).
            val showInstall =
                (pack.status == PackStatus.AVAILABLE || pack.status == PackStatus.UPDATABLE) &&
                    (pack.installPhase == InstallPhase.IDLE ||
                        pack.installPhase == InstallPhase.FAILED ||
                        pack.installPhase == InstallPhase.DONE)
            val showPause = pack.installPhase == InstallPhase.DOWNLOADING
            val showResume = pack.installPhase == InstallPhase.PAUSED
            val showRemove = pack.removable &&
                (pack.status == PackStatus.INSTALLED || pack.status == PackStatus.UPDATABLE) &&
                pack.installPhase != InstallPhase.DOWNLOADING &&
                pack.installPhase != InstallPhase.PAUSED &&
                pack.installPhase != InstallPhase.EXTRACTING
            if (showInstall || showPause || showResume || showRemove) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showInstall) {
                        FilledTonalButton(onClick = onInstall) {
                            Text(if (pack.status == PackStatus.UPDATABLE) "Perbarui" else "Pasang")
                        }
                    }
                    if (showPause) {
                        TextButton(onClick = onPause) { Text("Jeda") }
                        TextButton(onClick = onCancel) { Text("Batal") }
                    }
                    if (showResume) {
                        FilledTonalButton(onClick = onInstall) { Text("Lanjut") }
                        TextButton(onClick = onCancel) { Text("Batal") }
                    }
                    if (showRemove) {
                        OutlinedButton(
                            onClick = onRemove,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Hapus")
                        }
                    }
                }
            }
        }
    }
}
