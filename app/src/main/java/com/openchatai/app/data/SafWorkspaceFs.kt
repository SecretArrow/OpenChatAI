package com.openchai.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.openchai.core.data.WorkspaceFs

/**
 * Implementasi [WorkspaceFs] untuk workspace dari folder pilihan user
 * (SAF OpenDocumentTree). Murni [DocumentsContract] — TANPA
 * androidx.documentfile (dependensi baru dilarang).
 *
 * Root workspace: [treeUriString] dapat berupa
 *  - URI dokumen subfolder yang dibuat [AndroidWorkspaceManager.createFromTreeUri]
 *    (Project.treeUri tersimpan), atau
 *  - URI tree mentah hasil OpenDocumentTree.
 * Keduanya dipetakan ke documentId root workspace yang tepat.
 *
 * Resolusi path relatif dilakukan dengan menelusuri children per segmen
 * (query [DocumentsContract.buildChildDocumentsUriUsingTree]) — tanpa cache
 * peta child (kesederhanaan > kecepatan; workspace kecil).
 *
 * Semua operasi bersifat sinkron (blocking) — Dispatchers.IO ditangani caller
 * (BuiltInAgent sudah flowOn(IO)). Semua kegagalan → "ERROR: <pesan>",
 * tidak pernah melempar exception. Limit & format string identik AgentTools:
 * list 2 level maks 80 entri ("d name/" / "- name (N bytes)"), read 16 KB,
 * search maks 400 file ≤512 KB 40 match ("rel:line: teks").
 */
class SafWorkspaceFs(context: Context, private val treeUriString: String) : WorkspaceFs {

    private val resolver = context.applicationContext.contentResolver
    private val treeUri: Uri = Uri.parse(treeUriString)

    /** documentId root workspace — dukung URI dokumen (subfolder) maupun tree mentah. */
    private val rootDocId: String =
        if (DocumentsContract.isDocumentUri(context, treeUri)) {
            // URI dokumen (mis. hasil createDocument): documentId = subfolder workspace.
            DocumentsContract.getDocumentId(treeUri)
        } else {
            // URI tree mentah: root = dokumen puncak tree.
            DocumentsContract.getTreeDocumentId(treeUri)
        }

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private val rootDocUri: Uri by lazy { docUri(rootDocId) }

    override val supportsShell: Boolean = false

    /**
     * Label root untuk prompt: "<nama tree>/<nama workspace>".
     * Query DISPLAY_NAME bisa gagal (provider lambat/URI lepas) → fallback
     * segmen terakhir URI, lalu string URI yang dipendekkan.
     */
    override val rootLabel: String by lazy {
        val treeName = queryDisplayName(
            docUri(DocumentsContract.getTreeDocumentId(treeUri))
        ).orEmpty()
        val wsName = queryDisplayName(rootDocUri)
            ?: lastSegmentName()
            ?: "workspace"
        buildString {
            if (treeName.isNotBlank()) append(treeName).append('/')
            append(wsName)
        }.ifBlank { shortUri() }
    }

    // ------------------------------------------------------------------
    // Primitif query DocumentsContract
    // ------------------------------------------------------------------

    /** Satu entri child dari query children direktori. */
    private data class ChildDoc(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long
    )

