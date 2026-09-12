package com.openchai.core.skills

/**
 * Penyuntik skill ke prompt agent.
 *
 * [promptBlock] mengubah hasil pencocokan [SkillLoader.matchForTask] menjadi satu
 * blok teks siap-tempel ke system prompt (mis. digabung dengan hasil
 * `BuiltInAgent.buildSystemPrompt()` oleh main agent). Bila tidak ada skill yang
 * cocok, fungsi mengembalikan string kosong sehingga prompt tidak bertambah.
 *
 * Batas: maksimal [MAX_SKILLS] skill dan [MAX_INSTRUCTION_CHARS] karakter per
 * instruksi, agar prompt lokal (model kecil on-device) tidak membengkak.
 */
object SkillInjector {

    private const val HEADER = "ACTIVE SKILLS (follow these guidelines for this task):"

    /** Batas panjang instruksi per skill sebelum dipotong dan diberi ellipsis. */
    private const val MAX_INSTRUCTION_CHARS = 1200

    /** Batas jumlah skill yang disuntikkan untuk satu tugas. */
    private const val MAX_SKILLS = 3

    /**
     * Blok prompt berisi skill aktif yang cocok dengan [task], atau "" bila
     * tidak ada skill relevan. Format:
     * ```
     * ACTIVE SKILLS (follow these guidelines for this task):
     * - <name>: <instructions>
     * - <name>: <instructions>
     * ```
     */
    suspend fun promptBlock(loader: SkillLoader, task: String): String {
        val skills = loader.matchForTask(task, MAX_SKILLS)
            .filter { it.instructions.isNotBlank() }
        if (skills.isEmpty()) return ""
        return buildString {
            append(HEADER)
            for (skill in skills) {
                append('\n')
                append("- ")
                append(skill.name.trim())
                append(": ")
                append(clip(skill.instructions.trim()))
            }
        }
    }

    private fun clip(instructions: String): String =
        if (instructions.length > MAX_INSTRUCTION_CHARS) {
            instructions.take(MAX_INSTRUCTION_CHARS) + "…"
        } else {
            instructions
        }
}
