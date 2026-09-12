package com.openchatai.app.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okio.BufferedSource
import java.util.concurrent.TimeUnit

/**
 * OkHttpClient bersama untuk seluruh lapisan AI (provider streaming + OpenCode client).
 * - connect 15s (endpoint lokal maupun cloud harus cepat gagal),
 * - read 300s (LLM stream bisa diam lama saat generate),
 * - write 120s (payload prompt bisa besar).
 * Client ini thread-safe dan shared; provider yang butuh timeout berbeda
 * memakai [OkHttpClient.newBuilder].
 */
val AiHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/** MediaType JSON bersama untuk semua request provider. */
val JSON_MEDIA: MediaType = "application/json; charset=utf-8".toMediaType()

private val tolerantJson = Json { isLenient = true; ignoreUnknownKeys = true }

/** Parse JSON toleran menjadi JsonObject, atau null bila bukan objek / gagal parse. */
fun parseJsonSafe(text: String): JsonObject? = try {
    tolerantJson.parseToJsonElement(text) as? JsonObject
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** Parse JSON toleran menjadi JsonElement apa pun (objek/array/primitif), atau null. */
fun parseJsonElementSafe(text: String): JsonElement? = try {
    tolerantJson.parseToJsonElement(text)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** String aman dari obj[field]; null bila absen, JsonNull, atau bukan primitif. */
inline fun JsonObject.jsonStr(field: String): String? {
    val el = this[field] ?: return null
    if (el is JsonNull) return null
    return (el as? JsonPrimitive)?.content
}

/** JsonArray aman dari obj[field]; null bila absen / bukan array. */
inline fun JsonObject.jsonArr(field: String): JsonArray? = this[field] as? JsonArray

/** JsonObject aman dari obj[field]; null bila absen / bukan objek. */
inline fun JsonObject.jsonObj(field: String): JsonObject? = this[field] as? JsonObject

/**
 * Sequence lazy payload SSE: hanya baris "data: ..." yang di-yield.
 * Baris kosong, komentar (": ..."), dan baris event/id lain diabaikan.
 * Payload "[DONE]" tetap di-yield — caller memutus kapan menghentikan loop.
 * Blocking read per baris; panggil dari Dispatchers.IO dan cek
 * [kotlinx.coroutines.ensureActive] tiap iterasi agar cancel responsif.
 */
fun sseDataLines(source: BufferedSource): Sequence<String> = sequence {
    while (true) {
        val line = source.readUtf8Line() ?: break
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
        if (trimmed.startsWith("data:")) yield(trimmed.removePrefix("data:").trim())
    }
}

/**
 * Ringkasan pesan error dari response body (lintas format provider:
 * {"error":{"message":..}}, {"error":".."}, {"message":..}) untuk pesan user.
 * Kembalikan string kosong bila tidak ada yang bisa diekstrak.
 */
fun errorSnippet(body: String?): String {
    if (body.isNullOrBlank()) return ""
    val obj = parseJsonSafe(body) ?: return ""
    val msg = when (val el = obj["error"]) {
        is JsonPrimitive -> if (el is JsonNull) null else el.content
        is JsonObject -> el.jsonStr("message")
        else -> obj.jsonStr("message")
    }?.trim()?.take(200)
    return if (msg.isNullOrBlank()) "" else " — $msg"
}
