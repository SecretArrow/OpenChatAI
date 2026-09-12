package com.openchai.runtime

import android.content.Context
import com.openchatai.app.OpenChatApp
import java.io.File

/**
 * Lingkungan shell untuk proses & terminal di Android.
 *
 * PATH = direktori sistem Android + PATH tambahan dari pengaturan
 * (mis. runtime Node/Python berbasis Termux yang dipasang user).
 *
 * HOME = workspace root (bila ada) agar shell & tool berbasis file langsung
 * berada di direktori proyek; fallback ke filesDir aplikasi.
 */
class ShellEnvironment(private val context: Context) {

    /** Bangun environment minimal untuk ProcessBuilder / shell interaktif. */
    fun buildEnvironment(extraPath: String): MutableMap<String, String> {
        val extra = extraPath
            .split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(":")
        val path = if (extra.isBlank()) BASE_PATH else "$BASE_PATH:$extra"
        val home = workspaceRoot() ?: context.filesDir.absolutePath
        return mutableMapOf(
            "PATH" to path,
            "HOME" to home,
            "TMPDIR" to context.cacheDir.absolutePath,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8"
        )
    }

    /**
     * Daftar tool populer beserta ketersediaannya di PATH (dicek sebagai
     * file executable di setiap direktori PATH).
     */
    fun availableTools(): Map<String, Boolean> {
        val extraPath = try {
            (context.applicationContext as? OpenChatApp)
                ?.container?.settingsRepository?.settings?.value?.extraPathDirs ?: ""
        } catch (_: Exception) {
            ""
        }
        val dirs = buildEnvironment(extraPath)
            .getOrElse("PATH") { BASE_PATH }
            .split(':')
            .filter { it.isNotBlank() }
        return TOOLS.associateWith { tool ->
            dirs.any { dir ->
                val f = File(dir, tool)
                f.isFile && f.canExecute()
            }
        }
    }

    /** Workspace root aktif dari container; null bila tidak tersedia/kosong. */
    fun workspaceRoot(): String? {
        return try {
            val app = context.applicationContext as? OpenChatApp ?: return null
            val root = app.container.workspaceManager.workspaceRoot()
            root.takeIf { it.isNotBlank() && File(it).exists() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val BASE_PATH =
            "/system/bin:/system/xbin:/system/sbin:/vendor/bin:/odm/bin"

        private val TOOLS = listOf(
            "sh", "node", "npm", "npx", "python3", "python", "pip3",
            "git", "curl", "wget", "tsc", "tsx", "java"
        )
    }
}
