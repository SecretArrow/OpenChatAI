package com.openchatai.app.ai

import android.content.Context
import com.openchai.core.agent.CommandRunner
import com.openchai.core.linux.LinuxEnvManager
import com.openchai.core.linux.LinuxEnvPhase
import com.openchai.core.runtime.ManagedProcess
import com.openchai.core.runtime.ProcState
import com.openchai.core.runtime.ProcessSupervisor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Hasil percobaan menyalakan `ollama serve` di dalam sandbox Linux. */
sealed interface OllamaStartResult {
    /** Server berjalan; pid bila diketahui, selain itu id proses supervisor. */
    data class Started(val pidOrId: String?) : OllamaStartResult
    /** Lingkungan Linux belum siap — user harus menyelesaikan setup dulu. */
    data class EnvNotReady(val reason: String) : OllamaStartResult
    /** ollama tidak ada di dalam sandbox — arahkan ke Terminal. */
    data class NotInstalled(val reason: String) : OllamaStartResult
    /** Gagal start / proses mati sebelum siap (reason berisi potongan output). */
    data class Failed(val reason: String) : OllamaStartResult
}

/**
 * Menyalakan Ollama DI DALAM sandbox Linux (proot): `ollama serve` dijalankan via
 * [ProcessSupervisor] sehingga kehidupan proses + output terpantau. Ketersediaan
 * binary dicek via [CommandRunner] (delegating → sandbox Linux bila READY).
 * Tidak ada import UI — dipakai AppContainer/ChatViewModel.
 */
class OllamaLauncher(
    private val context: Context,
    private val linuxEnv: LinuxEnvManager,
    private val supervisor: ProcessSupervisor,
    private val runner: CommandRunner
) {
    companion object {
        private const val WORKSPACE_DIR = "/home/user/workspace"
        private const val SERVE_COMMAND = "ollama serve"
        private const val INSTALL_CHECK_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 400L
        private const val OUTPUT_SNIPPET_MAX = 600

        /** Marker output yang menandakan HTTP server Ollama sudah listening. */
        private val READY_MARKERS = listOf("Listening", "serving HTTP")
    }

    /** True bila binary `ollama` tersedia di dalam sandbox (butuh fase READY). */
    suspend fun isInstalledInSandbox(): Boolean {
        if (linuxEnv.status.value != LinuxEnvPhase.READY) return false
        return try {
            withContext(Dispatchers.IO) {
                runner.run("command -v ollama", WORKSPACE_DIR, INSTALL_CHECK_TIMEOUT_MS).exitCode == 0
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Siapkan lalu jalankan `ollama serve` di sandbox dan tunggu sampai siap:
     * - env belum READY → [OllamaStartResult.EnvNotReady]
     * - ollama tak terpasang → [OllamaStartResult.NotInstalled] (dengan instruksi apt)
     * - output berisi marker "Listening" (atau proses tetap hidup sampai timeout)
     *   → [OllamaStartResult.Started]
     * - proses mati sebelum siap → [OllamaStartResult.Failed] + potongan output.
     */
    suspend fun startAndAwait(timeoutMs: Long = 20_000L): OllamaStartResult {
        if (linuxEnv.status.value != LinuxEnvPhase.READY) {
            return OllamaStartResult.EnvNotReady(
                "Embedded Linux is not set up yet — open Settings → Linux Environment."
            )
        }
        if (!isInstalledInSandbox()) {
            return OllamaStartResult.NotInstalled(
                "Ollama is not installed in the Linux sandbox. In Terminal run: apt install ollama (or download the binary), then retry."
            )
        }

        // Reuse: bila `ollama serve` lama masih hidup, jangan dobel-start
        // (port 11434 akan bentrok dan proses baru langsung mati).
        findAliveServe()?.let { existing ->
            return OllamaStartResult.Started(existing.pid?.toString() ?: existing.id)
        }

        val proc: ManagedProcess = try {
            withContext(Dispatchers.IO) { supervisor.start(SERVE_COMMAND, WORKSPACE_DIR, false) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return OllamaStartResult.Failed("Failed to start ollama serve: ${e.message ?: e.toString()}")
        }

        // Poll sampai marker siap / proses mati / timeout.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val out = supervisor.outputFor(proc.id).orEmpty()
            if (READY_MARKERS.any { out.contains(it, ignoreCase = true) }) {
                return OllamaStartResult.Started(findServe(proc.id)?.pid?.toString() ?: proc.id)
            }
            val snap = findServe(proc.id)
            if (snap == null || isDead(snap)) {
                return OllamaStartResult.Failed(failedReason("ollama serve exited before it was ready", out))
            }
        }

        // Timeout: proses masih hidup → anggap Started (beberapa build tidak mencetak marker).
        val snap = findServe(proc.id)
        return if (snap != null && !isDead(snap)) {
            OllamaStartResult.Started(snap.pid?.toString() ?: proc.id)
        } else {
            OllamaStartResult.Failed(
                failedReason("ollama serve did not become ready within ${timeoutMs / 1000}s", supervisor.outputFor(proc.id).orEmpty())
            )
        }
    }

    /** Proses `ollama serve` yang masih hidup (apa pun id-nya). */
    private fun findAliveServe(): ManagedProcess? =
        supervisor.processes.value.firstOrNull { p -> p.command.startsWith(SERVE_COMMAND) && !isDead(p) }

    private fun findServe(id: String): ManagedProcess? =
        supervisor.processes.value.firstOrNull { it.id == id }

    /** Definisi "mati": sudah ada endedAt atau state akhir EXITED/FAILED/STOPPED. */
    private fun isDead(p: ManagedProcess): Boolean =
        p.endedAt != null || p.state == ProcState.EXITED || p.state == ProcState.FAILED || p.state == ProcState.STOPPED

    /** Alasan kegagalan + potongan output terakhir (truncated) agar penyebab terlihat. */
    private fun failedReason(prefix: String, output: String): String {
        val snippet = output.trim().takeLast(OUTPUT_SNIPPET_MAX)
        return if (snippet.isBlank()) "$prefix (no output captured)."
        else "$prefix — last output:\n$snippet"
    }
}
