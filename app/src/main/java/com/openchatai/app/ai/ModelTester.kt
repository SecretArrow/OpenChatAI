package com.openchatai.app.ai

import com.openchai.core.ai.StreamEvent
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.ProviderId
import com.openchai.core.model.Role
import com.openchai.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hasil uji satu model (tanpa mock — benar-benar memanggil provider):
 * - ok      : true bila provider mengalirkan teks sebelum timeout
 * - reply   : akumulasi teks balasan (dipotong [REPLY_MAX] karakter)
 * - error   : pesan ringkas untuk UI (satu baris pertama)
 * - detail  : pesan lengkap / potongan parsial untuk dialog "View details"
 * - durationMs: durasi uji (termasuk bila gagal)
 */
data class ModelTestOutcome(
    val ok: Boolean,
    val reply: String,
    val error: String? = null,
    val detail: String? = null,
    val durationMs: Long = 0L
)

/**
 * Menguji provider+model dengan satu prompt pendek: mengumpulkan stream sampai
 * Done (atau Error) di dalam timeout total. Dipakai ModelSelectorSheet lewat
 * ChatViewModel.testModel. Tidak ada mock — [AiProvider.streamChat] asli yang dipanggil.
 */
class ModelTester(private val registry: ProviderRegistry, private val settings: SettingsRepository) {

    companion object {
        private const val TEST_PROMPT = "Reply with exactly: OK"
        private const val REPLY_MAX = 400
        private const val ERROR_MAX = 160
    }

    /**
     * Kirim 1 prompt uji ke provider+model, kumpulkan stream sampai Done
     * (timeout total [timeoutMs]). CancellationException eksternal diteruskan.
     */
    suspend fun test(providerId: ProviderId, modelId: String, timeoutMs: Long = 30_000L): ModelTestOutcome {
        val startedNs = System.nanoTime()
        fun outcome(ok: Boolean, reply: String = "", error: String? = null, detail: String? = null) =
            ModelTestOutcome(ok, reply, error, detail, (System.nanoTime() - startedNs) / 1_000_000L)

        val provider = registry.get(providerId)
            ?: return outcome(false, error = "Provider not configured", detail = "No provider instance registered for ${providerId.name}.")
        if (!provider.isConfigured()) {
            return outcome(
                ok = false,
                error = "Provider not configured",
                detail = "${provider.displayName} is not configured yet — open Settings → AI."
            )
        }
        if (modelId.isBlank()) {
            return outcome(
                ok = false,
                error = "No model selected",
                detail = "Pick a model for ${provider.displayName} before testing."
            )
        }

        // Satu pesan user, kumpulkan Delta sampai Done/Error (pola collection standar).
        val partial = StringBuilder()
        var streamError: String? = null
        val finished: StringBuilder? = try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(timeoutMs) {
                    provider.streamChat(
                        listOf(ChatMessage(role = Role.USER, content = TEST_PROMPT)),
                        modelId
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.Delta -> partial.append(event.text)
                            is StreamEvent.Error -> streamError = event.message
                            StreamEvent.Done -> Unit
                        }
                    }
                    partial
                }
            }
        } catch (ce: CancellationException) {
            // Pembatalan eksternal (UI cancel / scope mati) → teruskan.
            throw ce
        } catch (e: Exception) {
            return outcome(
                ok = false,
                reply = partial.toString().take(REPLY_MAX),
                error = shortError(e.message ?: e.toString()),
                detail = e.message ?: e.toString()
            )
        }

        if (finished == null) {
            // withTimeoutOrNull → null berarti timeout total tercapai.
            val partialText = partial.toString().trim()
            return outcome(
                ok = false,
                reply = partialText.take(REPLY_MAX),
                error = "Timed out after ${timeoutMs / 1000}s",
                detail = buildString {
                    append("No completion within ").append(timeoutMs).append(" ms.")
                    if (partialText.isNotEmpty()) {
                        append(" Partial reply: ").append(partialText.take(REPLY_MAX))
                    }
                }
            )
        }

        // Provider melaporkan error di dalam stream → error ringkas + detail lengkap.
        streamError?.let { err ->
            return outcome(
                ok = false,
                reply = partial.toString().take(REPLY_MAX),
                error = shortError(err),
                detail = err
            )
        }

        val reply = partial.toString()
        if (reply.isBlank()) {
            return outcome(
                ok = false,
                error = "(empty response)",
                detail = "Provider finished without producing any text."
            )
        }
        return outcome(ok = true, reply = reply.take(REPLY_MAX))
    }

    /** Pesan error ringkas untuk UI: satu baris pertama, dipotong [ERROR_MAX]. */
    private fun shortError(raw: String): String =
        raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(ERROR_MAX) ?: raw.take(ERROR_MAX)
}
