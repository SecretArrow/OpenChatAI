package com.openchai.core.runtime

import kotlinx.coroutines.flow.StateFlow

enum class ProcState { STARTING, RUNNING, EXITED, FAILED, STOPPED, RESTARTING }

data class ManagedProcess(
    val id: String,
    val command: String,
    val cwd: String,
    val pid: Int? = null,
    val state: ProcState = ProcState.STARTING,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val exitCode: Int? = null,
    val autoRestart: Boolean = false,
    val detectedPort: Int? = null
)

/**
 * Supervisor process latar belakang (npm run dev, node server.js, python3 server.py, dst).
 * Output disimpan dalam ring buffer berbatas agar hemat memori.
 */
interface ProcessSupervisor {
    val processes: StateFlow<List<ManagedProcess>>

    fun start(command: String, cwd: String, autoRestart: Boolean = false): ManagedProcess

    fun stop(id: String)

    fun restart(id: String)

    /** Snapshot output saat ini (sudah dibatasi ukurannya). */
    fun outputFor(id: String): String?

    /** Counter revisi output; naik setiap ada output baru (untuk trigger refresh UI). */
    fun outputRevision(id: String): StateFlow<Long>

    fun pruneFinished()
}
