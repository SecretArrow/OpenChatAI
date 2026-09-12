package com.openchai.core.agent

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val success: Boolean get() = exitCode == 0
}

/**
 * Menjalankan command shell melalui ProcessManager (satu pintu keamanan).
 * Agent TIDAK boleh exec process sendiri di luar interface ini.
 */
interface CommandRunner {
    suspend fun run(command: String, cwd: String?, timeoutMs: Long = 120_000L): CommandResult
}
