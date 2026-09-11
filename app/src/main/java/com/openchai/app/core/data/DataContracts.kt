package com.openchai.core.data

import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Conversation
import com.openchai.core.model.Project
import java.io.File

/** Penyimpanan riwayat percakapan (lokal, JSON per percakapan). */
interface ConversationStore {
    suspend fun conversations(): List<Conversation>

    suspend fun messages(conversationId: String): List<ChatMessage>

    suspend fun createConversation(projectId: String?, title: String): Conversation

    suspend fun appendMessage(conversationId: String, message: ChatMessage)

    suspend fun updateMessage(conversationId: String, message: ChatMessage)

    /** Hapus pesan mulai [messageId] (inklusif) sampai akhir — untuk regenerate/edit. */
    suspend fun deleteMessagesFrom(conversationId: String, messageId: String)

    suspend fun renameConversation(conversationId: String, title: String)

    suspend fun deleteConversation(conversationId: String)

    suspend fun touch(conversationId: String)
}

/** Manajer workspace proyek. Semua operasi file agent dibatasi pada direktori ini. */
interface WorkspaceManager {
    fun workspaceRoot(): String

    suspend fun listProjects(): List<Project>

    suspend fun createProject(name: String): Project

    suspend fun deleteProject(id: String)

    suspend fun renameProject(id: String, newName: String)

    fun projectDir(project: Project): File

    /** True bila [target] berada di dalam [projectPath] (sandbox boundary). */
    fun guardIn(projectPath: String, target: String): Boolean

    /** True bila [target] berada di dalam salah satu proyek yang ada. */
    fun guardAny(target: String): Boolean
}

/** Penyimpanan kredensial aman (Android Keystore-backed). */
interface SecureStore {
    suspend fun apiKey(provider: String): String

    suspend fun saveApiKey(provider: String, value: String)

    suspend fun clearApiKey(provider: String)
}
