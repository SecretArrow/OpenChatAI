package com.openchatai.app.ai

import com.openchai.core.ai.AiProvider
import com.openchai.core.ai.StreamEvent
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale

/**
 * Provider untuk server Ollama lokal.
 * - listModels : GET /api/tags
 * - test       : GET /api/version
 * - streamChat : POST /api/chat (NDJSON per baris, "stream": true)
 * Semua error dikonversi menjadi [StreamEvent.Error]; CancellationException diteruskan
 * agar pembatalan oleh UI tetap bekerja.
 */
class OllamaProvider(private val settings: SettingsRepository) : AiProvider {

    override val providerId: ProviderId = ProviderId.OLLAMA
    override val displayName: String = "Ollama"

    private fun baseUrl(): String = settings.settings.value.ollamaEndpoint.trim().trimEnd('/')

    override fun isConfigured(): Boolean = baseUrl().isNotBlank()

    override suspend fun listModels(): List<ModelInfo> {
        val base = baseUrl()
        if (base.isBlank()) return emptyList()
        val req = Request.Builder().url("$base/api/tags").get().build()
        AiHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Ollama HTTP ${resp.code}${errorSnippet(resp.body?.string())}")
            }
            val root = parseJsonSafe(resp.body?.string().orEmpty()) ?: return emptyList()
            val models = root.jsonArr("models") ?: return emptyList()
            return models.mapNotNull { el ->
                val m = el as? JsonObject ?: return@mapNotNull null
                val name = m.jsonStr("name") ?: return@mapNotNull null
                val sizeBytes = m.jsonStr("size")?.toLongOrNull()
                ModelInfo(
                    id = name,
                    name = name,
                    providerId = ProviderId.OLLAMA,
                    providerName = displayName,
                    isLocal = true,
                    details = humanBytes(sizeBytes)
                )
            }
        }
    }

    override suspend fun testConnection(): Boolean {
        val base = baseUrl()
        if (base.isBlank()) return false
        return try {
            val req = Request.Builder().url("$base/api/version").get().build()
            AiHttp.newCall(req).execute().use { it.isSuccessful }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    override fun streamChat(messages: List<ChatMessage>, model: String): Flow<StreamEvent> = flow {
        val base = baseUrl()
        if (base.isBlank()) {
            emit(StreamEvent.Error("Ollama endpoint is not set. Open Settings → AI."))
            return@flow
        }
        if (model.isBlank()) {
            emit(StreamEvent.Error("No model selected for Ollama. Open Settings → AI."))
            return@flow
        }
        val payload = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                messages.forEach { msg ->
                    add(buildJsonObject {
                        // SYSTEM/USER → "user", ASSISTANT → "assistant" (sesuai kontrak).
                        put("role", if (msg.role == Role.ASSISTANT) "assistant" else "user")
                        put("content", msg.content)
                    })
                }
            })
            put("stream", true)
        }.toString()

        val request = Request.Builder()
            .url("$base/api/chat")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val call = AiHttp.newCall(request)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = try { resp.body?.string() } catch (_: Exception) { null }
                    emit(StreamEvent.Error("Ollama HTTP ${resp.code}${errorSnippet(errBody)}"))
                    return@flow
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(StreamEvent.Error("Ollama returned an empty body."))
                    return@flow
                }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val obj = parseJsonSafe(line) ?: continue
                    obj.jsonStr("error")?.let { err ->
                        emit(StreamEvent.Error("Ollama: $err"))
                        return@flow
                    }
                    val doneEl = obj["done"]
                    if (doneEl is JsonPrimitive && doneEl.booleanOrNull == true) break
                    val content = obj.jsonObj("message")?.jsonStr("content")
                    if (!content.isNullOrEmpty()) emit(StreamEvent.Delta(content))
                }
            }
            emit(StreamEvent.Done)
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (e: Exception) {
            emit(StreamEvent.Error("Ollama: ${e.message ?: "connection failed"}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun humanBytes(bytes: Long?): String? {
        if (bytes == null || bytes <= 0L) return null
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
