package com.openchatai.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openchai.core.skills.Skill
import com.openchai.core.skills.SkillLoaderProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Section Settings: daftar Skills & Plugins (plugin ringan berupa pak instruksi).
 *
 * Standalone — di-embed ke SettingsScreen sebagai satu item LazyColumn:
 * `item { SkillsSection() }`. Gaya visual mengikuti SectionCard di SettingsScreen
 * (label kecil uppercase + ElevatedCard sudut 16dp, padding 16dp, jarak 12dp).
 *
 * Interaksi:
 * - Switch  : skill bawaan → override tersimpan di prefs.json; skill user → field
 *             enabled di user_skills.json (keduanya lewat SkillLoader.setEnabled).
 * - Edit    : hanya untuk skill user (skill bawaan read-only).
 * - Delete  : hanya untuk skill user, dengan dialog konfirmasi.
 * - Add     : dialog form dengan validasi (instructions minimal 20 karakter).
 */
@Composable
fun SkillsSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = remember { SkillLoaderProvider.get(context) }
    val scope = rememberCoroutineScope()

    var skills by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Skill?>(null) }
    var pendingDelete by remember { mutableStateOf<Skill?>(null) }

    // Pemuatan pertama lewat Dispatchers.IO agar file JSON tidak dibaca di UI thread.
    LaunchedEffect(Unit) {
        skills = withContext(Dispatchers.IO) { loader.all() }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "SKILLS & PLUGINS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Skill aktif otomatis disuntikkan ke prompt agent sesuai tugas. " +
                        "Active skills are injected into the agent prompt when relevant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${skills.size} skill",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { adding = true }) { Text("+ Add skill") }
                }
                if (skills.isEmpty()) {
                    Text(
                        "Belum ada skill. Tambahkan skill pertamamu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    skills.forEachIndexed { index, skill ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        SkillRow(
                            skill = skill,
                            onToggle = { checked ->
                                scope.launch {
                                    loader.setEnabled(skill.id, checked)
                                    skills = loader.all()
                                }
                            },
                            onEdit = { editing = skill },
                            onDelete = { pendingDelete = skill }
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Dialog tambah / edit / hapus
    // ------------------------------------------------------------------

    if (adding) {
        SkillEditorDialog(
            onDismiss = { adding = false },
            onSave = { skill ->
                adding = false
                scope.launch {
                    loader.add(skill)
                    skills = loader.all()
                }
            }
        )
    }
    editing?.let { target ->
        SkillEditorDialog(
            initial = target,
            onDismiss = { editing = null },
            onSave = { skill ->
                editing = null
                scope.launch {
                    loader.update(skill)
                    skills = loader.all()
                }
            }
        )
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete skill") },
            text = { Text("Hapus skill \"${target.name}\"? Tindakan ini tidak dapat dibatalkan.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    pendingDelete = null
                    scope.launch {
                        loader.remove(id)
                        skills = loader.all()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ----------------------------------------------------------------------
// Baris skill
// ----------------------------------------------------------------------

@Composable
private fun SkillRow(
    skill: Skill,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(skill.name, style = MaterialTheme.typography.bodyLarge)
                if (skill.builtin) BuiltinBadge()
            }
            Text(
                skill.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (skill.triggers.isNotEmpty()) {
                Text(
                    "Triggers: " + skill.triggers.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Switch(checked = skill.enabled, onCheckedChange = onToggle)
        if (!skill.builtin) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit ${skill.name}")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${skill.name}")
            }
        }
    }
}

@Composable
private fun BuiltinBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            "Built-in",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ----------------------------------------------------------------------
// Dialog form tambah/edit skill
// ----------------------------------------------------------------------

@Composable
private fun SkillEditorDialog(
    initial: Skill? = null,
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }
    var triggers by remember(initial) {
        mutableStateOf(initial?.triggers?.joinToString(", ").orEmpty())
    }
    var instructions by remember(initial) { mutableStateOf(initial?.instructions.orEmpty()) }

    val nameValid = name.trim().isNotEmpty()
    val descValid = description.trim().isNotEmpty()
    val instructionsValid = instructions.trim().length >= MIN_INSTRUCTION_CHARS
    val formValid = nameValid && descValid && instructionsValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add skill" else "Edit skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = !nameValid,
                    supportingText = {
                        if (!nameValid) Text("Nama wajib diisi")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    placeholder = { Text("Ringkas, bilingual ID/EN jika bisa") },
                    isError = !descValid,
                    supportingText = {
                        if (!descValid) Text("Deskripsi wajib diisi")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = triggers,
                    onValueChange = { triggers = it },
                    label = { Text("Triggers (comma separated)") },
                    placeholder = { Text("react, frontend, web app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Instructions") },
                    placeholder = { Text("Petunjuk yang disuntikkan ke prompt agent") },
                    minLines = 4,
                    isError = !instructionsValid,
                    supportingText = {
                        Text(
                            if (instructionsValid) {
                                "${instructions.trim().length} karakter"
                            } else {
                                "Minimal $MIN_INSTRUCTION_CHARS karakter (${instructions.trim().length})"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = formValid,
                onClick = {
                    val triggerList = triggers.split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    val result = (initial ?: Skill(name = "", description = "", instructions = ""))
                        .copy(
                            name = name.trim(),
                            description = description.trim(),
                            instructions = instructions.trim(),
                            triggers = triggerList,
                            builtin = false
                        )
                    onSave(result)
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Batas minimal karakter instruksi agar skill punya isi yang berarti. */
private const val MIN_INSTRUCTION_CHARS = 20
