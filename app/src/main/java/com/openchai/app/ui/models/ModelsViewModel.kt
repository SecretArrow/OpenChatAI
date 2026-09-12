package com.openchai.app.ui.models

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openchai.app.OpenChatApp
import com.openchai.core.llm.ModelManager
import com.openchai.core.model.ProviderId
import com.openchai.core.settings.AppSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel screen Models: katalog GGUF (unduh/cancel), impor/ekspor file GGUF,
 * model terpasang (Use/Delete), dan rekomendasi berdasarkan RAM perangkat.
 */
class ModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as OpenChatApp).container
    private val manager = container.modelManager
    private val settingsRepo = container.settingsRepository

    val settings: StateFlow<AppSettings> = settingsRepo.settings

    /** Status unduhan per model id (dibagikan dari [ModelManager]). */
    val downloadStates: StateFlow<Map<String, ModelManager.DownloadState>> = manager.downloadStates

    /** Status transfer impor/ekspor (null = idle; dibagikan dari [ModelManager]). */
    val transferState: StateFlow<ModelManager.TransferState?> = manager.transferState

    private val _catalog = MutableStateFlow<List<ModelManager.CatalogModel>>(emptyList())
    val catalog: StateFlow<List<ModelManager.CatalogModel>> = _catalog.asStateFlow()

    private val _installed = MutableStateFlow<List<File>>(emptyList())
    val installed: StateFlow<List<File>> = _installed.asStateFlow()

    init {
        refresh()
    }

    /** Muat ulang katalog + daftar model terpasang. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _catalog.value = manager.catalog()
            _installed.value = manager.installedModels()
        }
    }

    /** Mulai unduh [model]; daftar terpasang di-refresh saat unduhan selesai. */
    fun download(model: ModelManager.CatalogModel) {
        manager.download(model)
        viewModelScope.launch(Dispatchers.IO) {
            manager.downloadStates.first { states ->
                states[model.id]?.state == ModelManager.DownloadPhase.DONE
            }
            _installed.value = manager.installedModels()
        }
    }

    fun cancelDownload(id: String) {
        manager.cancelDownload(id)
    }

    /** Impor model GGUF dari SAF uri; daftar terpasang di-refresh saat sukses. */
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            manager.importModel(uri).onSuccess {
                _installed.value = manager.installedModels()
            }
        }
    }

    /** Ekspor model [id] ke SAF uri tujuan (SAF CreateDocument). */
    fun exportModel(id: String, uri: Uri) {
        viewModelScope.launch {
            manager.exportModel(id, uri)
        }
    }

    /** Reset status transfer (dipanggil UI setelah snackbar ditampilkan). */
    fun clearTransfer() {
        manager.clearTransferState()
    }

    /** Hapus model terpasang; bila model aktif ikut terhapus, kosongkan pilihan lokal. */
    fun delete(id: String) {
        manager.delete(id)
        viewModelScope.launch(Dispatchers.IO) {
            _installed.value = manager.installedModels()
            val s = settingsRepo.settings.value
            if (s.localModelPath.isNotBlank() && !File(s.localModelPath).isFile) {
                settingsRepo.update { it.copy(localModelPath = "") }
            }
        }
    }

    /**
     * Jadikan [file] model AI aktif: set path lokal, provider LOCAL, dan nama
     * model, lalu [onDone] dipanggil (UI kembali ke Chat).
     */
    fun useModel(file: File, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepo.update {
                it.copy(
                    localModelPath = file.absolutePath,
                    activeProvider = ProviderId.LOCAL,
                    selectedModel = file.name
                )
            }
            onDone()
        }
    }

    /** Teks rekomendasi model sesuai RAM perangkat (untuk banner). */
    fun recommendationText(): String = manager.recommendationText()
}
