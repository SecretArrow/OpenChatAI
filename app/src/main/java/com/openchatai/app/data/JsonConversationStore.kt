package com.openchai.data

import android.content.Context
import com.openchai.core.data.ConversationStore
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Wrapper isi file JSON untuk satu percakapan (metadata + pesan). */
@Serializable
internal data class ConvFile(
    val conversation: Conversation,
    val messages: List<ChatMessage> = emptyList()
)

/**
 * Penyimpanan riwayat percakapan berbasis file JSON: satu file per percakapan
 * (`conv_<id>.json`) di dalam `<filesDir>/conversations`. Akses di-serialisasi
 * lewat [Mutex] dan dijalankan di [Dispatchers.IO].
 */
class JsonConversationStore(private val context: Context) : ConversationStore {

    private val dir: File = File(context.filesDir, "conversations").apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val mutex = Mutex()

    private fun fileFor(id: String): File = File(dir, FILE_PREFIX + id + FILE_SUFFIX)

    override suspend fun conversations(): List<Conversation> = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
                ?.mapNotNull { file ->
                    runCatching { json.decodeFromString(ConvFile.serializer(), file.readText()) }
                        .getOrNull()?.conversation
                }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
        }
    }

    override suspend fun messages(conversationId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readFile(conversationId)?.messages ?: emptyList()
        }
    }

    override suspend fun createConversation(projectId: String?, title: String): Conversation =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conv = Conversation(projectId = projectId, title = title)
                writeFile(ConvFile(conversation = conv, messages = emptyList()))
                conv
            }
        }

    override suspend fun appendMessage(conversationId: String, message: ChatMessage): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readFile(conversationId) ?: ConvFile(
                    conversation = Conversation(id = conversationId),
                    messages = emptyList()
                )
                writeFile(
                    ConvFile(
                        conversation = current.conversation.copy(updatedAt = System.currentTimeMillis()),
                        messages = current.messages + message
                    )
                )
            }
        }

    override suspend fun updateMessage(conversationId: String, message: ChatMessage): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readFile(conversationId) ?: return@withContext
                val updated = current.messages.map { if (it.id == message.id) message else it }
                writeFile(current.copy(messages = updated))
            }
        }

    override suspend fun deleteMessagesFrom(conversationId: String, messageId: String): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readFile(conversationId) ?: return@withContext
                val idx = current.messages.indexOfFirst { it.id == messageId }
                if (idx < 0) return@withContext
                writeFile(current.copy(messages = current.messages.subList(0, idx).toList()))
            }
        }

    override suspend fun renameConversation(conversationId: String, title: String): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readFile(conversationId) ?: return@withContext
                writeFile(current.copy(conversation = current.conversation.copy(title = title)))
            }
        }

    override suspend fun deleteConversation(conversationId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { fileFor(conversationId).delete() }
    }

    override suspend fun touch(conversationId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readFile(conversationId) ?: return@withContext
            writeFile(
                current.copy(
                    conversation = current.conversation.copy(updatedAt = System.currentTimeMillis())
                )
            )
        }
    }

    /** Baca + decode file percakapan; file korup dianggap tidak ada (dilewati, tidak crash). */
    private fun readFile(id: String): ConvFile? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(ConvFile.serializer(), file.readText()) }.getOrNull()
    }

    /** Tulis atomik: tulis ke file tmp lalu rename agar file tidak pernah setengah terisi. */
    private fun writeFile(data: ConvFile) {
        val file = fileFor(data.conversation.id)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ConvFile.serializer(), data))
        if (!tmp.renameTo(file)) {
            // Rename gagal (jarang, mis. lintas filesystem) — tulis langsung agar data tidak hilang.
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private companion object {
        const val FILE_PREFIX = "conv_"
        const val FILE_SUFFIX = ".json"
    }
}
