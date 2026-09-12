// Open Chat AI — JNI wrapper di atas llama.cpp b10919.
// Ekspos: load model GGUF, generate streaming (callback per token UTF-8 lengkap),
// reset konteks, posisi konteks. Thread-safety diatur di sisi Kotlin (LlamaEngine).

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <algorithm>
#include <android/log.h>

#include "llama.h"

#define TAG "openchai-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model*    model = nullptr;
    llama_context*  ctx   = nullptr;
    const llama_vocab* vocab = nullptr; // borrowed dari model
    uint32_t        nCtx = 0;
    int             pos  = 0;           // jumlah token di KV cache
};

void throwJava(JNIEnv* env, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg);
}

std::string toStdString(JNIEnv* env, jstring js) {
    if (js == nullptr) return {};
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s = (c != nullptr) ? c : "";
    if (c != nullptr) env->ReleaseStringUTFChars(js, c);
    return s;
}

// Evaluasi token ke KV cache dalam chunk n_batch.
bool evalTokens(Session* s, llama_token* tokens, int n) {
    const int32_t nBatch = 512;
    for (int i = 0; i < n; i += nBatch) {
        const int chunk = std::min(nBatch, n - i);
        llama_batch b = llama_batch_get_one(tokens + i, chunk);
        if (llama_decode(s->ctx, b) != 0) return false;
        s->pos += chunk;
    }
    return true;
}

// Kirim satu byte-sequence UTF-8 lengkap ke callback Kotlin. Return false = user minta stop.
bool callCallback(JNIEnv* env, jobject cb, jmethodID mid, const char* data, size_t len) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (arr == nullptr) return false;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(data));
    jboolean cont = env->CallBooleanMethod(cb, mid, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) return false;
    return cont == JNI_TRUE;
}

