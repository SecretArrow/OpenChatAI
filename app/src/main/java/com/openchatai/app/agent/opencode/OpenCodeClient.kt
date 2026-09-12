package com.openchai.agent.opencode

import com.openchatai.app.ai.JSON_MEDIA
import com.openchatai.app.ai.AiHttp
import com.openchatai.app.ai.jsonStr
import com.openchatai.app.ai.parseJsonElementSafe
import com.openchatai.app.ai.parseJsonSafe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client best-effort untuk server OpenCode mode serve (`opencode serve`).
 * Endpoint yang dipakai (konvensi server OpenCode):
 *  - GET  /config                     → health probe (timeout 3s)
 *  - POST /session                    → buat sesi {"title":"Open Chat AI"} → {"id"}
 *  - POST /session/{id}/message       → kirim prompt, tunggu jawaban (read 600s)
 *
 * Semua metode toleran: kegagalan jaringan/parse TIDAK dilempar keluar,
 * melainkan dikembalikan sebagai false/null agar caller (orchestrator)
 * bisa fallback ke built-in agent.
 */
class OpenCodeClient(base: String) {

    private val baseUrl: String = base.trim().trimEnd('/')

    private val healthClient: OkHttpClient = AiHttp.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private val sessionClient: OkHttpClient = AiHttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val messageClient: OkHttpClient = AiHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    /** GET /config → true bila server merespons 2xx. */
    fun health(): Boolean {
        if (baseUrl.isBlank()) return false
        return try {
            val req = Request.Builder().url("$baseUrl/config").get().build()
            healthClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** POST /session → id sesi baru, atau null bila gagal. */
    fun createSession(): String? {
        if (baseUrl.isBlank()) return null
        return try {
            val body = buildJsonObject { put("title", "Open Chat AI") }
                .toString()
                .toRequestBody(JSON_MEDIA)
            val req = Request.Builder().url("$baseUrl/session").post(body).build()
            sessionClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parseJsonSafe(resp.body?.string().orEmpty())
                    ?.jsonStr("id")
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * POST /session/{id}/message → jawaban gabungan (semua parts bertipe "text"
     * pada response JSON dikumpulkan rekursif dan digabung), atau null bila gagal.
     */
    fun sendMessage(sessionId: String, text: String): String? {
        if (baseUrl.isBlank() || sessionId.isBlank()) return null
        return try {
            val body = buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                })
            }.toString().toRequestBody(JSON_MEDIA)
            val req = Request.Builder()
                .url("$baseUrl/session/$sessionId/message")
                .post(body)
                .build()
            messageClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return null
                val root = parseJsonElementSafe(raw) ?: return null
                val texts = mutableListOf<String>()
                collectTexts(root, texts)
                texts.joinToString("\n\n").trim().ifEmpty { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Kumpulkan semua field "text" pada parts dengan type=="text", secara rekursif. */
    private fun collectTexts(el: JsonElement?, out: MutableList<String>) {
        when (el) {
            is JsonObject -> {
                if (el.jsonStr("type") == "text") {
                    el.jsonStr("text")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                }
                for (value in el.values) collectTexts(value, out)
            }
            is JsonArray -> el.forEach { collectTexts(it, out) }
            else -> Unit
        }
    }
}
