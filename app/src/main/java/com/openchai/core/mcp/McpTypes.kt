package com.openchai.core.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

// ======================================================================
// Tipe dasar MCP (Model Context Protocol): konfigurasi server, tool,
// kesehatan koneksi, dan tipe JSON-RPC 2.0 yang dipakai protokol MCP.
// ======================================================================

/** Transport yang dipakai untuk terhubung ke satu server MCP. */
enum class McpTransport { HTTP, STDIO }

/** Status kesehatan koneksi satu server MCP (tampil di Settings, dipakai agent). */
enum class McpHealthStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Konfigurasi satu server MCP yang dipersist ke `mcp_servers.json`.
 *
 * - HTTP  : [url] wajib (mis. `https://example.com/mcp`), streamable HTTP.
 * - STDIO : [command] + [args] untuk meluncurkan proses server lokal.
 * [headers] dipakai transport HTTP (custom header, mis. Authorization).
 */
@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val transport: McpTransport = McpTransport.HTTP,
    val url: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true
)

/** Satu tool yang diekspos server MCP, sudah diberi anotasi asal server. */
data class McpTool(
    val name: String,
    val description: String,
    val serverId: String,
    val serverName: String,
    val schemaJson: String
)

/** Snapshot kesehatan satu server untuk UI / agent. */
data class McpHealth(
    val status: McpHealthStatus = McpHealthStatus.DISCONNECTED,
    val message: String? = null,
    val toolCount: Int = 0
)

// ----------------------------------------------------------------------
// JSON-RPC 2.0 (dasar protokol MCP)
// ----------------------------------------------------------------------

/**
 * Request JSON-RPC 2.0.
 * [id] == null berarti notification: field `id` TIDAK diserialisasi
 * (lihat [McpJson] dengan `explicitNulls = false`).
 */
@Serializable
data class RpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val method: String,
    val params: JsonObject? = null
)

/** Error JSON-RPC. [data] longgar: server boleh mengirim tipe apa pun. */
@Serializable
data class RpcError(val code: Int, val message: String, val data: JsonElement? = null)

/** Response JSON-RPC 2.0; salah satu dari [result] / [error] biasanya terisi. */
@Serializable
data class RpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val result: JsonObject? = null,
    val error: RpcError? = null
) {
    /** True bila response bukan error JSON-RPC. */
    val isSuccessful: Boolean get() = error == null
}

/**
 * Json instance bersama seluruh lapisan MCP:
 * - `ignoreUnknownKeys`  : server boleh menambah field baru tanpa memecah parsing,
 * - `encodeDefaults`     : field bernilai default tetap ikut terkirim,
 * - `explicitNulls=false`: field null (id notification, params opsional) tidak diserialisasi,
 * - `isLenient`          : toleran terhadap id berupa string dari server,
 * - `coerceInputValues`  : null pada field non-null di-coerce ke default.
 */
val McpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
    coerceInputValues = true
}
