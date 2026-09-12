package com.openchatai.app.ui.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openchatai.app.OpenChatApp
import com.openchai.core.linux.DeviceCapability
import com.openchai.core.linux.LinuxEnvPhase
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
 *
 * Sumber terminalHost/processSupervisor adalah val DELEGATING di AppContainer:
 * bila lingkungan Linux READY, sesi shell & proses otomatis lewat proot;
 * else fallback shell Android legacy. [linuxStatus] di-collect UI (chip/banner
 * TerminalPanel) — ia sekaligus memicu recompose agar getter delegating
 * dievaluasi ulang saat status berubah.
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as OpenChatApp).container

    val terminalHost: TerminalHost = container.terminalHost

    // Delegating (bukan processManager langsung) agar proses latar belakang
    // juga berpindah ke userspace Linux bila READY.
    val processSupervisor: ProcessSupervisor = container.processSupervisor

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val sessions: StateFlow<List<TerminalSessionInfo>> = terminalHost.sessions
    val processes: StateFlow<List<ManagedProcess>> = processSupervisor.processes

    /** Fase lingkungan Linux (READY → shell terminal = proot Ubuntu). */
    val linuxStatus: StateFlow<LinuxEnvPhase> = container.linuxEnv.status

    /** Capability perangkat (dipakai banner NOT_SUPPORTED di TerminalPanel untuk reason). */
    val linuxCapability: DeviceCapability = container.linuxEnv.deviceCapability()

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

    /** Tulis baris ke stdin background process (mis. REPL `python3 -i`). */
    fun writeProcessStdin(id: String, line: String) = processSupervisor.writeStdin(id, line)

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

    // ---- Passthrough lingkungan Linux (layar setup menangani UI lengkap; ----
    // ---- panel hanya butuh status, tapi aksi tetap tersedia bila perlu). ----

    /** Mulai/lanjutkan (resume) instalasi rootfs [variantId]. */
    fun installLinux(variantId: String) = container.linuxEnv.install(variantId)

    /** Jeda unduhan rootfs berjalan. */
    fun pauseLinux() = container.linuxEnv.pauseInstall()

    /** Batalkan instalasi rootfs berjalan. */
    fun cancelLinux() = container.linuxEnv.cancelInstall()

    /** Hapus lingkungan Linux terpasang. */
    fun removeLinux() = container.linuxEnv.removeEnv()

    companion object {
        const val MIN_FONT = 9
        const val MAX_FONT = 20
    }
}
