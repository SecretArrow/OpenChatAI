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
 * - listModels : GET /v1/models → data[].id
 * - test       : GET /v1/models (2xx)
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

    override suspend fun listModels(): List<ModelInfo> {
        val base = baseUrl()
        val key = apiKey()
        if (base.isBlank() || key.isBlank()) return emptyList()
        val req = headers(Request.Builder().url("$base/v1/models").get(), key).build()
        AiHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Anthropic HTTP ${resp.code}${errorSnippet(resp.body?.string())}")
            }
            val root = parseJsonSafe(resp.body?.string().orEmpty()) ?: return emptyList()
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
    }

    override suspend fun testConnection(): Boolean {
        val base = baseUrl()
        val key = apiKey()
        if (base.isBlank() || key.isBlank()) return false
        return try {
            val req = headers(Request.Builder().url("$base/v1/models").get(), key).build()
            AiHttp.newCall(req).execute().use { it.isSuccessful }
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
