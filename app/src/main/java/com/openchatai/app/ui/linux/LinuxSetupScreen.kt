package com.openchatai.app.ui.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openchai.core.linux.DeviceCapability
import com.openchai.core.linux.LinuxEnvPhase
import com.openchai.core.linux.LinuxInstallState
import com.openchai.core.linux.RootfsVariant
import com.openchai.core.llm.humanBytes

/**
 * Screen setup lingkungan Linux (proot + rootfs Ubuntu):
 *  - Card PERANGKAT: ABI, RAM total, storage bebas, status didukung/tidak.
 *  - Card STATUS: chip fase + progress unduhan/ekstraksi/bootstrap + refresh.
 *  - Card PILIH ROOTFS: pilihan variant per-ABI (Pasang / Jeda / Lanjut / Batal).
 *  - Card MANAJEMEN (bila READY): hapus lingkungan dengan dialog konfirmasi.
 *  - Tombol "Mulai coding →" kembali ke Chat bila lingkungan siap.
 */
@Composable
fun LinuxSetupScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    vm: LinuxSetupViewModel = viewModel()
) {
    val status by vm.status.collectAsStateWithLifecycle()
    val installState by vm.installState.collectAsStateWithLifecycle()
    val capability by vm.capability.collectAsStateWithLifecycle()

    // Konfirmasi hapus lingkungan Linux.
    var showRemoveDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    // Penanda kegagalan yang sudah dilaporkan (identitas objek) agar snackbar
    // tidak muncul berulang untuk state FAILED yang sama.
    var reportedFailure by remember { mutableStateOf<Any?>(null) }

    // Snackbar error instalasi (kontrak: error installState).
    LaunchedEffect(installState) {
        val st = installState
        if (st != null && st.phase == LinuxEnvPhase.FAILED && reportedFailure !== st) {
            reportedFailure = st
            snackbarHostState.showSnackbar(
                message = st.message ?: "Instalasi lingkungan Linux gagal",
                actionLabel = "Tutup",
                duration = SnackbarDuration.Indefinite
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------------- Header ----------------
            Text(
                text = "Lingkungan Linux",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp)
            )
            Text(
                text = "Debian/Ubuntu userspace via proot — apt, nodejs, npm, python3, git " +
                    "berjalan nyata di dalam sandbox aplikasi. Tanpa root, tanpa VM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---------------- PERANGKAT ----------------
                item { DeviceCard(capability) }

                // ---------------- STATUS ----------------
                item {
                    StatusCard(
                        phase = status,
                        installState = installState,
                        onRefresh = vm::refresh
                    )
                }

                // ---------------- PILIH ROOTFS (bila belum READY) ----------------
                if (status != LinuxEnvPhase.READY && vm.variants.isNotEmpty()) {
                    item { SectionTitle("PILIH ROOTFS") }
                    items(vm.variants, key = { it.id }) { variant ->
                        VariantCard(
                            variant = variant,
                            phase = status,
                            canInstall = capability.supported,
                            onInstall = { vm.install(variant.id) },
                            onPause = vm::pause,
                            onCancel = vm::cancel
                        )
                    }
                }

                // ---------------- MANAJEMEN + Mulai coding (bila READY) ----------------
                if (status == LinuxEnvPhase.READY) {
                    item {
                        ManagementCard(onRemove = { showRemoveDialog = true })
                    }
                    item {
                        Button(
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Mulai coding →")
                        }
                    }
                }

                // ---------------- Catatan kecil ----------------
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Workspace tetap tersimpan di /home/user/workspace. " +
                                "Proses latar belakang berhenti bila aplikasi ditutup " +
                                "oleh sistem Android.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Snackbar error instalasi (di atas bottom area screen).
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ---------------- Konfirmasi hapus lingkungan ----------------
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Hapus lingkungan Linux?") },
            text = {
                Text(
                    "Rootfs Ubuntu akan dihapus permanen dari perangkat. " +
                        "Workspace model AI (folder proyek) tidak terpengaruh."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.remove()
                        showRemoveDialog = false
                    }
                ) {
                    Text("Hapus", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

/** Card PERANGKAT: ABI, RAM, storage bebas + status didukung/tidak (reason). */
@Composable
private fun DeviceCard(capability: DeviceCapability) {
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SectionTitle("PERANGKAT")
            SpecRow(label = "ABI", value = capability.abi)
            SpecRow(label = "RAM total", value = fmtGb(capability.totalRamMb))
            SpecRow(label = "Storage bebas", value = fmtStorage(capability.availStorageMb))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (capability.supported) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Perangkat didukung",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = capability.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Card STATUS: chip fase + detail progres/pesan + tombol refresh.
 * Progress memakai LinearProgressIndicator lambda-form (M3 2024.09.03).
 */
@Composable
private fun StatusCard(
    phase: LinuxEnvPhase,
    installState: LinuxInstallState?,
    onRefresh: () -> Unit
) {
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("STATUS")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Muat ulang status")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(phase)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = statusDetail(phase, installState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Progress bar untuk fase transfer/ekstraksi/bootstrap.
            if (phase in ACTIVE_INSTALL_PHASES && installState != null) {
                val st = installState
                if (st.totalBytes > 0) {
                    val fraction = (st.progressBytes.toFloat() / st.totalBytes.toFloat())
                        .coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = "${humanBytes(st.progressBytes)} / ${humanBytes(st.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                st.message?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Pesan error (merah) untuk fase FAILED.
            if (phase == LinuxEnvPhase.FAILED) {
                Text(
                    text = installState?.message ?: "Instalasi gagal — coba lagi dari card PILIH ROOTFS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** Fase-fase dengan progres aktif (progress bar ditampilkan). */
private val ACTIVE_INSTALL_PHASES = setOf(
    LinuxEnvPhase.DOWNLOADING,
    LinuxEnvPhase.EXTRACTING,
    LinuxEnvPhase.BOOTSTRAPPING
)

/** Teks detail per fase untuk chip status. */
private fun statusDetail(phase: LinuxEnvPhase, installState: LinuxInstallState?): String =
    when (phase) {
        LinuxEnvPhase.READY -> "Ubuntu userspace aktif — apt, node, python3 siap dipakai."
        LinuxEnvPhase.NOT_INSTALLED -> "Belum terpasang — pilih rootfs di bawah untuk memasang."
        LinuxEnvPhase.PAUSED -> "Terjeda — lanjutkan kapan saja dari part tersimpan."
        LinuxEnvPhase.FAILED -> "Instalasi gagal."
        LinuxEnvPhase.NOT_SUPPORTED -> "Perangkat tidak didukung."
        LinuxEnvPhase.DOWNLOADING -> "Mengunduh rootfs…"
        LinuxEnvPhase.EXTRACTING -> "Mengekstrak rootfs…"
        LinuxEnvPhase.BOOTSTRAPPING -> "Bootstrap awal (resolv/hosts/workspace)…"
    }

/** Pill status berwarna per fase (tanpa interaksi — murni indikator). */
@Composable
private fun StatusChip(phase: LinuxEnvPhase) {
    val (bg, fg) = when (phase) {
        LinuxEnvPhase.READY ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        LinuxEnvPhase.NOT_INSTALLED ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        LinuxEnvPhase.DOWNLOADING, LinuxEnvPhase.EXTRACTING, LinuxEnvPhase.BOOTSTRAPPING ->
            MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        LinuxEnvPhase.PAUSED ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        LinuxEnvPhase.FAILED ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        LinuxEnvPhase.NOT_SUPPORTED ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            text = when (phase) {
                LinuxEnvPhase.READY -> "Siap"
                LinuxEnvPhase.NOT_INSTALLED -> "Belum terpasang"
                LinuxEnvPhase.DOWNLOADING -> "Mengunduh"
                LinuxEnvPhase.PAUSED -> "Terjeda"
                LinuxEnvPhase.EXTRACTING -> "Ekstraksi"
                LinuxEnvPhase.BOOTSTRAPPING -> "Bootstrap"
                LinuxEnvPhase.FAILED -> "Gagal"
                LinuxEnvPhase.NOT_SUPPORTED -> "Tidak didukung"
            },
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Card satu variant rootfs: nama + deskripsi + badge python2 + matriks aksi:
 * DOWNLOADING → Jeda + Batal; EXTRACTING/BOOTSTRAPPING → Batal;
 * PAUSED → Lanjut + Batal; else → Pasang (nonaktif bila perangkat tak didukung).
 */
@Composable
private fun VariantCard(
    variant: RootfsVariant,
    phase: LinuxEnvPhase,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit
) {
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = variant.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${humanBytes(variant.sizeBytes)} · ${variant.abi}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (variant.python2) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "termasuk python2",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Text(
                text = variant.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (phase) {
                    LinuxEnvPhase.DOWNLOADING -> {
                        Button(onClick = onPause) { Text("Jeda") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onCancel) { Text("Batal") }
                    }
                    LinuxEnvPhase.EXTRACTING, LinuxEnvPhase.BOOTSTRAPPING -> {
                        TextButton(onClick = onCancel) { Text("Batal") }
                    }
                    LinuxEnvPhase.PAUSED -> {
                        Button(onClick = onInstall) { Text("Lanjut") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onCancel) { Text("Batal") }
                    }
                    else -> {
                        Button(
                            onClick = onInstall,
                            enabled = canInstall
                        ) { Text("Pasang") }
                    }
                }
            }
        }
    }
}

/** Card MANAJEMEN (bila READY): hapus lingkungan Linux (warna error). */
@Composable
private fun ManagementCard(onRemove: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle("MANAJEMEN")
            Text(
                text = "Menghapus lingkungan membuang rootfs Ubuntu dan seluruh paket " +
                    "yang terpasang di dalamnya (apt, node, python).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onRemove,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Hapus lingkungan Linux")
            }
        }
    }
}

/** Format MB → GB (1 desimal), contoh: 7376 → "7.2 GB". */
private fun fmtGb(mb: Long): String = "%.1f GB".format(mb / 1024f)

/** Format storage bebas: GB bila ≥ 1 GB, else MB. */
private fun fmtStorage(mb: Long): String =
    if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "$mb MB"
