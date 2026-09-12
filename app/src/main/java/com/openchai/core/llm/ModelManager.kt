package com.openchai.core.llm

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Format ukuran byte menjadi string manusiawi ("631.0 MB", "1.7 GB"). */
fun humanBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

/**
 * OkHttpClient khusus unduhan model (pola timeout sama dengan ai/Http.kt):
 * connect cepat gagal, read per-chunk cukup longgar untuk jaringan lambat.
 * Unduhan model bisa berjalan sangat lama sehingga tidak memakai client provider AI.
 */
private val DownloadHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/**
 * Katalog + manajemen model GGUF on-device:
 *  - katalog dibaca dari assets "models.json" (kotlinx.serialization),
 *  - unduhan streaming OkHttp ke "<id>.part" lalu di-rename "<id>.gguf",
 *  - progress dipublikasikan lewat [downloadStates] (StateFlow per model id),
 *  - cancel via [cancelDownload] (flag AtomicBoolean + call.cancel()),
 *  - impor/ekspor file GGUF via SAF (importModel/exportModel) → [transferState].
 */
class ModelManager(private val context: Context, private val scope: CoroutineScope) {

    /** Satu entri katalog model GGUF (dipetakan langsung dari models.json). */
    @Serializable
    data class CatalogModel(
        val id: String,
        val name: String,
        val family: String,
        val quant: String,
        val sizeBytes: Long,
        val url: String,
        val description: String
    )

    /** Fase unduhan satu model. */
    enum class DownloadPhase { IDLE, DOWNLOADING, DONE, FAILED }

    /** Snapshot status unduhan satu model. */
    data class DownloadState(
        val state: DownloadPhase = DownloadPhase.IDLE,
        val progressBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val error: String? = null
    )

    /** Fase transfer file model (impor/ekspor). */
    enum class TransferPhase { RUNNING, DONE, FAILED }

    /** Snapshot status transfer impor/ekspor (null = idle, tidak ada transfer berjalan). */
    data class TransferState(val phase: TransferPhase, val message: String? = null)

    val dir: File = File(context.filesDir, "models")

    private val json = Json { ignoreUnknownKeys = true }
    private val catalogCache = AtomicReference<List<CatalogModel>?>(null)

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val _transferState = MutableStateFlow<TransferState?>(null)
    val transferState: StateFlow<TransferState?> = _transferState.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()

    // ----------------------------------------------------------------------
    // Katalog
    // ----------------------------------------------------------------------

