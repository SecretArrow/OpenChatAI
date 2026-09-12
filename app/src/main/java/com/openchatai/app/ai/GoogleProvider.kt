package com.openchatai.app.ai

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
 * Provider Google Gemini via Generative Language API.
 * - listModels : GET /v1beta/models?key=… → models[].name ("models/gemini-…") → id tanpa prefix
 * - test       : GET /v1beta/models?key=… (2xx)
 * - streamChat : POST /v1beta/models/{model}:streamGenerateContent?alt=sse&key=…
 *     SSE payload → candidates[0].content.parts[*].text digabung per event.
 * Role ASSISTANT → "model"; pesan SYSTEM dikeluarkan ke "systemInstruction".
 */
class GoogleProvider(
    private val settings: SettingsRepository,
    private val secure: SecureStore
) : AiProvider {

    override val providerId: ProviderId = ProviderId.GOOGLE
    override val displayName: String = "Google Gemini"

    @Volatile
    private var cachedKey: String? = null

    private fun baseUrl(): String = settings.settings.value.googleEndpoint.trim().trimEnd('/')

    private suspend fun apiKey(): String =
        secure.apiKey(providerId.name).trim().also { cachedKey = it }

    override fun isConfigured(): Boolean = !cachedKey.isNullOrBlank()

    override suspend fun listModels(): List<ModelInfo> {
        val key = apiKey()
        if (key.isBlank()) return emptyList()
        val req = Request.Builder().url("${baseUrl()}/v1beta/models?key=$key").get().build()
        AiHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Google Gemini HTTP ${resp.code}${errorSnippet(resp.body?.string())}")
            }
            val root = parseJsonSafe(resp.body?.string().orEmpty()) ?: return emptyList()
            val models = root.jsonArr("models") ?: return emptyList()
            return models.mapNotNull { el ->
                val m = el as? JsonObject ?: return@mapNotNull null
                val rawName = m.jsonStr("name") ?: return@mapNotNull null
                val id = rawName.removePrefix("models/")
                if (id.isBlank()) return@mapNotNull null
                val tokenLimit = m.jsonStr("inputTokenLimit")?.toLongOrNull()
                ModelInfo(
                    id = id,
                    name = m.jsonStr("displayName") ?: id,
                    providerId = ProviderId.GOOGLE,
                    providerName = displayName,
                    isLocal = false,
                    details = tokenLimit?.let { "inputTokenLimit: $it" }
                )
            }.sortedBy { it.id }
        }
    }

    override suspend fun testConnection(): Boolean {
        val key = apiKey()
        if (key.isBlank()) return false
        return try {
            val req = Request.Builder().url("${baseUrl()}/v1beta/models?key=$key").get().build()
            AiHttp.newCall(req).execute().use { it.isSuccessful }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    override fun streamChat(messages: List<ChatMessage>, model: String): Flow<StreamEvent> = flow {
        val key = apiKey()
        if (key.isBlank()) {
            emit(StreamEvent.Error("Google API key is missing. Open Settings → AI."))
            return@flow
        }
        if (model.isBlank()) {
            emit(StreamEvent.Error("No model selected for Google Gemini. Open Settings → AI."))
            return@flow
        }

        val system = messages
            .filter { it.role == Role.SYSTEM }
            .joinToString("\n\n") { it.content }
        val contents = messages
            .filter { it.role != Role.SYSTEM }
            .map { if (it.role == Role.ASSISTANT) "model" to it.content else "user" to it.content }

        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                contents.forEach { (role, text) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", text) })
                        })
                    })
                }
            })
            if (system.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", system) })
                    })
                })
            }
        }.toString()

        val url = "${baseUrl()}/v1beta/models/$model:streamGenerateContent?alt=sse&key=$key"
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val call = AiHttp.newCall(request)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = try { resp.body?.string() } catch (_: Exception) { null }
                    emit(StreamEvent.Error("Google Gemini HTTP ${resp.code}${errorSnippet(errBody)}"))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Error("Google Gemini returned an empty body."))
                    return@flow
                }
                val lines = sseDataLines(source).iterator()
                while (lines.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val data = lines.next()
                    val obj = parseJsonSafe(data) ?: continue
                    obj.jsonObj("error")?.jsonStr("message")?.let { msg ->
                        emit(StreamEvent.Error("Google Gemini: $msg"))
                        return@flow
                    }
                    val text = obj.jsonArr("candidates")
                        ?.firstOrNull()
                        ?.let { it as? JsonObject }
                        ?.jsonObj("content")
                        ?.jsonArr("parts")
                        ?.joinToString("") { p -> (p as? JsonObject)?.jsonStr("text").orEmpty() }
                    if (!text.isNullOrEmpty()) emit(StreamEvent.Delta(text))
                }
            }
            emit(StreamEvent.Done)
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (e: Exception) {
            emit(StreamEvent.Error("Google Gemini: ${e.message ?: "connection failed"}"))
        }
    }.flowOn(Dispatchers.IO)
}
