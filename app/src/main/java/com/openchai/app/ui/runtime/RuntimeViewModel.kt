package com.openchai.app.ui.runtime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.openchai.app.OpenChatApp
import com.openchai.core.llm.InstallState
import com.openchai.core.llm.PackView
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel screen Runtime & Modul: lapisan tipis di atas [RuntimeManager]
 * milik AppContainer. Seluruh state (daftar pack, progress unduhan/pemasangan,
 * preferensi pasang otomatis, info manifest) dibagikan apa adanya sebagai
 * StateFlow, dan aksi UI dipassthrough ke manager.
 */
class RuntimeViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as OpenChatApp).container
    private val manager = container.runtimeManager

    /** Daftar pack dari manifest (kartu "bundled" selalu elemen pertama). */
    val packs: StateFlow<List<PackView>> = manager.packs

    /** Progress unduhan/pemasangan per pack id. */
    val installStates: StateFlow<Map<String, InstallState>> = manager.installStates

    /** Preferensi pasang otomatis (persist di SharedPreferences oleh manager). */
    val autoInstall: StateFlow<Boolean> = manager.autoInstall

    /** Info manifest terakhir; null bila manifest belum ada / gagal dimuat. */
    val manifestInfo: StateFlow<String?> = manager.manifestInfo

    /** Label runtime yang dipakai sesi ini (bawaan APK atau pack terpasang). */
    fun activeRuntimeLabel(): String = manager.activeRuntimeLabel()

    /** Mulai/lanjut (resume otomatis dari part) pemasangan pack [packId]. */
    fun install(packId: String) = manager.install(packId)

    /** Jeda unduhan pack [packId] (part + meta dipertahankan). */
    fun pause(packId: String) = manager.pauseInstall(packId)

    /** Batalkan unduhan pack [packId]. */
    fun cancel(packId: String) = manager.cancelInstall(packId)

    /** Hapus pack terpasang [packId]; mulai ulang berikutnya kembali ke bawaan. */
    fun remove(packId: String) = manager.removePack(packId)

    /** Ambil ulang manifest runtime/modul. */
    fun refresh() = manager.refreshManifest()

    /** Aktifkan/matikan pasang otomatis. */
    fun setAuto(enabled: Boolean) = manager.setAutoInstall(enabled)
}
