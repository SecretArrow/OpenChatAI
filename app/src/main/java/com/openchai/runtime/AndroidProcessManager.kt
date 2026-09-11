package com.openchai.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.openchai.app.OpenChatApp
import com.openchai.app.runtime.ProcessService
import com.openchai.core.agent.CommandResult
import com.openchai.core.agent.CommandRunner
import com.openchai.core.runtime.ManagedProcess
import com.openchai.core.runtime.ProcState
import com.openchai.core.runtime.ProcessSupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Implementasi [ProcessSupervisor] + [com.openchai.core.agent.CommandRunner]
 * di Android. Semua proses dijalankan via `sh -c` dengan environment dari
 * [ShellEnvironment].
 *
 * - Output tiap proses disimpan pada ring buffer 200 ribu karakter.
 * - Proses RUNNING memicu foreground service [ProcessService] agar tidak
 *   dimatikan sistem saat aplikasi di latar belakang.
 */
class AndroidProcessManager(private val context: Context) : ProcessSupervisor, CommandRunner {

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val shellEnv = ShellEnvironment(context)

    override val processes: StateFlow<List<ManagedProcess>>
        get() = _processes.asStateFlow()

    private val _processes = MutableStateFlow<List<ManagedProcess>>(emptyList())

    // State pendukung per proses (id -> data). ManagedProcess immutable,
    // sehingga command/cwd/autoRestart disimpan terpisah untuk keperluan restart.
    private val specs = ConcurrentHashMap<String, ProcessSpec>()
    private val handles = ConcurrentHashMap<String, Process>()
    private val runtimeJobs = ConcurrentHashMap<String, Job>()
    private val outputBuffers = ConcurrentHashMap<String, StringBuilder>()
    private val outputRevisions = ConcurrentHashMap<String, MutableStateFlow<Long>>()
    private val userStopFlags = ConcurrentHashMap<String, Boolean>()
    private val consecutiveRestarts = ConcurrentHashMap<String, Int>()
    private val crashNoted = ConcurrentHashMap.newKeySet<String>()

    /** Flag foreground service agar tidak start/stop berulang-ulang. */
    @Volatile
    private var foregroundActive = false

    private data class ProcessSpec(
        val command: String,
        val cwd: String,
        val autoRestart: Boolean
    )

    // ------------------------------------------------------------------
    // ProcessSupervisor
    // ------------------------------------------------------------------

    override fun start(command: String, cwd: String, autoRestart: Boolean): ManagedProcess {
        val id = newId()
        val dir = cwd.ifBlank { shellEnv.workspaceRoot() ?: context.filesDir.absolutePath }
        val entry = ManagedProcess(
            id = id,
            command = command,
            cwd = dir,
            state = ProcState.STARTING,
            autoRestart = autoRestart
        )
        specs[id] = ProcessSpec(command, dir, autoRestart)
        userStopFlags[id] = false
        consecutiveRestarts[id] = 0
        outputBuffers[id] = StringBuilder()
        outputRevisions[id] = MutableStateFlow(0L)
        crashNoted.remove(id)
        _processes.update { it + entry }

        launchRuntime(id, command, dir, autoRestart)
        return entry
    }

    override fun stop(id: String) {
        userStopFlags[id] = true
        val process = handles[id] ?: return
        try {
            process.destroy()
        } catch (_: Exception) {
        }
        scope.launch {
            delay(1500)
            try {
                if (process.isAlive) process.destroyForcibly()
            } catch (_: Exception) {
            }
        }
    }

    override fun restart(id: String) {
        val spec = specs[id] ?: return
        scope.launch {
            stop(id)
            delay(300)
            // start() membuat id baru; ManagedProcess immutable — UI refresh dari list.
            start(spec.command, spec.cwd, spec.autoRestart)
        }
    }

    override fun outputFor(id: String): String? {
        val buffer = outputBuffers[id] ?: return null
        return synchronized(buffer) { buffer.toString() }
    }

    override fun outputRevision(id: String): StateFlow<Long> =
        outputRevisions.getOrPut(id) { MutableStateFlow(0L) }

    override fun pruneFinished() {
        val cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS
        var removed: List<String> = emptyList()
        _processes.update { list ->
            val (keep, drop) = list.partition { p ->
                p.state == ProcState.RUNNING || p.state == ProcState.RESTARTING ||
                    p.endedAt == null || p.endedAt > cutoff
            }
            removed = drop.map { it.id }
            keep
        }
        if (removed.isEmpty()) return
        for (id in removed) {
            handles.remove(id)
            specs.remove(id)
            runtimeJobs.remove(id)
            outputBuffers.remove(id)
            outputRevisions.remove(id)
            userStopFlags.remove(id)
            consecutiveRestarts.remove(id)
            crashNoted.remove(id)
        }
    }

