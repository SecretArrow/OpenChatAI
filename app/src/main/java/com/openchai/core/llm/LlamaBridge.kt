package com.openchai.core.llm

/**
 * Binding JNI ke llama.cpp (libopenchai_llama.so), dipin di tag b10919.
 *
 * Handle = pointer native Session. Semua operasi pada handle yang sama harus
 * diserialisasi dari sisi Kotlin (lihat [LlamaEngine] yang memakai Mutex).
 * Generasi dijalankan pada thread pemanggil; pembatalan dilakukan dengan
 * mengembalikan `false` dari [TokenCallback.onToken].
 *
 * Pemuatan library bersifat lazy: pemanggil WAJIB melewati [ensureLoaded] /
 * [backendInitOnce] sebelum memanggil external fun mana pun ([LlamaEngine]
 * sudah mematuhi kontrak ini). Kontrak JNI tidak berubah — NAMA SEMUA
 * external fun tetap sama (standard JNI naming, lihat llama_jni.cpp).
 */
object LlamaBridge {

    /** Path absolut libopenchai_llama.so dari runtime pack terpasang (di-set RuntimeManager sebelum pemakaian pertama). */
    @Volatile
    var overrideLibPath: String? = null

    @Volatile
    private var loaded = false

    private val loadLock = Any()

    /**
     * Muat library native tepat satu kali: prioritas runtime pack terpasang
     * (System.load path absolut) dengan fallback otomatis ke library bawaan APK
     * (System.loadLibrary) bila pack gagal dimuat/tidak ada. Idempoten dan
     * thread-safe.
     */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val libOverride = overrideLibPath
            if (libOverride != null) {
                try {
                    System.load(libOverride)
                    loaded = true
                    return
                } catch (_: Throwable) {
                    // Pack gagal dimuat (file rusak / ABI salah) → buang override
                    // dan jatuh ke library bawaan APK.
                    overrideLibPath = null
                }
            }
            System.loadLibrary("openchai_llama")
            loaded = true
        }
    }

    /**
     * Inisialisasi global llama.cpp (idempoten, aman dipanggil berulang).
     * Wajib dipanggil lebih dulu oleh pemanggil external fun: di sini library
     * native dimuat via [ensureLoaded] sebelum init backend.
     */
    fun backendInitOnce() {
        ensureLoaded()
        backendInit()
    }

    private external fun backendInit()

    /**
     * Muat model GGUF dari [path]. Return handle (>0) atau melempar RuntimeException.
     * [contextSize] ukuran KV cache (0/kecil = 2048), [threads] jumlah thread CPU (0 = auto 2).
     */
    external fun loadModel(path: String, contextSize: Int, threads: Int): Long

    external fun freeModel(handle: Long)

    /**
     * Generate streaming dari [prompt] (sudah terformat template chat).
     * Callback dipanggil per sequence UTF-8 lengkap; return false = berhenti.
     * [addSpecial] harus true bila ini prompt pertama setelah reset (BOS).
     * Return jumlah token yang dihasilkan.
     */
    external fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        seed: Long,
        addSpecial: Boolean,
        callback: TokenCallback
    ): Int

    /** Kosongkan KV cache (mulai percakapan baru di memori). */
    external fun resetContext(handle: Long)

    /** Jumlah token yang sedang menempati KV cache. */
    external fun contextPosition(handle: Long): Int
}

/** Callback streaming token dari native. [piece] = bytes UTF-8 yang sudah lengkap. */
fun interface TokenCallback {
    /** Return false untuk menghentikan generasi. */
    fun onToken(piece: ByteArray): Boolean
}
