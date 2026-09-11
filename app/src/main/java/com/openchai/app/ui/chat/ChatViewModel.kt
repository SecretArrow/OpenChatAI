package com.openchai.app.ui.chat

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openchai.app.AgentOrchestrator
import com.openchai.app.OrchestrationEvent
import com.openchai.app.OpenChatApp
import com.openchai.core.model.ModelInfo
import com.openchai.core.data.ConversationStore
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Conversation
import com.openchai.core.model.Project
import com.openchai.core.model.ProviderId
import com.openchai.core.model.Role
import com.openchai.core.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

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

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as OpenChatApp).container
    private val store: ConversationStore = container.conversationStore
    private val settingsRepo = container.settingsRepository

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

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

    private var currentJob: Job? = null
    private var networkCallbackRegistered = false

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
        registerNetworkCallback()
        refreshEngineStatus()
    }

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

    fun refreshProjects() {
        viewModelScope.launch {
            _projects.value = container.workspaceManager.listProjects()
        }
    }

    fun selectProject(project: Project?) {
        container.activeProject.value = project
    }

    // ------------------------------------------------------------------
    // Send / generate
    // ------------------------------------------------------------------

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _isGenerating.value) return
        viewModelScope.launch {
            val convId = ensureConversation()
            store.appendMessage(convId, ChatMessage(conversationId = convId, role = Role.USER, content = text))
            _messages.value = store.messages(convId)
            generate(convId)
        }
    }

    fun stopGeneration() {
        currentJob?.cancel()
    }

    /** Hapus balasan terakhir lalu generate ulang dari pesan user terakhir. */
    fun regenerate() {
        if (_isGenerating.value) return
        viewModelScope.launch {
            val convId = _activeConversationId.value ?: return@launch
            val msgs = store.messages(convId)
            val lastUser = msgs.lastOrNull { it.role == Role.USER } ?: return@launch
            store.deleteMessagesFrom(convId, msgs.firstOrNull { it.timestamp > lastUser.timestamp }?.id ?: return@launch)
            _messages.value = store.messages(convId)
            generate(convId)
        }
    }

    /** Ulangi bila balasan terakhir adalah error. */
    fun retryLast() {
        if (_isGenerating.value) return
        viewModelScope.launch {
            val convId = _activeConversationId.value ?: return@launch
            val msgs = store.messages(convId)
            val last = msgs.lastOrNull() ?: return@launch
            if (last.role == Role.ASSISTANT && last.isError) {
                store.deleteMessagesFrom(convId, last.id)
                _messages.value = store.messages(convId)
                generate(convId)
            }
        }
    }

    /** Edit pesan user: update isi, hapus semua pesan setelahnya, generate ulang. */
    fun editMessage(messageId: String, newContent: String) {
        if (_isGenerating.value) return
        viewModelScope.launch {
            val convId = _activeConversationId.value ?: return@launch
            val msgs = store.messages(convId)
            val target = msgs.firstOrNull { it.id == messageId } ?: return@launch
            if (target.role != Role.USER) return@launch
            store.updateMessage(convId, target.copy(content = newContent.trim()))
            val next = msgs.firstOrNull { it.timestamp > target.timestamp }
            if (next != null) store.deleteMessagesFrom(convId, next.id)
            _messages.value = store.messages(convId)
            generate(convId)
        }
    }

    private fun generate(convId: String) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            _isGenerating.value = true
            val all = store.messages(convId)
            val lastUser = all.lastOrNull { it.role == Role.USER }
            if (lastUser == null) {
                _isGenerating.value = false
                return@launch
            }
            val history = all
                .filter { !it.isAgentActivity && it.role != Role.SYSTEM }
                .dropLast(1)
                .map { (if (it.role == Role.USER) "user" else "assistant") to it.content }

            val activityId = UUID.randomUUID().toString()
            val assistantId = UUID.randomUUID().toString()
            val steps = mutableListOf<com.openchai.core.model.AgentStep>()
            var assistantText = ""

            fun renderActivity() = ChatMessage(
                id = activityId,
                conversationId = convId,
                role = Role.ASSISTANT,
                content = "",
                steps = steps.toList(),
                isAgentActivity = true
            )

            fun renderAssistant() = ChatMessage(
                id = assistantId,
                conversationId = convId,
                role = Role.ASSISTANT,
                content = assistantText
            )

            fun publish() {
                val list = _messages.value.toMutableList()
                list.removeAll { it.id == activityId || it.id == assistantId }
                if (steps.isNotEmpty()) list.add(renderActivity())
                if (assistantText.isNotBlank() || steps.isEmpty()) list.add(renderAssistant())
                _messages.value = list
            }

            try {
                container.orchestrator.execute(lastUser.content, history).collect { event ->
                    when (event) {
                        is OrchestrationEvent.StepsChanged -> {
                            steps.clear()
                            steps.addAll(event.steps)
                            publish()
                        }
                        is OrchestrationEvent.PartialAnswer -> {
                            assistantText = event.accumulated
                            publish()
                        }
                        is OrchestrationEvent.Finished -> {
                            assistantText = event.answer
                        }
                        is OrchestrationEvent.Failed -> {
                            throw GenerationException(event.message)
                        }
                    }
                }
                if (steps.isNotEmpty()) store.appendMessage(convId, renderActivity())
                store.appendMessage(
                    convId,
                    renderAssistant().copy(content = assistantText.ifBlank { "(empty response)" })
                )
                _messages.value = store.messages(convId)
            } catch (c: CancellationException) {
                if (steps.isNotEmpty()) store.appendMessage(convId, renderActivity())
                val partial = assistantText.ifBlank { "Generation stopped." }
                store.appendMessage(convId, renderAssistant().copy(content = "$partial\n\n_(stopped)_"))
                _messages.value = store.messages(convId)
                _isGenerating.value = false
                throw c
            } catch (e: Exception) {
                val msg = (e as? GenerationException)?.message ?: (e.message ?: "Unexpected error")
                store.appendMessage(
                    convId,
                    ChatMessage(conversationId = convId, role = Role.ASSISTANT, content = msg, isError = true)
                )
                _messages.value = store.messages(convId)
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private class GenerationException(message: String) : RuntimeException(message)

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
        // currentJob dibatalkan otomatis oleh viewModelScope.
    }
}
