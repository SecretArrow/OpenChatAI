package com.openchai.app.ui.chat

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openchai.app.OpenChatApp
import com.openchai.app.background.GenerationManagerProvider
import com.openchai.app.background.SessionGenState
import com.openchai.core.data.ConversationStore
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Conversation
import com.openchai.core.model.ModelInfo
import com.openchai.core.model.Project
import com.openchai.core.model.ProviderId
import com.openchai.core.model.Role
import com.openchai.core.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Status koneksi satu provider untuk Model Selector. */
data class ProviderStatus(
    val connected: Boolean?,
    val message: String?
)

/** Status engine agent untuk error state di chat. */
data class EngineStatus(
    val engineName: String,
    val healthy: Boolean,
    val message: String
)

/**
 * ViewModel chat multi-sesi: UI hanya menyiapkan data di store lalu memulai /
 * membatalkan generasi lewat [com.openchai.app.background.GenerationManager]
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

    private val _models = MutableStateFlow<Map<ProviderId, List<ModelInfo>>>(emptyMap())
    val models: StateFlow<Map<ProviderId, List<ModelInfo>>> = _models.asStateFlow()

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepo.settings
    val activeProject: StateFlow<Project?> = container.activeProject

    /** State generasi semua sesi (Running/Done/Failed/Cancelled) dari manager. */
    val genStates: StateFlow<Map<String, SessionGenState>> = generationManager.states

    private var networkCallbackRegistered = false

    // Terminal state yang sudah diproses (equals data class) agar reload pesan
    // hanya terjadi SEKALI per kejadian Done/Failed/Cancelled.
    private val consumedTerminalStates = mutableSetOf<SessionGenState>()

    init {
        viewModelScope.launch {
            if (container.activeProject.value == null) {
                container.activeProject.value = container.workspaceManager.listProjects().firstOrNull()
            }
            refreshProjects()
            val convs = store.conversations()
            if (convs.isEmpty()) {
                createNewConversation()
            } else {
                selectConversation(convs.first().id)
            }
        }
        observeGenerationStates()
        registerNetworkCallback()
        refreshEngineStatus()
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

    // ------------------------------------------------------------------
    // Send / generate (delegasi ke GenerationManager)
    // ------------------------------------------------------------------

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val convId = ensureConversation()
            // Guard per-sesi: sesi lain tetap bisa mengirim saat sesi ini generating.
            if (generationManager.isGenerating(convId)) return@launch
            store.appendMessage(convId, ChatMessage(conversationId = convId, role = Role.USER, content = text))
            _messages.value = store.messages(convId)
            refreshConversations()
            generationManager.start(convId)
        }
    }

    /** Stop hanya membatalkan generasi sesi yang sedang AKTIF di layar. */
    fun stopGeneration() {
        activeConversationId.value?.let { generationManager.cancel(it) }
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
            _providerStatus.value = _providerStatus.value + (providerId to ProviderStatus(null, "Checking…"))
            val connected = try {
                provider.testConnection()
            } catch (_: Exception) {
                false
            }
            val list = if (connected) {
                try {
                    provider.listModels()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            _models.value = _models.value + (providerId to list)
            _providerStatus.value =
                _providerStatus.value + (providerId to ProviderStatus(connected, if (connected) "Connected" else "Unavailable"))
        }
    }

    fun setActiveModel(providerId: ProviderId, modelId: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeProvider = providerId, selectedModel = modelId) }
        }
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
