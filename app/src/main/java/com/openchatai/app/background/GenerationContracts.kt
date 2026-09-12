package com.openchatai.app.background

import com.openchai.core.model.AgentStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Kontrak generasi per-sesi (multi-session parallel).
 * Implementasi: DefaultGenerationManager (subagent 3-b).
 * Konsumen: ChatViewModel (subagent 3-a) & GenerationForegroundService (3-b).
 */
sealed class SessionGenState {
    abstract val sessionId: String

    /** Generasi sedang berjalan; [partialText] & [steps] terupdate berkala. */
    data class Running(
        override val sessionId: String,
        val partialText: String = "",
        val steps: List<AgentStep> = emptyList(),
        val startedAt: Long = System.currentTimeMillis()
    ) : SessionGenState()

    /** Selesai normal — pesan asisten sudah dipersist oleh manager. */
    data class Done(
        override val sessionId: String,
        val finalText: String
    ) : SessionGenState()

    /** Gagal — pesan error sudah dipersist oleh manager (isError=true). */
    data class Failed(
        override val sessionId: String,
        val error: String
    ) : SessionGenState()

    /** Dibatalkan user — pesan parsial sudah dipersist oleh manager. */
    data class Cancelled(
        override val sessionId: String,
        val partialText: String
    ) : SessionGenState()
}

/**
 * Jembatan deep-link dari notifikasi background ke navigasi.
 * MainActivity menaruh session_id (onCreate/onNewIntent), NavGraph mengambil
 * lalu mengosongkannya setelah menavigasi ke sesi tersebut.
 */
object SessionDeepLink {
    val pendingSessionId = MutableStateFlow<String?>(null)
}

/**
 * Pusat semua generasi aktif. Satu proses, banyak sesi paralel.
 * Scope coroutines milik manager (bukan viewModelScope) sehingga generasi
 * tetap berjalan saat navigasi antar tab / activity background.
 */
interface GenerationManager {
    /** State terakhir per sessionId (hanya entri yang relevan: Running/akhir). */
    val states: StateFlow<Map<String, SessionGenState>>

    /** True bila sesi ini sedang menghasilkan balasan. */
    fun isGenerating(sessionId: String): Boolean

    /**
     * Mulai generasi untuk [sessionId]. Manager sendiri yang: membaca pesan
     * dari ConversationStore, membangun history, memanggil orchestrator,
     * mempublish state, dan mempersist pesan asisten (Done/Failed/Cancelled),
     * termasuk auto-title percakapan bila masih "New conversation".
     * No-op bila sesi sudah generating.
     */
    fun start(sessionId: String)

    /** Batalkan generasi sesi ini (persist partial + state Cancelled). */
    fun cancel(sessionId: String)

    /** Batalkan semua sesi aktif (dipakai aksi notifikasi & shutdown). */
    fun cancelAll()
}
