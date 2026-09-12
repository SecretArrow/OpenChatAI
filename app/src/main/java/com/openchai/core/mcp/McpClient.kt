package com.openchai.core.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Klien MCP (Model Context Protocol) untuk satu server [McpServerConfig].
 *
 * Dua transport didukung:
 * - HTTP (streamable HTTP): POST JSON-RPC ke [McpServerConfig.url]; respons bisa
 *   `application/json` atau `text/event-stream` (SSE, diambil event terakhir
 *   yang id-nya cocok). Header `Mcp-Session-Id` dari handshake diikuti otomatis.
 * - STDIO: proses lokal via ProcessBuilder, JSON-RPC newline-delimited di
 *   stdin/stdout; stderr di-drain thread background (buffer 200 baris terakhir).
 *
 * Handshake: `initialize` → validasi `serverInfo` → notifikasi `notifications/initialized`
 * (POST tanpa field id / write tanpa id).
 *
 * Thread-safe: boleh dipakai dari coroutine mana pun secara bersamaan. Semua
 * blocking IO berjalan di [Dispatchers.IO]. Bila request gagal karena stream
 * mati (koneksi/proses putus), klien reconnect sekali lalu retry (lihat
 * [runWithReconnect]); timeout dianggap bukan stream mati agar tool tidak
 * tereksekusi dua kali.
 */
class McpClient(private val config: McpServerConfig) {

    // ------------------------------------------------------------------
    // State umum
    // ------------------------------------------------------------------

    private val json = McpJson
    private val nextRequestId = AtomicLong(1L)

    /** Serialisasi handshake agar connect() paralel tidak double-init. */
    private val connectMutex = Mutex()

    /** Mutex tulis stdin STDIO agar frame newline-delimited tidak bercampur. */
    private val writeMutex = Mutex()

    @Volatile
    private var initialized = false

    /** Header Mcp-Session-Id dari respons initialize (transport HTTP). */
    @Volatile
    private var sessionId: String? = null

    /** State transport STDIO (null bila HTTP / belum jalan). */
    @Volatile
    private var stdio: StdioSession? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ------------------------------------------------------------------
    // API publik
    // ------------------------------------------------------------------

