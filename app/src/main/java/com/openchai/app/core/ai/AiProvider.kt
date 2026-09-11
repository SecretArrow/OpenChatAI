package com.openchai.core.ai

import com.openchai.core.model.ChatMessage
import com.openchai.core.model.ModelInfo
import com.openchai.core.model.ProviderId
import kotlinx.coroutines.flow.Flow

/** Event streaming balasan model. */
sealed class StreamEvent {
    data class Delta(val text: String) : StreamEvent()
    data object Done : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

/**
 * Kontrak provider AI. Semua provider (Ollama, OpenAI, Anthropic, Google, custom)
 * mengimplementasikan interface ini sehingga Chat UI tidak pernah berubah ketika
 * backend model diganti.
 */
interface AiProvider {
    val providerId: ProviderId
    val displayName: String

    fun isConfigured(): Boolean

    /** Daftar model yang tersedia dari provider ini. */
    suspend fun listModels(): List<ModelInfo>

    /** True bila endpoint dapat dijangkau dan kredensial valid. */
    suspend fun testConnection(): Boolean

    /** Stream percakapan. Flow harus bisa dibatalkan (cancel) kapan pun. */
    fun streamChat(messages: List<ChatMessage>, model: String): Flow<StreamEvent>
}