    /** Baca katalog dari assets (hasil parse di-cache setelah berhasil). */
    suspend fun catalog(): List<CatalogModel> = withContext(Dispatchers.IO) {
        catalogCache.get()?.let { return@withContext it }
        val parsed = runCatching {
            val text = context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString<List<CatalogModel>>(text)
        }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) catalogCache.set(parsed)
        parsed
    }

    // ----------------------------------------------------------------------
    // Unduhan
    // ----------------------------------------------------------------------

    /** Mulai unduh [model] (abaikan bila sudah berjalan). Progress → [downloadStates]. */
    fun download(model: CatalogModel) {
        if (jobs.containsKey(model.id)) return
        val cancelled = AtomicBoolean(false)
        cancelFlags[model.id] = cancelled
        jobs[model.id] = scope.launch(Dispatchers.IO) {
            setDownloadState(model.id, DownloadState(DownloadPhase.DOWNLOADING, 0L, model.sizeBytes))
            try {
                doDownload(model, cancelled)
                if (cancelled.get()) {
                    removePartFile(model.id)
                    setDownloadState(model.id, DownloadState())
                } else {
                    val file = downloadedFile(model.id)
                    setDownloadState(
                        model.id,
                        DownloadState(DownloadPhase.DONE, file.length(), file.length())
                    )
                }
            } catch (ce: CancellationException) {
                removePartFile(model.id)
                setDownloadState(model.id, DownloadState())
                throw ce
            } catch (e: Exception) {
                removePartFile(model.id)
                if (cancelled.get()) {
                    // Dibatalkan user (flag/call.cancel) → kembali IDLE, bukan gagal.
                    setDownloadState(model.id, DownloadState())
                } else {
                    setDownloadState(
                        model.id,
                        DownloadState(
                            state = DownloadPhase.FAILED,
                            error = e.message ?: "Download failed"
                        )
                    )
                }
            } finally {
                jobs.remove(model.id)
                calls.remove(model.id)
                cancelFlags.remove(model.id)
            }
        }
    }

    /** Batalkan unduhan [id] (flag + call.cancel(); part file dibersihkan oleh job). */
    fun cancelDownload(id: String) {
        cancelFlags[id]?.set(true)
        calls[id]?.cancel()
        jobs[id]?.cancel()
    }

    private suspend fun doDownload(model: CatalogModel, cancelled: AtomicBoolean) {
        dir.mkdirs()
        // Cek ruang kosong: butuh ~1.2× ukuran model (file + margin rename/write).
        if (model.sizeBytes > 0L) {
            val required = (model.sizeBytes * 12L) / 10L
            if (dir.usableSpace < required) {
                throw IOException(
                    "Not enough free space — ${humanBytes(required)} needed, " +
                        "${humanBytes(dir.usableSpace)} available"
                )
            }
        }

        val request = Request.Builder().url(model.url).build()
        val call = DownloadHttp.newCall(request)
        calls[model.id] = call

        call.execute().use { resp ->
            if (cancelled.get()) throw IOException("Download cancelled")
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} while downloading ${model.name}")
            }
            val body = resp.body ?: throw IOException("Empty response body")
            val total = body.contentLength().takeIf { it > 0 } ?: model.sizeBytes
            val part = partFile(model.id)
            var progress = 0L
            var lastEmit = 0L

            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelled.get()) throw IOException("Download cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        progress += read
                        // Update progress per chunk, di-throttle ~200 ms agar
                        // StateFlow tidak membanjiri recomposition UI.
                        val now = System.nanoTime() / 1_000_000L
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                            lastEmit = now
                            setDownloadState(
                                model.id,
                                DownloadState(DownloadPhase.DOWNLOADING, progress, total)
                            )
                        }
                    }
                    output.flush()
                    setDownloadState(
                        model.id,
                        DownloadState(DownloadPhase.DOWNLOADING, progress, if (total > 0) total else progress)
                    )
                }
            }

            // Finalisasi: rename "<id>.part" → "<id>.gguf".
            val target = downloadedFile(model.id)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw IOException("Failed to finalize download of ${model.name}")
            }
        }
    }

    // ----------------------------------------------------------------------
    // Impor / Ekspor (SAF)
    // ----------------------------------------------------------------------

    /** Reset status transfer ke idle (dipanggil UI setelah snackbar ditampilkan). */
    fun clearTransferState() {
        _transferState.value = null
    }

    /**
     * Impor file GGUF dari [uri] (SAF OpenDocument) ke direktori model:
     *  - validasi magic bytes "GGUF" (pushback, tanpa reopen stream),
     *  - nama file asli disanitasi menjadi id model (tabrakan → "-2", "-3", …),
     *  - cek ruang kosong ~1.2× ukuran sumber, copy streaming ke "<id>.part",
     *  - rename final ke "<id>.gguf" (pola sama dengan doDownload).
     */
    suspend fun importModel(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        setTransferState(TransferState(TransferPhase.RUNNING, "Importing model…"))
        var part: File? = null
        try {
            dir.mkdirs()
            val resolver = context.contentResolver
            val input = resolver.openInputStream(uri)
                ?: throw IOException("Cannot open selected file")
            input.use { stream ->
                // Validasi magic bytes "GGUF": baca 4 byte pertama, lalu lanjutkan
                // copy dari byte ke-4 pada stream yang sama (pushback — jangan reopen).
                val magic = ByteArray(4)
                var magicRead = 0
                while (magicRead < magic.size) {
                    val n = stream.read(magic, magicRead, magic.size - magicRead)
                    if (n < 0) break
                    magicRead += n
                }
                if (magicRead < magic.size || !magic.contentEquals(GGUF_MAGIC)) {
                    setTransferState(TransferState(TransferPhase.FAILED, "Not a valid GGUF file"))
                    val failure: Result<File> =
                        Result.failure(IllegalStateException("Not a valid GGUF file"))
                    return@withContext failure
                }

                // Nama file asli (bila provider menyediakan) → id model tersanitasi.
                val info = queryFileInfo(uri)
                val id = sanitizeImportName(info?.first)

                // Hindari tabrakan id model yang sudah ada: "-2", "-3", …
                var unique = id
                var suffix = 2
                while (downloadedFile(unique).exists()) {
                    unique = "$id-$suffix"
                    suffix++
                }

                // Cek ruang kosong: butuh ~1.2× ukuran sumber (bila diketahui).
                val sourceSize = info?.second ?: 0L
                if (sourceSize > 0L) {
                    val required = (sourceSize * 12L) / 10L
                    if (dir.usableSpace < required) {
                        throw IOException(
                            "Not enough free space — ${humanBytes(required)} needed, " +
                                "${humanBytes(dir.usableSpace)} available"
                        )
                    }
                }

                // Copy streaming (buffer 64KB) ke "<id>.part".
                val staging = partFile(unique)
                part = staging
                staging.outputStream().use { output ->
                    // Pushback: 4 byte magic yang sudah dibaca ditulis lebih dulu.
                    output.write(magic, 0, magicRead)
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }

                // Finalisasi: rename "<id>.part" → "<id>.gguf" (hapus target lama dulu).
                val target = downloadedFile(unique)
                if (target.exists()) target.delete()
                if (!staging.renameTo(target)) {
                    throw IOException("Failed to finalize import of $unique")
                }
                part = null

                setTransferState(
                    TransferState(
                        TransferPhase.DONE,
                        "Imported $unique (${humanBytes(target.length())})"
                    )
                )
                Result.success(target)
            }
        } catch (ce: CancellationException) {
            part?.delete()
            setTransferState(null) // Dibatalkan (scope mati) → kembali idle, bukan gagal.
            throw ce
        } catch (e: Exception) {
            part?.delete()
            setTransferState(TransferState(TransferPhase.FAILED, e.message ?: "Import failed"))
            Result.failure(e)
        }
    }

    /**
     * Ekspor model [id] ke [uri] tujuan (SAF CreateDocument, mode "wt" = truncate).
     * Copy streaming 64KB dari file sumber ke output stream penyedia dokumen.
     */
    suspend fun exportModel(id: String, target: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        setTransferState(TransferState(TransferPhase.RUNNING, "Exporting $id.gguf…"))
        try {
            val source = downloadedFile(id)
            if (!source.isFile) throw IOException("Model not found: $id")
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: throw IOException("Cannot open destination")
            output.use { out ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
            }
            setTransferState(TransferState(TransferPhase.DONE, "Exported to selected location"))
            Result.success(Unit)
        } catch (ce: CancellationException) {
            setTransferState(null) // Dibatalkan (scope mati) → kembali idle, bukan gagal.
            throw ce
        } catch (e: Exception) {
            setTransferState(TransferState(TransferPhase.FAILED, e.message ?: "Export failed"))
            Result.failure(e)
        }
    }

    // ----------------------------------------------------------------------
    // Model terpasang
    // ----------------------------------------------------------------------

    /** Semua file *.gguf di direktori model, urut nama. */
    fun installedModels(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Hapus model [id] (unduhan berjalan juga dibatalkan). */
    fun delete(id: String) {
        cancelDownload(id)
        downloadedFile(id).delete()
        partFile(id).delete()
        _downloadStates.value = _downloadStates.value - id
    }

    /** Path absolut tempat model [id] tersimpan. */
    fun pathOf(id: String): String = downloadedFile(id).absolutePath

    // ----------------------------------------------------------------------
    // Info perangkat
    // ----------------------------------------------------------------------

    /** Total RAM perangkat dalam MB (0 bila tidak tersedia). */
    fun deviceRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / (1024L * 1024L)).toInt()
    }

    /**
     * Rekomendasi ukuran model sesuai RAM:
     * <3GB → 0.5B; 3–4GB → 1B; 4–8GB → 1.5–2B; >8GB → 3B.
     */
    fun recommendationText(): String {
        val ramMb = deviceRamMb()
        if (ramMb <= 0) return "Device RAM unknown — pick the smallest model first."
        val gb = ramMb / 1024.0
        val ram = String.format(Locale.US, "%.1f", gb)
        return when {
            gb < 3.0 ->
                "This device has ~$ram GB RAM — use a 0.5B model (Qwen2.5 0.5B Q8_0) for smooth on-device chat."
            gb < 4.0 ->
                "This device has ~$ram GB RAM — a 1B model (Qwen2.5 Coder 1.5B or Llama 3.2 1B) fits comfortably."
            gb < 8.0 ->
                "This device has ~$ram GB RAM — 1.5B–2B models (Llama 3.2 1B, Gemma 2 2B) are a good fit."
            else ->
                "This device has ~$ram GB RAM — you can run 3B models (Phi-3.5 Mini, Qwen2.5 Coder 3B) comfortably."
        }
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private fun setDownloadState(id: String, state: DownloadState) {
        _downloadStates.value = _downloadStates.value + (id to state)
    }

    private fun setTransferState(state: TransferState?) {
        _transferState.value = state
    }

    /** DISPLAY_NAME + SIZE file SAF (null bila query gagal / kolom tidak tersedia). */
    private fun queryFileInfo(uri: Uri): Pair<String?, Long?>? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else null
            name to size
        }
    }.getOrNull()

    /**
     * Sanitasi nama file hasil impor menjadi id model: tanpa path, tanpa suffix
     * ".gguf", hanya [A-Za-z0-9._-]; hasil kosong → "imported-model".
     */
    private fun sanitizeImportName(raw: String?): String {
        var name = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
        if (name.endsWith(".gguf", ignoreCase = true)) name = name.dropLast(5)
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return name.ifBlank { "imported-model" }
    }

    private fun partFile(id: String) = File(dir, "$id.part")

    private fun downloadedFile(id: String) = File(dir, "$id.gguf")

    private fun removePartFile(id: String) {
        partFile(id).delete()
    }

    private companion object {
        const val CATALOG_ASSET = "models.json"
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 200L

        /** Magic bytes "GGUF" (0x47 0x47 0x55 0x46) untuk validasi file impor. */
        val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    }
}
