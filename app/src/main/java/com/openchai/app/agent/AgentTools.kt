package com.openchai.agent

import java.io.File

/**
 * Operasi file ter-sandbox untuk Built-in Agent.
 *
 * Semua path relatif terhadap root workspace; setiap operasi memvalidasi boundary
 * lewat [guard] (canonical path) sebelum menyentuh filesystem. Semua kegagalan
 * dikembalikan sebagai string "ERROR: <pesan>" — tidak pernah melempar exception,
 * sehingga hasil tool aman langsung dimasukkan ke konteks LLM.
 */
object AgentTools {

    private const val MAX_LIST_ENTRIES = 80
    private const val MAX_READ_BYTES = 16 * 1024
    private const val MAX_SEARCH_MATCHES = 40
    private const val MAX_SEARCH_FILE_BYTES = 512L * 1024L
    private const val MAX_SEARCH_FILES = 400
    private const val MAX_MATCH_LINE = 200
    private val SKIP_DIRS = setOf(".git", "node_modules")

    /**
     * True bila [target] (relatif atau absolut) berada di dalam [root]
     * setelah canonicalisasi (menyelesaikan "..", symlink, dan duplikat slash).
     */
    fun guard(root: String, target: String): Boolean = try {
        if (root.isBlank() || target.isBlank()) {
            false
        } else {
            val rootDir = File(root).canonicalFile
            val raw = File(target)
            val resolved = (if (raw.isAbsolute) raw else File(rootDir, target)).canonicalFile
            val rootPath = rootDir.path
            val targetPath = resolved.path
            targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        }
    } catch (_: Exception) {
        false
    }

    /** Resolve path relatif/absolut ke dalam root; null bila keluar sandbox. */
    private fun resolve(root: String, relPath: String): File? {
        val clean = relPath.trim().ifEmpty { "." }
        if (!guard(root, clean)) return null
        val rootDir = File(root).canonicalFile
        val raw = File(clean)
        return (if (raw.isAbsolute) raw else File(rootDir, clean)).canonicalFile
    }

    private fun relativeToRoot(rootDir: File, f: File): String = try {
        f.toRelativeString(rootDir).replace(File.separatorChar, '/').ifEmpty { "." }
    } catch (_: Exception) {
        f.path
    }

    /**
     * Pohon direktori 2 level, maksimal [MAX_LIST_ENTRIES] entri.
     * Format: "d name/" untuk direktori, "- name (N bytes)" untuk file.
     */
    fun listFiles(root: String, relPath: String): String = try {
        val dir = resolve(root, relPath)
            ?: return "ERROR: Path is outside the workspace: $relPath"
        if (!dir.isDirectory) return "ERROR: Not a directory: $relPath"
        val rootDir = File(root).canonicalFile
        val sb = StringBuilder("Tree of ")
            .append(relativeToRoot(rootDir, dir))
            .append(" (max 2 levels):\n")
        var count = 0
        var truncated = false

        fun walk(d: File, indent: String, depth: Int) {
            val children = d.listFiles()
                ?.sortedWith(
                    compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
                )
                ?: return
            for (child in children) {
                if (count >= MAX_LIST_ENTRIES) {
                    truncated = true
                    return
                }
                if (child.isDirectory) {
                    sb.append(indent).append("d ").append(child.name).append("/\n")
                    count++
                    if (depth < 2) walk(child, "$indent  ", depth + 1)
                } else {
                    sb.append(indent).append("- ").append(child.name)
                        .append(" (").append(child.length()).append(" bytes)\n")
                    count++
                }
            }
        }

        walk(dir, "", 1)
        if (count == 0) sb.append("(empty)\n")
        if (truncated) sb.append("… (truncated at $MAX_LIST_ENTRIES entries)\n")
        sb.toString().trimEnd()
    } catch (e: Exception) {
        "ERROR: ${e.message ?: "list failed"}"
    }

