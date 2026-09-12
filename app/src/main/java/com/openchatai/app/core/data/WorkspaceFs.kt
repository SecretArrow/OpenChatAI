package com.openchai.core.data

/**
 * Abstraksi filesystem workspace untuk agent (kontrak FROZEN).
 *
 * Semua path RELATIF terhadap root workspace (separator '/'), "." = root.
 * Dua implementasi:
 *  - FileWorkspaceFs  (backend java.io.File — workspace app-dir)
 *  - SafWorkspaceFs   (backend DocumentFile — workspace dari folder yang dipilih
 *                      user lewat SAF OpenDocumentTree)
 *
 * Semua operasi mengembalikan string hasil yang aman dimasukkan ke konteks LLM;
 * kegagalan = "ERROR: <pesan>" (tidak pernah melempar exception). Limit yang sama
 * dengan AgentTools lama: list 2 level maks 80 entri, read 16 KB, search 40 match.
 */
interface WorkspaceFs {
    /** Label root untuk prompt, mis. "/storage/emulated/0/MyProject/workspace". */
    val rootLabel: String

    /** True bila workspace ini mendukung eksekusi shell di root (backend File). */
    val supportsShell: Boolean

    fun listFiles(relPath: String): String

    fun readFile(relPath: String): String

    /** Tulis file (parent dibuat otomatis). Kembalikan "OK (N bytes)" atau "ERROR: …". */
    fun writeFile(relPath: String, content: String): String

    fun deleteFile(relPath: String): String

    /** Pencarian teks case-insensitive rekursif: "file:line: teks" per baris. */
    fun search(query: String): String

    /** True bila [relPath] ada (file atau direktori). */
    fun exists(relPath: String): Boolean
}
