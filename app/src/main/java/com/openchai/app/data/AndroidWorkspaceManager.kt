package com.openchai.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.openchai.core.data.WorkspaceFs
import com.openchai.core.data.WorkspaceManager
import com.openchai.core.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manajer workspace berbasis direktori aplikasi. Semua proyek berada di bawah
 * `<externalFilesDir|filesDir>/projects` dan didaftarkan di `projects.json`.
 * Daftar proyek juga di-cache di memori agar [guardAny] dapat dipanggil secara
 * sinkron (sandbox boundary check) tanpa korutina.
 *
 * Selain workspace app-dir, mendukung workspace SAF ([Project.treeUri] non-null):
 * subfolder yang dibuat di dalam document tree pilihan user (OpenDocumentTree).
 * File backend dipilih lewat [fsFor]: FileWorkspaceFs utk app-dir,
 * SafWorkspaceFs utk treeUri.
 */
class AndroidWorkspaceManager(private val context: Context) : WorkspaceManager {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()

    private val root: File = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "projects"
    ).apply { mkdirs() }

    @Volatile
    private var cachedProjects: List<Project>? = null

    private val projectsFile: File get() = File(root, PROJECTS_FILE)

    override fun workspaceRoot(): String = root.absolutePath

    override suspend fun listProjects(): List<Project> = withContext(Dispatchers.IO) {
        mutex.withLock { loadProjects() }
    }

    override suspend fun createProject(name: String): Project = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clean = name.trim().ifBlank { "project" }
            var dirName = sanitize(clean)
            var seq = 1
            while (File(root, dirName).exists()) {
                seq++
                dirName = sanitize(clean) + "-" + seq
            }
            val dir = File(root, dirName).apply { mkdirs() }
            // Placeholder agar proyek baru tidak kosong total.
            runCatching { File(dir, "README.md").writeText("Created with Open Chat AI\n") }
            val project = Project(name = clean, path = dir.absolutePath)
            saveProjects(loadProjects() + project)
            project
        }
    }

    /**
     * Buat workspace SAF: subfolder bernama [name] di dalam document tree
     * pilihan user ([treeUriString] dari OpenDocumentTree).
     *
     *  - Ambil persistable URI permission (baca+tulis) agar tree tetap bisa
     *    diakses setelah proses mati / perangkat restart.
     *  - Nama workspace: sanitize([name]); bila kosong → "<nama-folder>-workspace"
     *    (DISPLAY_NAME tree; gagal → "workspace").
     *  - Anti-tabrakan antar children tree root: suffiks "-2", "-3", dst.
     *  - [Project.treeUri] menyimpan URI dokumen subfolder (null = app-dir).
     */
    override suspend fun createFromTreeUri(treeUriString: String, name: String): Project =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val uri = Uri.parse(treeUriString)
                // Hak akses persisten — WAJIB sebelum provider bisa diakses lintas restart.
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val treeDocId = DocumentsContract.getTreeDocumentId(uri)
                val treeDoc = DocumentsContract.buildDocumentUriUsingTree(uri, treeDocId)
                val fallbackName = treeDisplayName(uri, treeDocId)
                    ?.let { "$it-workspace" } ?: "workspace"
                val base = sanitize(name.trim().ifBlank { fallbackName })
                // Hindari nama tabrakan dgn children lain di root tree ("-2", "-3", …).
                var candidate = base
                var seq = 1
                while (childExists(uri, treeDocId, candidate)) {
                    seq++
                    candidate = "$base-$seq"
                }
                val created = DocumentsContract.createDocument(
                    context.contentResolver, treeDoc,
                    DocumentsContract.Document.MIME_TYPE_DIR, candidate
                ) ?: throw IllegalStateException("Provider tidak mengizinkan membuat folder workspace")
                val project = Project(name = candidate, path = "", treeUri = created.toString())
                saveProjects(loadProjects() + project)
                project
            }
        }

    /** WorkspaceFs sesuai backend project (SAF vs app-dir). */
    override fun fsFor(project: Project): WorkspaceFs {
        val treeUri = project.treeUri
        return if (treeUri != null) {
            SafWorkspaceFs(context, treeUri)
        } else {
            FileWorkspaceFs(project.path)
        }
    }

    override suspend fun deleteProject(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val projects = loadProjects()
            val target = projects.firstOrNull { it.id == id } ?: return@withContext
            val treeUriString = target.treeUri
            if (treeUriString != null) {
                // SAF: JANGAN pernah menghapus data user di luar app — cukup
                // putuskan link (hapus dari projects.json) + lepas izin persisten.
                releaseTreePermission(treeUriString)
            } else {
                val dir = File(target.path)
                // Keamanan: hanya hapus direktori yang berada di dalam workspace root.
                if (isInside(dir, root)) dir.deleteRecursively()
            }
            saveProjects(projects.filterNot { it.id == id })
        }
    }

    override suspend fun renameProject(id: String, newName: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val projects = loadProjects()
            val target = projects.firstOrNull { it.id == id } ?: return@withContext
            if (target.treeUri != null) {
                // SAF: folder fisik milik user tidak di-rename (path tetap) —
                // cukup perbarui nama tampilan di projects.json.
                saveProjects(
                    projects.map {
                        if (it.id == id) {
                            it.copy(name = newName.trim().ifBlank { target.name })
                        } else it
                    }
                )
                return@withContext
            }
            val oldDir = File(target.path)
            val baseName = sanitize(newName.trim().ifBlank { target.name })
            var dirName = baseName
            var seq = 1
            while (File(root, dirName).exists() &&
                File(root, dirName).absolutePath != oldDir.absolutePath
            ) {
                seq++
                dirName = baseName + "-" + seq
            }
            val newDir = File(root, dirName)
            if (oldDir.exists() && isInside(oldDir, root)) {
                if (!oldDir.renameTo(newDir)) return@withContext
            }
            saveProjects(
                projects.map {
                    if (it.id == id) it.copy(name = newName.trim().ifBlank { target.name }, path = newDir.absolutePath)
                    else it
                }
            )
        }
    }

    override fun projectDir(project: Project): File = File(project.path)

    /** True bila [target] (kanonikal) berada di dalam [projectPath] atau sama dengannya. */
    override fun guardIn(projectPath: String, target: String): Boolean {
        return try {
            val base = File(projectPath).canonicalPath
            val t = File(target).canonicalPath
            t == base || t.startsWith(base + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    override fun guardAny(target: String): Boolean {
        val projects = cachedProjects ?: run {
            val loaded = try {
                loadProjects()
            } catch (_: Exception) {
                emptyList()
            }
            cachedProjects = loaded
            loaded
        }
        return projects.any { guardIn(it.path, target) }
    }

    /** Baca sinkron dari disk — dipanggil di dalam mutex / thread IO. */
    private fun loadProjects(): List<Project> {
        val file = projectsFile
        if (!file.exists()) {
            cachedProjects = emptyList()
            return emptyList()
        }
        val projects = runCatching {
            json.decodeFromString(ListSerializer(Project.serializer()), file.readText())
        }.getOrDefault(emptyList())
        cachedProjects = projects
        return projects
    }

    private fun saveProjects(projects: List<Project>) {
        cachedProjects = projects
        val tmp = File(root, PROJECTS_FILE + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Project.serializer()), projects))
        if (!tmp.renameTo(projectsFile)) {
            projectsFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun isInside(child: File, parent: File): Boolean = try {
        val c = child.canonicalPath
        val p = parent.canonicalPath
        c == p || c.startsWith(p + File.separator)
    } catch (_: Exception) {
        false
    }

    /** Sanitasi nama direktori: karakter di luar [A-Za-z0-9._- ] diganti '-'. */
    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\- ]"), "-").trim().ifBlank { "project" }

    /** DISPLAY_NAME dokumen puncak tree (mis. nama folder terpilih); null bila gagal. */
    private fun treeDisplayName(treeUri: Uri, treeDocId: String): String? = try {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        context.contentResolver
            .query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    /** True bila root tree memuat child bernama [name] (anti-tabrakan). */
    private fun childExists(treeUri: Uri, treeDocId: String, name: String): Boolean = try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        var found = false
        context.contentResolver
            .query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c ->
                while (c.moveToNext() && !found) {
                    if (c.getString(0) == name) found = true
                }
            }
        found
    } catch (_: Exception) {
        false
    }

    /** Lepas persistable permission tree workspace (best-effort, tidak melempar). */
    private fun releaseTreePermission(treeUriString: String) {
        try {
            val uri = Uri.parse(treeUriString)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            // URI tersimpan bisa berupa dokumen subfolder — lepas persis seperti
            // tersimpan, plus URI tree mentah turunannya (grant tercatat per tree).
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
            runCatching {
                val treeDocId = DocumentsContract.getTreeDocumentId(uri)
                val plainTree = Uri.Builder()
                    .scheme("content")
                    .authority(uri.authority ?: return@runCatching)
                    .appendPath("tree")
                    .appendPath(treeDocId)
                    .build()
                context.contentResolver.releasePersistableUriPermission(plainTree, flags)
            }
        } catch (_: Exception) {
            // Izin tertinggal bukan masalah fatal — hilang saat uninstall.
        }
    }

    private companion object {
        const val PROJECTS_FILE = "projects.json"
    }
}