    // ------------------------------------------------------------------
    // Runtime loop per proses (termasuk auto-restart)
    // ------------------------------------------------------------------

    private fun launchRuntime(id: String, command: String, cwd: String, autoRestart: Boolean) {
        val job = scope.launch {
            while (true) {
                val process = try {
                    ProcessBuilder("sh", "-c", command)
                        .apply {
                            directory(File(cwd))
                            environment().putAll(shellEnv.buildEnvironment(extraPath()))
                            redirectErrorStream(false)
                        }
                        .start()
                } catch (e: Exception) {
                    markFailed(id, e.message)
                    return@launch
                }
                handles[id] = process
                val startedAt = System.currentTimeMillis()
                val pid = extractPid(process)
                userStopFlags[id] = false
                updateEntry(id) {
                    it.copy(
                        pid = pid,
                        state = ProcState.RUNNING,
                        startedAt = startedAt,
                        endedAt = null,
                        exitCode = null
                    )
                }
                updateForeground()

                val outJob = launch { readStream(id, process.inputStream) }
                val errJob = launch { readStream(id, process.errorStream) }

                val exitCode = try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    -1
                }

                // Lepaskan reader agar tidak menggantung pada pipe grand-child.
                outJob.cancel()
                errJob.cancel()
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
                handles.remove(id)

                if (System.currentTimeMillis() - startedAt > STABLE_RUNTIME_MS) {
                    consecutiveRestarts[id] = 0
                }

                val endedAt = System.currentTimeMillis()
                if (userStopFlags[id] == true) {
                    updateEntry(id) {
                        it.copy(state = ProcState.STOPPED, endedAt = endedAt, exitCode = exitCode)
                    }
                    updateForeground()
                    return@launch
                }

                val finalState = if (exitCode == 0) ProcState.EXITED else ProcState.FAILED
                updateEntry(id) {
                    it.copy(state = finalState, endedAt = endedAt, exitCode = exitCode)
                }
                updateForeground()

                if (autoRestart && (consecutiveRestarts[id] ?: 0) < MAX_RESTARTS) {
                    consecutiveRestarts[id] = (consecutiveRestarts[id] ?: 0) + 1
                    updateEntry(id) { it.copy(state = ProcState.RESTARTING) }
                    delay(RESTART_DELAY_MS)
                    if (userStopFlags[id] == true) {
                        updateEntry(id) {
                            it.copy(state = ProcState.STOPPED, endedAt = System.currentTimeMillis())
                        }
                        updateForeground()
                        return@launch
                    }
                    continue
                }
                return@launch
            }
        }
        runtimeJobs[id] = job
    }

    private fun markFailed(id: String, message: String?) {
        handles.remove(id)
        val endedAt = System.currentTimeMillis()
        updateEntry(id) {
            it.copy(state = ProcState.FAILED, endedAt = endedAt, exitCode = -1)
        }
        message?.let { appendOutput(id, "[openchat] gagal memulai proses: $it\n") }
        updateForeground()
    }

    private fun extractPid(process: Process): Int? = runCatching {
        val method = try {
            process.javaClass.getMethod("pid")
        } catch (_: NoSuchMethodException) {
            process.javaClass.getDeclaredMethod("pid")
        }
        method.isAccessible = true
        (method.invoke(process) as? Number)?.toInt()
    }.getOrNull()

    // ------------------------------------------------------------------
    // Pembacaan output (ring buffer + deteksi port & crash)
    // ------------------------------------------------------------------

    private suspend fun readStream(id: String, stream: InputStream) {
        try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 8192).use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    appendOutput(id, String(buf, 0, n))
                }
            }
        } catch (_: Exception) {
            // Stream ditutup saat stop/restart — abaikan.
        }
    }

    private fun appendOutput(id: String, raw: String) {
        if (raw.isEmpty()) return
        val normalized = raw.replace("\r\n", "\n").replace(Regex("\r+"), "\n")
        val buffer = outputBuffers[id] ?: return
        synchronized(buffer) {
            buffer.append(normalized)
            if (buffer.length > MAX_BUFFER_CHARS) {
                buffer.delete(0, buffer.length - MAX_BUFFER_CHARS / 2)
            }
        }
        outputRevisions[id]?.let { rev -> rev.value = rev.value + 1 }
        detectPort(id, normalized)
        detectCrash(id, normalized)
    }

    private fun detectPort(id: String, chunk: String) {
        val current = _processes.value.firstOrNull { it.id == id }?.detectedPort
        if (current != null) return // port pertama yang menang
        val match = PORT_REGEX.find(chunk) ?: return
        val port = match.groupValues[1].toIntOrNull() ?: return
        if (port in 1024..65535) {
            updateEntry(id) { it.copy(detectedPort = port) }
        }
    }

    private fun detectCrash(id: String, chunk: String) {
        if (crashNoted.contains(id)) return
        val hit = CRASH_PATTERNS.any { chunk.contains(it, ignoreCase = true) }
        if (hit) {
            crashNoted.add(id)
            appendOutput(id, "[openchat] indikasi crash terdeteksi pada output proses\n")
        }
    }

    private fun updateEntry(id: String, transform: (ManagedProcess) -> ManagedProcess) {
        _processes.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun extraPath(): String = try {
        (context.applicationContext as? OpenChatApp)
            ?.container?.settingsRepository?.settings?.value?.extraPathDirs ?: ""
    } catch (_: Exception) {
        ""
    }

    // ------------------------------------------------------------------
    // Foreground service hook
    // ------------------------------------------------------------------

    private fun updateForeground() {
        val anyRunning = _processes.value.any { it.state == ProcState.RUNNING }
        synchronized(this) {
            if (anyRunning && !foregroundActive) {
                foregroundActive = true
                try {
                    ContextCompat.startForegroundService(
                        context, Intent(context, ProcessService::class.java)
                    )
                } catch (_: Exception) {
                    foregroundActive = false
                }
            } else if (!anyRunning && foregroundActive) {
                foregroundActive = false
                try {
                    context.stopService(Intent(context, ProcessService::class.java))
                } catch (_: Exception) {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // CommandRunner (satu pintu eksekusi untuk agent)
    // ------------------------------------------------------------------

    override suspend fun run(command: String, cwd: String?, timeoutMs: Long): CommandResult =
        withContext(Dispatchers.IO) {
            BLOCKED_PATTERNS.forEach { rule ->
                if (rule.containsMatchIn(command)) {
                    return@withContext CommandResult(-1, "", "Blocked by safety policy")
                }
            }

            val dir = File(
                cwd?.takeIf { it.isNotBlank() }
                    ?: shellEnv.workspaceRoot()
                    ?: context.filesDir.absolutePath
            )
            val process = try {
                ProcessBuilder("sh", "-c", command)
                    .apply {
                        directory(dir)
                        environment().putAll(shellEnv.buildEnvironment(extraPath()))
                        redirectErrorStream(false)
                    }
                    .start()
            } catch (e: Exception) {
                return@withContext CommandResult(-1, "", e.message ?: "Failed to start process")
            }

            // Baca stdout & stderr paralel agar pipe tidak penuh (hindari deadlock).
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = thread(name = "oc-cmd-out", isDaemon = true) {
                drainStream(process.inputStream, stdout)
            }
            val errThread = thread(name = "oc-cmd-err", isDaemon = true) {
                drainStream(process.errorStream, stderr)
            }

            val finished = try {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                false
            }

            if (!finished) {
                process.destroyForcibly()
                outThread.join(1000)
                errThread.join(1000)
                return@withContext CommandResult(-1, stdout.toString(), "Timeout")
            }

            outThread.join(5000)
            errThread.join(5000)
            val exitCode = try {
                process.exitValue()
            } catch (_: Exception) {
                -1
            }
            CommandResult(exitCode, stdout.toString(), stderr.toString())
        }

    private fun drainStream(stream: InputStream, sink: StringBuilder) {
        try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 8192).use { reader ->
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    synchronized(sink) { sink.append(buf, 0, n) }
                }
            }
        } catch (_: Exception) {
            // Proses dimatikan / stream ditutup — abaikan.
        }
    }

    companion object {
        private const val MAX_BUFFER_CHARS = 200_000
        private const val MAX_RESTARTS = 5
        private const val RESTART_DELAY_MS = 2_000L
        private const val STABLE_RUNTIME_MS = 60_000L
        private const val PRUNE_AFTER_MS = 30 * 60 * 1000L

        private val PORT_REGEX = Regex(":(\\d{4,5})\\b")

        private val CRASH_PATTERNS = listOf(
            "FATAL EXCEPTION",
            "FATAL ERROR",
            "Segmentation fault",
            "OutOfMemoryError",
            "EADDRINUSE",
            "Cannot find module",
            "uncaught exception"
        )

        private val BLOCKED_PATTERNS = listOf(
            Regex("rm\\s+-rf\\s+/(\\s|$)"),
            Regex("mkfs"),
            Regex("dd\\s+.*of=/dev/"),
            Regex("reboot"),
            Regex.fromLiteral(":(){ :|:& };:")
        )
    }
}
