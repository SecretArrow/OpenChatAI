package com.openchai.core.linux

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
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Implementasi [ProcessSupervisor] + [CommandRunner] berbasis proot untuk
 * v1.8.0 (Embedded Linux). Semua proses dijalankan di dalam userspace Ubuntu
 * milik [LinuxEnvManager] lewat `/bin/bash -c` (pola AndroidProcessManager).
 *
 * - Output tiap proses disimpan pada ring buffer 256 ribu karakter per id,
 *   dengan counter revisi (StateFlow<Long>) yang naik tiap batch output baru
 *   sebagai pemicu refresh UI.
 * - cwd host dipetakan lewat [LinuxEnvManager.guestWorkspaceBind]: bila cwd
 *   adalah direktori Android nyata, direktori itu di-bind ke
 *   /home/user/workspace (bind TERAKHIR menimpa workspace internal rootfs)
 *   sehingga command berjalan PADA workspace Android aktif; bila tidak bisa
 *   di-bind, perilaku lama tetap dipakai (workspace internal rootfs).
 * - pid tidak tersedia (di dalam proot) dan detectedPort tidak dideteksi.
 * - Saat lingkungan READY, cron in-app (LinuxCron) ikut dijalankan; fase
 *   lain menghentikannya.
 */
class LinuxProcessSupervisor(
    private val env: LinuxEnvManager,
    parentScope: CoroutineScope
) : ProcessSupervisor, CommandRunner {

    /** Scope anak dari parentScope; otomatis selesai bila parent dibatalkan. */
    private val scope =
        CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)

    override val processes: StateFlow<List<ManagedProcess>>
        get() = _processes.asStateFlow()

    private val _processes = MutableStateFlow<List<ManagedProcess>>(emptyList())

    // State pendukung per proses (id -> data). ManagedProcess immutable,
    // sehingga command/autoRestart disimpan terpisah untuk keperluan restart.
    private val specs = ConcurrentHashMap<String, ProcSpec>()
    private val handles = ConcurrentHashMap<String, Process>()
    private val stdins = ConcurrentHashMap<String, OutputStream>()
    private val outputBuffers = ConcurrentHashMap<String, StringBuilder>()
    private val outputRevisions = ConcurrentHashMap<String, MutableStateFlow<Long>>()
    private val userStopped = ConcurrentHashMap<String, Boolean>()
    private val monitorJobs = ConcurrentHashMap<String, Job>()

    /** Generasi runtime per id: naik saat restart agar monitor lama diam. */
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    /** Cron in-app: dijalankan saat lingkungan READY, dihentikan selain itu. */
    private val cron = LinuxCron(env, this, parentScope)

    private data class ProcSpec(
        val command: String,
        val autoRestart: Boolean,
        /** Bind host workspace (hasil guestWorkspaceBind) — dipakai ulang saat restart. */
        val bind: String?
    )

    init {
        // Pantau status lingkungan: READY → cron jalan; fase lain → cron berhenti.
        scope.launch {
            env.status.collect { phase ->
                if (phase == LinuxEnvPhase.READY) cron.start() else cron.stop()
            }
        }
        // Teardown hook: hentikan semua proses saat lingkungan Linux dilepas.
        env.teardownHooks.add { stopAllProcesses() }
    }

    // ------------------------------------------------------------------
    // ProcessSupervisor
    // ------------------------------------------------------------------

    override fun start(command: String, cwd: String, autoRestart: Boolean): ManagedProcess {
        // cwd host dipetakan ke bind host (bila direktori Android nyata) agar
        // proses latar berjalan PADA workspace aktif; else workspace internal.
        val bind = env.guestWorkspaceBind(cwd)
        val id = UUID.randomUUID().toString()
        val entry = ManagedProcess(
            id = id,
            command = command,
            cwd = LinuxShell.WORKSPACE_DIR,
            pid = null,          // PID host tidak bermakna di dalam proot
            state = ProcState.STARTING,
            autoRestart = autoRestart,
            detectedPort = null  // deteksi port tidak berlaku di userspace proot
        )
        specs[id] = ProcSpec(command, autoRestart, bind)
        userStopped[id] = false
        generations[id] = AtomicLong(0)
        outputBuffers[id] = StringBuilder()
        outputRevisions[id] = MutableStateFlow(0L)
        _processes.update { it + entry }
        launchRuntime(id, command, bind)
        return entry
    }

    /**
     * Hentikan proses: destroy() (proot --kill-on-exit membersihkan tracee).
     * State akhir (STOPPED karena user, EXITED/FAILED bila mati sendiri),
     * endedAt dan exitCode diisi monitor saat waitFor selesai ("bila bisa").
     */
    override fun stop(id: String) {
        userStopped[id] = true
        val process = handles[id] ?: return
        try {
            process.destroy()
        } catch (_: Exception) {
        }
        // Pastikan benar-benar berhenti: tunggu sebentar, lalu paksa.
        scope.launch {
            try {
                if (!process.waitFor(2_000, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Jalankan ulang command yang sama dengan id yang sama:
     * state RESTARTING → RUNNING. Monitor proses lama dinonaktifkan lewat
     * penomoran generasi agar tidak menimpa state proses yang baru.
     */
    override fun restart(id: String) {
        val spec = specs[id] ?: return
        generations[id]?.incrementAndGet()
        updateEntry(id) {
            it.copy(state = ProcState.RESTARTING, endedAt = null, exitCode = null)
        }
        // Matikan proses lama bila masih hidup.
        handles[id]?.let { runCatching { it.destroy() } }
        userStopped[id] = false
        scope.launch {
            delay(RESTART_DELAY_MS) // beri waktu proot membersihkan tracee lama
            launchRuntime(id, spec.command, spec.bind)
        }
    }

    override fun outputFor(id: String): String? {
        val buffer = outputBuffers[id] ?: return null
        return synchronized(buffer) { buffer.toString() }
    }

    override fun outputRevision(id: String): StateFlow<Long> =
        outputRevisions.getOrPut(id) { MutableStateFlow(0L) }

    /** Buang entri EXITED/STOPPED/FAILED yang sudah berakhir > 30 menit. */
    override fun pruneFinished() {
        val cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS
        var removed: List<String> = emptyList()
        _processes.update { list ->
            val (keep, drop) = list.partition { p ->
                p.state == ProcState.RUNNING || p.state == ProcState.RESTARTING ||
                    p.state == ProcState.STARTING ||
                    p.endedAt == null || p.endedAt > cutoff
            }
            removed = drop.map { it.id }
            keep
        }
        for (id in removed) {
            specs.remove(id)
            handles.remove(id)
            stdins.remove(id)
            outputBuffers.remove(id)
            outputRevisions.remove(id)
            userStopped.remove(id)
            monitorJobs.remove(id)
            generations.remove(id)
        }
    }

    /** Tulis satu baris ke stdin proses latar belakang (newline otomatis). */
    override fun writeStdin(id: String, line: String) {
        val stream = stdins[id] ?: return
        scope.launch {
            try {
                val text = if (line.endsWith("\n")) line else line + "\n"
                synchronized(stream) {
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            } catch (_: Exception) {
                // Proses sudah mati / stream ditutup — abaikan.
            }
        }
    }

    // ------------------------------------------------------------------
    // Runtime per proses
    // ------------------------------------------------------------------

    private fun launchRuntime(id: String, command: String, bind: String?) {
        // Rekam generasi saat ini; bila restart menaikkan generasi,
        // monitor lama tidak boleh mengubah state entri lagi.
        val generation = generations.getOrPut(id) { AtomicLong(0) }.get()
        val job = scope.launch {
            // Prefix `cd` sebagai pengaman: command user selalu dari workspace
            // (cwd guest = WORKSPACE_DIR; `2>/dev/null` agar gagal cd tidak
            // berisik). Bind host (bila ada) menimpa direktori tersebut dengan
            // workspace Android asli.
            val shellCommand = "cd ${LinuxShell.WORKSPACE_DIR} 2>/dev/null; $command"
            val process = try {
                ProcessBuilder(
                    env.prootBinary().absolutePath,
                    *env.prootArgs(LinuxShell.WORKSPACE_DIR, bind).toTypedArray(),
                    "/bin/bash", "-c", shellCommand
                ).apply {
                    environment().putAll(env.envEnvVars())
                    redirectErrorStream(false)
                }.start()
            } catch (e: Exception) {
                markFailed(id, e.message)
                return@launch
            }
            handles[id] = process
            process.outputStream?.let { stdins[id] = it }
            if (userStopped[id] == true) {
                // stop() dipanggil saat proses masih STARTING — hentikan segera.
                runCatching { process.destroy() }
            }
            updateEntry(id) {
                it.copy(
                    state = ProcState.RUNNING,
                    startedAt = System.currentTimeMillis(),
                    endedAt = null,
                    exitCode = null
                )
            }

            // Dua reader paralel (stdout & stderr) agar pipe tidak penuh
            // dan proses tidak deadlock.
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
            // Lepas handle hanya bila masih merujuk proses ini
            // (bukan proses baru hasil restart).
            handles.remove(id, process)
            process.outputStream?.let { stream -> stdins.remove(id, stream) }

            // State akhir hanya ditulis bila generasi masih sama (tidak di-restart):
            // STOPPED bila user yang menghentikan, EXITED bila rc=0, FAILED selain itu.
            if (generations[id]?.get() == generation) {
                val endedAt = System.currentTimeMillis()
                val finalState = when {
                    userStopped[id] == true -> ProcState.STOPPED
                    exitCode == 0 -> ProcState.EXITED
                    else -> ProcState.FAILED
                }
                updateEntry(id) {
                    it.copy(state = finalState, endedAt = endedAt, exitCode = exitCode)
                }
            }
        }
        monitorJobs[id] = job
    }

    private fun markFailed(id: String, message: String?) {
        updateEntry(id) {
            it.copy(
                state = ProcState.FAILED,
                endedAt = System.currentTimeMillis(),
                exitCode = -1
            )
        }
        if (!message.isNullOrBlank()) {
            appendOutput(id, "[openchai] gagal memulai proses: $message\n")
        }
    }

    /** Baca satu stream output sampai EOF, append per batch ke ring buffer. */
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

    /**
     * Append batch output ke ring buffer 256k per id + naikkan revisi
     * (pola AndroidProcessManager): revisi naik tiap batch append.
     */
    private fun appendOutput(id: String, raw: String) {
        if (raw.isEmpty()) return
        val normalized = raw.replace("\r\n", "\n").replace(Regex("\r+"), "\n")
        val buffer = outputBuffers[id] ?: return
        synchronized(buffer) {
            buffer.append(normalized)
            if (buffer.length > MAX_BUFFER_CHARS) {
                // Ring buffer: buang paruh awal agar hemat memori.
                buffer.delete(0, buffer.length - MAX_BUFFER_CHARS / 2)
            }
        }
        outputRevisions[id]?.let { rev -> rev.value = rev.value + 1 }
    }

    private fun updateEntry(id: String, transform: (ManagedProcess) -> ManagedProcess) {
        _processes.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** Untuk teardown hook: hentikan seluruh proses yang masih tercatat. */
    private fun stopAllProcesses() {
        for ((id, process) in handles) {
            userStopped[id] = true
            runCatching { process.destroy() }
        }
    }

    // ------------------------------------------------------------------
    // CommandRunner (satu pintu eksekusi untuk agent di dalam proot)
    // ------------------------------------------------------------------

    override suspend fun run(command: String, cwd: String?, timeoutMs: Long): CommandResult =
        withContext(Dispatchers.IO) {
            // cwd host dipetakan ke bind host (bila direktori nyata); bila tidak
            // bisa di-bind, command tetap jalan di workspace internal rootfs
            // (perilaku lama).
            val bind = env.guestWorkspaceBind(cwd)
            env.execOnce(command, LinuxShell.WORKSPACE_DIR, timeoutMs, bind)
        }

    companion object {
        private const val MAX_BUFFER_CHARS = 256_000
        private const val PRUNE_AFTER_MS = 30 * 60 * 1000L
        private const val RESTART_DELAY_MS = 500L
    }
}
