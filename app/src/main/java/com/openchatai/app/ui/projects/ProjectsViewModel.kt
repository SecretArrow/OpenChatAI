package com.openchatai.app.ui.projects

import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openchatai.app.OpenChatApp
import com.openchai.core.model.Project
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel workspace (dipakai ProjectsScreen & WorkspaceSetupScreen).
 *
 * "Workspace" = proyek aktif untuk agent: app-dir (di penyimpanan aplikasi)
 * atau SAF (subfolder di dalam folder pilihan user, [Project.treeUri] non-null).
 * Workspace aktif adalah satu sumber kebenaran di [com.openchatai.app.AppContainer];
 * perubahan juga dicatat di settings (activeWorkspaceId) agar dipulihkan
 * saat app dimulai ulang.
 */
class ProjectsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as OpenChatApp).container

    private val _workspaces = MutableStateFlow<List<Project>>(emptyList())
    val workspaces: StateFlow<List<Project>> = _workspaces.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Jumlah file per workspace id (pemindaian dibatasi; SAF tidak discan → 0). */
    private val _fileCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val fileCounts: StateFlow<Map<String, Int>> = _fileCounts.asStateFlow()

    /** Pesan kegagalan terakhir (mis. provider menolak membuat folder) — null = tidak ada. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Nama proyek hasil import terakhir (null = belum ada / import dibatalkan / gagal). */
    private val _importedProject = MutableStateFlow<String?>(null)
    val importedProject: StateFlow<String?> = _importedProject.asStateFlow()

    /** Proyek aktif — dibagikan dari [com.openchatai.app.AppContainer] (satu sumber kebenaran). */
    val activeProject: StateFlow<Project?> = container.activeProject

    init {
        refresh()
        // Daftar workspace ikut di-refresh saat workspace aktif berubah (mis.
        // dibuat/dipilih dari layar lain) agar badge "Active" tetap akurat.
        viewModelScope.launch {
            container.activeProject.collect { refresh() }
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    // ------------------------------------------------------------------
    // Muat ulang daftar workspace + hitung file singkat
    // ------------------------------------------------------------------

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            val projects = runCatching { container.workspaceManager.listProjects() }
                .getOrDefault(emptyList())
            _workspaces.value = projects
            _fileCounts.value = projects.associate { it.id to countFiles(it) }
            _loading.value = false
        }
    }

    private fun countFiles(project: Project): Int {
        // Workspace SAF tidak discan dari path (folder milik user di luar app).
        if (project.treeUri != null) return 0
        return try {
            val dir = container.workspaceManager.projectDir(project)
            if (dir.exists()) {
                dir.walkTopDown()
                    .filter { it.isFile }
                    .take(MAX_SCAN_FILES + 1)
                    .count()
                    .coerceAtMost(MAX_SCAN_FILES)
            } else 0
        } catch (_: Exception) {
            0
        }
    }

    // ------------------------------------------------------------------
    // Aksi workspace
    // ------------------------------------------------------------------

    /**
     * Tandai workspace aktif: set [com.openchatai.app.AppContainer.activeProject]
     * + simpan activeWorkspaceId di settings (field lain dipertahankan).
     * Navigasi ke Chat dilakukan UI lewat callback.
     */
    fun setActive(project: Project) {
        viewModelScope.launch {
            container.activeProject.value = project
            runCatching {
                container.settingsRepository.update { it.copy(activeWorkspaceId = project.id) }
            }
        }
    }

    /** Workspace app-dir baru (createProject) + langsung diaktifkan. */
    fun createAppWorkspace(name: String, onDone: (Project) -> Unit) {
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) {
                runCatching { container.workspaceManager.createProject(name) }.getOrNull()
            }
            if (project != null) {
                setActive(project)
                refresh()
                onDone(project)
            } else {
                _lastError.value = "Could not create app workspace"
            }
        }
    }

    /**
     * Workspace SAF baru: subfolder di dalam folder pilihan user
     * (createFromTreeUri) + langsung diaktifkan.
     */
    fun createSafWorkspace(treeUriString: String, name: String, onDone: (Project) -> Unit) {
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) {
                runCatching { container.workspaceManager.createFromTreeUri(treeUriString, name) }
                    .getOrNull()
            }
            if (project != null) {
                setActive(project)
                refresh()
                onDone(project)
            } else {
                _lastError.value = "Could not create workspace in the selected folder"
            }
        }
    }

    /** Hapus workspace: app-dir → folder dihapus; SAF → hanya link yang dilepas. */
    fun deleteWorkspace(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { container.workspaceManager.deleteProject(id) }
            }
            if (container.activeProject.value?.id == id) {
                container.activeProject.value = null
                runCatching {
                    container.settingsRepository.update { it.copy(activeWorkspaceId = "") }
                }
            }
            refresh()
        }
    }

    /** Rename tampilan (SAF: hanya nama di json — folder fisik tidak disentuh). */
    fun renameProject(id: String, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { container.workspaceManager.renameProject(id, name) }
            }
            refresh()
        }
    }

    // ------------------------------------------------------------------
    // Import folder (document tree) ke proyek app-dir baru
    // ------------------------------------------------------------------

    /**
     * Copy rekursif isi document tree ([uri] dari OpenDocumentTree) ke proyek baru
     * bernama "imported_<timestamp>".
     *
     * Catatan implementasi: memakai [DocumentsContract] (API framework) alih-alih
     * androidx.documentfile.DocumentFile karena artefak documentfile tidak berada
     * di compile classpath proyek ini dan dependensi build.gradle.kts tidak boleh
     * diubah dari modul data/UI. Perilaku identik: walk tree, buat subdirektori,
     * copy via ContentResolver.
     *
     * Batasan: maksimum [MAX_IMPORT_FILES] file, satu file maksimum [MAX_FILE_BYTES],
     * direktori di [SKIP_DIRS] dilewati. Nama proyek yang dibuat dilaporkan lewat
     * [importedProject] (null bila gagal).
     */
    fun importFromUri(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            _importedProject.value = runCatching { importTree(uri) }.getOrNull()
            _loading.value = false
            refresh()
        }
    }

    private suspend fun importTree(treeUri: Uri): String {
        val project = container.workspaceManager.createProject(
            "imported_" + System.currentTimeMillis()
        )
        val destDir = container.workspaceManager.projectDir(project)
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val stats = ImportStats()
            copyChildren(
                resolver = getApplication<OpenChatApp>().contentResolver,
                treeUri = treeUri,
                parentDocId = rootDocId,
                dest = destDir,
                stats = stats
            )
            return project.name
        } catch (e: Exception) {
            // Gagal di tengah jalan: bersihkan proyek setengah jadi agar tidak ada sisa kosong.
            runCatching { container.workspaceManager.deleteProject(project.id) }
            if (container.activeProject.value?.id == project.id) {
                container.activeProject.value = null
            }
            throw e
        }
    }

    private class ImportStats {
        var files = 0
    }

    private fun copyChildren(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        dest: File,
        stats: ImportStats
    ) {
        if (stats.files >= MAX_IMPORT_FILES) return
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val cursor = runCatching {
            resolver.query(childrenUri, projection, null, null, null)
        }.getOrNull() ?: return

        cursor.use { c ->
            while (c.moveToNext() && stats.files < MAX_IMPORT_FILES) {
                val docId = c.stringOrNull(0) ?: continue
                val name = (c.stringOrNull(1) ?: continue).replace('/', '_')
                val mime = c.stringOrNull(2).orEmpty()
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name in SKIP_DIRS) continue
                    val sub = File(dest, name)
                    if (!sub.exists() && !sub.mkdirs()) continue
                    copyChildren(resolver, treeUri, docId, sub, stats)
                } else {
                    if (size > MAX_FILE_BYTES) continue
                    val target = File(dest, name)
                    val copied = runCatching {
                        resolver.openInputStream(childUri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } != null
                    }.getOrDefault(false)
                    if (copied) stats.files++
                }
            }
        }
    }

    private companion object {
        const val MAX_SCAN_FILES = 500
        const val MAX_IMPORT_FILES = 500
        const val MAX_FILE_BYTES = 10L * 1024L * 1024L // 10 MB
        val SKIP_DIRS = setOf(".git", "node_modules")
    }
}

/** Baca kolom String nullable dengan aman dari cursor. */
private fun Cursor.stringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)
