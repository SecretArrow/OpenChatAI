package com.openchatai.app.ui.chat

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openchatai.app.OpenChatApp
import com.openchatai.app.ai.ModelTester
import com.openchatai.app.ai.OllamaDiag
import com.openchatai.app.ai.OllamaProvider
import com.openchatai.app.ai.OllamaStartResult
import com.openchatai.app.background.GenerationManagerProvider
import com.openchatai.app.background.SessionGenState
import com.openchai.core.agent.PermissionDecision
import com.openchai.core.agent.PermissionMode
import com.openchai.core.agent.PermissionRequest
import com.openchai.core.data.ConversationStore
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Conversation
import com.openchai.core.model.ModelInfo
import com.openchai.core.model.Project
import com.openchai.core.model.ProviderId
import com.openchai.core.model.Role
import com.openchai.core.settings.AppSettings
import com.openchai.core.settings.EngineMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Status koneksi satu provider untuk Model Selector.
 * - connected: null = sedang mengecek, true/false = hasil cek terakhir.
 * - stale: true bila daftar model yang ditampilkan adalah cache list lama
 *   (fetch terakhir gagal) — UI menandai pesan dengan suffix " · cached".
 * Konstruktor posisional lama (connected, message) tetap compile (stale default).
 */
data class ProviderStatus(
    val connected: Boolean?,
    val message: String,
    val stale: Boolean = false
)

/** Status engine agent untuk error state di chat. */
data class EngineStatus(
    val engineName: String,
    val healthy: Boolean,
    val message: String
)