    /** Baca file teks maksimal 16 KB; bila lebih besar, potong dan beri catatan. */
    fun readFile(root: String, relPath: String): String = try {
        val file = resolve(root, relPath)
            ?: return "ERROR: Path is outside the workspace: $relPath"
        if (!file.isFile) return "ERROR: Not a file: $relPath"
        val size = file.length()
        val toRead = minOf(size, MAX_READ_BYTES.toLong()).toInt()
        val buf = ByteArray(toRead)
        var read = 0
        file.inputStream().use { input ->
            while (read < toRead) {
                val r = input.read(buf, read, toRead - read)
                if (r < 0) break
                read += r
            }
        }
        if (buf.contains(0.toByte())) return "ERROR: Binary file is not shown: $relPath"
        val text = String(buf, 0, read, Charsets.UTF_8)
        if (size > MAX_READ_BYTES) {
            "$text\n… (truncated; file is $size bytes, showing first $MAX_READ_BYTES)"
        } else {
            text
        }
    } catch (e: Exception) {
        "ERROR: ${e.message ?: "read failed"}"
    }

    /** Tulis file (parent dibuat otomatis). Kembalikan "OK (N bytes)". */
    fun writeFile(root: String, relPath: String, content: String): String = try {
        if (relPath.isBlank()) return "ERROR: Empty path"
        val file = resolve(root, relPath)
            ?: return "ERROR: Path is outside the workspace: $relPath"
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return "ERROR: Cannot create directories for: $relPath"
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        file.outputStream().use { it.write(bytes) }
        "OK (${bytes.size} bytes)"
    } catch (e: Exception) {
        "ERROR: ${e.message ?: "write failed"}"
    }

    /** Hapus file atau direktori (rekursif untuk direktori). */
    fun deleteFile(root: String, relPath: String): String = try {
        val file = resolve(root, relPath)
            ?: return "ERROR: Path is outside the workspace: $relPath"
        if (!file.exists()) return "ERROR: Not found: $relPath"
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (deleted) "OK (deleted $relPath)" else "ERROR: Could not delete: $relPath"
    } catch (e: Exception) {
        "ERROR: ${e.message ?: "delete failed"}"
    }

    /**
     * Pencarian teks sederhana (case-insensitive) secara rekursif.
     * Maksimal [MAX_SEARCH_MATCHES] match "file:line: text",
     * skip direktori .git & node_modules, file > 512KB dilewati, maksimal 400 file.
     */
    fun search(root: String, query: String): String = try {
        if (query.isBlank()) return "ERROR: Empty search query"
        val rootDir = File(root).canonicalFile
        if (!rootDir.isDirectory) return "ERROR: Workspace root not found: $root"
        val needle = query.lowercase()
        val matches = mutableListOf<String>()
        var filesScanned = 0
        var hitLimit = false

        val walker = rootDir.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS }
            .iterator()
        while (walker.hasNext() && matches.size < MAX_SEARCH_MATCHES && filesScanned < MAX_SEARCH_FILES) {
            val f = walker.next()
            if (!f.isFile) continue
            if (f.length() > MAX_SEARCH_FILE_BYTES) continue
            filesScanned++
            val rel = relativeToRoot(rootDir, f)
            try {
                f.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (matches.size >= MAX_SEARCH_MATCHES) return@useLines
                        if (line.indexOf('\u0000') >= 0) return@useLines // file biner → lewati
                        if (line.lowercase().contains(needle)) {
                            matches.add("$rel:${index + 1}: ${line.trim().take(MAX_MATCH_LINE)}")
                        }
                    }
                }
            } catch (_: Exception) {
                // File tidak terbaca (permission/encoding) → lewati.
            }
        }
        if (matches.size >= MAX_SEARCH_MATCHES || filesScanned >= MAX_SEARCH_FILES) hitLimit = true

        when {
            matches.isEmpty() -> "No matches for '$query'"
            else -> buildString {
                matches.forEach {
                    append(it).append('\n')
                }
                if (hitLimit) append("… (search stopped at limit)")
            }.trimEnd()
        }
    } catch (e: Exception) {
        "ERROR: ${e.message ?: "search failed"}"
    }
}