    /** Query seluruh children sebuah direktori (tanpa cache — workspace kecil). */
    private fun queryChildren(dirDocId: String): List<ChildDoc> = try {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = mutableListOf<ChildDoc>()
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.stringOrNull(0) ?: continue
                val name = c.stringOrNull(1) ?: continue
                val mime = c.stringOrNull(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                out.add(ChildDoc(id, name, mime, size))
            }
        }
        out
    } catch (_: Exception) {
        emptyList()
    }

    /** DISPLAY_NAME satu dokumen; null bila query gagal. */
    private fun queryDisplayName(docUri: Uri): String? = try {
        resolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.stringOrNull(0) else null }
    } catch (_: Exception) {
        null
    }

    /** MIME_TYPE satu dokumen; null bila query gagal. */
    private fun docMime(docId: String): String? = try {
        resolver.query(
            docUri(docId),
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.stringOrNull(0) else null }
    } catch (_: Exception) {
        null
    }

    /** COLUMN_SIZE satu dokumen; -1 bila tidak diketahui. */
    private fun docSize(docId: String): Long = try {
        resolver.query(
            docUri(docId),
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    // ------------------------------------------------------------------
    // Resolusi path relatif → documentId
    // ------------------------------------------------------------------

    /** Normalisasi segmen: buang kosong & "."; ".." ditolak (keluar sandbox). */
    private fun splitSegments(relPath: String): List<String>? {
        val segs = relPath.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }
        if (segs.any { it == ".." }) return null
        return segs
    }

    /**
     * Telusuri path relatif segmen demi segmen lewat query children.
     * Path kosong/"." = root. Null bila tidak ada / keluar sandbox.
     */
    private fun resolveDocId(relPath: String): String? {
        val segs = splitSegments(relPath) ?: return null
        var current = rootDocId
        for (seg in segs) {
            val child = queryChildren(current).firstOrNull { it.name == seg }
                ?: return null
            current = child.docId
        }
        return current
    }

    /** Tampilan path relatif utk header list ("." utk root). */
    private fun displayRel(relPath: String): String =
        splitSegments(relPath)?.joinToString("/")?.ifBlank { "." } ?: "."

    private fun lastSegmentName(): String? = try {
        Uri.decode(treeUri.lastPathSegment.orEmpty())
            .substringAfterLast(':')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun shortUri(): String = treeUriString.takeLast(48)

    // ------------------------------------------------------------------
    // WorkspaceFs
    // ------------------------------------------------------------------

    override fun listFiles(relPath: String): String {
        return try {
        val dirDocId = resolveDocId(relPath)
            ?: return "ERROR: Not found: $relPath"
        if (docMime(dirDocId) != DocumentsContract.Document.MIME_TYPE_DIR) {
            return "ERROR: Not a directory: $relPath"
        }
        val sb = StringBuilder("Tree of ")
            .append(displayRel(relPath))
            .append(" (max 2 levels):\n")
        var count = 0
        var truncated = false

        fun walk(dirDoc: String, indent: String, depth: Int) {
            // Urutan sama dengan AgentTools: direktori dulu, lalu nama (case-insensitive).
            val children = queryChildren(dirDoc).sortedWith(
                compareByDescending<ChildDoc> { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
                    .thenBy { it.name.lowercase() }
            )
            for (child in children) {
                if (count >= MAX_LIST_ENTRIES) {
                    truncated = true
                    return
                }
                if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    sb.append(indent).append("d ").append(child.name).append("/\n")
                    count++
                    if (depth < 2) walk(child.docId, "$indent  ", depth + 1)
                } else {
                    sb.append(indent).append("- ").append(child.name)
                        .append(" (").append(child.size).append(" bytes)\n")
                    count++
                }
            }
        }

        walk(dirDocId, "", 1)
        if (count == 0) sb.append("(empty)\n")
        if (truncated) sb.append("… (truncated at $MAX_LIST_ENTRIES entries)\n")
        sb.toString().trimEnd()
    } catch (e: Exception) {
        "ERROR: ${e.message ?: "list failed"}"
    }
    }

    override fun readFile(relPath: String): String {
        return try {
        val docId = resolveDocId(relPath)
            ?: return "ERROR: Not found: $relPath"
        if (docMime(docId) == DocumentsContract.Document.MIME_TYPE_DIR) {
            return "ERROR: Not a file: $relPath"
        }
        val uri = docUri(docId)
        val size = docSize(docId)
        val toRead = if (size > 0) minOf(size, MAX_READ_BYTES.toLong()).toInt() else MAX_READ_BYTES
        val buf = ByteArray(toRead)
        var read = 0
        resolver.openInputStream(uri)?.use { input ->
            while (read < toRead) {
                val r = input.read(buf, read, toRead - read)
                if (r < 0) break
                read += r
            }
        } ?: return "ERROR: Cannot open: $relPath"
        // Tolak file biner (mengandung 0x00) — sama seperti AgentTools.
        if (buf.copyOf(read).contains(0.toByte())) {
            return "ERROR: Binary file is not shown: $relPath"
        }
        val text = String(buf, 0, read, Charsets.UTF_8)
        if (size > MAX_READ_BYTES) {
            "$text\n… (truncated; file is $size bytes, showing first $MAX_READ_BYTES)"
        } else {
            text
        }
    } catch (e: Exception) {
        "ERROR: ${e.message ?: "read failed"}"
    }
    }

    override fun writeFile(relPath: String, content: String): String {
        return try {
        if (relPath.isBlank()) return "ERROR: Empty path"
        val segs = splitSegments(relPath)
            ?.takeIf { it.isNotEmpty() }
            ?: return "ERROR: Empty path"
        val name = segs.last()

        // Telusuri parent; buat folder bertingkat yang belum ada (MIME_DIR).
        var parentDocId = rootDocId
        for (seg in segs.dropLast(1)) {
            val existing = queryChildren(parentDocId).firstOrNull { it.name == seg }
            parentDocId = if (existing != null) {
                if (existing.mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                    return "ERROR: Not a directory: $seg"
                }
                existing.docId
            } else {
                val created = DocumentsContract.createDocument(
                    resolver, docUri(parentDocId),
                    DocumentsContract.Document.MIME_TYPE_DIR, seg
                ) ?: return "ERROR: Cannot create directory: $seg"
                DocumentsContract.getDocumentId(created)
            }
        }

        // Bila target sudah ada pakai URI-nya, bila belum buat dokumen baru.
        val existing = queryChildren(parentDocId).firstOrNull { it.name == name }
        val targetUri = if (existing != null) {
            if (existing.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                return "ERROR: Not a file: $relPath"
            }
            docUri(existing.docId)
        } else {
            DocumentsContract.createDocument(
                resolver, docUri(parentDocId),
                "application/octet-stream", name
            ) ?: return "ERROR: Cannot create: $relPath"
        }

        val bytes = content.toByteArray(Charsets.UTF_8)
        // "wt" = write + truncate (overwrite bersih); beberapa provider hanya
        // mendukung "w" — fallback agar tetap bisa menulis.
        val out = runCatching { resolver.openOutputStream(targetUri, "wt") }
            .getOrElse { runCatching { resolver.openOutputStream(targetUri) }.getOrNull() }
        out?.use { it.write(bytes) } ?: return "ERROR: Cannot open output: $relPath"
        "OK (${bytes.size} bytes)"
    } catch (e: Exception) {
        "ERROR: ${e.message ?: "write failed"}"
    }
    }

    override fun deleteFile(relPath: String): String {
        return try {
        val docId = resolveDocId(relPath)
            ?: return "ERROR: Not found: $relPath"
        // Lindungi root workspace dari penghapusan (provider menghapus
        // direktori secara rekursif — root hilang = workspace rusak).
        if (docId == rootDocId) return "ERROR: Cannot delete workspace root"
        val deleted = DocumentsContract.deleteDocument(resolver, docUri(docId))
        if (deleted) "OK (deleted $relPath)" else "ERROR: Could not delete: $relPath"
    } catch (e: Exception) {
        "ERROR: ${e.message ?: "delete failed"}"
    }
    }

    override fun search(query: String): String {
        return try {
        if (query.isBlank()) return "ERROR: Empty search query"
        val needle = query.lowercase()
        val matches = mutableListOf<String>()
        var filesScanned = 0
        var hitLimit = false

        fun walk(dirDocId: String, relPrefix: String) {
            if (matches.size >= MAX_SEARCH_MATCHES || filesScanned >= MAX_SEARCH_FILES) {
                hitLimit = true
                return
            }
            for (child in queryChildren(dirDocId)) {
                if (matches.size >= MAX_SEARCH_MATCHES || filesScanned >= MAX_SEARCH_FILES) {
                    hitLimit = true
                    return
                }
                val childRel = if (relPrefix == ".") child.name else "$relPrefix/${child.name}"
                if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (child.name in SKIP_DIRS) continue
                    walk(child.docId, childRel)
                } else {
                    // File > 512KB dilewati (ukuran -1 = tidak diketahui, tetap discan —
                    // pembacaan di [scanFile] tetap dibatasi).
                    if (child.size > MAX_SEARCH_FILE_BYTES) continue
                    filesScanned++
                    scanFile(child, childRel, needle, matches)
                }
            }
        }

        walk(rootDocId, ".")

        when {
            matches.isEmpty() -> "No matches for '$query'"
            else -> buildString {
                matches.forEach { append(it).append('\n') }
                if (hitLimit) append("… (search stopped at limit)")
            }.trimEnd()
        }
    } catch (e: Exception) {
        "ERROR: ${e.message ?: "search failed"}"
    }
    }

    override fun exists(relPath: String): Boolean = try {
        resolveDocId(relPath) != null
    } catch (_: Exception) {
        false
    }

    /**
     * Scan satu file teks (case-insensitive) dengan pembacaan terbatas
     * [MAX_SEARCH_FILE_BYTES] — size dari provider tidak selalu bisa dipercaya.
     * File biner (0x00) dilewati.
     */
    private fun scanFile(child: ChildDoc, rel: String, needle: String, matches: MutableList<String>) {
        try {
            val buf = ByteArray(MAX_SEARCH_FILE_BYTES.toInt() + 1)
            var read = 0
            resolver.openInputStream(docUri(child.docId))?.use { input ->
                while (read < buf.size) {
                    val r = input.read(buf, read, buf.size - read)
                    if (r < 0) break
                    read += r
                }
            } ?: return
            if (read > MAX_SEARCH_FILE_BYTES) return // melebihi batas → lewati
            val content = buf.copyOf(read)
            if (content.contains(0.toByte())) return // biner → lewati
            String(content, Charsets.UTF_8).lineSequence().forEachIndexed { index, line ->
                if (matches.size >= MAX_SEARCH_MATCHES) return
                if (line.lowercase().contains(needle)) {
                    matches.add("$rel:${index + 1}: ${line.trim().take(MAX_MATCH_LINE)}")
                }
            }
        } catch (_: Exception) {
            // File tidak terbaca (permission/encoding) → lewati.
        }
    }

    private companion object {
        const val MAX_LIST_ENTRIES = 80
        const val MAX_READ_BYTES = 16 * 1024
        const val MAX_SEARCH_MATCHES = 40
        const val MAX_SEARCH_FILE_BYTES = 512L * 1024L
        const val MAX_SEARCH_FILES = 400
        const val MAX_MATCH_LINE = 200
        val SKIP_DIRS = setOf(".git", "node_modules")
    }
}

/** Baca kolom String nullable dengan aman dari cursor. */
private fun Cursor.stringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)
