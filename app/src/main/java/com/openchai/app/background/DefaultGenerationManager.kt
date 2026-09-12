package com.openchai.app.background

import android.content.Context
import android.content.Intent
import android.util.Log
import com.openchai.app.OpenChatApp
import com.openchai.app.OrchestrationEvent
import com.openchai.core.model.AgentStep
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton app-scoped: satu [GenerationManager] untuk seluruh aplikasi,
 * selalu memakai applicationContext agar tidak menahan Activity/Fragment.
 */
object GenerationManagerProvider {

    @Volatile
    private var inst: GenerationManager? = null

    /** Ambil instance bersama; dibuat lazy pada pemanggilan pertama. */
    fun get(context: Context): GenerationManager =
        inst ?: synchronized(this) {
            inst ?: DefaultGenerationManager(context.applicationContext).also { inst = it }
        }
}

/**
 * Implementasi [GenerationManager]: mesin generasi multi-sesi paralel.
 *
 * Desain lifecycle:
 *  - Scope coroutine ([scope]) MILIK manager sendiri (bukan appScope/viewModelScope)
 *    sehingga generasi tetap berjalan saat navigasi antar tab maupun saat activity
 *    pindah ke background. Scope tidak pernah dibatalkan selama proses hidup;
 *    proses mati = state mati (acceptable — state tidak disilang-proses).
 *  - Satu [Job] per sesi di [activeJobs]; guard duplikat memastikan satu sesi
 *    hanya punya satu generasi aktif.
 *  - Persist pesan (Done/Failed/Cancelled) adalah tanggung jawab manager —
 *    ChatViewModel hanya membaca [states].
 *
 * Konsumsi event [OrchestrationEvent] meniru logika persist
 * lama ChatViewModel: activity message (isAgentActivity + steps), assistant
 * message, "(empty response)", cancel → "_(stopped)_", error → isError=true.
 */
class DefaultGenerationManager(private val appContext: Context) : GenerationManager {

    private val container = (appContext as OpenChatApp).container

    /**
     * Scope milik manager: SupervisorJob agar satu generasi gagal tidak
     * menjatuhkan generasi lain; Dispatcher.Default untuk kerja CPU/network.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Pekerjaan generasi aktif per sessionId. */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Kunci guard start (mencegah dua start() simultan untuk sesi yang sama). */
    private val startLock = Any()

    private val _states = MutableStateFlow<Map<String, SessionGenState>>(emptyMap())
    override val states: StateFlow<Map<String, SessionGenState>> = _states.asStateFlow()

    override fun isGenerating(sessionId: String): Boolean = activeJobs.containsKey(sessionId)

    override fun start(sessionId: String) {
        synchronized(startLock) {
            // Guard duplikat: sesi yang sedang generating diabaikan (no-op).
            if (activeJobs.containsKey(sessionId)) return

            // Pastikan foreground service hidup SEBELUM state Running dipublish,
            // sehingga notifikasi progress langsung muncul. Dipanggil tiap start()
            // (idempoten): onStartCommand service memanggil startForeground ulang
            // dan collector-nya di-guard agar tidak dobel.
            ensureService()

            val startedAt = System.currentTimeMillis()
            // Publish Running awal dulu supaya UI langsung bereaksi (spinner).
            _states.update {
                it + (sessionId to SessionGenState.Running(sessionId, startedAt = startedAt))
            }
            activeJobs[sessionId] = scope.launch { runGeneration(sessionId, startedAt) }
        }
    }

    override fun cancel(sessionId: String) {
        // Job.cancel() memicu CancellationException di tengah collect() —
        // orchestrator/provider streaming sudah cancel-able.
        activeJobs[sessionId]?.cancel()
    }

    override fun cancelAll() {
        activeJobs.keys.toList().forEach { cancel(it) }
    }

    // ------------------------------------------------------------------
    // Inti generasi satu sesi
    // ------------------------------------------------------------------

