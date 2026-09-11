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
    val workspacePath: String? = null
)

/**
 * Kontrak engine agent (pola OpenCode). Implementasi:
 *  - com.openchai.app.agent.opencode.OpenCodeRuntime — client untuk server OpenCode eksternal.
 *  - com.openchai.app.agent.BuiltInAgent — agent bawaan dengan tool loop ter-sandbox.
 */
interface AgentEngine {
    val engineName: String

    suspend fun start(workspacePath: String?): Result<Unit>

    suspend fun stop()

    suspend fun healthCheck(): Boolean

    fun execute(request: AgentRequest): Flow<AgentEvent>
}
