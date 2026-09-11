package com.openchai.app.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openchai.app.OpenChatApp
import com.openchai.core.runtime.ManagedProcess
import com.openchai.core.runtime.ProcessSupervisor
import com.openchai.core.settings.AppSettings
import com.openchai.core.terminal.TerminalHost
import com.openchai.core.terminal.TerminalSessionInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel panel terminal: menjembatani UI dengan TerminalHost,
 * ProcessSupervisor, dan SettingsRepository dari container aplikasi.
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as OpenChatApp).container

    val terminalHost: TerminalHost = container.terminalHost
    val processSupervisor: ProcessSupervisor = container.processManager

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val sessions: StateFlow<List<TerminalSessionInfo>> = terminalHost.sessions
    val processes: StateFlow<List<ManagedProcess>> = processSupervisor.processes

    /** Buat sesi shell baru di direktori proyek aktif (fallback: workspace root). */
    fun newSession(): String {
        val cwd = container.activeProject.value?.path?.takeIf { it.isNotBlank() }
            ?: container.workspaceManager.workspaceRoot()
        return terminalHost.createSession(cwd)
    }

    fun killSession(sessionId: String) = terminalHost.killSession(sessionId)

    fun write(sessionId: String, input: String) = terminalHost.write(sessionId, input)

    fun clear(sessionId: String) = terminalHost.clearOutput(sessionId)

    fun stopProcess(id: String) = processSupervisor.stop(id)

    fun restartProcess(id: String) = processSupervisor.restart(id)

    /** Buang entri proses non-aktif yang lebih tua dari 30 menit. */
    fun refreshProcesses() = processSupervisor.pruneFinished()

    fun outputFlow(sessionId: String): StateFlow<String> = terminalHost.output(sessionId)

    fun processOutputFor(id: String): String? = processSupervisor.outputFor(id)

    fun processOutputRevision(id: String): StateFlow<Long> = processSupervisor.outputRevision(id)

    /** Ubah ukuran font terminal (delta +1 / -1), clamp 9..20. */
    fun changeFontSize(delta: Int) {
        viewModelScope.launch {
            container.settingsRepository.update { current ->
                current.copy(
                    terminalFontSize = (current.terminalFontSize + delta).coerceIn(MIN_FONT, MAX_FONT)
                )
            }
        }
    }

    companion object {
        const val MIN_FONT = 9
        const val MAX_FONT = 20
    }
}
