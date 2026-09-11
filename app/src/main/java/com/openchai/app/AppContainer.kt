package com.openchai.app

import android.content.Context
import com.openchai.agent.BuiltInAgent
import com.openchai.agent.opencode.OpenCodeRuntime
import com.openchai.app.ai.ProviderRegistry
import com.openchai.app.ai.OllamaProvider
import com.openchai.app.ai.OpenAiCompatProvider
import com.openchai.app.ai.AnthropicProvider
import com.openchai.app.ai.GoogleProvider
import com.openchai.core.agent.AgentEngine
import com.openchai.core.data.ConversationStore
import com.openchai.core.data.SecureStore
import com.openchai.core.data.WorkspaceManager
import com.openchai.core.model.Project
import com.openchai.core.model.ProviderId
import com.openchai.core.runtime.ProcessSupervisor
import com.openchai.core.settings.SettingsRepository
import com.openchai.core.terminal.TerminalHost
import com.openchai.data.AndroidWorkspaceManager
import com.openchai.data.DataStoreSettingsRepository
import com.openchai.data.EncryptedSecureStore
import com.openchai.data.JsonConversationStore
import com.openchai.runtime.AndroidProcessManager
import com.openchai.terminal.TerminalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Graph dependency aplikasi. Semua modul modular & dapat diganti independently. */
class AppContainer(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Data layer
    val secureStore: SecureStore = EncryptedSecureStore(context)
    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(context, secureStore)
    val conversationStore: ConversationStore = JsonConversationStore(context)
    val workspaceManager: WorkspaceManager = AndroidWorkspaceManager(context)

    // Runtime layer
    val processManager: AndroidProcessManager = AndroidProcessManager(context)
    val processSupervisor: ProcessSupervisor = processManager
    val terminalHost: TerminalHost = TerminalManager(context)

    // Proyek aktif (dipakai UI, agent, dan terminal)
    val activeProject = MutableStateFlow<Project?>(null)

    // AI providers
    val providerRegistry = ProviderRegistry(
        ollama = OllamaProvider(settingsRepository),
        openai = OpenAiCompatProvider(settingsRepository, secureStore, ProviderId.OPENAI, "https://api.openai.com/v1"),
        anthropic = AnthropicProvider(settingsRepository, secureStore),
        google = GoogleProvider(settingsRepository, secureStore),
        custom = OpenAiCompatProvider(settingsRepository, secureStore, ProviderId.CUSTOM, "")
    )

    // Agent engines
    val builtInAgent: AgentEngine =
        BuiltInAgent(settingsRepository, secureStore, processManager, providerRegistry)
    val openCodeRuntime: AgentEngine = OpenCodeRuntime(settingsRepository)

    // Orkestrator: satu pintu dari Chat UI ke engine agent / provider
    val orchestrator = AgentOrchestrator(
        settings = settingsRepository,
        registry = providerRegistry,
        builtIn = builtInAgent,
        openCode = openCodeRuntime,
        activeProject = activeProject
    )
}
