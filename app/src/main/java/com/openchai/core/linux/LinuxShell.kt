package com.openchai.core.linux

import com.openchai.core.terminal.TerminalHost
import com.openchai.core.terminal.TerminalSessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Implementasi [TerminalHost] berbasis proot untuk v1.8.0 (Embedded Linux).
 *
 * Setiap sesi menjalankan `/bin/bash -li` di dalam userspace Ubuntu (proot)
 * milik [LinuxEnvManager]. Output stdout+stderr digabung ke satu ring buffer
 * 256 ribu karakter per sesi (pola com.openchai.terminal.TerminalManager):
 * reader menulis ke StringBuilder otoritatif, lalu snapshot dipublikasikan
 * ke StateFlow secara berkala agar UI tidak re-render tiap karakter.
 *
 * Catatan perilaku:
 * - Parameter cwd pada createSession DIABAIKAN; shell Linux selalu mulai di
 *   /home/user/workspace (proot sudah mengatur cwd lewat argumen `-w`).
 * - Entri sesi yang prosesnya sudah mati TIDAK dihapus otomatis
 *   (alive=false) agar UI tetap bisa menampilkan output terakhir;
 *   bersihkan lewat [pruneDeadSessions] bila perlu.
 * - PS1 dan variabel lingkungan lain disuplai LinuxEnvManager.envEnvVars().
 */