/**
 * ViewModel chat multi-sesi: UI hanya menyiapkan data di store lalu memulai /
 * membatalkan generasi lewat [com.openchatai.app.background.GenerationManager]
 * (app-scoped, mendukung banyak sesi paralel). Logika orchestrator pindah
 * sepenuhnya ke manager — ViewModel tidak lagi menjalankan generasi sendiri.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as OpenChatApp).container
    private val store: ConversationStore = container.conversationStore
    private val settingsRepo = container.settingsRepository

    // Pusat generasi app-scoped (pola singleton sama dengan McpManagerProvider).
    private val generationManager = GenerationManagerProvider.get(app)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    // Derived dari state manager untuk sesi AKTIF (bukan blok global lagi).
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _networkAvailable = MutableStateFlow(true)
    val networkAvailable: StateFlow<Boolean> = _networkAvailable.asStateFlow()

    private val _engineStatus = MutableStateFlow(EngineStatus("Built-in agent", true, "Ready"))
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()

    private val _providerStatus = MutableStateFlow<Map<ProviderId, ProviderStatus>>(emptyMap())
    val providerStatus: StateFlow<Map<ProviderId, ProviderStatus>> = _providerStatus.asStateFlow()

    // Status Ollama khusus (diagnose /api/version + /api/tags): memberi beda
    // tegas "Not running" vs "Connection error" untuk UI Model Selector.
    private val _ollamaStatus =
        MutableStateFlow(ProviderStatus(null, "Not checked"))
    val ollamaStatus: StateFlow<ProviderStatus> = _ollamaStatus.asStateFlow()

    /** True bila proses start `ollama serve` di sandbox sedang berjalan. */
    private val _ollamaStarting = MutableStateFlow(false)
    val ollamaStarting: StateFlow<Boolean> = _ollamaStarting.asStateFlow()

    // Uji model satu klik (null = tidak ada pengujian aktif/hasil tampil).
    private val _modelTest = MutableStateFlow<ModelTestUi?>(null)
    val modelTest: StateFlow<ModelTestUi?> = _modelTest.asStateFlow()

    private var modelTestJob: Job? = null

    /**
     * State uji model satu klik (Model Selector → tombol "Test").
     *  - Running : pengujian berjalan (spinner + Cancel).
     *  - Result  : sukses — balasan model + durasi.
     *  - Failed  : gagal — error ringkas + detail penuh untuk "View details".
     */
    sealed class ModelTestUi {
        abstract val providerId: ProviderId
        abstract val model: String

        data class Running(
            override val providerId: ProviderId,
            override val model: String
        ) : ModelTestUi()

        data class Result(
            override val providerId: ProviderId,
            override val model: String,
            val reply: String,
            val durationMs: Long
        ) : ModelTestUi()

        data class Failed(
            override val providerId: ProviderId,
            override val model: String,
            val error: String,
            val detail: String?
        ) : ModelTestUi()
    }

    private val _models = MutableStateFlow<Map<ProviderId, List<ModelInfo>>>(emptyMap())
    val models: StateFlow<Map<ProviderId, List<ModelInfo>>> = _models.asStateFlow()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val activeProject: StateFlow<Project?> = container.activeProject

    // ------------------------------------------------------------------
    // Permission modes (gaya Claude Code) & plan approval
    // ------------------------------------------------------------------

    /** Request izin yang menunggu jawaban user (UI menampilkan PermissionDialog). */
    val pendingPermission: StateFlow<PermissionRequest?> = container.permissionBroker.pending

    /**
     * Mode izin saat run terakhir dimulai (disimpan di [send] SEBELUM generasi).
     * Null = tidak ada run berjalan yang relevan untuk plan approval.
     */
    private val lastRunMode = MutableStateFlow<PermissionMode?>(null)

    private val _planApproval = MutableStateFlow(false)

    /**
     * True bila run terakhir dijalankan dalam PLAN mode, mode masih PLAN,
     * dan sesi aktif sudah selesai generating — saat itu kartu "Plan ready"
     * ditampilkan untuk disetujui user.
     */
    val planApproval: StateFlow<Boolean> = _planApproval.asStateFlow()

    /** State generasi semua sesi (Running/Done/Failed/Cancelled) dari manager. */
    val genStates: StateFlow<Map<String, SessionGenState>> = generationManager.states

    private var networkCallbackRegistered = false

    // Terminal state yang sudah diproses (equals data class) agar reload pesan
    // hanya terjadi SEKALI per kejadian Done/Failed/Cancelled.
    private val consumedTerminalStates = mutableSetOf<SessionGenState>()

    init {
        viewModelScope.launch {
            // CATATAN: TIDAK ada auto-pick proyek pertama di sini — pemulihan
            // workspace aktif adalah tanggung jawab AppContainer.restore
            // (settings.activeWorkspaceId) dan gating UI menunggu initState.
            refreshProjects()
            val convs = store.conversations()
            if (convs.isEmpty()) {
                createNewConversation()
            } else {
                selectConversation(convs.first().id)
            }
        }
        observeGenerationStates()
        observePlanApproval()
        registerNetworkCallback()
        refreshEngineStatus()
        // Auto-deteksi Ollama saat aplikasi dibuka (status untuk Model Selector).
        refreshOllamaStatus()
    }

    /** Derived plan approval (pola sama dengan isGenerating): combine 3 state. */
    private fun observePlanApproval() {
        viewModelScope.launch {
            combine(lastRunMode, settingsRepo.settings, _isGenerating) { last, s, generating ->
                last == PermissionMode.PLAN &&
                    s.permissionMode == PermissionMode.PLAN &&
                    !generating
            }.collect { _planApproval.value = it }
        }
    }

    /**
     * Sinkronisasi UI <-> GenerationManager (dijalankan di viewModelScope —
     * aman, otomatis berhenti saat onCleared):
     *  - isGenerating sesi aktif diturunkan dari state manager.
     *  - Saat entri TERMINAL (Done/Failed/Cancelled) masuk untuk sesi aktif,
     *    pesan akhir (sudah dipersist manager) dimuat ulang dari store sekali
     *    per kejadian via [consumedTerminalStates].
     */
    private fun observeGenerationStates() {
        viewModelScope.launch {
            combine(generationManager.states, _activeConversationId) { states, activeId ->
                states to activeId
            }.collect { (states, activeId) ->
                _isGenerating.value =
                    activeId != null && states[activeId] is SessionGenState.Running
                states.values.forEach { state ->
                    val terminal = state !is SessionGenState.Running
                    if (terminal && consumedTerminalStates.add(state)) {
                        if (state.sessionId == activeId) {
                            _messages.value = store.messages(state.sessionId)
                        }
                        // Urutan drawer / History ikut segar (updatedAt berubah).
                        _conversations.value = store.conversations()
                    }
                }
            }
        }
    }

    /** State generasi satu sesi (helper ringkas untuk UI). */
    fun genStateOf(sessionId: String): SessionGenState? = genStates.value[sessionId]

    // ------------------------------------------------------------------
    // Conversations
    // ------------------------------------------------------------------

    fun createNewConversation() {
        viewModelScope.launch {
            val conv = store.createConversation(container.activeProject.value?.id, "New conversation")
            _activeConversationId.value = conv.id
            _messages.value = emptyList()
            refreshConversations()
        }
    }

    fun selectConversation(id: String) {
        viewModelScope.launch {
            _activeConversationId.value = id
            _messages.value = store.messages(id)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            // Hentikan dulu generasi sesi yang dihapus agar tidak menulis lagi ke store.
            if (generationManager.isGenerating(id)) generationManager.cancel(id)
            store.deleteConversation(id)
            refreshConversations()
            if (_activeConversationId.value == id) {
                val remaining = store.conversations()
                if (remaining.isNotEmpty()) selectConversation(remaining.first().id)
                else createNewConversation()
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            store.renameConversation(id, title.trim().ifBlank { "Untitled" })
            refreshConversations()
        }
    }

    private suspend fun ensureConversation(): String {
        _activeConversationId.value?.let { return it }
        val conv = store.createConversation(container.activeProject.value?.id, "New conversation")
        _activeConversationId.value = conv.id
        return conv.id
    }

    private suspend fun refreshConversations() {
        _conversations.value = store.conversations()
    }

    /** Muat ulang daftar riwayat (dipakai layar History saat dibuka). */
    fun refreshHistory() {
        viewModelScope.launch { refreshConversations() }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            _projects.value = container.workspaceManager.listProjects()
        }
    }

    fun selectProject(project: Project?) {
        container.activeProject.value = project
    }

    /**
     * Buat workspace app-private "workspace", jadikan proyek aktif, persist
     * activeWorkspaceId, lalu echo konfirmasi ke chat (dipakai tombol
     * "Use app-private workspace" di workspace gate).
     */
    fun createNewAppWorkspace() {
        viewModelScope.launch {
            val project = container.workspaceManager.createProject("workspace")
            container.activeProject.value = project
            settingsRepo.update { it.copy(activeWorkspaceId = project.id) }
            refreshProjects()
            appendLocalAssistant(
                ensureConversation(),
                "Workspace \"${project.name}\" created (app-private storage). " +
                    "The agent can now read and edit files inside it."
            )
        }
    }

    // ------------------------------------------------------------------
    // Send / generate (delegasi ke GenerationManager)
    // ------------------------------------------------------------------

    fun send(raw: String) {
        viewModelScope.launch { performSend(raw.trim()) }
    }

    /**
     * Aksi cepat agent (Fix/Test/Build/Run/Debug/Explain/Review/Commit) dari
     * menu ikon tools di header: pastikan mode engine AGENT (tool loop aktif)
     * lalu kirim prompt terkait seperti pesan user biasa.
     */
    fun runAgentAction(name: String) {
        val prompt = AGENT_ACTION_PROMPTS[name.trim()] ?: return
        viewModelScope.launch {
            if (settingsRepo.settings.value.engineMode != EngineMode.AGENT) {
                settingsRepo.update { it.copy(engineMode = EngineMode.AGENT) }
            }
            performSend(prompt)
        }
    }

    private suspend fun performSend(text: String) {
        if (text.isEmpty()) return
        val convId = ensureConversation()
        // Guard per-sesi: sesi lain tetap bisa mengirim saat sesi ini generating.
        if (generationManager.isGenerating(convId)) return

        // Slash commands dijawab lokal sebagai assistant echo — tanpa generasi.
        if (handleSlashCommand(convId, text)) return

        // Gating workspace: engine AGENT butuh folder proyek aktif.
        if (settingsRepo.settings.value.engineMode == EngineMode.AGENT &&
            container.activeProject.value == null
        ) {
            store.appendMessage(
                convId,
                ChatMessage(conversationId = convId, role = Role.USER, content = text)
            )
            appendLocalAssistant(
                convId,
                "Create a workspace first — pick a project folder.",
                isError = true
            )
            return
        }

        store.appendMessage(convId, ChatMessage(conversationId = convId, role = Role.USER, content = text))
        _messages.value = store.messages(convId)
        refreshConversations()
        // Simpan mode izin run ini (dasar keputusan plan approval nanti).
        lastRunMode.value = settingsRepo.settings.value.permissionMode
        generationManager.start(convId)
    }

    /**
     * Slash commands lokal (TIDAK dikirim ke model). Bila [text] dikenali,
     * jawab sebagai assistant message lokal dan return true.
     *
     *  - /ask · /mode ask  → PermissionMode.ASK
     *  - /plan · /mode plan → PermissionMode.PLAN
     *  - /mode edit        → PermissionMode.AUTO_READ_EDIT
     *  - /yolo · /mode yolo → PermissionMode.FULL_ACCESS
     *  - /init             → tulis template AGENTS.md di proyek aktif
     *  - /mode             → bantuan pemakaian
     *  Unknown slash lain lolos sebagai pesan biasa ke model.
     */
    private suspend fun handleSlashCommand(convId: String, text: String): Boolean = when {
        text == "/ask" || text == "/mode ask" -> {
            setPermissionMode(PermissionMode.ASK)
            appendLocalAssistant(convId, modeEcho(PermissionMode.ASK))
            true
        }
        text == "/plan" || text == "/mode plan" -> {
            setPermissionMode(PermissionMode.PLAN)
            appendLocalAssistant(convId, modeEcho(PermissionMode.PLAN))
            true
        }
        text == "/mode edit" -> {
            setPermissionMode(PermissionMode.AUTO_READ_EDIT)
            appendLocalAssistant(convId, modeEcho(PermissionMode.AUTO_READ_EDIT))
            true
        }
        text == "/yolo" || text == "/mode yolo" -> {
            setPermissionMode(PermissionMode.FULL_ACCESS)
            appendLocalAssistant(convId, modeEcho(PermissionMode.FULL_ACCESS))
            true
        }
        text == "/mode" -> {
            appendLocalAssistant(
                convId,
                "Usage: /mode ask|plan|edit|yolo — current mode: " +
                    settingsRepo.settings.value.permissionMode.friendlyName()
            )
            true
        }
        text == "/init" -> {
            runInitCommand(convId)
            true
        }
        else -> false
    }

    private fun modeEcho(mode: PermissionMode): String = when (mode) {
        PermissionMode.ASK ->
            "Permission mode: Ask — every write, delete and command asks for confirmation."
        PermissionMode.PLAN ->
            "Permission mode: Plan — read-only research; write tools are denied " +
                "until you approve the plan."
        PermissionMode.AUTO_READ_EDIT ->
            "Permission mode: Edit — reads and file edits run automatically; " +
                "deletes and commands still ask."
        PermissionMode.FULL_ACCESS ->
            "Permission mode: YOLO — everything auto-approved. " +
                "Use only inside a trusted workspace."
    }

    /** Label manusiawi mode izin (dipakai echo slash command). */
    private fun PermissionMode.friendlyName(): String = when (this) {
        PermissionMode.ASK -> "Ask"
        PermissionMode.PLAN -> "Plan"
        PermissionMode.AUTO_READ_EDIT -> "Edit"
        PermissionMode.FULL_ACCESS -> "YOLO"
    }

    /**
     * /init: tulis template AGENTS.md ke root proyek aktif (overwrite bila
     * sudah ada — konfirmasi disebut di echo). Tanpa proyek aktif → echo error.
     */
    private suspend fun runInitCommand(convId: String) {
        val project = container.activeProject.value
        if (project == null) {
            appendLocalAssistant(
                convId,
                "No active workspace — /init writes AGENTS.md into the active project. " +
                    "Create or pick a project folder first.",
                isError = true
            )
            return
        }
        val fs = container.workspaceManager.fsFor(project)
        val template = buildString {
            appendLine("# ${project.name.ifBlank { "Project" }}")
            appendLine()
            appendLine("## Overview")
            appendLine("Describe what this project does, its goals, and its main components.")
            appendLine()
            appendLine("## Build & Test")
            appendLine("- Build: <main build command>")
            appendLine("- Test: <test command>")
            appendLine()
            appendLine("## Conventions (edit me)")
            appendLine("- Code style, folder layout, naming rules, and what the agent should never touch.")
        }
        val existed = fs.exists("AGENTS.md")
        val result = fs.writeFile("AGENTS.md", template)
        when {
            result.startsWith("ERROR") ->
                appendLocalAssistant(convId, "Failed to write AGENTS.md — $result", isError = true)
            existed ->
                appendLocalAssistant(
                    convId,
                    "AGENTS.md overwritten in ${project.name} (previous content replaced)."
                )
            else ->
                appendLocalAssistant(convId, "AGENTS.md created in ${project.name}.")
        }
    }

    /**
     * Tambahkan assistant message LOKAL (echo slash command / pesan error
     * gating) ke store dan refresh UI — tanpa memicu generasi.
     */
    private suspend fun appendLocalAssistant(
        convId: String,
        content: String,
        isError: Boolean = false
    ) {
        store.appendMessage(
            convId,
            ChatMessage(conversationId = convId, role = Role.ASSISTANT, content = content, isError = isError)
        )
        _messages.value = store.messages(convId)
        refreshConversations()
    }

    /** Stop hanya membatalkan generasi sesi yang sedang AKTIF di layar. */
    fun stopGeneration() {
        activeConversationId.value?.let { generationManager.cancel(it) }
    }

    // ------------------------------------------------------------------
    // Permission broker & plan approval (mode izin gaya Claude Code)
    // ------------------------------------------------------------------

    /** Jawab request izin yang pending (dipanggil PermissionDialog). */
    fun respondPermission(reqId: String, decision: PermissionDecision) =
        container.permissionBroker.respond(reqId, decision)

    /** Ganti mode izin agent (persist di AppSettings.permissionMode). */
    fun setPermissionMode(mode: PermissionMode) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(permissionMode = mode) }
        }
    }

    /**
     * Setujui rencana hasil PLAN mode: mode naik ke AUTO_READ_EDIT (edit
     * auto-approved), tandai run berikutnya, lalu run ulang sesi aktif —
     * runGeneration memakai USER message terakhir (perilaku manager).
     */
    fun approvePlan() {
        viewModelScope.launch {
            settingsRepo.update { it.copy(permissionMode = PermissionMode.AUTO_READ_EDIT) }
            lastRunMode.value = PermissionMode.AUTO_READ_EDIT
            val convId = _activeConversationId.value ?: return@launch
            if (!generationManager.isGenerating(convId)) generationManager.start(convId)
        }
    }

    /** Tutup kartu plan approval tanpa mengeksekusi (mode tetap PLAN). */
    fun dismissPlanApproval() {
        lastRunMode.value = null
    }

    /** Hapus balasan terakhir lalu generate ulang dari pesan user terakhir. */
    fun regenerate() {
        viewModelScope.launch {
            val convId = _activeConversationId.value ?: return@launch
            if (generationManager.isGenerating(convId)) return@launch
            val msgs = store.messages(convId)
            val lastUser = msgs.lastOrNull { it.role == Role.USER } ?: return@launch
            store.deleteMessagesFrom(convId, msgs.firstOrNull { it.timestamp > lastUser.timestamp }?.id ?: return@launch)
            _messages.value = store.messages(convId)
            generationManager.start(convId)
        }
    }

    /** Ulangi bila balasan terakhir adalah error. */
    fun retryLast() {
        viewModelScope.launch {
            val convId = _activeConversationId.value ?: return@launch
            if (generationManager.isGenerating(convId)) return@launch
            val msgs = store.messages(convId)
            val last = msgs.lastOrNull() ?: return@launch
            if (last.role == Role.ASSISTANT && last.isError) {
                store.deleteMessagesFrom(convId, last.id)
                _messages.value = store.messages(convId)
                generationManager.start(convId)
            }
        }
    }

    /** Edit pesan user: update isi, hapus semua pesan setelahnya, generate ulang. */
    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch {
            val convId = _activeConversationId.value ?: return@launch
            if (generationManager.isGenerating(convId)) return@launch
            val msgs = store.messages(convId)
            val target = msgs.firstOrNull { it.id == messageId } ?: return@launch
            if (target.role != Role.USER) return@launch
            store.updateMessage(convId, target.copy(content = newContent.trim()))
            val next = msgs.firstOrNull { it.timestamp > target.timestamp }
            if (next != null) store.deleteMessagesFrom(convId, next.id)
            _messages.value = store.messages(convId)
            generationManager.start(convId)
        }
    }

    // ------------------------------------------------------------------
    // Providers & models (Model Selector)
    // ------------------------------------------------------------------

    fun refreshProvider(providerId: ProviderId) {
        viewModelScope.launch {
            val provider = container.providerRegistry.get(providerId) ?: return@launch
            val hadOldList = _models.value[providerId].orEmpty().isNotEmpty()
            // 1) Status "Checking…" — list lama di _models DIPERTAHANKAN (tidak di-wipe)
            //    supaya UI masih menampilkan daftar cache selama pengecekan.
            _providerStatus.value = _providerStatus.value +
                (providerId to ProviderStatus(null, "Checking…", stale = hadOldList))
            // 2) SELALU fetch listModels (tidak lagi gated testConnection) — endpoint
            //    yang health-check-nya gagal tapi /models-nya jalan tetap tampil listnya.
            //    Blokir I/O jaringan dijalankan di Dispatchers.IO (bukan Main).
            var fetched: List<ModelInfo>? = null
            var failure: Exception? = null
            try {
                fetched = withContext(Dispatchers.IO) { provider.listModels() }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                failure = e
            }
            if (fetched != null) {
                _models.value = _models.value + (providerId to fetched)
                _providerStatus.value = _providerStatus.value + (providerId to ProviderStatus(
                    true,
                    if (fetched.isEmpty()) "Connected · no models" else "Connected · ${fetched.size} models",
                    stale = false
                ))
            } else {
                // 3) Gagal: list lama TETAP ada di _models (UI menandai " · cached"),
                //    status menampilkan pesan error ringkas — bukan sekadar "Unavailable".
                _providerStatus.value = _providerStatus.value + (providerId to ProviderStatus(
                    false,
                    providerErrorMessage(failure),
                    stale = hadOldList
                ))
            }
        }
    }

    /**
     * Pesan error ringkas untuk Model Selector: ambil e.message (sudah informatif,
     * mis. "Anthropic HTTP 401 — …"), batasi ±120 char. IOException tanpa message
     * → "Connection failed"; exception lain tanpa message → nama class-nya.
     */
    private fun providerErrorMessage(e: Exception?): String {
        if (e == null) return "Unavailable"
        val msg = e.message?.trim().orEmpty()
        if (msg.isNotEmpty()) return msg.take(120)
        return if (e is IOException) "Connection failed" else e.javaClass.simpleName
    }

    fun setActiveModel(providerId: ProviderId, modelId: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeProvider = providerId, selectedModel = modelId) }
        }
    }

    // ------------------------------------------------------------------
    // Ollama: diagnose, start di sandbox, dan uji model satu klik
    // ------------------------------------------------------------------

    /**
     * Deteksi status Ollama via [OllamaProvider.diagnose] (/api/version +
     * /api/tags, timeout pendek). Dipanggil otomatis saat app dibuka dan saat
     * user menekan Retry/Start di Model Selector. Hasilnya membedakan tegas
     * "Not running" (koneksi ditolak) dari "Connection error" (sebab lain).
     */
    fun refreshOllamaStatus() {
        viewModelScope.launch {
            val provider = container.providerRegistry.get(ProviderId.OLLAMA) as? OllamaProvider
            if (provider == null) {
                _ollamaStatus.value = ProviderStatus(false, "Ollama provider unavailable")
                return@launch
            }
            val diag = try {
                withContext(Dispatchers.IO) { provider.diagnose() }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _ollamaStatus.value = ProviderStatus(false, e.message ?: "Diagnosis failed")
                return@launch
            }
            when (diag) {
                is OllamaDiag.Running ->
                    _ollamaStatus.value = ProviderStatus(
                        true,
                        "Connected · ${diag.modelCount} models" +
                            (diag.version?.let { " · $it" } ?: "")
                    )
                is OllamaDiag.NotRunning ->
                    _ollamaStatus.value = ProviderStatus(
                        false,
                        "Ollama is not reachable at ${diag.base} — is it running?"
                    )
                is OllamaDiag.Error ->
                    _ollamaStatus.value = ProviderStatus(false, diag.reason)
            }
        }
    }

    /**
     * Start `ollama serve` DI DALAM sandbox Linux embedded (bila READY dan
     * ollama terpasang di rootfs) — bukan mock: benar-benar menjalankan proses
     * via ProcessSupervisor. Setelah start, status & daftar model di-refresh.
     */
    fun startOllama() {
        if (_ollamaStarting.value) return
        viewModelScope.launch {
            _ollamaStarting.value = true
            try {
                when (val r = container.ollamaLauncher.startAndAwait()) {
                    is OllamaStartResult.Started -> refreshOllamaStatus()
                    is OllamaStartResult.EnvNotReady ->
                        _ollamaStatus.value = ProviderStatus(false, r.reason)
                    is OllamaStartResult.NotInstalled ->
                        _ollamaStatus.value = ProviderStatus(false, r.reason)
                    is OllamaStartResult.Failed ->
                        _ollamaStatus.value = ProviderStatus(false, r.reason)
                }
                // Daftar model ikut dicoba ulang setelah upaya start.
                refreshProvider(ProviderId.OLLAMA)
            } finally {
                _ollamaStarting.value = false
            }
        }
    }

    /**
     * Uji model satu klik: kirim prompt kecil via [ModelTester] dan publikasikan
     * hasilnya ke [modelTest] (dialog di Model Selector). Pengujian berjalan di
     * job terpisah — Cancel membatalkan job (tidak ada dialog zombie).
     */
    fun testModel(providerId: ProviderId, modelId: String) {
        modelTestJob?.cancel()
        _modelTest.value = ModelTestUi.Running(providerId, modelId)
        modelTestJob = viewModelScope.launch {
            val tester = ModelTester(container.providerRegistry, settingsRepo)
            try {
                val outcome = tester.test(providerId, modelId)
                _modelTest.value =
                    if (outcome.ok) {
                        ModelTestUi.Result(providerId, modelId, outcome.reply, outcome.durationMs)
                    } else {
                        ModelTestUi.Failed(
                            providerId, modelId,
                            outcome.error ?: "Unknown error",
                            outcome.detail ?: outcome.error
                        )
                    }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _modelTest.value = ModelTestUi.Failed(
                    providerId, modelId,
                    e.message ?: "Test failed", e.toString()
                )
            }
        }
    }

    /** Tutup dialog uji + batalkan pengujian berjalan (jika ada). */
    fun clearModelTest() {
        modelTestJob?.cancel()
        modelTestJob = null
        _modelTest.value = null
    }

    private companion object {
        /** Prompt aksi cepat agent — nama persis dipakai AgentActionsMenu (UI). */
        val AGENT_ACTION_PROMPTS = mapOf(
            "Fix" to "Find the error in this project, fix it, and verify the result.",
            "Test" to "Run the project tests, summarize the failures, and propose fixes.",
            "Build" to "Build the project, inspect every error, and fix them until the build succeeds.",
            "Run" to "Run the project (start the dev server or main entry point), then report how to access it.",
            "Debug" to "Reproduce the current bug, inspect logs and code, find the root cause, and fix it.",
            "Explain" to "Explain the structure of this project in short bullets.",
            "Review" to "Review the recent changes in this project and suggest improvements.",
            "Commit" to "Create a clean git commit for the current changes with a good message."
        )
    }

    // ------------------------------------------------------------------
    // Engine & network status
    // ------------------------------------------------------------------

    fun refreshEngineStatus() {
        viewModelScope.launch {
            val s = settingsRepo.settings.value
            val label = container.orchestrator.engineLabel()
            if (s.engineMode == com.openchai.core.settings.EngineMode.PLAIN_CHAT) {
                _engineStatus.value = EngineStatus(label, true, "Direct chat mode")
                return@launch
            }
            if (s.preferOpenCodeEngine && s.openCodeServerUrl.isNotBlank()) {
                val healthy = try {
                    container.openCodeRuntime.healthCheck()
                } catch (_: Exception) {
                    false
                }
                _engineStatus.value =
                    if (healthy) EngineStatus(label, true, "OpenCode server connected")
                    else EngineStatus(label, true, "OpenCode unavailable — fallback to built-in agent")
            } else {
                _engineStatus.value = EngineStatus(label, true, "Built-in agent ready")
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        try {
            val cm = getApplication<Application>()
                .getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _networkAvailable.value = true
                }

                override fun onLost(network: Network) {
                    _networkAvailable.value = false
                }
            })
            networkCallbackRegistered = true
        } catch (_: Exception) {
            // Network status bersifat informatif; abaikan bila tidak tersedia.
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Collector genStates mati bersama viewModelScope; generasi berlanjut di
        // GenerationManager (app-scoped) — sesi lain tidak ikut berhenti.
    }
}
