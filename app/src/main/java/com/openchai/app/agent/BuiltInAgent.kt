package com.openchai.agent

import com.openchai.app.ai.ProviderRegistry
import com.openchai.app.ai.jsonObj
import com.openchai.app.ai.jsonStr
import com.openchai.app.ai.parseJsonSafe
import com.openchai.core.ai.StreamEvent
import com.openchai.core.agent.AgentEngine
import com.openchai.core.agent.AgentEvent
import com.openchai.core.agent.AgentRequest
import com.openchai.core.agent.CommandRunner
import com.openchai.core.data.SecureStore
import com.openchai.core.mcp.AgentMcpBridge
import com.openchai.core.mcp.McpManager
import com.openchai.core.skills.SkillInjector
import com.openchai.core.skills.SkillLoader
import com.openchai.core.model.AgentStep
import com.openchai.core.model.ChatMessage
import com.openchai.core.model.Role
import com.openchai.core.model.StepState
import com.openchai.core.settings.AppSettings
import com.openchai.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject

/**
 * Engine agent bawaan: loop tool (pola ReAct) di atas provider AI apa pun.
 *
 * Model diminta mengeluarkan blok <tool>{"name":"...","args":{...}}</tool>;
 * engine mengeksekusinya lewat [AgentTools] (file ops ter-sandbox) atau [CommandRunner]
 * (shell, hanya bila auto-approve), lalu mengirim hasilnya kembali sebagai pesan
 * "TOOL_RESULT …" sampai model menjawab final tanpa blok tool.
 *
 * Isi blok <tool> disembunyikan dari Delta yang tampil di UI (lihat [ToolStreamFilter]).
 * Semua exception (kecuali CancellationException) dikonversi menjadi [AgentEvent.Failed].
 */
