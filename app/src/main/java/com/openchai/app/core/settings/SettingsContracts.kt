package com.openchai.core.settings

import com.openchai.core.model.ProviderId
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ChatDensity { COMPACT, COMFORTABLE }

/** AGENT = mode coding agent (tool loop), PLAIN_CHAT = tanya-jawab langsung tanpa tools. */
enum class EngineMode { AGENT, PLAIN_CHAT }

data class AppSettings(
    val engineMode: EngineMode = EngineMode.AGENT,
    val activeProvider: ProviderId = ProviderId.OLLAMA,
    val selectedModel: String = "",
    // Endpoint provider
    val ollamaEndpoint: String = "http://127.0.0.1:11434",
    val openaiEndpoint: String = "https://api.openai.com/v1",
    val anthropicEndpoint: String = "https://api.anthropic.com",
    val googleEndpoint: String = "https://generativelanguage.googleapis.com",
    val poolsideEndpoint: String = "https://inference.poolside.ai/v1",
    val customEndpoint: String = "",
    // AI lokal on-device (llama.cpp / GGUF)
    val localModelPath: String = "",
    val localContextSize: Int = 2048,
    val localThreads: Int = 0,
    val localAutoLoad: Boolean = true,
    // OpenCode engine (opsional, server mode)
    val openCodeServerUrl: String = "",
    val preferOpenCodeEngine: Boolean = false,
    // Agent
    val maxAgentIterations: Int = 12,
    val autoApproveCommands: Boolean = false,
    // Terminal
    val terminalFontSize: Int = 13,
    val terminalAutoScroll: Boolean = true,
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val chatFontScale: Float = 1.0f,
    val chatDensity: ChatDensity = ChatDensity.COMFORTABLE,
    // PATH tambahan untuk terminal (mis. runtime Node/Python berbasis Termux)
    val extraPathDirs: String = ""
)

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)

    /** API key dibaca dari SecureStore (EncryptedSharedPreferences). */
    suspend fun apiKey(provider: ProviderId): String

    suspend fun setApiKey(provider: ProviderId, key: String)
}
