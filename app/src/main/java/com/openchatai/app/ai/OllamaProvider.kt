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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Hasil diagnosis endpoint Ollama (dipakai UI untuk status + tombol Start Ollama). */
sealed interface OllamaDiag {
    /** Server hidup; version boleh null bila /api/version tak bisa diparse. */
    data class Running(val version: String?, val modelCount: Int) : OllamaDiag
    /** Koneksi ditolak / timeout / host tak ditemukan → server kemungkinan mati. */
    data class NotRunning(val base: String) : OllamaDiag
    /** Server terjangkau tapi jawabannya salah (HTTP error / payload tak terduga). */
    data class Error(val base: String, val reason: String) : OllamaDiag
}

/**
 * Provider untuk server Ollama lokal.
 * - listModels : GET /api/tags
 * - test       : GET /api/version
 * - diagnose   : GET /api/version + /api/tags (timeout pendek, status kaya)
 * - streamChat : POST /api/chat (NDJSON per baris, "stream": true)
 * Semua error dikonversi menjadi [StreamEvent.Error]; CancellationException diteruskan
 * agar pembatalan oleh UI tetap bekerja.
 */
class OllamaProvider(private val settings: SettingsRepository) : AiProvider {

    override val providerId: ProviderId = ProviderId.OLLAMA
    override val displayName: String = "Ollama"

    // Client timeout pendek khusus diagnosis: server mati harus terdeteksi cepat,
    // tidak boleh menunggu timeout panjang milik [AiHttp] (read 300s).
    private val diagClient: OkHttpClient = AiHttp.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun baseUrl(): String = settings.settings.value.ollamaEndpoint.trim().trimEnd('/')

    override fun isConfigured(): Boolean = baseUrl().isNotBlank()

    override suspend fun listModels(): List<ModelInfo> {
        val base = baseUrl()
        if (base.isBlank()) return emptyList()
        val req = Request.Builder().url("$base/api/tags").get().build()
        try {
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            // Jangan bocorkan pesan mentah OkHttp — UI mencari frasa "not reachable"
            // untuk menampilkan tombol Start Ollama.
            throw IOException("Ollama is not reachable at $base — is it running?")
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

    /**
     * Diagnosis ringkas endpoint Ollama dengan timeout pendek: /api/version lalu
     * /api/tags. Koneksi ditolak/timeout/host tak dikenal → [OllamaDiag.NotRunning]
     * (server kemungkinan mati); HTTP error / payload tak terduga → [OllamaDiag.Error];
     * selain itu [OllamaDiag.Running] dengan versi + jumlah model terpasang.
     */
    suspend fun diagnose(): OllamaDiag {
        val base = baseUrl()
        if (base.isBlank()) {
            return OllamaDiag.Error(
                base = "http://127.0.0.1:11434",
                reason = "Ollama endpoint is not set. Open Settings → AI."
            )
        }
        return try {
            withContext(Dispatchers.IO) {
                // 1) /api/version — bukti server hidup + nomor versi.
                val versionReq = Request.Builder().url("$base/api/version").get().build()
                val version: String? = diagClient.newCall(versionReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Ollama HTTP ${resp.code} at /api/version")
                    }
                    parseJsonSafe(resp.body?.string().orEmpty())?.jsonStr("version")
                }
                // 2) /api/tags — jumlah model terpasang.
                val tagsReq = Request.Builder().url("$base/api/tags").get().build()
                val modelCount: Int = diagClient.newCall(tagsReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Ollama HTTP ${resp.code} at /api/tags")
                    }
                    parseJsonSafe(resp.body?.string().orEmpty())?.jsonArr("models")?.size
                        ?: throw IOException("Unexpected response from /api/tags")
                }
                OllamaDiag.Running(version, modelCount)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            // UnknownHost / Connect refused / timeout → server kemungkinan mati.
            if (e is UnknownHostException || e is ConnectException || e is SocketTimeoutException) {
                OllamaDiag.NotRunning(base)
            } else {
                OllamaDiag.Error(base, e.message ?: e.toString())
            }
        } catch (e: Exception) {
            OllamaDiag.Error(base, e.message ?: e.toString())
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
        } catch (e: IOException) {
            // Koneksi gagal (server mati/refused) → pesan konsisten dengan diagnose/listModels.
            emit(StreamEvent.Error("Ollama is not reachable at $base — is it running?"))
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
