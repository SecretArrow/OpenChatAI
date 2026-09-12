package com.openchai.agent.opencode

import com.openchai.core.agent.AgentEngine
import com.openchai.core.agent.AgentEvent
import com.openchai.core.agent.AgentRequest
import com.openchai.core.model.AgentStep
import com.openchai.core.model.StepState
import com.openchai.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Engine "OpenCode": best-effort client untuk OpenCode server mode (`opencode serve`),
 * endpoint dianggap kompatibel dengan konvensi API /config, /session, /session/{id}/message.
 *
 * Ini bukan implementasi lengkap protokol OpenCode — bila server tidak tersedia,
 * tidak sehat, atau jawabannya tidak bisa di-parse, engine memancarkan
 * [AgentEvent.Failed] dan orchestrator akan fallback ke Built-in agent.
 */
class OpenCodeRuntime(private val settings: SettingsRepository) : AgentEngine {

    override val engineName: String = "OpenCode"

    private fun serverUrl(): String = settings.settings.value.openCodeServerUrl.trim()

    override suspend fun healthCheck(): Boolean {
        val url = serverUrl()
        if (url.isBlank()) return false
        return OpenCodeClient(url).health()
    }

    override suspend fun start(workspacePath: String?): Result<Unit> {
        val url = serverUrl()
        if (url.isBlank()) {
            return Result.failure(
                IllegalStateException("OpenCode server URL is not set. Open Settings → Agent.")
            )
        }
        return if (OpenCodeClient(url).health()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("OpenCode server unreachable at $url"))
        }
    }

    override suspend fun stop() { /* no-op */ }

    override fun execute(request: AgentRequest): Flow<AgentEvent> = flow {
        try {
            val url = serverUrl()
            if (url.isBlank()) {
                emit(AgentEvent.Failed("OpenCode server URL is not set. Open Settings → Agent."))
                return@flow
            }
            emit(AgentEvent.StepUpdated(AgentStep("Connecting to OpenCode", StepState.RUNNING)))
            val client = OpenCodeClient(url)
            val session = client.createSession()
            if (session == null) {
                emit(AgentEvent.Failed("OpenCode server unavailable."))
                return@flow
            }
            emit(
                AgentEvent.StepUpdated(
                    AgentStep("Connecting to OpenCode", StepState.DONE, detail = url.take(80))
                )
            )
            emit(AgentEvent.StepUpdated(AgentStep("Working via OpenCode", StepState.RUNNING)))

            val workspace = request.workspacePath?.trim().orEmpty()
            val prompt = if (workspace.isBlank()) {
                request.task
            } else {
                request.task + "\n\nWorkspace: $workspace"
            }
            val answer = client.sendMessage(session, prompt)
            if (answer == null) {
                emit(AgentEvent.Failed("OpenCode returned no answer."))
                return@flow
            }
            emit(
                AgentEvent.StepUpdated(
                    AgentStep(
                        "Working via OpenCode",
                        StepState.DONE,
                        detail = "Answer received (${answer.length} chars)"
                    )
                )
            )
            emit(AgentEvent.Completed(answer))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            emit(AgentEvent.Failed(e.message ?: "OpenCode error"))
        }
    }.flowOn(Dispatchers.IO)
}
