package com.openchai.data

import android.content.Context
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

    override suspend fun deleteProject(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val projects = loadProjects()
            val target = projects.firstOrNull { it.id == id } ?: return@withContext
            val dir = File(target.path)
            // Keamanan: hanya hapus direktori yang berada di dalam workspace root.
            if (isInside(dir, root)) dir.deleteRecursively()
            saveProjects(projects.filterNot { it.id == id })
        }
    }

    override suspend fun renameProject(id: String, newName: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val projects = loadProjects()
            val target = projects.firstOrNull { it.id == id } ?: return@withContext
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

    private companion object {
        const val PROJECTS_FILE = "projects.json"
    }
}
