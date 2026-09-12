package com.openchatai.app

import android.content.Context
import com.openchai.agent.BuiltInAgent
import com.openchai.agent.opencode.OpenCodeRuntime
import com.openchatai.app.ai.ProviderRegistry
import com.openchatai.app.ai.LocalLlamaProvider
import com.openchatai.app.ai.OllamaLauncher
import com.openchatai.app.permissions.DefaultPermissionBroker
import com.openchatai.app.ai.OllamaProvider
import com.openchatai.app.ai.OpenAiCompatProvider
import com.openchatai.app.ai.AnthropicProvider
import com.openchatai.app.ai.GoogleProvider
import com.openchai.core.agent.AgentEngine
import com.openchai.core.agent.CommandResult
import com.openchai.core.agent.CommandRunner
import com.openchai.core.data.ConversationStore
import com.openchai.core.data.SecureStore
import com.openchai.core.data.WorkspaceManager
import com.openchai.core.model.Project
import com.openchai.core.model.ProviderId
import com.openchai.core.runtime.ManagedProcess
import com.openchai.core.runtime.ProcessSupervisor
import com.openchai.core.settings.SettingsRepository
import com.openchai.core.terminal.TerminalHost
import com.openchai.core.terminal.TerminalSessionInfo
import com.openchai.core.llm.LlamaEngine
import com.openchai.core.llm.ModelManager
import com.openchai.core.llm.RuntimeManager
import com.openchai.core.linux.LinuxEnvManager
import com.openchai.core.linux.LinuxEnvPhase
import com.openchai.core.linux.LinuxProcessSupervisor
import com.openchai.core.linux.LinuxShell
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Runtime layer — urutan deklarasi: legacy dulu, lalu Linux, lalu delegating
    // (semua val init-nya valid karena hanya bergantung pada yang di atasnya).
    val terminalManagerLegacy: TerminalManager = TerminalManager(context)
    val processManager: AndroidProcessManager = AndroidProcessManager(context)

    // Embedded Linux userspace (proot + rootfs Ubuntu) — dev environment nyata
    val linuxEnv: LinuxEnvManager = LinuxEnvManager(context, appScope)
    val linuxShell: LinuxShell = LinuxShell(linuxEnv, appScope)
    val linuxSupervisor: LinuxProcessSupervisor = LinuxProcessSupervisor(linuxEnv, appScope)

    // Delegating: status Linux READY → pakai userspace Linux (proot), else fallback shell Android legacy
    val terminalHost: TerminalHost = DelegatingTerminalHost(linuxEnv, linuxShell, terminalManagerLegacy)
    val processSupervisor: ProcessSupervisor = DelegatingProcessSupervisor(linuxEnv, linuxSupervisor, processManager)

    // Satu pintu keamanan command agent — KDoc CommandRunner tetap berlaku:
    // agent TIDAK boleh exec process sendiri di luar interface ini. Dengan
    // delegating, command agent kini berjalan di sandbox Linux (proot, user
    // non-root) bila lingkungan READY, else fallback AndroidProcessManager legacy.
    // (Dipindah ke atas: urutan inisialisasi property Kotlin mengikuti urutan
    // deklarasi, dan val ollamaLauncher di bawah membutuhkannya.)
    private val delegatingCommandRunner: CommandRunner =
        DelegatingCommandRunner(linuxEnv, linuxSupervisor, processManager)

    // Launcher Ollama di dalam sandbox Linux (kontrak 11-c). Dipakai VM/UI lewat
    // tombol "Start Ollama": mulai `ollama serve` via supervisor delegating dan
    // probe `command -v ollama` via delegatingCommandRunner.
    val ollamaLauncher: OllamaLauncher =
        OllamaLauncher(context, linuxEnv, processSupervisor, delegatingCommandRunner)

    // Proyek aktif (dipakai UI, agent, dan terminal)
    val activeProject = MutableStateFlow<Project?>(null)

    // Sinyal selesai restore awal: true SETELAH snapshot settings pertama dibaca
    // + attempt pemulihan activeWorkspaceId (apapun hasilnya). Dipakai NavGraph
    // untuk gating first-run TANPA delay heuristik.
    private val _initState = MutableStateFlow(false)

    /** True bila restore workspace awal (DataStore first emission + attempt) SUDAH selesai. */
    val initState: StateFlow<Boolean> = _initState.asStateFlow()

    // AI lokal on-device (llama.cpp)
    // RuntimeManager HARUS dibuat sebelum llamaEngine: ctor-nya memindah pack
    // runtime terpasang dan men-set LlamaBridge.overrideLibPath sebelum lib
    // native dimuat pertama kali.
    val runtimeManager: RuntimeManager = RuntimeManager(context, appScope)
    val modelManager: ModelManager = ModelManager(context, appScope)
    val llamaEngine: LlamaEngine = LlamaEngine.getInstance(context)

    // Permission broker (mode izin ASK/PLAN/AUTO_READ_EDIT/FULL_ACCESS gaya CLI)
    val permissionBroker: com.openchai.core.agent.PermissionBroker = DefaultPermissionBroker()

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

        // Pulihkan workspace aktif terakhir (activeWorkspaceId) — chat langsung
        // terhubung ke workspace sebelumnya tanpa setup ulang. TANPA timeout:
        // settings adalah StateFlow, jadi first() selesai deterministik begitu
        // DataStore selesai dibaca dari disk (snapshot pertama SELALU datang).
        // Workspace SAF sudah punya persistable grant.
        appScope.launch {
            val s = runCatching { settingsRepository.settings.first() }.getOrNull()
            if (s != null && s.activeWorkspaceId.isNotBlank()) {
                val p = runCatching {
                    workspaceManager.listProjects().firstOrNull { pr -> pr.id == s.activeWorkspaceId }
                }.getOrNull()
                if (p != null && activeProject.value == null) {
                    activeProject.value = p
                }
            }
            // Sinyal selesai SELALU diset — id kosong, project tak ditemukan,
            // maupun baca settings gagal — agar gating navigasi tidak menggantung.
            _initState.value = true
        }
    }

    // Agent engines (MCP + Skills di-wire ke BuiltInAgent)
    val builtInAgent: AgentEngine =
        BuiltInAgent(
            settings = settingsRepository,
            secure = secureStore,
            runner = delegatingCommandRunner,
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

/**
 * Rute ke Linux userspace bila status [LinuxEnvManager.status] == READY, else
 * fallback host legacy (TerminalManager — shell mksh Android).
 *
 * CATATAN KONSISTENSI UI: `sessions` (dan semua method) dipilih lewat properti
 * computed berdasarkan snapshot status Linux — TANPA flatMapLatest. UI pemakai
 * delegating ini juga meng-collect status Linux (TerminalViewModel.linuxStatus /
 * TerminalPanel chip), sehingga saat status berubah UI recompose dan getter
 * dievaluasi ulang: sumber StateFlow ikut bertukar dan collector UI berpindah
 * ke flow yang baru secara otomatis.
 */
private class DelegatingTerminalHost(
    private val linuxEnv: LinuxEnvManager,
    private val linux: TerminalHost,
    private val legacy: TerminalHost
) : TerminalHost {

    private val active: TerminalHost
        get() = if (linuxEnv.status.value == LinuxEnvPhase.READY) linux else legacy

    override val sessions: StateFlow<List<TerminalSessionInfo>>
        get() = active.sessions

    override fun createSession(cwd: String, title: String): String =
        active.createSession(cwd, title)

    override fun write(sessionId: String, input: String) = active.write(sessionId, input)

    override fun output(sessionId: String): StateFlow<String> = active.output(sessionId)

    override fun killSession(sessionId: String) = active.killSession(sessionId)

    override fun clearOutput(sessionId: String) = active.clearOutput(sessionId)
}

/**
 * Rute supervisor proses latar belakang ke Linux userspace bila READY, else
 * fallback legacy (AndroidProcessManager). Pola properti computed sama dengan
 * [DelegatingTerminalHost] — lihat KDoc di sana soal konsistensi UI.
 */
private class DelegatingProcessSupervisor(
    private val linuxEnv: LinuxEnvManager,
    private val linux: ProcessSupervisor,
    private val legacy: ProcessSupervisor
) : ProcessSupervisor {

    private val active: ProcessSupervisor
        get() = if (linuxEnv.status.value == LinuxEnvPhase.READY) linux else legacy

    override val processes: StateFlow<List<ManagedProcess>>
        get() = active.processes

    override fun start(command: String, cwd: String, autoRestart: Boolean): ManagedProcess =
        active.start(command, cwd, autoRestart)

    override fun stop(id: String) = active.stop(id)

    override fun restart(id: String) = active.restart(id)

    override fun outputFor(id: String): String? = active.outputFor(id)

    override fun outputRevision(id: String): StateFlow<Long> = active.outputRevision(id)

    override fun writeStdin(id: String, line: String) = active.writeStdin(id, line)

    override fun pruneFinished() = active.pruneFinished()
}

/**
 * Rute CommandRunner untuk agent: READY → [LinuxProcessSupervisor] (command
 * jalan di sandbox Linux via proot), else fallback legacy (AndroidProcessManager).
 */
private class DelegatingCommandRunner(
    private val linuxEnv: LinuxEnvManager,
    private val linux: CommandRunner,
    private val legacy: CommandRunner
) : CommandRunner {
    override suspend fun run(command: String, cwd: String?, timeoutMs: Long): CommandResult =
        if (linuxEnv.status.value == LinuxEnvPhase.READY) {
            linux.run(command, cwd, timeoutMs)
        } else {
            legacy.run(command, cwd, timeoutMs)
        }
}
