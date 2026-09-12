package com.openchatai.app.ui.linux

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.openchatai.app.OpenChatApp
import com.openchai.core.linux.DeviceCapability
import com.openchai.core.linux.LinuxEnvPhase
import com.openchai.core.linux.LinuxEnvManager
import com.openchai.core.linux.LinuxInstallState
import com.openchai.core.linux.RootfsVariant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel layar setup lingkungan Linux: expose status instalasi
 * ([LinuxEnvManager.status] + [LinuxEnvManager.installState]), daftar variant
 * rootfs yang cocok dengan ABI perangkat, capability perangkat (ABI/RAM/storage),
 * dan aksi install/pause/cancel/remove.
 */
class LinuxSetupViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as OpenChatApp).container
    private val env: LinuxEnvManager = container.linuxEnv

    /** Fase lingkungan Linux saat ini (NOT_INSTALLED → READY, dst). */
    val status: StateFlow<LinuxEnvPhase> = env.status

    /** Progress instalasi aktif (null = idle). */
    val installState: StateFlow<LinuxInstallState?> = env.installState

    /** Capability perangkat: ABI, RAM total, storage bebas, status didukung + reason. */
    private val _capability = MutableStateFlow(env.deviceCapability())
    val capability: StateFlow<DeviceCapability> = _capability.asStateFlow()

    /**
     * Variant rootfs yang tersedia untuk ABI perangkat ini (filter dari
     * [LinuxEnvManager.variants]). ABI perangkat tidak berubah saat runtime,
     * jadi cukup dihitung sekali dari snapshot capability awal.
     */
    val variants: List<RootfsVariant> = env.variants.filter {
        it.abi.equals(_capability.value.abi, ignoreCase = true)
    }

    /** Lingkungan sudah terpasang & siap dipakai? */
    val isInstalled: Boolean
        get() = env.isInstalled()

    /** Mulai / lanjutkan (resume) instalasi variant [variantId]. */
    fun install(variantId: String) = env.install(variantId)

    /** Jeda unduhan berjalan (part + meta dipertahankan). */
    fun pause() = env.pauseInstall()

    /** Batalkan instalasi & buang part. */
    fun cancel() = env.cancelInstall()

    /** Hapus rootfs + workspace lingkungan Linux (workspace model AI tidak terpengaruh). */
    fun remove() = env.removeEnv()

    /** Muat ulang capability perangkat (RAM/storage bisa berubah saat runtime). */
    fun refresh() {
        _capability.value = env.deviceCapability()
    }
}
