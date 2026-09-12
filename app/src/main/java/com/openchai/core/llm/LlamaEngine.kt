package com.openchai.core.llm

import android.app.ActivityManager
import android.content.Context
import com.openchai.core.ai.StreamEvent
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale

/**
 * Singleton engine AI lokal di atas [LlamaBridge] (llama.cpp JNI).
 *
 * Tanggung jawab:
 *  - Serialisasi semua operasi pada handle native (satu [Mutex]) karena session
 *    llama.cpp tidak thread-safe.
 *  - Muat/lepas model ([ensureLoaded] / [unload]) + pemeriksaan memori sebelum load.
 *  - Membangun prompt sesuai template chat "family" model (deteksi dari nama file)
 *    lalu streaming token sebagai [StreamEvent].
 *
 * Generasi dijalankan pada thread pemanggil (Dispatchers.Default via flowOn);
 * pembatalan coroutine otomatis menghentikan loop native dengan mengembalikan
 * `false` dari [TokenCallback.onToken].
 */
class LlamaEngine private constructor(private val appContext: Context) {

    companion object {
        /** Default batas token per generasi. */
        const val DEFAULT_MAX_TOKENS = 1024

        /** Faktor kebutuhan RAM saat load: ukuran file model × faktor (model + KV cache + working set). */
        private const val MEMORY_FACTOR = 2.5

        @Volatile
        private var instance: LlamaEngine? = null

        /** Instance aplikasi tunggal (pakai Application context). */
        fun getInstance(context: Context): LlamaEngine =
            instance ?: synchronized(this) {
                instance ?: LlamaEngine(context.applicationContext).also { instance = it }
            }
    }

    private val generateMutex = Mutex()

    @Volatile
    private var currentHandle: Long = 0L

    @Volatile
    private var loadedPath: String = ""

    /** True bila ada model yang sedang dimuat di memori. */
    fun isLoaded(): Boolean = currentHandle != 0L

    /** Path model yang sedang dimuat ("" bila tidak ada). */
    fun loadedModelPath(): String = loadedPath

    /**
     * Muat model [modelPath] bila belum dimuat atau path-nya berubah
     * (model lama di-free dulu). [backendInit][LlamaBridge.backendInitOnce]
     * dipanggil sebelum load. Melempar exception bila memori tidak cukup
     * atau llama.cpp gagal (RuntimeException dari JNI).
     */
    suspend fun ensureLoaded(modelPath: String, ctxSize: Int, threads: Int) {
        require(modelPath.isNotBlank()) { "Model path is blank" }
        generateMutex.withLock {
            if (currentHandle != 0L && loadedPath == modelPath) return@withLock
            if (currentHandle != 0L) {
                LlamaBridge.freeModel(currentHandle)
                currentHandle = 0L
                loadedPath = ""
            }
            LlamaBridge.backendInitOnce()
            checkMemoryBeforeLoad(modelPath)
            val handle = LlamaBridge.loadModel(modelPath, ctxSize.coerceAtLeast(0), threads.coerceAtLeast(0))
            if (handle == 0L) {
                throw IllegalStateException("Failed to load model: $modelPath")
            }
            currentHandle = handle
            loadedPath = modelPath
        }
    }

    /** Lepaskan model dari memori (aman dipanggil kapan pun). */
    suspend fun unload() {
        generateMutex.withLock {
            if (currentHandle != 0L) {
                LlamaBridge.freeModel(currentHandle)
                currentHandle = 0L
                loadedPath = ""
            }
        }
    }

