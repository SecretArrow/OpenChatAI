package com.openchai.terminal

import android.content.Context
import com.openchai.app.OpenChatApp
import com.openchai.core.terminal.TerminalHost
import com.openchai.core.terminal.TerminalSessionInfo
import com.openchai.runtime.ShellEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Host sesi terminal interaktif (bash/sh) di Android.
 *
 * Sesi tetap hidup meski panel ditutup: proses shell diluncurkan lewat
 * ProcessBuilder dan dibaca terus-menerus oleh coroutine reader; output
 * disimpan dalam ring buffer 256 ribu karakter per sesi.
 */
class TerminalManager(private val context: Context) : TerminalHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shellEnv = ShellEnvironment(context)

    override val sessions: StateFlow<List<TerminalSessionInfo>>
        get() = _sessions.asStateFlow()

    private val _sessions = MutableStateFlow<List<TerminalSessionInfo>>(emptyList())

    private val sessionsById = ConcurrentHashMap<String, Session>()

    /** Record internal per sesi: proses, stream, buffer, flag hidup. */
    private class Session(
        val info: TerminalSessionInfo,
        val process: Process?,
        val outputStream: OutputStream?
    ) {
        val buffer = MutableStateFlow("")

        @Volatile
        var alive: Boolean = process != null

        /** Isi buffer otoritatif (dirangkum berkala ke [buffer] oleh reader). */
        val combined = StringBuilder()

        @Volatile
        var dirty = false

        fun appendChunk(chunk: String) {
            synchronized(combined) {
                combined.append(chunk)
                if (combined.length > MAX_BUFFER_CHARS) {
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
        val id = UUID.randomUUID().toString()
        val dir = File(cwd).takeIf { it.isDirectory } ?: context.filesDir
        val shell = if (File(BASH_PATH).exists()) BASH_PATH else SH_PATH

        var process: Process? = null
        var startError: String? = null
        try {
            process = ProcessBuilder(shell)
                .apply {
                    directory(dir)
                    environment().putAll(shellEnv.buildEnvironment(extraPath()))
                    redirectErrorStream(true)
                }
                .start()
        } catch (e: Exception) {
            startError = e.message ?: "shell tidak tersedia"
        }

        val info = TerminalSessionInfo(
            id = id,
            title = title,
            cwd = dir.absolutePath,
            createdAt = System.currentTimeMillis(),
            alive = process != null
        )
        val session = Session(info, process, process?.outputStream)
        sessionsById[id] = session
        _sessions.update { it + info }

        if (startError != null) {
            session.appendChunk("[openchat] gagal memulai shell: $startError\n")
            session.drain()?.let { session.buffer.value = it }
            return id
        }

        // Tulis awal: pindah ke cwd yang diminta user (abaikan bila tidak ada).
        // Ditulis sinkron di sini agar urutan stdin terjamin sebelum input user.
        if (cwd.isNotBlank()) {
            try {
                session.outputStream?.let { stream ->
                    val quoted = cwd.replace("'", "'\\''")
                    synchronized(stream) {
                        stream.write("cd '$quoted' 2>/dev/null\n".toByteArray(Charsets.UTF_8))
                        stream.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }

        startReader(session)
        return id
    }

    private fun startReader(session: Session) {
        val process = session.process ?: return
        scope.launch {
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8), 8192).use { reader ->
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
                // Stream ditutup saat kill — abaikan.
            } finally {
                session.alive = false
                runCatching { process.outputStream.close() }
                session.drain()?.let { session.buffer.value = it }
                publishInfo(session.info.copy(alive = false))
            }
        }
    }

    override fun write(sessionId: String, input: String) {
        val session = sessionsById[sessionId] ?: return
        val stream = session.outputStream ?: return
        if (!session.alive) return
        val text = if (input.endsWith("\n")) input else "$input\n"
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

    override fun killSession(sessionId: String) {
        val session = sessionsById.remove(sessionId) ?: return
        session.alive = false
        runCatching { session.outputStream?.close() }
        runCatching { session.process?.destroy() }
        _sessions.update { list -> list.filterNot { it.id == sessionId } }
    }

    override fun clearOutput(sessionId: String) {
        sessionsById[sessionId]?.clear()
    }

    private fun publishInfo(info: TerminalSessionInfo) {
        _sessions.update { list -> list.map { if (it.id == info.id) info else it } }
    }

    private fun extraPath(): String = try {
        (context.applicationContext as? OpenChatApp)
            ?.container?.settingsRepository?.settings?.value?.extraPathDirs ?: ""
    } catch (_: Exception) {
        ""
    }

    companion object {
        private const val SH_PATH = "/system/bin/sh"
        private const val BASH_PATH = "/system/bin/bash"
        private const val MAX_BUFFER_CHARS = 256_000
        private const val FLUSH_THRESHOLD = 2048
        private const val FLUSH_INTERVAL_MS = 80L
    }
}
