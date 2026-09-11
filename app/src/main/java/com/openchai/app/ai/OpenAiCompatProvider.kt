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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Provider kompatibel OpenAI — dipakai untuk OPENAI (api.openai.com) dan CUSTOM
 * (endpoint apa pun dengan API chat-completions gaya OpenAI).
 * - listModels : GET /models → data[].id
 * - test       : GET /models (bila 404 → GET base; 2xx dianggap sehat)
 * - streamChat : POST /chat/completions, SSE, delta.content, "[DONE]" menutup stream.
 * API key dibaca dari [SecureStore] dengan kunci "OPENAI"/"CUSTOM".
 */
class OpenAiCompatProvider(
    private val settings: SettingsRepository,
    private val secure: SecureStore,
    override val providerId: ProviderId,
    private val defaultBaseUrl: String
) : AiProvider {

    override val displayName: String =
        if (providerId == ProviderId.CUSTOM) "Custom" else "OpenAI"

    /**
     * Cache key terakhir yang dibaca agar [isConfigured] (non-suspend) bisa menjawab
     * tanpa memblokir. Di-refresh setiap call jaringan (listModels/test/streamChat).
     */
    @Volatile
    private var cachedKey: String? = null

    private fun baseUrl(): String {
        val s = settings.settings.value
        val configured = if (providerId == ProviderId.CUSTOM) s.customEndpoint else s.openaiEndpoint
        return configured.ifBlank { defaultBaseUrl }.trim().trimEnd('/')
    }

    private suspend fun apiKey(): String =
        secure.apiKey(providerId.name).trim().also { cachedKey = it }

    private fun authorized(builder: Request.Builder, key: String): Request.Builder =
        if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key") else builder

    override fun isConfigured(): Boolean {
        if (baseUrl().isBlank()) return false
        if (providerId == ProviderId.CUSTOM) return true
        return !cachedKey.isNullOrBlank()
    }

    override suspend fun listModels(): List<ModelInfo> {
        val base = baseUrl()
        if (base.isBlank()) return emptyList()
        val key = apiKey()
        val req = authorized(Request.Builder().url("$base/models").get(), key).build()
        AiHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("${displayName} HTTP ${resp.code}${errorSnippet(resp.body?.string())}")
            }
            val root = parseJsonSafe(resp.body?.string().orEmpty()) ?: return emptyList()
            val data = root.jsonArr("data") ?: return emptyList()
            return data.mapNotNull { el ->
                val m = el as? JsonObject ?: return@mapNotNull null
                val id = m.jsonStr("id") ?: return@mapNotNull null
                ModelInfo(
                    id = id,
                    name = id,
                    providerId = providerId,
                    providerName = displayName,
                    isLocal = false,
                    details = m.jsonStr("owned_by")
                )
            }.sortedBy { it.id }
        }
    }

    override suspend fun testConnection(): Boolean {
        val base = baseUrl()
        if (base.isBlank()) return false
        val key = apiKey()
        return try {
            val modelsReq = authorized(Request.Builder().url("$base/models").get(), key).build()
            val modelsResp = AiHttp.newCall(modelsReq).execute()
            modelsResp.use { if (it.isSuccessful) return true }
            // Endpoint custom kadang tidak punya /models — bila 404, coba root URL.
            if (modelsResp.code == 404) {
                val baseReq = authorized(Request.Builder().url(base).get(), key).build()
                AiHttp.newCall(baseReq).execute().use { it.isSuccessful }
            } else {
                false
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    override fun streamChat(messages: List<ChatMessage>, model: String): Flow<StreamEvent> = flow {
        val base = baseUrl()
        if (base.isBlank()) {
            emit(StreamEvent.Error("${displayName} endpoint is not set. Open Settings → AI."))
            return@flow
        }
        val key = apiKey()
        if (key.isBlank() && providerId != ProviderId.CUSTOM) {
            emit(StreamEvent.Error("${displayName} API key is missing. Open Settings → AI."))
            return@flow
        }
        if (model.isBlank()) {
            emit(StreamEvent.Error("No model selected for ${displayName}. Open Settings → AI."))
            return@flow
        }

        val payload = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        put(
                            "role",
                            when (msg.role) {
                                Role.SYSTEM -> "system"
                                Role.ASSISTANT -> "assistant"
                                Role.USER -> "user"
                            }
                        )
                        put("content", msg.content)
                    })
                }
            })
            put("stream", true)
        }.toString()

        val request = authorized(
            Request.Builder()
                .url("$base/chat/completions")
                .post(payload.toRequestBody(JSON_MEDIA)),
            key
        ).build()
        val call = AiHttp.newCall(request)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = try { resp.body?.string() } catch (_: Exception) { null }
                    emit(StreamEvent.Error("${displayName} HTTP ${resp.code}${errorSnippet(errBody)}"))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Error("${displayName} returned an empty body."))
                    return@flow
                }
                val lines = sseDataLines(source).iterator()
                while (lines.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val data = lines.next()
                    if (data == "[DONE]") break
                    val obj = parseJsonSafe(data) ?: continue
                    val errMsg = obj.jsonObj("error")?.jsonStr("message")
                        ?: (obj["error"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    if (!errMsg.isNullOrBlank()) {
                        emit(StreamEvent.Error("${displayName}: $errMsg"))
                        return@flow
                    }
                    val delta = obj.jsonArr("choices")
                        ?.firstOrNull()
                        ?.let { it as? JsonObject }
                        ?.jsonObj("delta")
                        ?.jsonStr("content")
                    if (!delta.isNullOrEmpty()) emit(StreamEvent.Delta(delta))
                }
            }
            emit(StreamEvent.Done)
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (e: Exception) {
            emit(StreamEvent.Error("${displayName}: ${e.message ?: "connection failed"}"))
        }
    }.flowOn(Dispatchers.IO)
}
