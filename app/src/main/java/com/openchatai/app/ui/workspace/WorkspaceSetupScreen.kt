package com.openchatai.app.ui.workspace

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openchatai.app.ui.projects.ProjectsViewModel
import com.openchai.core.model.Project

/**
 * Layar wajib-workspace: chat Agent mode butuh workspace aktif, jadi bila belum
 * ada, user diarahkan ke sini (gating di NavGraph).
 *
 *  - Dua kartu opsi: folder device (SAF OpenDocumentTree) atau app-private.
 *  - Setelah folder dipilih → dialog nama (default: nama folder terakhir di URI).
 *  - Daftar workspace yang sudah ada: tap = aktifkan + masuk Chat.
 *  - Hapus via menu MoreVert; untuk SAF hanya LINK yang dilepas (folder user
 *    TIDAK dihapus).
 */
@Composable
fun WorkspaceSetupScreen(
    onWorkspaceReady: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ProjectsViewModel = viewModel()
) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val activeProject by vm.activeProject.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()

    // URI SAF yang menunggu pengisian nama workspace (null = tidak ada).
    var pendingSafUri by remember { mutableStateOf<Uri?>(null) }
    var showAppDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Launcher SAF: hasilnya langsung ditampilkan dialog pemberian nama workspace.
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) pendingSafUri = uri
    }

    // Tampilkan kegagalan pembuatan workspace via snackbar (jangan diam saja).
    val reportedError = lastError
    LaunchedEffect(reportedError) {
        if (reportedError != null) {
            snackbarHostState.showSnackbar(reportedError)
            vm.clearError()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Set up workspace",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            )
            Text(
                "The agent reads and writes files inside one workspace. " +
                    "Pick a folder on your device or create a private one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )

            // ----------------------------------------------------------
            // Opsi 1: folder device (SAF)
            // ----------------------------------------------------------
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Pick a device folder",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Work on a real folder on your device. A new subfolder workspace " +
                            "is created inside it, so the rest of your files stay untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { treeLauncher.launch(null) }) {
                        Text("Choose folder")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ----------------------------------------------------------
            // Opsi 2: workspace app-private
            // ----------------------------------------------------------
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "App-private workspace",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A private folder inside the app's storage. No permissions needed; " +
                            "it is removed when the app is uninstalled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { showAppDialog = true }) {
                        Text("Create workspace")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Chat in Agent mode requires a workspace.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // ----------------------------------------------------------
            // Workspace yang sudah ada — tap = aktifkan + masuk Chat
            // ----------------------------------------------------------
            if (workspaces.isNotEmpty()) {
                Text(
                    "Existing workspaces",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(workspaces, key = { it.id }) { project ->
                        WorkspaceRow(
                            project = project,
                            isActive = activeProject?.id == project.id,
                            onOpen = {
                                // Await inline (bukan fire-and-forget): onWorkspaceReady
                                // baru SETELAH activeProject terisi + persist — navigasi
                                // ke Chat tidak mendahului pengisian workspace (bug lama:
                                // chat tidak muncul, gate setup tampil terus).
                                vm.setActive(project) { onWorkspaceReady() }
                            },
                            onDelete = { deleteTarget = project }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // Dialog nama setelah folder SAF dipilih (default: nama folder terakhir).
    pendingSafUri?.let { uri ->
        NameDialog(
            title = "Name this workspace",
            placeholder = "Workspace name",
            initial = deriveFolderName(uri),
            confirmLabel = "Create",
            onDismiss = { pendingSafUri = null },
            onConfirm = { name ->
                pendingSafUri = null
                // Manager akan sanitize; nama kosong → "<nama-folder>-workspace".
                vm.createSafWorkspace(uri.toString(), name) { onWorkspaceReady() }
            }
        )
    }

    // Dialog nama workspace app-private (default "workspace").
    if (showAppDialog) {
        NameDialog(
            title = "Name this workspace",
            placeholder = "Workspace name",
            initial = "workspace",
            confirmLabel = "Create",
            onDismiss = { showAppDialog = false },
            onConfirm = { name ->
                showAppDialog = false
                vm.createAppWorkspace(name) { onWorkspaceReady() }
            }
        )
    }

    // Dialog hapus: teks berbeda utk SAF (folder user TIDAK ikut terhapus).
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove workspace?") },
            text = {
                Text(
                    if (target.treeUri != null) {
                        "Workspace link will be removed — your folder is NOT deleted."
                    } else {
                        "“" + target.name + "” and all its files will be removed from the workspace. " +
                            "This cannot be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    vm.deleteWorkspace(target.id)
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

/** Baris workspace existing: nama + badge jenis + menu hapus (gaya MoreVert). */
@Composable
private fun WorkspaceRow(
    project: Project,
    isActive: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    WorkspaceBadge(isSaf = project.treeUri != null)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    if (project.treeUri != null) "Linked device folder (SAF)" else "App-private folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isActive) {
                Text("●", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        onClick = {
                            menuOpen = false
                            onOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

/** Badge jenis workspace: "SAF" (folder user) atau "App" (app-dir). */
@Composable
private fun WorkspaceBadge(isSaf: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            if (isSaf) "SAF" else "App",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * Derive nama folder dari URI SAF: segmen path terakhir di-decode, buang prefix
 * provider ("primary:"), ambil nama terakhir — fallback "workspace".
 */
private fun deriveFolderName(uri: Uri): String = try {
    Uri.decode(uri.lastPathSegment.orEmpty())
        .substringAfterLast(':')
        .substringAfterLast('/')
        .ifBlank { "workspace" }
} catch (_: Exception) {
    "workspace"
}

/** Dialog nama generik untuk pembuatan workspace. */
@Composable
private fun NameDialog(
    title: String,
    placeholder: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
