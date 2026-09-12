package com.openchai.app

import com.openchai.app.ai.ProviderRegistry
import com.openchai.core.agent.AgentEngine
import com.openchai.core.agent.AgentEvent
import com.openchai.core.agent.AgentRequest
import com.openchai.core.ai.StreamEvent
import com.openchai.core.model.AgentStep
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Project
import com.openchai.core.model.ProviderId
import com.openchai.core.model.Role
import com.openchai.core.settings.EngineMode
import com.openchai.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow

/** Event orkestrasi yang dikonsumsi ChatViewModel. */
sealed class OrchestrationEvent {
    data class StepsChanged(val steps: List<AgentStep>) : OrchestrationEvent()
    data class PartialAnswer(val accumulated: String) : OrchestrationEvent()
    data class Finished(val answer: String, val steps: List<AgentStep>) : OrchestrationEvent()
    data class Failed(val message: String) : OrchestrationEvent()
}

/**
 * Satu pintu antara Chat UI dan engine. Memilih engine:
 *  1. OpenCode server (bila diaktifkan & sehat)
 *  2. Built-in agent (tool loop sandboxed)
 * atau mode plain-chat langsung ke provider AI.
 */
class AgentOrchestrator(
    private val settings: SettingsRepository,
    private val registry: ProviderRegistry,
    private val builtIn: AgentEngine,
    private val openCode: AgentEngine,
    private val activeProject: StateFlow<Project?>
) {

    fun engineLabel(): String {
        val s = settings.settings.value
        return if (s.engineMode == EngineMode.PLAIN_CHAT) {
            "Direct chat"
        } else if (s.preferOpenCodeEngine && s.openCodeServerUrl.isNotBlank()) {
            openCode.engineName
        } else {
            builtIn.engineName
        }
    }

    fun execute(
        task: String,
        history: List<Pair<String, String>>,
        workspaceFs: com.openchai.core.data.WorkspaceFs? = null,
        permission: com.openchai.core.agent.PermissionContext? = null
    ): Flow<OrchestrationEvent> =
        channelFlow {
            val s = settings.settings.value

            // ---- Mode plain chat: langsung streaming ke provider -------------
            if (s.engineMode == EngineMode.PLAIN_CHAT) {
                val provider = registry.get(s.activeProvider)
                if (provider == null) {
                    send(OrchestrationEvent.Failed("Provider AI belum dikonfigurasi."))
                    return@channelFlow
                }
                val messages = buildList {
                    add(ChatMessage(role = Role.SYSTEM, content = PLAIN_CHAT_SYSTEM))
                    history.forEach { (role, content) ->
                        add(
                            ChatMessage(
                                role = if (role == "user") Role.USER else Role.ASSISTANT,
                                content = content
                            )
                        )
                    }
                    add(ChatMessage(role = Role.USER, content = task))
                }
                val acc = StringBuilder()
                provider.streamChat(messages, s.selectedModel).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            acc.append(event.text)
                            send(OrchestrationEvent.PartialAnswer(acc.toString()))
                        }
                        StreamEvent.Done ->
                            send(OrchestrationEvent.Finished(acc.toString(), emptyList()))
                        is StreamEvent.Error ->
                            send(OrchestrationEvent.Failed(event.message))
                    }
                }
                return@channelFlow
            }

            // ---- Mode agent --------------------------------------------------
            var engine: AgentEngine? = null
            if (s.preferOpenCodeEngine && s.openCodeServerUrl.isNotBlank()) {
                try {
                    if (openCode.healthCheck()) engine = openCode
                } catch (_: Exception) {
                    engine = null
                }
            }
            val chosen = engine ?: builtIn

            val request = AgentRequest(
                task = task,
                history = history,
                workspacePath = activeProject.value?.path,
                workspaceFs = workspaceFs,
                permission = permission
            )

            val acc = StringBuilder()
            val steps = LinkedHashMap<String, AgentStep>()

            try {
                chosen.execute(request).collect { event ->
                    when (event) {
                        is AgentEvent.StepUpdated -> {
                            steps[event.step.label] = event.step
                            send(OrchestrationEvent.StepsChanged(steps.values.toList()))
                        }
                        is AgentEvent.Delta -> {
                            acc.append(event.text)
                            send(OrchestrationEvent.PartialAnswer(acc.toString()))
                        }
                        is AgentEvent.Completed -> {
                            val answer = event.answer.ifBlank { acc.toString() }
                            send(OrchestrationEvent.Finished(answer, steps.values.toList()))
                        }
                        is AgentEvent.Failed ->
                            send(OrchestrationEvent.Failed(event.message))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                send(OrchestrationEvent.Failed(e.message ?: "Agent error"))
            }
        }

    companion object {
        val PLAIN_CHAT_SYSTEM: String = """
            You are Open Chat AI, a friendly and precise AI assistant embedded in an
            Android app. Answer with clear markdown. When showing code, always use
            fenced code blocks with the language tag. Keep answers concise but complete.
        """.trimIndent()
    }
}
