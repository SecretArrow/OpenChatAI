package com.openchai.agent

import com.openchatai.app.ai.ProviderRegistry
import com.openchatai.app.ai.jsonObj
import com.openchatai.app.ai.jsonStr
import com.openchatai.app.ai.parseJsonSafe
import com.openchai.core.ai.StreamEvent
import com.openchai.core.agent.AgentEngine
import com.openchai.core.agent.AgentEvent
import com.openchai.core.agent.AgentRequest
import com.openchai.core.agent.CommandRunner
import com.openchai.core.agent.PermissionContext
import com.openchai.core.agent.PermissionDecision
import com.openchai.core.agent.PermissionMode
import com.openchai.core.agent.PermissionRequest
import com.openchai.core.agent.PermissionRule
import com.openchai.core.agent.toolCategoryOf
import com.openchai.core.data.WorkspaceFs
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
import java.util.UUID

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
            val fs = request.workspaceFs
            val perm = request.permission

            emit(AgentEvent.StepUpdated(AgentStep("Analyzing project", StepState.RUNNING)))
            val workspaceContext = when {
                fs != null ->
                    "Workspace: ${fs.rootLabel.ifBlank { "workspace" }}\n" + fs.listFiles(".")
                workspace.isNotBlank() ->
                    "Workspace: $workspace\n" + AgentTools.listFiles(workspace, ".")
                else -> ""
            }
            emit(
                AgentEvent.StepUpdated(
                    AgentStep(
                        "Analyzing project",
                        StepState.DONE,
                        detail = (fs?.rootLabel ?: workspace).take(80)
                            .ifBlank { "No workspace — general mode" }
                    )
                )
            )

            val projectRules = readProjectRules(fs, workspace)
            val systemPrompt = buildSystemPrompt(workspaceContext, task, perm?.mode, projectRules)
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
                    val result = executeTool(tc.name, tc.args, fs, workspace, s, perm)
                    // Detail penuh (dipotong 2000 char) — UI menampilkan collapsible.
                    emit(AgentEvent.StepUpdated(AgentStep(label, StepState.DONE, detail = result.take(2000))))
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

    private suspend fun buildSystemPrompt(
        workspaceContext: String,
        task: String,
        mode: PermissionMode?,
        projectRules: String?
    ): String = buildString {
        appendLine("You are Open Chat AI, a precise and pragmatic coding agent running inside an Android app.")
        appendLine("Complete the user's task inside the workspace, using the tools below when needed.")
        if (mode != null) appendLine(modeBlock(mode))
        if (!projectRules.isNullOrBlank()) {
            appendLine()
            appendLine("PROJECT RULES (AGENTS.md):")
            appendLine(projectRules.trim())
        }
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
        fs: WorkspaceFs?,
        workspace: String,
        s: AppSettings,
        perm: PermissionContext?
    ): String {
        if (workspace.isBlank() && fs == null) {
            return "ERROR: No workspace selected. Create a workspace first."
        }
        // ---- Permission gating (gaya Claude Code / OpenCode) -----------------
        if (perm != null) {
            val category = toolCategoryOf(name)
            when (PermissionRule.decide(perm.mode, category)) {
                PermissionRule.Action.DENY -> return denyMessage(perm.mode, name)
                PermissionRule.Action.ASK -> {
                    val decision = perm.broker.request(
                        PermissionRequest(
                            id = "perm-" + UUID.randomUUID().toString(),
                            sessionId = perm.sessionId,
                            toolName = name,
                            category = category,
                            detail = permissionDetail(name, args),
                            isDangerous = isDangerousTool(name, args)
                        )
                    )
                    if (decision is PermissionDecision.Deny) {
                        return "ERROR: user denied tool '$name'. Continue without it or " +
                            "finish with what you have."
                    }
                    // AllowOnce / AllowSession → lanjut eksekusi.
                }
                PermissionRule.Action.AUTO -> Unit
            }
        }
        return try {
            when (name) {
                "list_files" -> {
                    val p = args.jsonStr("path") ?: "."
                    fs?.listFiles(p) ?: AgentTools.listFiles(workspace, p)
                }
                "read_file" -> {
                    val path = args.jsonStr("path")
                    if (path.isNullOrBlank()) "ERROR: read_file requires a 'path' argument."
                    else fs?.readFile(path) ?: AgentTools.readFile(workspace, path)
                }
                "write_file" -> {
                    val path = args.jsonStr("path")
                    if (path.isNullOrBlank()) "ERROR: write_file requires a 'path' argument."
                    else writeFileWithDiff(fs, workspace, path, args.jsonStr("content") ?: "")
                }
                "delete_file" -> {
                    val path = args.jsonStr("path")
                    if (path.isNullOrBlank()) "ERROR: delete_file requires a 'path' argument."
                    else fs?.deleteFile(path) ?: AgentTools.deleteFile(workspace, path)
                }
                "search" -> {
                    val query = args.jsonStr("query")
                    if (query.isNullOrBlank()) "ERROR: search requires a 'query' argument."
                    else fs?.search(query) ?: AgentTools.search(workspace, query)
                }
                "run_command" -> {
                    // cwd host: bind path dari fs (app-dir / SAF primary storage)
                    // agar command berjalan PADA workspace asli; bila tidak ada,
                    // pakai legacy workspace (path host langsung).
                    val shellCwd = fs?.hostBindPath ?: workspace
                    // SAF non-primary TANPA pemetaan bind DAN tanpa legacy path:
                    // tidak ada tempat sah untuk mengeksekusi command → error jelas.
                    // SAF dengan bind path TETAP BOLEH jalan (di bind itu).
                    if (fs != null && !fs.supportsShell && fs.hostBindPath == null &&
                        workspace.isBlank()
                    ) {
                        return "ERROR: run_command unavailable for this SAF workspace " +
                            "(not on primary storage) — use an app-private or " +
                            "primary-storage workspace for shell commands, or use " +
                            "the file tools."
                    }
                    // Legacy (tanpa permission context): tetap dijaga auto-approve.
                    if (perm == null && !s.autoApproveCommands) {
                        return "ERROR: Command execution is disabled. Enable Full access (YOLO) " +
                            "mode or Auto-approve in Settings → Agent."
                    }
                    val command = args.jsonStr("command")
                    if (command.isNullOrBlank()) {
                        "ERROR: run_command requires a 'command' argument."
                    } else {
                        val res = runner.run(command, shellCwd, 120_000L)
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
    // Permission helpers (gaya Claude Code / OpenCode)
    // ------------------------------------------------------------------

    /** Pesan penolakan otomatis; PLAN memandu model menyusun rencana. */
    private fun denyMessage(mode: PermissionMode, name: String): String =
        if (mode == PermissionMode.PLAN) {
            "PLAN MODE: read-only. Tool '$name' is blocked in plan mode — do NOT attempt " +
                "modifications. Research the workspace with read tools, then present a concise " +
                "numbered implementation plan as your FINAL answer (files to create/change, " +
                "key steps, risks)."
        } else {
            "ERROR: tool '$name' is denied in ${mode.name} mode."
        }

    private fun permissionDetail(name: String, args: JsonObject): String = when (name) {
        "run_command" -> args.jsonStr("command")?.trim()?.take(200) ?: "(no command)"
        "read_file", "write_file", "delete_file" -> args.jsonStr("path")?.trim()?.take(200) ?: "(no path)"
        "search" -> args.jsonStr("query")?.trim()?.take(120) ?: "(no query)"
        else -> name
    }

    private fun isDangerousTool(name: String, args: JsonObject): Boolean = when (name) {
        "delete_file" -> true
        "run_command" -> DANGEROUS_COMMAND.containsMatchIn(args.jsonStr("command").orEmpty())
        else -> false
    }

    /** Tulis file + ringkasan diff ringkas bila file sudah ada (fs backend saja). */
    private fun writeFileWithDiff(
        fs: WorkspaceFs?,
        workspace: String,
        path: String,
        content: String
    ): String {
        if (fs != null) {
            val old = if (fs.exists(path)) fs.readFile(path).takeIf { !it.startsWith("ERROR") } else null
            val res = fs.writeFile(path, content)
            if (res.startsWith("ERROR")) return res
            val diff = old?.let { diffSummary(it, content) }
            return if (diff != null) "$res · $diff" else res
        }
        return AgentTools.writeFile(workspace, path, content)
    }

    /** Estimasi diff berbasis himpunan baris (murah, cukup untuk step detail). */
    private fun diffSummary(old: String, new: String): String {
        val oldLines = old.lines().filter { it.isNotBlank() }.toSet()
        val newLines = new.lines().filter { it.isNotBlank() }.toSet()
        val removed = oldLines.count { it !in newLines }
        val added = newLines.count { it !in oldLines }
        return "+$added -$removed lines"
    }

    /**
     * Rules file gaya OpenCode/Claude: AGENTS.md → CLAUDE.md dari root workspace.
     * Maks 4000 karakter; null bila tidak ada.
     */
    private fun readProjectRules(fs: WorkspaceFs?, workspace: String): String? {
        val fromFs = fs?.let { f ->
            listOf("AGENTS.md", "CLAUDE.md").firstNotNullOfOrNull { rulesName ->
                f.readFile(rulesName).takeIf { !it.startsWith("ERROR") }
            }
        }
        if (fromFs != null) return fromFs.take(4000)
        if (workspace.isNotBlank()) {
            listOf("AGENTS.md", "CLAUDE.md").forEach { rulesName ->
                val file = java.io.File(workspace, rulesName)
                if (file.isFile) {
                    val text = runCatching { file.readText() }.getOrNull()
                    if (!text.isNullOrBlank()) return text.take(4000)
                }
            }
        }
        return null
    }

    private fun modeBlock(mode: PermissionMode): String = when (mode) {
        PermissionMode.ASK ->
            "PERMISSION MODE: ASK — write/delete/command tools will ask the user for " +
                "approval; prefer minimal, well-explained changes."
        PermissionMode.PLAN ->
            "PERMISSION MODE: PLAN — read-only research mode. Do not modify anything. " +
                "Investigate the workspace, then deliver a numbered implementation plan " +
                "as your FINAL answer."
        PermissionMode.AUTO_READ_EDIT ->
            "PERMISSION MODE: AUTO READ-EDIT — file reads and writes are auto-approved; " +
                "commands, deletes and MCP tools ask for approval."
        PermissionMode.FULL_ACCESS ->
            "PERMISSION MODE: FULL ACCESS (YOLO) — all tools are auto-approved; still " +
                "avoid destructive actions unless the task demands them."
    }

    // ------------------------------------------------------------------
    // Label human-readable (tampil di chat)
    // ------------------------------------------------------------------

    private fun humanize(name: String, args: JsonObject): String {
        // Tool MCP (mcp_<server>_<tool>) dicek dulu — bukan cabang when ber-subjek.
        if (name.startsWith("mcp_")) {
            return "Calling ${name.removePrefix("mcp_").replace('_', ' ').trim()}"
        }
        return when (name) {
            "list_files" -> {
                val p = args.jsonStr("path").orEmpty().trim()
                if (p.isEmpty() || p == "." || p == "./") "Exploring project" else "Listing $p"
            }
            "read_file" -> "Reading ${labelPath(args, "path")}"
            "write_file" -> "Writing ${labelPath(args, "path")}"
            "delete_file" -> "Deleting ${labelPath(args, "path")}"
            "search" -> "Searching '${args.jsonStr("query")?.trim()?.take(40).orEmpty()}'"
            "run_command" -> "Running: ${args.jsonStr("command")?.trim()?.take(60).orEmpty()}"
            else -> "Using tool $name"
        }
    }

    private fun labelPath(args: JsonObject, key: String): String =
        args.jsonStr(key)?.trim()?.take(80)?.ifBlank { null } ?: "(unspecified)"

    private companion object {
        const val MAX_COMMAND_OUTPUT = 4000

        /** Pola command berbahaya untuk badge isDangerous di dialog izin. */
        val DANGEROUS_COMMAND = Regex(
            "rm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)|mkfs|dd\\s+if=|" +
                "chmod\\s+-R\\s+777|shutdown|reboot|:\\(\\)\\s*\\{"
        )
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