class BuiltInAgent(
    private val settings: SettingsRepository,
    private val secure: SecureStore,
    private val runner: CommandRunner,
    private val registry: ProviderRegistry,
    /** Server MCP aktif — tools eksternal di-expose ke model lewat prefix "mcp_". */
    private val mcp: McpManager,
    /** Skill loader — instruksi pak skill disuntikkan ke system prompt sesuai tugas. */
    private val skills: SkillLoader
) : AgentEngine {

    override val engineName: String = "Built-in agent"

    override suspend fun start(workspacePath: String?): Result<Unit> = Result.success(Unit)

    override suspend fun stop() { /* no-op */ }

    override suspend fun healthCheck(): Boolean = true

    override fun execute(request: AgentRequest): Flow<AgentEvent> = flow {
        try {
            val s = settings.settings.value
            val task = request.task.trim()
            if (task.isEmpty()) {
                emit(AgentEvent.Failed("Empty task."))
                return@flow
            }
            val workspace = request.workspacePath?.trim().orEmpty()

            emit(AgentEvent.StepUpdated(AgentStep("Analyzing project", StepState.RUNNING)))
            val workspaceContext = if (workspace.isNotBlank()) {
                "Workspace: $workspace\n" + AgentTools.listFiles(workspace, ".")
            } else {
                ""
            }
            emit(
                AgentEvent.StepUpdated(
                    AgentStep(
                        "Analyzing project",
                        StepState.DONE,
                        detail = workspace.take(80).ifBlank { "No workspace — general mode" }
                    )
                )
            )

            val systemPrompt = buildSystemPrompt(workspaceContext, task)
            val chatTail = mutableListOf<Pair<String, String>>()
            var lastAnswer = ""

            for (iteration in 1..s.maxAgentIterations.coerceAtLeast(1)) {
                val provider = registry.get(s.activeProvider)
                if (provider == null) {
                    emit(AgentEvent.Failed("No AI provider configured. Open Settings → AI."))
                    return@flow
                }

                val messages = buildMessages(systemPrompt, request.history, task, chatTail)
                val full = StringBuilder()
                val filter = ToolStreamFilter()
                var streamError: String? = null

                provider.streamChat(messages, s.selectedModel).collect { se ->
                    when (se) {
                        is StreamEvent.Delta -> {
                            full.append(se.text)
                            val visible = filter.feed(se.text)
                            if (visible.isNotEmpty()) emit(AgentEvent.Delta(visible))
                        }
                        is StreamEvent.Done -> Unit
                        is StreamEvent.Error -> streamError = se.message
                    }
                }
                if (streamError != null) {
                    emit(AgentEvent.Failed(streamError ?: "AI provider error"))
                    return@flow
                }
                val visibleTail = filter.flush()
                if (visibleTail.isNotEmpty()) emit(AgentEvent.Delta(visibleTail))

                lastAnswer = full.toString()
                val toolCalls = parseToolCalls(lastAnswer)
                if (toolCalls.isEmpty()) {
                    emit(AgentEvent.Completed(lastAnswer.trim()))
                    return@flow
                }

                chatTail.add("assistant" to lastAnswer)
                for (tc in toolCalls) {
                    val label = humanize(tc.name, tc.args)
                    emit(AgentEvent.StepUpdated(AgentStep(label, StepState.RUNNING)))
                    val result = executeTool(tc.name, tc.args, workspace, s)
                    emit(AgentEvent.StepUpdated(AgentStep(label, StepState.DONE, detail = summarize(result))))
                    chatTail.add("user" to "TOOL_RESULT $label:\n$result")
                }
            }

            emit(AgentEvent.Completed(lastAnswer.trim() + "\n\n_(iteration limit reached)_"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            emit(AgentEvent.Failed(e.message ?: "Agent error: ${e.javaClass.simpleName}"))
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // Prompt & messages
    // ------------------------------------------------------------------

    private suspend fun buildSystemPrompt(workspaceContext: String, task: String): String = buildString {
        appendLine("You are Open Chat AI, a precise and pragmatic coding agent running inside an Android app.")
        appendLine("Complete the user's task inside the workspace, using the tools below when needed.")
        if (workspaceContext.isNotBlank()) {
            appendLine()
            appendLine(workspaceContext.trim())
        }
        appendLine()
        appendLine("TOOL RULES:")
        appendLine("- To call a tool, output exactly one block per call:")
        appendLine("""  <tool>{"name":"...","args":{...}}</tool>""")
        appendLine("- Available tools:")
        appendLine("""  - list_files {"path": "."} — directory tree, 2 levels""")
        appendLine("""  - read_file {"path": "src/App.tsx"} — file content, max 16 KB""")
        appendLine("""  - write_file {"path": "src/App.tsx", "content": "..."} — create or overwrite a file""")
        appendLine("""  - delete_file {"path": "src/old.txt"} — delete a file or directory""")
        appendLine("""  - search {"query": "text"} — case-insensitive search in the workspace""")
        appendLine("""  - run_command {"command": "npm test"} — run a shell command (needs auto-approve)""")
        appendLine("- Paths are relative to the workspace root; every access stays inside the workspace.")
        appendLine("""- After each tool block you receive a user message starting with "TOOL_RESULT" containing the output.""")
        appendLine("- Work step by step until the task is fully done, then reply with the final answer WITHOUT any tool block.")
        appendLine("Final answer style: concise markdown, fenced code blocks with language tags, no filler.")

        // Tools MCP eksternal (bila ada server aktif) — dipanggil dengan nama mcp_<server>_<tool>.
        val mcpBlock = AgentMcpBridge.toolsPromptBlock(mcp)
        if (mcpBlock.isNotBlank()) {
            appendLine()
            appendLine(mcpBlock.trimEnd())
        }

        // Skill aktif yang cocok dengan tugas ini (maks 3, instruksi terpotong 1200 char).
        val skillBlock = SkillInjector.promptBlock(skills, task)
        if (skillBlock.isNotBlank()) {
            appendLine()
            appendLine(skillBlock.trimEnd())
        }
    }

    private fun buildMessages(
        system: String,
        history: List<Pair<String, String>>,
        task: String,
        tail: List<Pair<String, String>>
    ): List<ChatMessage> = buildList {
        add(ChatMessage(role = Role.SYSTEM, content = system))
        history.forEach { (role, content) ->
            add(ChatMessage(role = if (role == "user") Role.USER else Role.ASSISTANT, content = content))
        }
        add(ChatMessage(role = Role.USER, content = task))
        tail.forEach { (role, content) ->
            add(ChatMessage(role = if (role == "user") Role.USER else Role.ASSISTANT, content = content))
        }
    }

    // ------------------------------------------------------------------
    // Tool parsing & execution
    // ------------------------------------------------------------------

    private data class ToolCall(val name: String, val args: JsonObject)

    private fun parseToolCalls(text: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        val regex = Regex("<tool>\\s*([\\s\\S]*?)\\s*</tool>")
        for (match in regex.findAll(text)) {
            val body = match.groupValues[1].trim()
            val obj = parseJsonSafe(body) ?: continue
            val name = obj.jsonStr("name")?.trim().orEmpty()
            if (name.isEmpty()) continue
            // Bila model lupa membungkus argumen di "args", pakai objek luar sebagai fallback.
            val args = obj.jsonObj("args") ?: obj
            calls.add(ToolCall(name, args))
        }
        return calls
    }

    private suspend fun executeTool(
        name: String,
        args: JsonObject,
        workspace: String,
        s: AppSettings
    ): String {
        if (workspace.isBlank()) return "ERROR: No workspace selected. Open a project first."
        return try {
            when (name) {
                "list_files" -> AgentTools.listFiles(workspace, args.jsonStr("path") ?: ".")
                "read_file" -> {
                    val path = args.jsonStr("path")
                    if (path.isNullOrBlank()) "ERROR: read_file requires a 'path' argument."
                    else AgentTools.readFile(workspace, path)
                }
                "write_file" -> {
                    val path = args.jsonStr("path")
                    if (path.isNullOrBlank()) "ERROR: write_file requires a 'path' argument."
                    else AgentTools.writeFile(workspace, path, args.jsonStr("content") ?: "")
                }
                "delete_file" -> {
                    val path = args.jsonStr("path")
                    if (path.isNullOrBlank()) "ERROR: delete_file requires a 'path' argument."
                    else AgentTools.deleteFile(workspace, path)
                }
                "search" -> {
                    val query = args.jsonStr("query")
                    if (query.isNullOrBlank()) "ERROR: search requires a 'query' argument."
                    else AgentTools.search(workspace, query)
                }
                "run_command" -> {
                    val command = args.jsonStr("command")
                    if (command.isNullOrBlank()) {
                        "ERROR: run_command requires a 'command' argument."
                    } else if (!s.autoApproveCommands) {
                        "ERROR: Command execution is disabled. Enable Auto-approve in Settings → Agent."
                    } else {
                        val res = runner.run(command, workspace, 120_000L)
                        val combined = buildString {
                            append(res.stdout)
                            if (res.stderr.isNotBlank()) {
                                if (isNotEmpty()) append('\n')
                                append("[stderr] ").append(res.stderr)
                            }
                        }
                        val output =
                            if (combined.length > MAX_COMMAND_OUTPUT) combined.take(MAX_COMMAND_OUTPUT) + "\n…(truncated)"
                            else combined
                        "exit=${res.exitCode}\n$output"
                    }
                }
                else -> {
                    // Tool MCP eksternal (mcp_<server>_<tool>); null berarti memang bukan MCP.
                    AgentMcpBridge.execute(mcp, name, args)
                        ?: "ERROR: Unknown tool '$name'"
                }
            }
        } catch (e: Exception) {
            "ERROR: ${e.message ?: "tool '$name' failed"}"
        }
    }

    // ------------------------------------------------------------------
    // Label human-readable (tampil di chat)
    // ------------------------------------------------------------------

    private fun humanize(name: String, args: JsonObject): String = when (name) {
        "list_files" -> {
            val p = args.jsonStr("path").orEmpty().trim()
            if (p.isEmpty() || p == "." || p == "./") "Exploring project" else "Listing $p"
        }
        "read_file" -> "Reading ${labelPath(args, "path")}"
        "write_file" -> "Writing ${labelPath(args, "path")}"
        "delete_file" -> "Deleting ${labelPath(args, "path")}"
        "search" -> "Searching '${args.jsonStr("query")?.trim()?.take(40).orEmpty()}'"
        "run_command" -> "Running: ${args.jsonStr("command")?.trim()?.take(60).orEmpty()}"
        name.startsWith("mcp_") -> "Calling ${name.removePrefix("mcp_").replace('_', ' ').trim()}"
        else -> "Using tool $name"
    }

    private fun labelPath(args: JsonObject, key: String): String =
        args.jsonStr(key)?.trim()?.take(80)?.ifBlank { null } ?: "(unspecified)"

    private fun summarize(result: String): String =
        result.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "done"

    private companion object {
        const val MAX_COMMAND_OUTPUT = 4000
    }
}

/**
 * Filter streaming sederhana: menahan teks yang berada di dalam blok
 * <tool>…</tool> agar tidak tampil sebagai Delta di UI, termasuk kasus tag
 * yang terpotong di tengah chunk (state kecil, memori berbatas).
 */
private class ToolStreamFilter {
    private var inside = false
    private var pending = ""

    /** Proses potongan baru; kembalikan teks yang aman ditampilkan. */
    fun feed(chunk: String): String {
        if (chunk.isEmpty() && pending.isEmpty()) return ""
        val out = StringBuilder()
        var rest = pending + chunk
        pending = ""
        while (rest.isNotEmpty()) {
            if (!inside) {
                val open = rest.indexOf(OPEN)
                if (open >= 0) {
                    out.append(rest, 0, open)
                    rest = rest.substring(open + OPEN.length)
                    inside = true
                    continue
                }
                val keep = longestTagPrefix(rest, OPEN)
                out.append(rest, 0, rest.length - keep)
                if (keep > 0) pending = rest.substring(rest.length - keep)
                rest = ""
            } else {
                val close = rest.indexOf(CLOSE)
                if (close >= 0) {
                    rest = rest.substring(close + CLOSE.length)
                    inside = false
                    continue
                }
                val keep = longestTagPrefix(rest, CLOSE)
                if (keep > 0) pending = rest.substring(rest.length - keep)
                rest = ""
            }
        }
        return out.toString()
    }

    /** Dipanggil saat stream selesai: lepaskan sisa buffer bila bukan bagian tag. */
    fun flush(): String {
        val rest = pending
        pending = ""
        return if (inside) "" else rest
    }

    private fun longestTagPrefix(s: String, tag: String): Int {
        val max = minOf(tag.length - 1, s.length)
        for (k in max downTo 1) {
            if (s.regionMatches(s.length - k, tag, 0, k)) return k
        }
        return 0
    }

    private companion object {
        const val OPEN = "<tool>"
        const val CLOSE = "</tool>"
    }
}