class LinuxShell(
    private val env: LinuxEnvManager,
    parentScope: CoroutineScope
) : TerminalHost {

    /** Scope anak dari parentScope; otomatis selesai bila parent dibatalkan. */
    private val scope =
        CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)

    override val sessions: StateFlow<List<TerminalSessionInfo>>
        get() = _sessions.asStateFlow()

    private val _sessions = MutableStateFlow<List<TerminalSessionInfo>>(emptyList())

    private val sessionsById = ConcurrentHashMap<String, Session>()

    init {
        // Teardown hook: saat lingkungan Linux dilepas, matikan semua sesi.
        // proot --kill-on-exit otomatis membersihkan tracee (bash + turunannya)
        // begitu proses proot di-destroy.
        env.teardownHooks.add {
            for (session in sessionsById.values) {
                runCatching { session.outputStream?.close() }
                runCatching { session.process?.destroy() }
            }
        }
    }

    /** Record internal per sesi: proses, stream stdin, buffer, flag hidup. */
    private class Session(
        val info: TerminalSessionInfo,
        val process: Process?,
        val outputStream: OutputStream?
    ) {
        val buffer = MutableStateFlow("")

        /** Flag agar peristiwa "mati" hanya dipublikasikan sekali. */
        val deadFlag = AtomicBoolean(process == null)

        @Volatile
        var alive: Boolean = process != null

        /** Isi buffer otoritatif (dirangkum ke [buffer] oleh reader). */
        val combined = StringBuilder()

        @Volatile
        var dirty = false

        fun appendChunk(chunk: String) {
            synchronized(combined) {
                combined.append(chunk)
                if (combined.length > MAX_BUFFER_CHARS) {
                    // Ring buffer: buang paruh awal agar hemat memori.
                    combined.delete(0, combined.length - MAX_BUFFER_CHARS / 2)
                }
                dirty = true
            }
        }

        /** Ambil snapshot terbaru bila ada perubahan; null bila tidak. */
        fun drain(): String? {
            synchronized(combined) {
                if (!dirty) return null
                dirty = false
                return combined.toString()
            }
        }

        fun clear() {
            synchronized(combined) {
                combined.setLength(0)
                dirty = true
                buffer.value = ""
            }
        }
    }

    override fun createSession(cwd: String, title: String): String {
        // cwd host DIABAIKAN — shell Linux selalu berjalan di workspace proot.
        val workspace = WORKSPACE_DIR
        val id = UUID.randomUUID().toString()

        var process: Process? = null
        var startError: String? = null
        try {
            // proot <args -w /home/user/workspace> /bin/bash -li
            process = ProcessBuilder(
                env.prootBinary().absolutePath,
                *env.prootArgs(workspace).toTypedArray(),
                "/bin/bash", "-li"
            ).apply {
                environment().putAll(env.envEnvVars()) // HOME/PATH/PS1 dsb dari env manager
                redirectErrorStream(false)             // stdout & stderr dibaca terpisah
            }.start()
        } catch (e: Exception) {
            startError = e.message ?: "lingkungan Linux belum siap"
        }

        val info = TerminalSessionInfo(
            id = id,
            title = title,
            cwd = workspace,
            createdAt = System.currentTimeMillis(),
            alive = process != null
        )
        val session = Session(info, process, process?.outputStream)

        // Banner hanya saat sesi PERTAMA dibuat (belum ada sesi lain).
        val firstSession = sessionsById.isEmpty()
        sessionsById[id] = session
        _sessions.update { it + info }

        if (firstSession) {
            session.appendChunk(BANNER)
        }
        if (startError != null) {
            session.appendChunk("[openchai] gagal memulai shell Linux: $startError\n")
        }
        session.drain()?.let { session.buffer.value = it }
        if (startError != null) return id // sesi gagal: tidak ada reader diluncurkan

        // Dua reader: satu untuk stdout, satu untuk stderr.
        startReader(session, process!!.inputStream)
        startReader(session, process.errorStream)
        return id
    }

    /**
     * Reader satu stream (stdout ATAU stderr) — dipanggil dua kali per sesi.
     * Append ke buffer gabungan dan emit snapshot ke StateFlow secara berkala
     * (threshold ukuran ATAU interval waktu, pola TerminalManager).
     */
    private fun startReader(session: Session, stream: InputStream) {
        scope.launch {
            try {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 8192).use { reader ->
                    val chunk = CharArray(4096)
                    var sinceFlush = 0
                    var lastEmit = 0L
                    while (true) {
                        val n = reader.read(chunk)
                        if (n < 0) break
                        session.appendChunk(String(chunk, 0, n))
                        sinceFlush += n
                        val now = System.currentTimeMillis()
                        if (sinceFlush >= FLUSH_THRESHOLD || now - lastEmit >= FLUSH_INTERVAL_MS) {
                            session.drain()?.let { session.buffer.value = it }
                            sinceFlush = 0
                            lastEmit = now
                        }
                    }
                }
            } catch (_: Exception) {
                // Stream ditutup saat kill / proses mati — abaikan.
            } finally {
                // Reader selesai berarti proses berhenti (atau di-kill):
                // perbarui alive mengikuti kondisi proses.
                markSessionDead(session)
            }
        }
    }

    /** Tandai sesi mati sekali saja: alive=false + publish info terbaru. */
    private fun markSessionDead(session: Session) {
        if (!session.deadFlag.compareAndSet(false, true)) return
        session.alive = false
        runCatching { session.outputStream?.close() }
        session.drain()?.let { session.buffer.value = it }
        publishInfo(session.info.copy(alive = false))
    }

    override fun write(sessionId: String, input: String) {
        val session = sessionsById[sessionId] ?: return
        val stream = session.outputStream ?: return
        if (!session.alive) return
        // Tulis byte apa adanya; tambahkan newline KECUALI input sudah diakhiri
        // '\n' ATAU merupakan Ctrl+C (ETX \u0003) yang ditulis mentah tanpa newline.
        val text = when {
            input.endsWith("\n") -> input
            input == CTRL_C -> input
            else -> input + "\n"
        }
        // Tulis di dispatcher IO agar tidak memblokir thread UI bila pipe penuh.
        scope.launch {
            try {
                synchronized(stream) {
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            } catch (_: Exception) {
                // Proses sudah mati — abaikan.
            }
        }
    }

    override fun output(sessionId: String): StateFlow<String> =
        sessionsById[sessionId]?.buffer ?: MutableStateFlow("")

    override fun clearOutput(sessionId: String) {
        sessionsById[sessionId]?.clear()
    }

    /**
     * Matikan sesi: destroy() pada proses proot; argumen --kill-on-exit akan
     * membersihkan seluruh tracee (bash + turunannya). Entri sesi TIDAK
     * dihapus dari daftar agar UI masih bisa menampilkan output terakhir
     * (alive=false); bersihkan dengan [pruneDeadSessions].
     */
    override fun killSession(sessionId: String) {
        val session = sessionsById[sessionId] ?: return
        markSessionDead(session) // tandai mati + publish segera (reader menyusul EOF)
        runCatching { session.process?.destroy() }
    }

    /**
     * Prune opsional: buang sesi yang prosesnya sudah mati dari daftar.
     * Tidak dipanggil otomatis — pemilik UI yang memutuskan kapan membersihkan.
     */
    fun pruneDeadSessions() {
        val deadIds = sessionsById.values.filter { !it.alive }.map { it.info.id }
        for (id in deadIds) {
            sessionsById.remove(id) ?: continue
            _sessions.update { list -> list.filterNot { it.id == id } }
        }
    }

    private fun publishInfo(info: TerminalSessionInfo) {
        _sessions.update { list -> list.map { if (it.id == info.id) info else it } }
    }

    companion object {
        /** Workspace tunggal di dalam rootfs proot (dipakai juga supervisor). */
        internal const val WORKSPACE_DIR = "/home/user/workspace"

        private const val MAX_BUFFER_CHARS = 256_000
        private const val FLUSH_THRESHOLD = 2048
        private const val FLUSH_INTERVAL_MS = 80L
        private const val CTRL_C = "\u0003"

        private const val BANNER =
            "Open Chat AI Linux (Ubuntu userspace) — apt/nodejs/python3 tersedia. Workspace: /home/user/workspace\n"
    }
}
