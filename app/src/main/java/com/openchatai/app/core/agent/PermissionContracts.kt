package com.openchai.core.agent

import kotlinx.coroutines.flow.StateFlow

/**
 * Mode izin gaya Claude Code / OpenCode CLI untuk tool loop agent.
 *
 *  - ASK            : mode aman (default). Tool tulis/hapus/perintah/command WAJIB
 *                     dikonfirmasi user lewat [PermissionBroker] sebelum dieksekusi.
 *  - PLAN           : mode riset read-only. Tool tulis/hapus/MCP/run_command DITOLAK
 *                     otomatis (pesan memerintahkan model menyusun rencana).
 *  - AUTO_READ_EDIT : baca + tulis file otomatis disetujui; delete/run_command/MCP
 *                     tetap bertanya.
 *  - FULL_ACCESS    : YOLO — semua tool dieksekusi tanpa konfirmasi.
 */
enum class PermissionMode { ASK, PLAN, AUTO_READ_EDIT, FULL_ACCESS }

/** Kategori tool untuk keputusan izin (gating matrix). */
enum class ToolCategory { READ, WRITE, DELETE, COMMAND, MCP }

/**
 * Matriks keputusan izin (kontrak FROZEN — semua engine wajib patuh):
 *
 * | Kategori | ASK  | PLAN | AUTO_READ_EDIT | FULL_ACCESS |
 * |----------|------|------|----------------|-------------|
 * | READ     | auto | auto | auto           | auto        |
 * | WRITE    | ASK  | deny | auto           | auto        |
 * | DELETE   | ASK  | deny | ASK            | auto        |
 * | COMMAND  | ASK  | deny | ASK            | auto        |
 * | MCP      | ASK  | deny | ASK            | auto        |
 *
 * "auto" = eksekusi tanpa bertanya; "ASK" = tanya user via [PermissionBroker];
 * "deny" = tool TIDAK dieksekusi, kembalikan pesan error ke model (di PLAN mode
 * pesannya harus menjelaskan bahwa agent sedang dalam plan mode read-only dan
 * memerintahkan model menyusun rencana implementasi sebagai gantinya).
 */
data class PermissionRule(val mode: PermissionMode, val category: ToolCategory) {
    enum class Action { AUTO, ASK, DENY }

    companion object {
        /** Evaluasi matriks di atas — satu sumber kebenaran untuk semua engine. */
        fun decide(mode: PermissionMode, category: ToolCategory): Action = when (mode) {
            PermissionMode.FULL_ACCESS -> Action.AUTO
            PermissionMode.PLAN -> if (category == ToolCategory.READ) Action.AUTO else Action.DENY
            PermissionMode.AUTO_READ_EDIT -> when (category) {
                ToolCategory.READ, ToolCategory.WRITE -> Action.AUTO
                else -> Action.ASK
            }
            PermissionMode.ASK -> if (category == ToolCategory.READ) Action.AUTO else Action.ASK
        }
    }
}

/** Kategori tool bawaan berdasarkan nama (MCP selalu MCP). */
fun toolCategoryOf(toolName: String): ToolCategory = when (toolName) {
    "list_files", "read_file", "search" -> ToolCategory.READ
    "write_file" -> ToolCategory.WRITE
    "delete_file" -> ToolCategory.DELETE
    "run_command" -> ToolCategory.COMMAND
    else -> ToolCategory.MCP
}

/** Satu permintaan izin yang menunggu jawaban user di UI. */
data class PermissionRequest(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val category: ToolCategory,
    /** Ringkasan manusiawi: command line, path file, atau nama tool MCP. */
    val detail: String,
    val isDangerous: Boolean = false
)

/** Jawaban user atas [PermissionRequest]. */
sealed class PermissionDecision {
    /** Izinkan sekali saja untuk permintaan ini. */
    data object AllowOnce : PermissionDecision()

    /** Izinkan tool ini untuk sisa run/session (tidak bertanya lagi untuk tool sama). */
    data class AllowSession(val toolName: String) : PermissionDecision()

    /** Tolak — model menerima "ERROR: user denied …". */
    data object Deny : PermissionDecision()
}

/**
 * Jembatan suspend antara tool loop agent (thread IO) dan dialog izin di UI.
 * Implementasi referensi: com.openchatai.app.permissions.DefaultPermissionBroker.
 */
interface PermissionBroker {

    /**
     * Suspend sampai user menjawab (atau run dibatalkan → CancellationException).
     * Dipanggil dari Dispatchers.IO di tengah tool loop; aman dipanggil bersamaan
     * (tiap request id unik). Bila tool sudah di-approve untuk session ini
     * (AllowSession), implementasi HARUS langsung mengembalikan AllowOnce tanpa UI.
     */
    suspend fun request(req: PermissionRequest): PermissionDecision

    /** Request yang sedang menunggu jawaban (UI mengoleksi ini untuk menampilkan dialog). */
    val pending: StateFlow<PermissionRequest?>

    /** Jawab request yang pending. */
    fun respond(reqId: String, decision: PermissionDecision)

    /** Bersihkan approval per-session (dipanggil di awal tiap run generasi). */
    fun clearSession(sessionId: String)

    /** Bersihkan semua state (process-wide). */
    fun clearAll()
}
