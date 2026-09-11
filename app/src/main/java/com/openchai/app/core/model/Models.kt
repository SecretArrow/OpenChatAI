package com.openchai.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class Role { SYSTEM, USER, ASSISTANT }

enum class StepState { RUNNING, DONE, FAILED }

/** Satu langkah aktivitas agent yang ditampilkan secara manusiawi di chat. */
@Serializable
data class AgentStep(
    val label: String,
    val state: StepState = StepState.RUNNING,
    val detail: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/** Pesan chat. Pesan agent-activity berisi daftar langkah, bukan konten markdown. */
@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String = "",
    val role: Role = Role.USER,
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val steps: List<AgentStep> = emptyList(),
    val isError: Boolean = false,
    val isAgentActivity: Boolean = false
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New conversation",
    val projectId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val path: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis()
)

enum class ProviderId { OLLAMA, OPENAI, ANTHROPIC, GOOGLE, CUSTOM }

data class ModelInfo(
    val id: String,
    val name: String,
    val providerId: ProviderId,
    val providerName: String,
    val isLocal: Boolean,
    val details: String? = null
)

data class ProviderConfig(
    val providerId: ProviderId = ProviderId.OLLAMA,
    val baseUrl: String = "",
    val apiKey: String = "",
    val selectedModel: String = ""
)
