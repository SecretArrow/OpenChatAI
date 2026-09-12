package com.openchai.data

import com.openchai.agent.AgentTools
import com.openchai.core.data.WorkspaceFs
import java.io.File

/**
 * Implementasi [WorkspaceFs] untuk workspace berbasis direktori aplikasi
 * (app-dir). Semua operasi didelegasikan ke statik [AgentTools] dengan
 * `root = rootPath` — sandbox boundary, limit (list 80 entri, read 16 KB,
 * search 40 match) dan format string hasil identik dengan perilaku lama.
 *
 * Mendukung eksekusi shell di root ([supportsShell] = true) karena backend
 * berupa direktori biasa di penyimpanan aplikasi.
 */
class FileWorkspaceFs(private val rootPath: String) : WorkspaceFs {

    override val rootLabel: String = rootPath

    override val supportsShell: Boolean = true

    override fun listFiles(relPath: String): String =
        AgentTools.listFiles(rootPath, relPath)

    override fun readFile(relPath: String): String =
        AgentTools.readFile(rootPath, relPath)

    override fun writeFile(relPath: String, content: String): String =
        AgentTools.writeFile(rootPath, relPath, content)

    override fun deleteFile(relPath: String): String =
        AgentTools.deleteFile(rootPath, relPath)

    override fun search(query: String): String =
        AgentTools.search(rootPath, query)

    /**
     * Guard + exists kecil (meniru pola [AgentTools.resolve] tanpa mengeditnya):
     * canonicalisasi target lalu pastikan tetap di dalam root sebelum cek ada.
     * Path keluar sandbox dianggap tidak ada.
     */
    override fun exists(relPath: String): Boolean = try {
        if (rootPath.isBlank()) {
            false
        } else {
            val clean = relPath.trim().ifEmpty { "." }
            val rootDir = File(rootPath).canonicalFile
            val raw = File(clean)
            val resolved = (if (raw.isAbsolute) raw else File(rootDir, clean)).canonicalFile
            val rootPathStr = rootDir.path
            val targetPath = resolved.path
            val inside = targetPath == rootPathStr ||
                targetPath.startsWith(rootPathStr + File.separator)
            inside && resolved.exists()
        }
    } catch (_: Exception) {
        false
    }
}
