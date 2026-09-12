package com.openchai.core.mcp

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manajer MCP tingkat aplikasi: konfigurasi server (persist ke
 * `mcp_servers.json`), status kesehatan, koneksi ber-cache per serverId,
 * agregasi tool lintas server, dan eksekusi tool untuk agent.
 *
 * Singleton via [McpManagerProvider]; UI Settings dan agent memakai instance
 * yang sama sehingga status di UI selalu sinkron dengan koneksi agent.
 */
class McpManager internal constructor(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(context.applicationContext.filesDir, STORAGE_FILE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Serialisasi penulisan file konfigurasi. */
    private val saveMutex = Mutex()

    /** Serialisasi pembuatan/menghubungkan client per manager (hindari race). */
    private val clientMutex = Mutex()

    /** Cache client aktif per serverId. */
    private val clients = ConcurrentHashMap<String, McpClient>()

    /** Konfigurasi semua server; dimuat dari file saat inisialisasi. */
    private val _configs = MutableStateFlow(loadConfigs())
    val configs: StateFlow<List<McpServerConfig>> = _configs.asStateFlow()

    /** Kesehatan per serverId (untuk UI dot status & log agent). */
    private val _health = MutableStateFlow<Map<String, McpHealth>>(emptyMap())
    val health: StateFlow<Map<String, McpHealth>> = _health.asStateFlow()

    // ------------------------------------------------------------------
    // Konfigurasi
    // ------------------------------------------------------------------

    /** Tambah server baru (id dikosongkan → UUID baru); simpan ke file. */
    fun add(config: McpServerConfig) {
        val withId = if (config.id.isBlank()) config.copy(id = UUID.randomUUID().toString()) else config
        _configs.update { it + withId }
        _health.update { it + (withId.id to McpHealth()) }
        save()
    }

    /**
     * Perbarui server. Client lama (bila ada) ditutup karena url/command/headers/
     * enabled bisa berubah; koneksi ulang terjadi on-demand di [ensureConnected].
     */
    fun update(config: McpServerConfig) {
        clients.remove(config.id)?.disconnect()
        _configs.update { list -> list.map { if (it.id == config.id) config else it } }
        _health.update { it + (config.id to McpHealth()) }
        save()
    }

    /** Hapus server + tutup client-nya. */
    fun remove(id: String) {
        clients.remove(id)?.disconnect()
        _configs.update { list -> list.filterNot { it.id == id } }
        _health.update { it - id }
        save()
    }

    // ------------------------------------------------------------------
    // Koneksi & tool
    // ------------------------------------------------------------------

    /**
     * Uji satu konfigurasi: connect + tools/list (client sementara, tidak
     * di-cache). Health diperbarui sesuai hasil; sukses → "Connected: N tools".
     */
    suspend fun testServer(config: McpServerConfig): Result<String> {
        _health.update { it + (config.id to McpHealth(McpHealthStatus.CONNECTING)) }
        val client = McpClient(config)
        return try {
            client.connect().getOrThrow()
            val tools = client.listTools()
            _health.update {
                it + (config.id to McpHealth(McpHealthStatus.CONNECTED, toolCount = tools.size))
            }
            Result.success("Connected: ${tools.size} tools")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            _health.update {
                it + (config.id to McpHealth(McpHealthStatus.ERROR, e.message ?: e.javaClass.simpleName))
            }
            Result.failure(e)
        } finally {
            client.disconnect()
        }
    }

    /**
     * Ambil client yang sudah konek untuk [config]; dibuat + di-handshake bila
     * belum ada di cache. Bila server disabled → [IllegalStateException].
     */
    suspend fun ensureConnected(config: McpServerConfig): McpClient {
        if (!config.enabled) {
            throw IllegalStateException("MCP server '${config.name}' is disabled")
        }
        return clientMutex.withLock {
            clients[config.id]?.let { return it }
            _health.update { it + (config.id to McpHealth(McpHealthStatus.CONNECTING)) }
            try {
                val client = McpClient(config)
                client.connect().getOrThrow()
                clients[config.id] = client
                _health.update { it + (config.id to McpHealth(McpHealthStatus.CONNECTED)) }
                client
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _health.update {
                    it + (config.id to McpHealth(McpHealthStatus.ERROR, e.message ?: e.javaClass.simpleName))
                }
                throw e
            }
        }
    }

    /**
     * Gabungkan tool dari semua server enabled. Server yang gagal dikonek:
     * health ditandai ERROR lalu dilewati (tidak menggagalkan keseluruhan).
     */
    suspend fun listAllTools(): List<McpTool> {
        val result = mutableListOf<McpTool>()
        for (config in _configs.value) {
            if (!config.enabled) continue
            try {
                val client = ensureConnected(config)
                val tools = client.listTools()
                _health.update {
                    it + (config.id to McpHealth(McpHealthStatus.CONNECTED, toolCount = tools.size))
                }
                result += tools
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Health sudah ditandai ERROR di ensureConnected; server ini diskip.
            }
        }
        return result
    }

    /**
     * Eksekusi tool di server tertentu. Tidak pernah melempar exception ke
     * pemanggil agent — kegagalan dikembalikan sebagai string berawalan "ERROR: "
     * (selaras gaya hasil tool BuiltInAgent).
     */
    suspend fun callTool(serverId: String, toolName: String, argsJson: JsonObject): String {
        val config = _configs.value.firstOrNull { it.id == serverId }
            ?: return "ERROR: MCP server '$serverId' is not configured"
        return try {
            val client = ensureConnected(config)
            client.callTool(toolName, argsJson)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            "ERROR: MCP call '$toolName' failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------------
    // Persistensi
    // ------------------------------------------------------------------

    /** Baca konfigurasi dari file; file korup dianggap kosong (tidak crash). */
    private fun loadConfigs(): List<McpServerConfig> {
        if (!file.exists()) return emptyList()
        // File konfigurasi kecil; baca sinkron agar configs siap sebelum render pertama.
        return runCatching {
            json.decodeFromString<List<McpServerConfig>>(file.readText())
        }.getOrDefault(emptyList())
    }

    /** Tulis atomik (tmp → rename) di Dispatchers.IO; snapshot diambil saat dipanggil. */
    private fun save() {
        val snapshot = _configs.value
        scope.launch {
            saveMutex.withLock {
                runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(json.encodeToString<List<McpServerConfig>>(snapshot))
                    if (!tmp.renameTo(file)) {
                        // Rename gagal (jarang, lintas filesystem) — tulis langsung.
                        file.writeText(tmp.readText())
                        tmp.delete()
                    }
                }
            }
        }
    }

    companion object {
        private const val STORAGE_FILE = "mcp_servers.json"
    }
}

/**
 * Singleton app-scoped: satu [McpManager] untuk seluruh aplikasi, selalu
 * memakai applicationContext agar tidak menahan Activity/Fragment.
 */
object McpManagerProvider {

    @Volatile
    private var instance: McpManager? = null

    /** Ambil instance bersama; dibuat lazy pada pemanggilan pertama. */
    fun get(context: Context): McpManager =
        instance ?: synchronized(this) {
            instance ?: McpManager(context.applicationContext).also { instance = it }
        }
}
