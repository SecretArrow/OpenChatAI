package com.openchatai.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchai.core.model.Project

/**
 * Project selector cepat dari Chat — Material Design 3: ModalBottomSheet
 * (dragHandle default) berisi ListItem per proyek; proyek aktif ditandai ikon
 * CheckCircle primary. Kelola proyek (rename/delete/import) ada di halaman
 * Projects.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSelectorSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onManageProjects: () -> Unit,
    vm: ChatViewModel
) {
    if (!visible) return
    val projects by vm.projects.collectAsStateWithLifecycle()
    val active by vm.activeProject.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Project", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                items(projects, key = { it.id }) { project: Project ->
                    ListItem(
                        headlineContent = {
                            Text(project.name, style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = {
                            Text(
                                project.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingContent = {
                            if (active?.id == project.id) {
                                // Penanda proyek aktif — ikon M3, bukan teks "●".
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Active project",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.selectProject(project)
                                onDismiss()
                            }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Aksi utama sheet — tombol tonal M3 dengan ikon Add.
                FilledTonalButton(onClick = { onDismiss(); onManageProjects() }) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("New / manage projects")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