    private suspend fun runGeneration(sessionId: String, startedAt: Long) {
        val store = container.conversationStore
        var steps: List<AgentStep> = emptyList()
        var partial = ""
        try {
            val all = store.messages(sessionId)
            val lastUser = all.lastOrNull { it.role == Role.USER }
                ?: throw GenerationException("Nothing to send")

            // Auto-title: percakapan baru diberi judul dari prompt (maks 48 char).
            maybeAutoTitle(sessionId, lastUser.content)

            // History persis logika lama ChatViewModel.generate(): buang pesan
            // agent-activity & system, buang pesan terakhir (prompt baru),
            // petakan ke pasangan ("user"/"assistant", konten).
            val history = all
                .filter { !it.isAgentActivity && it.role != Role.SYSTEM }
                .dropLast(1)
                .map { (if (it.role == Role.USER) "user" else "assistant") to it.content }

            var finalText = ""
            container.orchestrator.execute(lastUser.content, history).collect { event ->
                when (event) {
                    is OrchestrationEvent.StepsChanged -> steps = event.steps
                    is OrchestrationEvent.PartialAnswer -> partial = event.accumulated
                    is OrchestrationEvent.Finished -> finalText = event.answer
                    is OrchestrationEvent.Failed ->
                        throw GenerationException(event.message)
                }
                // Pertahankan startedAt dari Running awal.
                _states.update {
                    it + (sessionId to SessionGenState.Running(sessionId, partial, steps, startedAt))
                }
            }

            // ---- Persist sukses ----
            if (steps.isNotEmpty()) {
                store.appendMessage(
                    sessionId,
                    ChatMessage(
                        conversationId = sessionId,
                        role = Role.ASSISTANT,
                        content = "",
                        steps = steps.toList(),
                        isAgentActivity = true
                    )
                )
            }
            store.appendMessage(
                sessionId,
                ChatMessage(
                    conversationId = sessionId,
                    role = Role.ASSISTANT,
                    content = finalText.ifBlank { "(empty response)" }
                )
            )
            store.touch(sessionId)
            _states.update { it + (sessionId to SessionGenState.Done(sessionId, finalText)) }
        } catch (_: CancellationException) {
            // ---- Dibatalkan user via cancel() ----
            // Persist dengan NonCancellable: job sudah dibatalkan, suspend call
            // biasa (withContext/withLock di store) akan langsung batal sebelum
            // menulis apa pun.
            withContext(NonCancellable) {
                if (steps.isNotEmpty()) {
                    store.appendMessage(
                        sessionId,
                        ChatMessage(
                            conversationId = sessionId,
                            role = Role.ASSISTANT,
                            content = "",
                            steps = steps.toList(),
                            isAgentActivity = true
                        )
                    )
                }
                val partialOrStopped = partial.ifBlank { "Generation stopped." }
                store.appendMessage(
                    sessionId,
                    ChatMessage(
                        conversationId = sessionId,
                        role = Role.ASSISTANT,
                        content = "$partialOrStopped\n\n_(stopped)_"
                    )
                )
                store.touch(sessionId)
            }
            _states.update { it + (sessionId to SessionGenState.Cancelled(sessionId, partial)) }
            // Tidak rethrow — job selesai "normal"; scope tetap hidup untuk sesi lain.
        } catch (e: Exception) {
            // ---- Gagal (GenerationException dari event Failed / exception lain) ----
            val msg = (e as? GenerationException)?.message ?: (e.message ?: "Unexpected error")
            withContext(NonCancellable) {
                store.appendMessage(
                    sessionId,
                    ChatMessage(
                        conversationId = sessionId,
                        role = Role.ASSISTANT,
                        content = msg,
                        isError = true
                    )
                )
                store.touch(sessionId)
            }
            _states.update { it + (sessionId to SessionGenState.Failed(sessionId, msg)) }
        } finally {
            activeJobs.remove(sessionId)
        }
    }

    /** Beri judul otomatis bila percakapan masih memakai judul default. */
    private suspend fun maybeAutoTitle(sessionId: String, prompt: String) {
        val conv = container.conversationStore.conversations().firstOrNull { it.id == sessionId }
            ?: return
        if (conv.title != DEFAULT_TITLE) return
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return
        val title =
            if (trimmed.length <= AUTO_TITLE_MAX) trimmed
            else trimmed.take(AUTO_TITLE_MAX).trimEnd() + "…"
        container.conversationStore.renameConversation(sessionId, title)
    }

    // ------------------------------------------------------------------
    // Service trigger
    // ------------------------------------------------------------------

    /**
     * Hidupkan [GenerationForegroundService] agar generasi di background punya
     * notifikasi progress dan proses tidak dibunuh sistem. Dipanggil dari
     * [start] — generasi hanya dimulai dari UI foreground atau notifikasi,
     * jadi startForegroundService legal (Android 12+ melarang start dari
     * background; bila itu terjadi, generasi tetap jalan tanpa notifikasi
     * daripada crash).
     */
    private fun ensureService() {
        try {
            appContext.startForegroundService(
                Intent(appContext, GenerationForegroundService::class.java)
            )
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+) & sejenisnya.
            Log.w(TAG, "Gagal memulai GenerationForegroundService: ${e.message}")
        }
    }

    /** Penanda kegagalan generasi dari event orkestrasi. */
    private class GenerationException(message: String) : RuntimeException(message)

    companion object {
        private const val TAG = "GenerationManager"
        private const val DEFAULT_TITLE = "New conversation"
        private const val AUTO_TITLE_MAX = 48
    }
}
