package com.openchai.core.terminal

import kotlinx.coroutines.flow.StateFlow

data class TerminalSessionInfo(
    val id: String,
    val title: String,
    val cwd: String,
    val createdAt: Long,
    val alive: Boolean = true
)

/**
 * Host sesi terminal interaktif (sh/mksh). Sesi tetap hidup meski panel ditutup;
 * proses long-running berpindah ke ProcessSupervisor.
 */
interface TerminalHost {
    val sessions: StateFlow<List<TerminalSessionInfo>>

    fun createSession(cwd: String, title: String = "shell"): String

    /** Tulis baris input (diakhiri newline otomatis bila perlu). */
    fun write(sessionId: String, input: String)

    /** Output buffer sesi (ring buffer berbatas). */
    fun output(sessionId: String): StateFlow<String>

    fun killSession(sessionId: String)

    fun clearOutput(sessionId: String)
}
