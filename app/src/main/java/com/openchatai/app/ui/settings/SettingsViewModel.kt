package com.openchatai.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openchatai.app.OpenChatApp
import com.openchai.core.model.ProviderId
import com.openchai.core.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as OpenChatApp).container

    val settings = container.settingsRepository.settings

    /** Terapkan transformasi ke [AppSettings] dan persist ke DataStore. */
    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            container.settingsRepository.update(transform)
        }
    }

    // ------------------------------------------------------------------
    // API key (SecureStore)
    // ------------------------------------------------------------------

    /**
     * Simpan API key ke SecureStore. Callback [onSaved] dipanggil HANYA bila
     * penyimpanan sukses — dipakai UI untuk feedback Toast "API key tersimpan".
     */
    fun saveApiKey(provider: ProviderId, value: String, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            container.settingsRepository.setApiKey(provider, value.trim())
            // viewModelScope berjalan di Dispatchers.Main → aman untuk Toast/UI.
            onSaved()
        }
    }

    /**
     * Baca API key tersimpan (dipakai bila UI ingin menampilkan/prefill manual).
     * Sengaja TIDAK dipanggil otomatis saat compose.
     */
    fun apiKey(provider: ProviderId, onLoaded: (String) -> Unit) {
        viewModelScope.launch {
            val key = runCatching { container.settingsRepository.apiKey(provider) }
                .getOrDefault("")
            onLoaded(key)
        }
    }

    // ------------------------------------------------------------------
    // Tes koneksi
    // ------------------------------------------------------------------

    fun testOllama(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                withTimeout(TEST_TIMEOUT_MS) { container.providerRegistry.ollama.testConnection() }
            }.getOrDefault(false)
            onResult(ok)
        }
    }

    /** Health check OpenCode engine (AgentEngine.healthCheck). */
    fun testOpenCode(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                withTimeout(TEST_TIMEOUT_MS) { container.openCodeRuntime.healthCheck() }
            }.getOrDefault(false)
            onResult(ok)
        }
    }

    // ------------------------------------------------------------------
    // Status runtime (bagian RUNTIME di Settings)
    // ------------------------------------------------------------------

    /**
     * Kumpulkan status runtime: Node.js / Python / Git / Curl di PATH + kesehatan
     * endpoint Ollama. Hasil berupa daftar (nama, ok, detail).
     *
     * Catatan implementasi: deteksi tool memakai `which` lewat ProcessBuilder
     * (shell yang sama dengan yang dipakai terminal). Modul ShellEnvironment dari
     * lapisan runtime bisa menjadi pengganti internal fungsi ini tanpa mengubah
     * kontrak [runtimeStatus].
     */
    fun runtimeStatus(onResult: (List<Triple<String, Boolean, String>>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = mutableListOf<Triple<String, Boolean, String>>()

            listOf(
                "Node.js" to listOf("node"),
                "Python" to listOf("python3", "python"),
                "Git" to listOf("git"),
                "Curl" to listOf("curl")
            ).forEach { (label, binaries) ->
                val found = binaries.firstNotNullOfOrNull { which(it) }
                rows += if (found != null) {
                    Triple(label, true, found)
                } else {
                    Triple(label, false, "Not found in PATH")
                }
            }

            val ollamaOk = runCatching {
                withTimeout(TEST_TIMEOUT_MS) { container.providerRegistry.ollama.testConnection() }
            }.getOrDefault(false)
            rows += if (ollamaOk) {
                Triple("Ollama", true, "Endpoint reachable")
            } else {
                Triple("Ollama", false, "Unreachable")
            }

            withContext(Dispatchers.Main) { onResult(rows.toList()) }
        }
    }

    private fun which(binary: String): String? = try {
        val process = ProcessBuilder("which", binary).start()
        val path = process.inputStream.bufferedReader().use { it.readText().trim() }
        process.waitFor()
        if (process.exitValue() == 0 && path.isNotBlank()) path else null
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------
    // Proses latar belakang
    // ------------------------------------------------------------------

    /** Buang entri proses yang sudah selesai dari daftar supervisor. */
    fun pruneProcesses() {
        container.processSupervisor.pruneFinished()
    }

    /** Prune + refresh (onDone dipanggil di main thread untuk memicu reload status). */
    fun restartRuntime(onDone: () -> Unit) {
        viewModelScope.launch {
            container.processSupervisor.pruneFinished()
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 8_000L
    }
}