    /**
     * Streaming percakapan lokal. Asumsi: model sudah dimuat via [ensureLoaded]
     * (bila belum, emit [StreamEvent.Error]).
     *
     * - Prompt diformat sesuai family model (deteksi nama file).
     * - Semua pesan SYSTEM digabung jadi satu blok system.
     * - addSpecial = true hanya bila contextPosition == 0 (BOS disisipkan sekali).
     * - Pembatalan collector → callback return false → generasi native berhenti.
     */
    fun generateFlow(
        messages: List<ChatMessage>,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): Flow<StreamEvent> = channelFlow {
        try {
            var errorMessage: String? = null
            generateMutex.withLock {
                val handle = currentHandle
                if (handle == 0L) {
                    errorMessage =
                        "No local model is loaded. Open Models to download a GGUF model, then tap Use."
                    return@withLock
                }
                // generate() native selalu menambahkan SELURUH prompt ke KV cache
                // (tanpa reuse prefix), jadi konteks direset tiap generasi agar
                // tidak dobel. Setelah reset contextPosition == 0 → addSpecial true.
                if (LlamaBridge.contextPosition(handle) > 0) {
                    LlamaBridge.resetContext(handle)
                }
                val addSpecial = LlamaBridge.contextPosition(handle) == 0
                val prompt = buildPrompt(messages, detectFamily(loadedPath))

                val scope = this
                val callback = TokenCallback { piece ->
                    // Return false bila coroutine sudah dibatalkan → loop native berhenti.
                    val active = try {
                        scope.coroutineContext.ensureActive()
                        true
                    } catch (_: CancellationException) {
                        false
                    }
                    if (!active) return@TokenCallback false
                    scope.trySend(StreamEvent.Delta(String(piece, Charsets.UTF_8)))
                    true
                }
                LlamaBridge.generate(
                    handle = handle,
                    prompt = prompt,
                    maxTokens = maxTokens.coerceAtLeast(1),
                    temperature = temperature,
                    topP = topP,
                    seed = 0L,
                    addSpecial = addSpecial,
                    callback = callback
                )
            }
            if (errorMessage != null) send(StreamEvent.Error(errorMessage!!))
            else send(StreamEvent.Done)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            runCatching { send(StreamEvent.Error(e.message ?: "Local generation failed")) }
        }
    }
        .buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.Default)

    // ----------------------------------------------------------------------
    // Prompt template
    // ----------------------------------------------------------------------

    private enum class ChatFamily { CHATML, LLAMA3, GEMMA, MISTRAL, PLAIN }

    /** Deteksi family model dari nama file GGUF. */
    private fun detectFamily(modelPath: String): ChatFamily {
        val name = modelPath.substringAfterLast('/').lowercase(Locale.US)
        return when {
            name.contains("qwen") -> ChatFamily.CHATML
            name.contains("llama") -> ChatFamily.LLAMA3
            name.contains("gemma") -> ChatFamily.GEMMA
            name.contains("phi") -> ChatFamily.CHATML
            name.contains("mistral") -> ChatFamily.MISTRAL
            else -> ChatFamily.PLAIN
        }
    }

    /** Pesan SYSTEM digabung (dipisah baris kosong); "" bila tidak ada. */
    private fun systemText(messages: List<ChatMessage>): String =
        messages.filter { it.role == Role.SYSTEM }
            .joinToString("\n\n") { it.content.trim() }
            .trim()

    /** Bangun prompt sesuai template chat family yang didukung. */
    private fun buildPrompt(messages: List<ChatMessage>, family: ChatFamily): String {
        val system = systemText(messages)
        val turns = messages.filter { it.role != Role.SYSTEM }
        return when (family) {
            ChatFamily.CHATML -> buildString {
                if (system.isNotEmpty()) append("<|im_start|>system\n$system<|im_end|>\n")
                turns.forEach { m ->
                    if (m.role == Role.USER) {
                        append("<|im_start|>user\n${m.content.trim()}<|im_end|>\n")
                    } else {
                        append("<|im_start|>assistant\n${m.content.trim()}<|im_end|>\n")
                    }
                }
                append("<|im_start|>assistant\n")
            }

            ChatFamily.LLAMA3 -> buildString {
                if (system.isNotEmpty()) {
                    append("<|start_header_id|>system<|end_header_id|>\n\n$system<|eot_id|>\n")
                }
                turns.forEach { m ->
                    if (m.role == Role.USER) {
                        append("<|start_header_id|>user<|end_header_id|>\n\n${m.content.trim()}<|eot_id|>\n")
                    } else {
                        append("<|start_header_id|>assistant<|end_header_id|>\n\n${m.content.trim()}<|eot_id|>\n")
                    }
                }
                append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }

            ChatFamily.GEMMA -> buildString {
                // Gemma tidak punya role system — system digabung ke user pertama.
                var firstUser = true
                turns.forEach { m ->
                    if (m.role == Role.USER) {
                        val content =
                            if (firstUser && system.isNotEmpty()) "$system\n\n${m.content.trim()}"
                            else m.content.trim()
                        append("<start_of_turn>user\n$content<end_of_turn>\n")
                        firstUser = false
                    } else {
                        append("<start_of_turn>model\n${m.content.trim()}<end_of_turn>\n")
                    }
                }
                append("<start_of_turn>model\n")
            }

            ChatFamily.MISTRAL -> buildString {
                // [INST] ... [/INST] — system digabung ke instruksi pertama.
                var firstUser = true
                turns.forEach { m ->
                    if (m.role == Role.USER) {
                        val content =
                            if (firstUser && system.isNotEmpty()) "$system\n\n${m.content.trim()}"
                            else m.content.trim()
                        append("[INST] $content [/INST]")
                        firstUser = false
                    } else {
                        append("${m.content.trim()}</s>")
                    }
                }
            }

            ChatFamily.PLAIN -> buildString {
                if (system.isNotEmpty()) append("$system\n\n")
                turns.forEach { m ->
                    if (m.role == Role.USER) append("User: ${m.content.trim()}\n\n")
                    else append("Assistant: ${m.content.trim()}\n\n")
                }
                append("Assistant:")
            }
        }
    }

    // ----------------------------------------------------------------------
    // Pemeriksaan memori
    // ----------------------------------------------------------------------

    /**
     * Tolak load bila RAM tersedia < ukuran file × [MEMORY_FACTOR]
     * (model dimuat + KV cache + ruang kerja allocator).
     */
    private fun checkMemoryBeforeLoad(modelPath: String) {
        val file = File(modelPath)
        if (!file.isFile) {
            throw IllegalStateException("Model file not found: $modelPath")
        }
        val sizeBytes = file.length()
        if (sizeBytes <= 0L) return
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.availMem < sizeBytes * MEMORY_FACTOR) {
            throw IllegalStateException(
                "Not enough free memory to load this model " +
                    "(${humanBytes(sizeBytes)} file needs ~${humanBytes((sizeBytes * MEMORY_FACTOR).toLong())}, " +
                    "available ${humanBytes(info.availMem)}). Try a smaller model, reduce the context " +
                    "size, or use a cloud provider."
            )
        }
    }
}
