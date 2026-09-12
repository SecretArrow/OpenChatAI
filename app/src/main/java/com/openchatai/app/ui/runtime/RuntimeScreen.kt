package com.openchatai.app.ui.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openchai.core.llm.InstallPhase
import com.openchai.core.llm.PackStatus
import com.openchai.core.llm.PackView
import com.openchai.core.llm.humanBytes

/**
 * Screen Runtime & Modul: kelola runtime AI lokal (llama.cpp) dan modul
 * pendukung dari manifest.
 *
 *  - Header + tombol refresh manifest (info manifest tampil saat ada).
 *  - Kartu runtime aktif + penjelasan fallback bawaan APK.
 *  - Switch pasang otomatis (hanya update pack terpasang & modul wajib).
 *  - Daftar pack RUNTIME & MODULE: Pasang/Perbarui, Jeda/Lanjut/Batal
 *    (unduhan resume dari part), Hapus dengan dialog konfirmasi.
 *  - Error unduhan/pemasangan ditampilkan sebagai snackbar (sekali per pesan).
 */
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

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------- Header + refresh manifest ----------------
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Runtime & Modul",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                "Kelola runtime AI lokal (llama.cpp) dan modul pendukung. " +
                                    "Unduhan mendukung jeda/lanjut.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Muat ulang manifest"
                            )
                        }
                    }
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
                        style = MaterialTheme.typography.bodySmall,
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
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = autoInstall, onCheckedChange = { vm.setAuto(it) })
                    }
                }
            }
            // ---------------- Pack runtime ----------------
            item {
                SectionCard("RUNTIME") {
                    runtimePacks.forEachIndexed { index, pack ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
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
            // ---------------- Modul & pustaka ----------------
            item {
                SectionCard("MODUL & PUSTAKA") {
                    if (modulePacks.isEmpty()) {
                        Text(
                            "Belum ada modul di manifest.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        modulePacks.forEachIndexed { index, pack ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
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

        // Snackbar error unduhan/pemasangan.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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

/** Teks chip status sesuai status pack (versi fallback "?" bila versi null). */
private fun statusText(pack: PackView): String = when (pack.status) {
    PackStatus.BUNDLED -> "Bawaan APK"
    PackStatus.INSTALLED -> "Terpasang v${pack.installedVersion ?: "?"}"
    PackStatus.UPDATABLE ->
        "Tersedia pembaruan v${pack.installedVersion ?: "?"} → v${pack.availableVersion ?: "?"}"
    PackStatus.AVAILABLE -> "Tersedia v${pack.availableVersion ?: "?"}"
}

/**
 * Satu baris pack: nama + chip status berwarna + deskripsi + ukuran + progress
 * unduhan/ekstraksi + tombol aksi. Kartu bundled (removable=false) hanya
 * menampilkan status tanpa tombol. Selama fase EXTRACTING tidak ada tombol
 * aksi yang tampil (implisit disabled) — ekstraksi tidak dapat dijeda.
 */
@Composable
private fun PackRow(
    pack: PackView,
    onInstall: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(pack.name, style = MaterialTheme.typography.titleMedium)
        // Chip teks status: UPDATABLE → tertiary, INSTALLED → primary,
        // lainnya (BUNDLED/AVAILABLE) → onSurfaceVariant.
        Text(
            text = statusText(pack),
            style = MaterialTheme.typography.labelSmall,
            color = when (pack.status) {
                PackStatus.UPDATABLE -> MaterialTheme.colorScheme.tertiary
                PackStatus.INSTALLED -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (pack.description.isNotBlank()) {
            Text(
                pack.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (pack.sizeBytes > 0) {
            Text(
                humanBytes(pack.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${humanBytes(pack.progressBytes)} / ${humanBytes(pack.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        when (pack.installPhase) {
                            InstallPhase.DOWNLOADING -> "Mengunduh…"
                            InstallPhase.PAUSED -> "Dijeda"
                            else -> "Memasang…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            else -> Unit
        }

        // Error terakhir saat fase FAILED.
        if (pack.installPhase == InstallPhase.FAILED && pack.error != null) {
            Text(
                pack.error ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
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
                    Button(onClick = onInstall) {
                        Text(if (pack.status == PackStatus.UPDATABLE) "Perbarui" else "Pasang")
                    }
                }
                if (showPause) {
                    TextButton(onClick = onPause) { Text("Jeda") }
                    TextButton(onClick = onCancel) { Text("Batal") }
                }
                if (showResume) {
                    Button(onClick = onInstall) { Text("Lanjut") }
                    TextButton(onClick = onCancel) { Text("Batal") }
                }
                if (showRemove) {
                    TextButton(onClick = onRemove) {
                        Text("Hapus", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