    /**
     * Hubungkan ke server: mulai transport, handshake `initialize`, validasi
     * `serverInfo`, lalu kirim notifikasi `notifications/initialized`.
     * Aman dipanggil ulang (idempoten).
     */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        connectMutex.withLock {
            try {
                when (config.transport) {
                    McpTransport.HTTP -> sessionId = null
                    McpTransport.STDIO -> startStdio()
                }
                val response = sendRequest(
                    RpcRequest(
                        id = nextRequestId.getAndIncrement(),
                        method = "initialize",
                        params = buildJsonObject {
                            put("protocolVersion", PROTOCOL_VERSION)
                            put("capabilities", buildJsonObject { })
                            put("clientInfo", buildJsonObject {
                                put("name", CLIENT_NAME)
                                put("version", CLIENT_VERSION)
                            })
                        }
                    ),
                    timeoutMs = HTTP_TIMEOUT_MS
                )
                validateInitialize(response)
                sendNotification(RpcRequest(method = "notifications/initialized"))
                initialized = true
                Result.success(Unit)
            } catch (ce: CancellationException) {
                // Handshake dibatalkan — matikan transport agar tidak bocor proses.
                initialized = false
                sessionId = null
                shutdownTransport()
                throw ce
            } catch (e: Exception) {
                initialized = false
                sessionId = null
                shutdownTransport()
                Result.failure(e)
            }
        }
    }

    /**
     * `tools/list`: daftar tool yang diekspos server. Reconnect otomatis bila
     * stream mati. Lempar exception bila server tidak bisa dihubungi.
     */
    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        runWithReconnect {
            val response = sendRequest(
                RpcRequest(
                    id = nextRequestId.getAndIncrement(),
                    method = "tools/list",
                    params = buildJsonObject { }
                ),
                timeoutMs = HTTP_TIMEOUT_MS
            )
            response.error?.let { throw IOException("MCP tools/list error ${it.code}: ${it.message}") }
            val tools = response.result?.get("tools") as? JsonArray ?: emptyList()
            tools.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val name = obj.str("name") ?: return@mapNotNull null
                McpTool(
                    name = name,
                    description = obj.str("description").orEmpty(),
                    serverId = config.id,
                    serverName = config.name,
                    schemaJson = obj["inputSchema"]?.toString()?.ifBlank { null } ?: "{}"
                )
            }
        }
    }

    /**
     * `tools/call`: jalankan [toolName] dengan argumen [argsJson] dan kembalikan
     * gabungan teks dari array `content[]` hasil. Bila server menandai
     * `isError`, string diawali "ERROR: ". Timeout default 60 detik.
     */
    suspend fun callTool(toolName: String, argsJson: JsonObject, timeoutMs: Long = 60_000): String =
        withContext(Dispatchers.IO) {
            runWithReconnect {
                val response = sendRequest(
                    RpcRequest(
                        id = nextRequestId.getAndIncrement(),
                        method = "tools/call",
                        params = buildJsonObject {
                            put("name", toolName)
                            put("arguments", argsJson)
                        }
                    ),
                    timeoutMs = timeoutMs
                )
                response.error?.let { throw IOException("MCP tools/call error ${it.code}: ${it.message}") }
                val result = response.result
                    ?: throw IOException("MCP tools/call returned no result")
                val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
                val text = (result["content"] as? JsonArray).orEmpty()
                    .mapNotNull { el ->
                        val item = el as? JsonObject ?: return@mapNotNull null
                        val type = item.str("type")
                        // Hanya item bertipe teks; image/resource dilewati.
                        if (type != null && type != "text") return@mapNotNull null
                        item.str("text")
                    }
                    .joinToString("\n")
                when {
                    text.isNotEmpty() && isError -> "ERROR: $text"
                    text.isNotEmpty() -> text
                    isError -> "ERROR: (server reported error without content)"
                    else -> result.toString() // fallback: structuredContent dsb. tetap terkirim
                }
            }
        }

    /**
     * Tutup transport (matikan proses STDIO, buang sesi HTTP). Aman dipanggil
     * berkali-kali dan dari state apa pun; tidak blocking.
     */
    fun disconnect() {
        initialized = false
        sessionId = null
        shutdownTransport()
    }

    // ------------------------------------------------------------------
    // Handshake
    // ------------------------------------------------------------------

    /** Validasi response initialize: wajib ada result.serverInfo.name. */
    private fun validateInitialize(response: RpcResponse) {
        response.error?.let {
            throw IOException("MCP initialize rejected: ${it.code} ${it.message}")
        }
        val result = response.result
            ?: throw IOException("MCP server returned no result for initialize")
        val serverInfo = result["serverInfo"] as? JsonObject
            ?: throw IOException("MCP server response has no serverInfo (not a valid MCP server?)")
        if (serverInfo.str("name").isNullOrBlank()) {
            throw IOException("MCP server returned serverInfo without a name")
        }
    }

    // ------------------------------------------------------------------
    // Pengiriman request / notification
    // ------------------------------------------------------------------

    /** Kirim request JSON-RPC (dengan id) dan tunggu responsnya. */
    private suspend fun sendRequest(request: RpcRequest, timeoutMs: Long): RpcResponse =
        withContext(Dispatchers.IO) {
            requireNotNull(request.id) { "sendRequest butuh id (bukan notification)" }
            when (config.transport) {
                McpTransport.HTTP -> httpExchange(request, timeoutMs)
                    ?: throw IOException("MCP server returned an empty response for '${request.method}'")
                McpTransport.STDIO -> stdioExchange(request, timeoutMs)
            }
        }

    /** Kirim notification (tanpa id); respons/202 diabaikan. */
    private suspend fun sendNotification(request: RpcRequest) {
        if (request.id != null) {
            throw IllegalArgumentException("sendNotification tidak boleh punya id")
        }
        when (config.transport) {
            McpTransport.HTTP -> withContext(Dispatchers.IO) {
                try {
                    // 202/empty → null, wajar untuk notification.
                    httpExchange(request, HTTP_TIMEOUT_MS)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // Notification fire-and-forget: kegagalan dikirim diabaikan.
                }
            }
            McpTransport.STDIO -> withContext(Dispatchers.IO) {
                val session = stdio ?: throw IOException("MCP STDIO server is not running")
                writeToStdin(session, json.encodeToString(RpcRequest.serializer(), request))
            }
        }
    }

    // ------------------------------------------------------------------
    // Transport HTTP (streamable HTTP)
    // ------------------------------------------------------------------

    /**
     * Satu POST JSON-RPC. Mengembalikan null untuk notification (202/empty).
     * Respons `application/json` dibaca langsung; `text/event-stream` di-parse
     * baris `data: {...}` (event terakhir dengan id cocok yang dipakai).
     */
    private suspend fun httpExchange(request: RpcRequest, timeoutMs: Long): RpcResponse? =
        withContext(Dispatchers.IO) {
            if (!config.url.startsWith("http://") && !config.url.startsWith("https://")) {
                throw IOException("MCP server URL must start with http:// or https://")
            }
            val call = newHttpCall(request, timeoutMs)
            try {
                call.execute().use { response ->
                    if (response.code == 202) return@withContext null
                    if (!response.isSuccessful) {
                        val errBody = runCatching { response.body?.string() }.getOrNull()
                        throw IOException("MCP HTTP ${response.code}${errorSnippet(errBody)}")
                    }
                    response.header("Mcp-Session-Id")?.let { sessionId = it }
                    val source = response.body?.source() ?: return@withContext null
                    val contentType = response.header("Content-Type").orEmpty()
                    if (contentType.contains("text/event-stream", ignoreCase = true)) {
                        parseSse(source, request.id)
                    } else {
                        val text = runCatching { source.readUtf8() }.getOrNull().orEmpty()
                        if (text.isBlank()) return@withContext null
                        runCatching { json.decodeFromString(RpcResponse.serializer(), text) }
                            .getOrElse {
                                throw IOException("Invalid JSON-RPC response from MCP server: ${it.message}", it)
                            }
                    }
                }
            } catch (ce: CancellationException) {
                call.cancel()
                throw ce
            }
        }

    /** Rakit OkHttp call: Accept sesuai spec streamable HTTP + header custom. */
    private fun newHttpCall(request: RpcRequest, timeoutMs: Long): Call {
        val body = json.encodeToString(RpcRequest.serializer(), request).toRequestBody(jsonMedia)
        val builder = Request.Builder()
            .url(config.url)
            .post(body)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        config.headers.forEach { (name, value) ->
            if (name.isNotBlank()) builder.header(name.trim(), value)
        }
        // Read timeout mengikuti timeout request (call tool bisa > 20s), minimal 20s.
        val readMs = timeoutMs.coerceAtLeast(HTTP_TIMEOUT_MS)
        val client = httpClient.newBuilder()
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .build()
        return client.newCall(builder.build())
    }

    /**
     * Parse stream SSE: hanya baris `data: {...}` yang dipakai, ambil event
     * TERAKHIR yang id-nya cocok. Bila server tetap membuka stream setelah
     * mengirim respons, tunggu maksimal [SSE_TAIL_TOTAL_MS] lalu selesaikan
     * dengan respons yang sudah didapat.
     */
    private fun parseSse(source: BufferedSource, requestId: Long?): RpcResponse? {
        var matched: RpcResponse? = null
        var last: RpcResponse? = null
        var tailDeadline = 0L
        try {
            while (true) {
                if (tailDeadline > 0 && System.currentTimeMillis() >= tailDeadline) break
                val line = source.readUtf8Line() ?: break
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val payload = trimmed.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val parsed = runCatching { json.decodeFromString(RpcResponse.serializer(), payload) }
                    .getOrNull() ?: continue
                last = parsed
                if (requestId == null || parsed.id == requestId) {
                    matched = parsed
                    if (tailDeadline == 0L) {
                        // Respons sudah didapat: singkat waktu tunggu event lanjutan.
                        tailDeadline = System.currentTimeMillis() + SSE_TAIL_TOTAL_MS
                        source.timeout().timeout(SSE_TAIL_READ_MS, TimeUnit.MILLISECONDS)
                    }
                }
            }
        } catch (e: IOException) {
            // Stream dipotong / timeout tunggu — pakai respons terakhir yang cocok.
            if (matched != null) return matched
            throw e
        }
        // Fallback: respons tanpa id yang cocok (server non-konform), hindari
        // notification tanpa result ikut terkirim.
        return matched ?: last?.takeIf { it.result != null || it.error != null }
    }

    private fun errorSnippet(body: String?): String {
        val s = body?.trim().orEmpty()
        return if (s.isEmpty()) "" else " — " + s.take(200)
    }

    // ------------------------------------------------------------------
    // Transport STDIO
    // ------------------------------------------------------------------

    /** Luncurkan proses server STDIO + reader stdout & drain stderr. */
    private fun startStdio() {
        shutdownTransport() // bersihkan sesi lama bila ada (reconnect)
        val commandLine = listOf(config.command) + config.args
        val process = try {
            ProcessBuilder(commandLine)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            throw IOException("Failed to start MCP server '${config.command}': ${e.message}", e)
        }
        val session = StdioSession(process)
        stdio = session
        startStdioReader(session)
        startStdioStderrDrain(session)
    }

    /** Kirim request lewat STDIO dan tunggu respons dengan id yang cocok. */
    private suspend fun stdioExchange(request: RpcRequest, timeoutMs: Long): RpcResponse {
        val id = requireNotNull(request.id)
        val session = stdio ?: throw RequestNotSentException("MCP STDIO server is not running")
        if (!session.alive || !session.process.isAlive) {
            throw RequestNotSentException("MCP STDIO server process is not running")
        }
        val deferred = CompletableDeferred<RpcResponse>()
        session.pending[id] = deferred
        try {
            try {
                writeToStdin(session, json.encodeToString(RpcRequest.serializer(), request))
            } catch (e: IOException) {
                throw RequestNotSentException("Failed to write to MCP server stdin: ${e.message}", e)
            }
            try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw SocketTimeoutException(
                    "Timed out waiting for MCP response for '${request.method}' ($timeoutMs ms)"
                )
            }
        } finally {
            session.pending.remove(id)
        }
    }

    /** Tulis satu baris JSON-RPC + '\n' ke stdin proses (diserialisasi mutex). */
    private suspend fun writeToStdin(session: StdioSession, line: String) {
        writeMutex.withLock {
            try {
                // PENTING: stream TIDAK ditutup di sini (tanpa use{}) —
                // stdin dipakai ulang untuk request berikutnya.
                val stream = session.process.outputStream
                stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
            } catch (e: IOException) {
                session.alive = false
                throw IOException("MCP STDIO write failed: ${e.message}", e)
            }
        }
    }

    /**
     * Reader loop stdout: satu objek JSON per baris. Respons dengan id cocok
     * disalurkan ke [StdioSession.pending]; request/notification dari server
     * diabaikan untuk saat ini.
     */
    private fun startStdioReader(session: StdioSession) {
        scope.launch {
            try {
                BufferedReader(InputStreamReader(session.process.inputStream, Charsets.UTF_8), 8192).use { reader ->
                    while (session.alive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        handleStdioLine(session, line.trim())
                    }
                }
            } catch (_: Exception) {
                // Stream ditutup saat proses mati / disconnect — abaikan.
            } finally {
                session.alive = false
                session.pending.values.forEach {
                    it.completeExceptionally(IOException("MCP STDIO stream closed"))
                }
                session.pending.clear()
            }
        }
    }

    private fun handleStdioLine(session: StdioSession, line: String) {
        val obj = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return
        val idPrimitive = obj["id"] as? JsonPrimitive
        if (idPrimitive == null || idPrimitive is JsonNull) return
        val id = idPrimitive.longOrNull ?: return
        val response = runCatching { json.decodeFromString(RpcResponse.serializer(), line) }
            .getOrNull() ?: return
        session.pending.remove(id)?.complete(response)
    }

    /** Drain stderr di background; simpan 200 baris terakhir sebagai buffer. */
    private fun startStdioStderrDrain(session: StdioSession) {
        scope.launch {
            try {
                BufferedReader(InputStreamReader(session.process.errorStream, Charsets.UTF_8), 2048).use { reader ->
                    while (session.alive) {
                        val line = reader.readLine() ?: break
                        synchronized(session.stderr) {
                            session.stderr.addLast(line)
                            while (session.stderr.size > STDIO_MAX_STDERR_LINES) {
                                session.stderr.removeFirst()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // errorStream ditutup saat disconnect — abaikan.
            }
        }
    }

    /** State transport STDIO: proses + panggilan tertunda + buffer stderr. */
    private class StdioSession(val process: Process) {
        val pending = ConcurrentHashMap<Long, CompletableDeferred<RpcResponse>>()

        /** Dijaga lewat synchronized(stderr). */
        val stderr = ArrayDeque<String>()

        @Volatile
        var alive = true
    }

    // ------------------------------------------------------------------
    // Auto-reconnect
    // ------------------------------------------------------------------

    /**
     * Jalankan [block] setelah memastikan klien ter-handshake; bila gagal karena
     * stream mati ([isRetryable]), reconnect sekali lalu retry [block] sekali.
     */
    private suspend fun <T> runWithReconnect(block: suspend () -> T): T {
        if (!initialized) connect().getOrThrow()
        return try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (!isRetryable(e)) throw e
            disconnect()
            if (!connect().isSuccess) throw e
            block()
        }
    }

    /**
     * True bila error menandakan transport mati (layak reconnect+retry).
     * Timeout TIDAK di-retry: request kemungkinan sudah diproses server,
     * retry bisa mengeksekusi tool dua kali.
     */
    private fun isRetryable(e: Throwable): Boolean = when {
        e is RequestNotSentException -> true // gagal sebelum request terkirim
        e is IOException &&
            e !is SocketTimeoutException &&
            e !is InterruptedIOException -> true
        else -> false
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    private fun shutdownTransport() {
        val session = stdio
        stdio = null
        if (session == null) return
        session.alive = false
        session.pending.values.forEach {
            it.completeExceptionally(IOException("MCP client disconnected"))
        }
        session.pending.clear()
        runCatching { session.process.outputStream.close() }
        runCatching { session.process.destroy() }
        // Watchdog: paksa hentikan proses yang menolak SIGTERM.
        scope.launch {
            delay(3_000)
            runCatching { if (session.process.isAlive) session.process.destroyForcibly() }
        }
    }

    companion object {
        /** Versi protokol MCP yang didukung klien ini. */
        const val PROTOCOL_VERSION = "2024-11-05"

        /** Identitas klien yang dikirim saat handshake initialize. */
        const val CLIENT_NAME = "Open Chat AI"
        const val CLIENT_VERSION = "1.2.0"

        /** Timeout HTTP (connect/read/write) sekaligus default handshake & tools/list. */
        const val HTTP_TIMEOUT_MS = 20_000L

        /** Batas baris stderr yang disimpan (debug pesan error server). */
        const val STDIO_MAX_STDERR_LINES = 200

        private const val SSE_TAIL_TOTAL_MS = 2_000L
        private const val SSE_TAIL_READ_MS = 500L
    }

    /** Request belum terkirim ke server — aman untuk reconnect + retry. */
    private class RequestNotSentException(message: String, cause: Throwable? = null) :
        IOException(message, cause)
}

/** String aman dari obj[field]; null bila absen / JsonNull / bukan primitif. */
private fun JsonObject.str(field: String): String? {
    val el = this[field] ?: return null
    if (el is JsonNull) return null
    return (el as? JsonPrimitive)?.content
}
