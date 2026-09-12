package com.openchatai.app.ai

import android.content.Context
import com.openchai.core.ai.AiProvider
import com.openchai.core.ai.StreamEvent
import com.openchai.core.llm.LlamaEngine
import com.openchai.core.llm.ModelManager
import com.openchai.core.llm.humanBytes
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.ModelInfo
import com.openchai.core.model.ProviderId
import com.openchai.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Provider AI lokal on-device di atas llama.cpp (GGUF).
 *
 * - model dipilih lewat screen Models (unduh katalog → tap "Use"),
 * - [ModelInfo.id] = path absolut file .gguf sehingga Chat UI cukup menyimpan id,
 * - streaming didelegasikan ke [LlamaEngine.generateFlow].
 */
class LocalLlamaProvider(
    private val context: Context,
    private val settings: SettingsRepository,
    private val engine: LlamaEngine,
    private val models: ModelManager
) : AiProvider {

    override val providerId: ProviderId = ProviderId.LOCAL
    override val displayName: String = "On-device (llama.cpp)"

    /** Path model terkonfigurasi di settings, atau null bila kosong/file hilang. */
    private fun configuredPath(): String? {
        val path = settings.settings.value.localModelPath.trim()
        return if (path.isNotBlank() && File(path).isFile) path else null
    }

    override fun isConfigured(): Boolean = configuredPath() != null

    override suspend fun listModels(): List<ModelInfo> =
        models.installedModels().map { file ->
            ModelInfo(
                id = file.absolutePath,
                name = file.name,
                providerId = ProviderId.LOCAL,
                providerName = displayName,
                isLocal = true,
                details = "GGUF · ${humanBytes(file.length())}"
            )
        }

    override suspend fun testConnection(): Boolean = engine.isLoaded() || isConfigured()

    override fun streamChat(messages: List<ChatMessage>, model: String): Flow<StreamEvent> = flow {
        val path = resolveModelPath(model)
        if (path == null) {
            emit(
                StreamEvent.Error(
                    "No local model is installed. Open Models to download a GGUF model, then tap Use."
                )
            )
            return@flow
        }
        val s = settings.settings.value
        // Sinkronkan settings bila model dipilih lewat Model Selector (yang hanya
        // menulis selectedModel) agar badge "Active" di screen Models tetap benar.
        if (s.localModelPath != path) {
            settings.update { it.copy(localModelPath = path) }
        }
        try {
            engine.ensureLoaded(path, s.localContextSize, s.localThreads)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            emit(StreamEvent.Error("Failed to load local model: ${e.message}"))
            return@flow
        }
        engine.generateFlow(
            messages = messages,
            temperature = 0.7f,
            topP = 0.9f,
            maxTokens = LlamaEngine.DEFAULT_MAX_TOKENS
        ).collect { emit(it) }
    }.flowOn(Dispatchers.Default)

    /**
     * Resolve path GGUF dari [model]:
     *  1. path absolut (pilihan dari Model Selector — id = path absolut),
     *  2. nama file di direktori model (hasil "Use" dari screen Models),
     *  3. fallback: model yang terkonfigurasi di settings.
     */
    private fun resolveModelPath(model: String): String? {
        if (model.isNotBlank()) {
            if (model.contains(File.separatorChar)) {
                val byPath = File(model)
                if (byPath.isFile) return byPath.absolutePath
            } else {
                val byName = File(models.dir, model)
                if (byName.isFile) return byName.absolutePath
            }
        }
        return configuredPath()
    }
}
