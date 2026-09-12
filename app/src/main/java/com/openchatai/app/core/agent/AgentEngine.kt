package com.openchai.core.agent

import com.openchai.core.model.AgentStep
import kotlinx.coroutines.flow.Flow

/** Event yang dipancarkan engine agent selama mengerjakan satu tugas. */
sealed class AgentEvent {
    data class StepUpdated(val step: AgentStep) : AgentEvent()
    data class Delta(val text: String) : AgentEvent()
    data class Completed(val answer: String) : AgentEvent()
    data class Failed(val message: String) : AgentEvent()
}

data class AgentRequest(
    val task: String,
    /** Riwayat (role, content) untuk konteks lanjutan percakapan. */
    val history: List<Pair<String, String>> = emptyList(),
    val workspacePath: String? = null,
    /**
     * Filesystem workspace untuk tool agent. Bila non-null, engine WAJIB memakai
     * ini (bukan akses File langsung) — mendukung workspace SAF (folder pilihan
     * user) maupun app-dir. Null = fallback legacy ke [workspacePath].
     */
    val workspaceFs: com.openchai.core.data.WorkspaceFs? = null,
    /**
     * Konteks izin gaya Claude Code / OpenCode (mode + broker + sessionId).
     * Null = perilaku legacy (auto-approve read, run_command via autoApproveCommands).
     */
    val permission: PermissionContext? = null
)

/** Mode izin + broker untuk satu run generasi agent. */
data class PermissionContext(
    val mode: PermissionMode,
    val broker: PermissionBroker,
    val sessionId: String
)

/**
 * Kontrak engine agent (pola OpenCode). Implementasi:
 *  - com.openchatai.app.agent.opencode.OpenCodeRuntime — client untuk server OpenCode eksternal.
 *  - com.openchatai.app.agent.BuiltInAgent — agent bawaan dengan tool loop ter-sandbox.
 */
interface AgentEngine {
    val engineName: String

    suspend fun start(workspacePath: String?): Result<Unit>

    suspend fun stop()

    suspend fun healthCheck(): Boolean

    fun execute(request: AgentRequest): Flow<AgentEvent>
}
