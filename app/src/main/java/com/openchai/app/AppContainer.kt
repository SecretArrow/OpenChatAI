package com.openchai.app

import android.content.Context
import com.openchai.agent.BuiltInAgent
import com.openchai.agent.opencode.OpenCodeRuntime
import com.openchai.app.ai.ProviderRegistry
import com.openchai.app.ai.LocalLlamaProvider
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
import com.openchai.core.llm.LlamaEngine
import com.openchai.core.llm.ModelManager
import com.openchai.core.mcp.McpManagerProvider
import com.openchai.core.skills.SkillLoaderProvider
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    // AI lokal on-device (llama.cpp)
    val modelManager: ModelManager = ModelManager(context, appScope)
    val llamaEngine: LlamaEngine = LlamaEngine.getInstance(context)

    // AI providers
    val providerRegistry = ProviderRegistry(
        ollama = OllamaProvider(settingsRepository),
        openai = OpenAiCompatProvider(settingsRepository, secureStore, ProviderId.OPENAI, "https://api.openai.com/v1"),
        anthropic = AnthropicProvider(settingsRepository, secureStore),
        google = GoogleProvider(settingsRepository, secureStore),
        poolside = OpenAiCompatProvider(settingsRepository, secureStore, ProviderId.POOLSIDE, "https://inference.poolside.ai/v1"),
        custom = OpenAiCompatProvider(settingsRepository, secureStore, ProviderId.CUSTOM, ""),
        local = LocalLlamaProvider(context, settingsRepository, llamaEngine, modelManager)
    )

    init {
        // Pre-load model lokal bila diaktifkan (localAutoLoad). Ditunggu sampai
        // settings berisi path (DataStore load async); gagal diabaikan — load
        // ulang terjadi otomatis saat streamChat.
        appScope.launch {
            val s = settingsRepository.settings.first()
            if (s.localAutoLoad && s.localModelPath.isNotBlank() && !llamaEngine.isLoaded()) {
                runCatching {
                    llamaEngine.ensureLoaded(s.localModelPath, s.localContextSize, s.localThreads)
                }
            }
        }
    }

    // Agent engines (MCP + Skills di-wire ke BuiltInAgent)
    val builtInAgent: AgentEngine =
        BuiltInAgent(
            settings = settingsRepository,
            secure = secureStore,
            runner = processManager,
            registry = providerRegistry,
            mcp = McpManagerProvider.get(context),
            skills = SkillLoaderProvider.get(context)
        )
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
