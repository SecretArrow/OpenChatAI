package com.openchai.core.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Jembatan antara agent bawaan (protokol blok `<tool>{"name","args"}</tool>`,
 * TOOL_RESULT) dan tool eksternal dari server MCP.
 *
 * Setiap tool MCP diberi nama unik untuk model: `mcp_<serverSnake>_<toolName>`
 * (lihat [prefixedName]). [toolsPromptBlock] menyuntikkan daftar tool ke system
 * prompt; [execute] mengeksekusi blok `<tool>` yang nama-namanya berawalan
 * `mcp_` — mengembalikan null bila bukan tool MCP sehingga agent bisa fallback
 * ke tool bawaannya.
 */
object AgentMcpBridge {

    /** Prefix nama tool MCP yang terlihat oleh model. */
    const val MCP_TOOL_PREFIX = "mcp_"

    // ------------------------------------------------------------------
    // Penamaan
    // ------------------------------------------------------------------

    /** Nama tool MCP yang tampil ke model: `mcp_<serverSnake>_<toolName>`. */
    fun prefixedName(tool: McpTool): String =
        "$MCP_TOOL_PREFIX${snakeCase(tool.serverName)}_${tool.name}"

    /** True bila nama tool (dari blok `<tool>`) ditujukan untuk MCP. */
    fun isMcpTool(name: String): Boolean = name.startsWith(MCP_TOOL_PREFIX)

    /** Nama server → snake_case: lowercase, semua non-alfanumerik jadi '_'. */
    fun snakeCase(name: String): String = buildString {
        for (ch in name.trim().lowercase()) {
            append(if (ch.isLetterOrDigit()) ch else '_')
        }
    }.trimEnd('_')

    // ------------------------------------------------------------------
    // Prompt & eksekusi
    // ------------------------------------------------------------------

    /**
     * Blok daftar tool MCP untuk system prompt agent. "" bila tidak ada tool
     * (semua server disabled / gagal konek / belum dikonfigurasi).
     */
    suspend fun toolsPromptBlock(manager: McpManager): String {
        val tools = manager.listAllTools()
        if (tools.isEmpty()) return ""
        return buildString {
            appendLine("MCP TOOLS (eksternal):")
            for (tool in tools) {
                appendLine(
                    "  - ${prefixedName(tool)} ${summarizeSchema(tool.schemaJson)} " +
                        "— [${tool.serverName}] ${tool.description}"
                )
            }
            appendLine("MCP TOOL RULES:")
            appendLine("""  - Panggil tool MCP dengan blok yang sama: <tool>{"name":"mcp_<server>_<tool>","args":{...}}</tool>""")
            appendLine("  - Gunakan nama persis seperti terdaftar di atas; properti bertanda * wajib ada di args.")
            appendLine("""  - Hasil tool dikirim balik sebagai pesan "TOOL_RESULT"; bila diawali "ERROR: ", perbaiki argumen lalu coba lagi.""")
        }.trimEnd()
    }

    /**
     * Eksekusi panggilan tool MCP dari blok `<tool>` agent.
     *
     * Pencocokan nama (berurutan):
     * 1. persis `mcp_<serverSnake>_<toolName>`,
     * 2. sama, case-insensitive,
     * 3. suffix `_toolName` (model kadang menebak nama server).
     *
     * @return null bila [toolCallName] bukan tool MCP (tidak berawalan `mcp_`);
     *         selain itu string hasil — berawalan "ERROR: " bila gagal.
     */
    suspend fun execute(manager: McpManager, toolCallName: String, args: JsonObject): String? {
        if (!isMcpTool(toolCallName)) return null
        val rest = toolCallName.removePrefix(MCP_TOOL_PREFIX)
        val tools = manager.listAllTools()
        val match = tools.firstOrNull { prefixedName(it) == toolCallName }
            ?: tools.firstOrNull { rest.equals("${snakeCase(it.serverName)}_${it.name}", ignoreCase = true) }
            ?: tools.firstOrNull { rest.endsWith("_${it.name}", ignoreCase = true) }
        val tool = match ?: return "ERROR: Unknown MCP tool '$toolCallName'"
        return try {
            manager.callTool(tool.serverId, tool.name, args)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            "ERROR: MCP tool '${tool.name}' failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------------
    // Ringkasan schema
    // ------------------------------------------------------------------

    /**
     * Ringkas JSON Schema input tool menjadi `{prop*:type, ...}` (maks
     * [SCHEMA_MAX_PROPS] properti; `*` = required) agar prompt tetap pendek.
     */
    private fun summarizeSchema(schemaJson: String): String {
        val schema = runCatching { McpJson.parseToJsonElement(schemaJson) }
            .getOrNull() as? JsonObject ?: return "{...}"
        val properties = schema["properties"] as? JsonObject ?: return "{...}"
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.toSet()
            ?: emptySet()
        val parts = properties.keys.take(SCHEMA_MAX_PROPS).map { key ->
            val type = (properties[key] as? JsonObject)
                ?.get("type")?.let { (it as? JsonPrimitive)?.content } ?: "any"
            val mark = if (key in required) "*" else ""
            "$key$mark:$type"
        }
        val more = if (properties.size > SCHEMA_MAX_PROPS) ", …" else ""
        return "{${parts.joinToString(", ")}$more}"
    }

    private const val SCHEMA_MAX_PROPS = 6
}