// Panjang (byte) sequence UTF-8 yang diawali byte c; 0 = byte tidak valid.
size_t utf8SeqLen(unsigned char c) {
    if ((c & 0x80u) == 0x00u) return 1;
    if ((c & 0xE0u) == 0xC0u) return 2;
    if ((c & 0xF0u) == 0xE0u) return 3;
    if ((c & 0xF8u) == 0xF0u) return 4;
    return 0;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_openchai_core_llm_LlamaBridge_backendInit(JNIEnv* env, jobject) {
    static bool done = false;
    if (!done) {
        llama_backend_init();
        done = true;
        LOGI("llama backend initialized");
    }
}

JNIEXPORT jlong JNICALL
Java_com_openchai_core_llm_LlamaBridge_loadModel(JNIEnv* env, jobject, jstring jPath,
                                                 jint contextSize, jint threads) {
    const std::string path = toStdString(env, jPath);
    if (path.empty()) {
        throwJava(env, "Model path is empty");
        return 0;
    }

    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-only di Android

    llama_model* model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        throwJava(env, "Failed to load GGUF model (file missing or unsupported)");
        return 0;
    }

    auto cparams = llama_context_default_params();
    cparams.n_ctx     = (contextSize > 0) ? static_cast<uint32_t>(contextSize) : 2048u;
    cparams.n_batch   = 512u;
    cparams.n_seq_max = 1u;
    const int nThreads = (threads > 0) ? threads : 2;
    cparams.n_threads      = nThreads;
    cparams.n_threads_batch = nThreads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        LOGE("failed to create context (out of memory?)");
        throwJava(env, "Failed to create llama.cpp context (device out of memory — try a smaller model or lower context size)");
        return 0;
    }

    auto* s = new Session();
    s->model = model;
    s->ctx   = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->nCtx  = cparams.n_ctx;
    s->pos   = 0;

    LOGI("model loaded: %s ctx=%u threads=%d", path.c_str(), s->nCtx, nThreads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_openchai_core_llm_LlamaBridge_freeModel(JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    auto* s = reinterpret_cast<Session*>(handle);
    if (s->ctx != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
    delete s;
    LOGI("model freed");
}

// Generate streaming. Return jumlah token yang dihasilkan.
JNIEXPORT jint JNICALL
Java_com_openchai_core_llm_LlamaBridge_generate(JNIEnv* env, jobject, jlong handle,
                                                jstring jPrompt, jint maxTokens,
                                                jfloat temperature, jfloat topP, jlong seed,
                                                jboolean addSpecial, jobject callback) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr || s->model == nullptr || s->ctx == nullptr) {
        throwJava(env, "Model is not loaded");
        return 0;
    }

    jclass cbCls = env->GetObjectClass(callback);
    jmethodID cbMid = env->GetMethodID(cbCls, "onToken", "([B)Z");
    if (cbMid == nullptr) {
        throwJava(env, "Callback method onToken([B)Z not found");
        return 0;
    }

    const std::string prompt = toStdString(env, jPrompt);
    if (prompt.empty()) {
        throwJava(env, "Prompt is empty");
        return 0;
    }

    // ---- Tokenize prompt ----
    const int32_t maxPrompt = 8192;
    std::vector<llama_token> tokens(static_cast<size_t>(maxPrompt));
    int nPrompt = llama_tokenize(s->vocab, prompt.c_str(),
                                 static_cast<int32_t>(prompt.size()),
                                 tokens.data(), maxPrompt,
                                 addSpecial == JNI_TRUE, true);
    if (nPrompt < 0) {
        // Negatif = butuh buffer sebesar itu; clamp ke kapasitas.
        nPrompt = std::min(-nPrompt, maxPrompt);
    }
    if (nPrompt <= 0) {
        throwJava(env, "Failed to tokenize prompt");
        return 0;
    }

    int limit = maxTokens > 0 ? maxTokens : 256;

    // ---- Auto-reset KV cache bila konteks penuh ----
    if (s->pos + nPrompt + limit + 8 > static_cast<int>(s->nCtx)) {
        LOGI("context full (pos=%d) — clearing KV cache", s->pos);
        llama_memory_clear(llama_get_memory(s->ctx), true);
        s->pos = 0;
        if (nPrompt + 8 >= static_cast<int>(s->nCtx)) {
            throwJava(env, "Prompt too long for context size");
            return 0;
        }
        if (nPrompt + limit + 8 > static_cast<int>(s->nCtx)) {
            limit = static_cast<int>(s->nCtx) - nPrompt - 8;
        }
    }

    // ---- Eval prompt ----
    if (!evalTokens(s, tokens.data(), nPrompt)) {
        throwJava(env, "Failed to evaluate prompt (context overflow)");
        return 0;
    }

    // ---- Sampler chain ----
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* chain = llama_sampler_chain_init(sparams);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP > 0.0f ? topP : 0.9f, 1));
        const uint32_t sseed = (seed <= 0) ? 0xFFFFFFFFu : static_cast<uint32_t>(seed);
        llama_sampler_chain_add(chain, llama_sampler_init_dist(sseed));
    }

    // ---- Loop generasi ----
    int generated = 0;
    std::string pending; // buffer UTF-8 (menahan sequence belum lengkap)
    bool stopped = false;

    while (generated < limit && !stopped) {
        const llama_token tok = llama_sampler_sample(chain, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;

        char buf[512];
        const int n = llama_token_to_piece(s->vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) {
            pending.append(buf, static_cast<size_t>(n));
            size_t off = 0;
            while (off < pending.size() && !stopped) {
                const size_t need = utf8SeqLen(static_cast<unsigned char>(pending[off]));
                if (need == 0) { off += 1; continue; }              // byte invalid, lewati
                if (off + need > pending.size()) break;              // sequence belum lengkap
                if (!callCallback(env, callback, cbMid, pending.data() + off, need)) {
                    stopped = true;
                }
                off += need;
            }
            pending.erase(0, off);
        }
        generated++;

        if (!stopped) {
            llama_token one = tok;
            llama_batch b = llama_batch_get_one(&one, 1);
            if (llama_decode(s->ctx, b) != 0) break;
            s->pos += 1;
        }
    }

    // Siram sisa buffer saat stream berakhir (biar tidak ada karakter hilang).
    if (!pending.empty() && !stopped) {
        callCallback(env, callback, cbMid, pending.data(), pending.size());
    }

    llama_sampler_free(chain);
    return generated;
}

JNIEXPORT void JNICALL
Java_com_openchai_core_llm_LlamaBridge_resetContext(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s == nullptr || s->ctx == nullptr) return;
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->pos = 0;
    LOGI("context cleared");
}

JNIEXPORT jint JNICALL
Java_com_openchai_core_llm_LlamaBridge_contextPosition(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    return (s == nullptr) ? 0 : s->pos;
}

} // extern "C"
