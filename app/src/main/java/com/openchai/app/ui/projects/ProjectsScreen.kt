package com.openchai.app.ui.projects

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openchai.core.model.Project

/**
 * Halaman Projects: daftar proyek, buka/rename/delete, dan import folder.
 * Tanpa bottom bar sendiri — NavigationBar global ada di NavGraph.
 */
@Composable
fun ProjectsScreen(onProjectSelected: () -> Unit, modifier: Modifier = Modifier) {
    val vm: ProjectsViewModel = viewModel()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val fileCounts by vm.fileCounts.collectAsStateWithLifecycle()
    val activeProject by vm.activeProject.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        vm.importFromUri(uri)
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Projects",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            )
            when {
                projects.isEmpty() && loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                projects.isEmpty() -> EmptyState()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            fileCount = fileCounts[project.id] ?: 0,
                            isActive = activeProject?.id == project.id,
                            onOpen = {
                                vm.openProject(project)
                                onProjectSelected()
                            },
                            onRename = { renameTarget = project },
                            onDelete = { deleteTarget = project },
                            onImport = { importLauncher.launch(null) }
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showCreateDialog = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New project") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
    }

    if (showCreateDialog) {
        NameDialog(
            title = "New project",
            placeholder = "Project name",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                vm.createProject(name)
            }
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            title = "Rename project",
            placeholder = "Project name",
            initial = target.name,
            confirmLabel = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                vm.renameProject(target.id, name)
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete project?") },
            text = {
                Text(
                    "“" + target.name + "” and all its files will be removed from the workspace. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    vm.deleteProject(target.id)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    fileCount: Int,
    isActive: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (isActive) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
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
                            text = { Text("Rename") },
                            onClick = {
                                menuOpen = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Import files") },
                            onClick = {
                                menuOpen = false
                                onImport()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                project.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                fileCountLabel(fileCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen) { Text("Open") }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No projects yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Create a new project or import an existing folder from your device. " +
                "The agent only reads and writes inside the active project.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Dialog nama generik untuk create/rename proyek. */
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

private fun fileCountLabel(count: Int): String = when {
    count <= 0 -> "No files"
    count >= MAX_FILES_SHOWN -> "$count+ files"
    else -> "$count files"
}

private const val MAX_FILES_SHOWN = 500
