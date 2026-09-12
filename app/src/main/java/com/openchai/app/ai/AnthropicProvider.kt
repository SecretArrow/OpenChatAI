package com.openchai.app.ai

import com.openchai.core.ai.AiProvider
import com.openchai.core.ai.StreamEvent
import com.openchai.core.data.SecureStore
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.ModelInfo
import com.openchai.core.model.ProviderId
import com.openchai.core.model.Role
import com.openchai.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Provider Anthropic (Claude) via Messages API.
 * - listModels : GET /v1/models → data[].id + display_name (bila base ber-/v1 → /models)
 * - test       : kandidat /models yang sama, sehat bila ada yang 2xx
 * - streamChat : POST /v1/messages, SSE:
 *     "content_block_delta" → delta.text → Delta,
 *     "message_stop" → selesai, type "error" → Error.
 * Header: x-api-key + anthropic-version: 2023-06-01. Pesan SYSTEM dikeluarkan dari
 * "messages" dan digabung ke field "system".
 */
class AnthropicProvider(
    private val settings: SettingsRepository,
    private val secure: SecureStore
) : AiProvider {

    override val providerId: ProviderId = ProviderId.ANTHROPIC
    override val displayName: String = "Anthropic"

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val MAX_TOKENS = 8192
    }

    @Volatile
    private var cachedKey: String? = null

    private fun baseUrl(): String = settings.settings.value.anthropicEndpoint.trim().trimEnd('/')

    private suspend fun apiKey(): String =
        secure.apiKey(providerId.name).trim().also { cachedKey = it }

    private fun headers(builder: Request.Builder, key: String): Request.Builder = builder
        .header("x-api-key", key)
        .header("anthropic-version", ANTHROPIC_VERSION)

    override fun isConfigured(): Boolean = !cachedKey.isNullOrBlank()

    /**
     * Kandidat URL /models: "$base/v1/models" selalu dicoba lebih dulu; bila base
     * sudah berakhiran "/v1" (user mengisi endpoint termasuk /v1) tambahkan
     * "$base/models" agar tidak jatuh ke "/v1/v1/models" yang salah.
     */
    private fun modelCandidates(base: String): List<String> {
        val candidates = mutableListOf("$base/v1/models")
        if (base.endsWith("/v1")) candidates.add("$base/models")
        return candidates
    }

    /**
     * Ambil body /models dari kandidat pertama yang menjawab 2xx (header
     * x-api-key + anthropic-version tetap). 404/401/403 → lanjut kandidat
     * berikutnya; error jaringan (IOException) langsung dilempar. Semua kandidat
     * gagal HTTP → IOException informatif supaya pesannya tampil di Model Selector.
     */
    private fun fetchModelsBody(base: String, key: String): String {
        var lastCode = 0
        var lastSnippet = ""
        for (url in modelCandidates(base)) {
            val req = headers(Request.Builder().url(url).get(), key).build()
            val resp = try {
                AiHttp.newCall(req).execute()
            } catch (ce: CancellationException) {
                throw ce
            } catch (io: IOException) {
                throw io
            }
            resp.use {
                if (it.isSuccessful) return it.body?.string().orEmpty()
                lastCode = it.code
                lastSnippet = errorSnippet(try { it.body?.string() } catch (_: Exception) { null })
            }
        }
        throw IOException("Anthropic HTTP $lastCode$lastSnippet")
    }

    override suspend fun listModels(): List<ModelInfo> {
        val base = baseUrl()
        val key = apiKey()
        if (base.isBlank() || key.isBlank()) return emptyList()
        val body = fetchModelsBody(base, key)
        val root = parseJsonSafe(body) ?: return emptyList()
        val data = root.jsonArr("data") ?: return emptyList()
        return data.mapNotNull { el ->
            val m = el as? JsonObject ?: return@mapNotNull null
            val id = m.jsonStr("id") ?: return@mapNotNull null
            ModelInfo(
                id = id,
                name = m.jsonStr("display_name") ?: id,
                providerId = ProviderId.ANTHROPIC,
                providerName = displayName,
                isLocal = false
            )
        }.sortedBy { it.id }
    }

    override suspend fun testConnection(): Boolean {
        val base = baseUrl()
        val key = apiKey()
        if (base.isBlank() || key.isBlank()) return false
        return try {
            for (url in modelCandidates(base)) {
                val req = headers(Request.Builder().url(url).get(), key).build()
                val resp = try {
                    AiHttp.newCall(req).execute()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: IOException) {
                    // Error jaringan → tidak sehat, jangan coba kandidat lain.
                    return false
                }
                resp.use { if (it.isSuccessful) return true }
            }
            false
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    override fun streamChat(messages: List<ChatMessage>, model: String): Flow<StreamEvent> = flow {
        val base = baseUrl()
        val key = apiKey()
        if (base.isBlank() || key.isBlank()) {
            emit(StreamEvent.Error("Anthropic API key is missing. Open Settings → AI."))
            return@flow
        }
        if (model.isBlank()) {
            emit(StreamEvent.Error("No model selected for Anthropic. Open Settings → AI."))
            return@flow
        }

        val system = messages
            .filter { it.role == Role.SYSTEM }
            .joinToString("\n\n") { it.content }
        val convo = messages
            .filter { it.role != Role.SYSTEM }
            .map { if (it.role == Role.ASSISTANT) "assistant" to it.content else "user" to it.content }

        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            if (system.isNotBlank()) put("system", system)
            put("messages", buildJsonArray {
                convo.forEach { (role, content) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("content", content)
                    })
                }
            })
            put("stream", true)
        }.toString()

        val request = headers(
            Request.Builder()
                .url("$base/v1/messages")
                .post(payload.toRequestBody(JSON_MEDIA)),
            key
        ).build()
        val call = AiHttp.newCall(request)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = try { resp.body?.string() } catch (_: Exception) { null }
                    emit(StreamEvent.Error("Anthropic HTTP ${resp.code}${errorSnippet(errBody)}"))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Error("Anthropic returned an empty body."))
                    return@flow
                }
                val lines = sseDataLines(source).iterator()
                while (lines.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val data = lines.next()
                    val obj = parseJsonSafe(data) ?: continue
                    when (obj.jsonStr("type")) {
                        "content_block_delta" -> {
                            val text = obj.jsonObj("delta")?.jsonStr("text")
                            if (!text.isNullOrEmpty()) emit(StreamEvent.Delta(text))
                        }
                        "message_stop" -> break
                        "error" -> {
                            val msg = obj.jsonObj("error")?.jsonStr("message") ?: "Anthropic stream error"
                            emit(StreamEvent.Error("Anthropic: $msg"))
                            return@flow
                        }
                        else -> Unit // message_start / ping / content_block_* → abaikan
                    }
                }
            }
            emit(StreamEvent.Done)
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (e: Exception) {
            emit(StreamEvent.Error("Anthropic: ${e.message ?: "connection failed"}"))
        }
    }.flowOn(Dispatchers.IO)
}
