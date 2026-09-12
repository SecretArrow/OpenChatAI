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
import java.io.FileOutputStream
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
 *  - pause/resume via HTTP Range + sidecar "<id>.meta.json" (url/ETag/total):
 *    [pauseDownload] menjeda & menyimpan part, memanggil [download] lagi =
 *    lanjut dari offset terakhir, [cancelDownload] = discard penuh,
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

    /** Fase unduhan satu model (PAUSED = dijeda user; part + meta dipertahankan). */
    enum class DownloadPhase { IDLE, DOWNLOADING, PAUSED, DONE, FAILED }

    /** Snapshot status unduhan satu model. */
    data class DownloadState(
        val state: DownloadPhase = DownloadPhase.IDLE,
        val progressBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val error: String? = null
    )

    /**
     * Sidecar metadata unduhan ("<id>.meta.json") untuk integritas resume:
     * url sumber, ETag server (dikirim ulang sebagai If-Range), dan total byte
     * konten (deteksi sumber berubah sejak pause).
     */
    @Serializable
    data class DownloadMeta(
        val url: String,
        val etag: String? = null,
        val totalBytes: Long = 0L
    )

    /** Sinyal internal pause (bukan error): ditangkap khusus oleh job download. */
    private class PauseSignal : Exception()

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
    private val pauseFlags = ConcurrentHashMap<String, AtomicBoolean>()
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

    /**
     * Mulai / lanjutkan unduh [model] (abaikan bila sudah berjalan). Bila ada
     * "<id>.part" tersisa (pause / gagal / proses mati), unduhan otomatis
     * dilanjutkan dari offset terakhir (HTTP Range). Progress → [downloadStates].
     */
    fun download(model: CatalogModel) {
        if (jobs.containsKey(model.id)) return
        val cancelled = AtomicBoolean(false)
        cancelFlags[model.id] = cancelled
        val paused = AtomicBoolean(false)
        pauseFlags[model.id] = paused
        jobs[model.id] = scope.launch(Dispatchers.IO) {
            // Emit awal: bila part lama ada (resume), tampilkan offset tersimpan
            // sejak frame pertama, bukan 0 (di Dispatchers.IO — aman baca file).
            val existing = partFile(model.id)
            val resumeFrom = if (existing.isFile) existing.length() else 0L
            val resumeTotal =
                if (resumeFrom > 0L) readMeta(model.id)?.totalBytes?.takeIf { it > 0 }
                    ?: model.sizeBytes
                else model.sizeBytes
            setDownloadState(
                model.id,
                DownloadState(DownloadPhase.DOWNLOADING, resumeFrom, resumeTotal)
            )
            try {
                doDownload(model, cancelled, paused)
                if (cancelled.get()) {
                    removePartFile(model.id)
                    deleteMeta(model.id)
                    setDownloadState(model.id, DownloadState())
                } else {
                    val file = downloadedFile(model.id)
                    setDownloadState(
                        model.id,
                        DownloadState(DownloadPhase.DONE, file.length(), file.length())
                    )
                }
            } catch (sig: PauseSignal) {
                // Pause eksplisit: part + meta DIPERTAHANKAN agar Resume bisa
                // lanjut dari offset terakhir.
                emitPaused(model)
            } catch (ce: CancellationException) {
                if (paused.get()) {
                    // pauseDownload() membatalkan job → perlakukan sebagai pause
                    // (part + meta dipertahankan).
                    emitPaused(model)
                } else {
                    // Cancel lama = "Discard": part + meta dihapus, kembali IDLE.
                    removePartFile(model.id)
                    deleteMeta(model.id)
                    setDownloadState(model.id, DownloadState())
                }
                throw ce
            } catch (e: Exception) {
                when {
                    // call.cancel() saat pause memicu IOException di read blocking
                    // → cek flag pause lebih dulu sebelum dianggap gagal.
                    paused.get() -> emitPaused(model)
                    cancelled.get() -> {
                        // Dibatalkan user (flag/call.cancel) → IDLE + discard.
                        removePartFile(model.id)
                        deleteMeta(model.id)
                        setDownloadState(model.id, DownloadState())
                    }
                    else -> {
                        // Gagal (jaringan/dll): part + meta DIPERTAHANKAN agar
                        // Retry → download() otomatis resume dari offset terakhir.
                        setDownloadState(
                            model.id,
                            DownloadState(
                                state = DownloadPhase.FAILED,
                                error = e.message ?: "Download failed"
                            )
                        )
                    }
                }
            } finally {
                jobs.remove(model.id)
                calls.remove(model.id)
                cancelFlags.remove(model.id)
                pauseFlags.remove(model.id)
            }
        }
    }

    /**
     * Jeda unduhan [id]: set flag pause lalu cancel call/job. Part + meta
     * DIPERTAHANKAN — lanjutkan dengan memanggil [download] lagi (auto-resume).
     */
    fun pauseDownload(id: String) {
        if (!jobs.containsKey(id)) return
        pauseFlags.getOrPut(id) { AtomicBoolean(false) }.set(true)
        calls[id]?.cancel()
        jobs[id]?.cancel()
    }

    /**
     * Discard penuh unduhan [id]: bila job masih berjalan → flag + call.cancel()
     * dan job membersihkan part + meta; bila tidak ada job (PAUSED / part
     * tertinggal setelah proses mati) → hapus part + meta langsung, status IDLE.
     */
    fun cancelDownload(id: String) {
        cancelFlags[id]?.set(true)
        calls[id]?.cancel()
        val job = jobs[id]
        if (job != null) {
            job.cancel()
        } else {
            removePartFile(id)
            deleteMeta(id)
            _downloadStates.value = _downloadStates.value - id
        }
    }

    /**
     * Ukuran part yang bisa dilanjutkan per model id (scan "*.part" di [dir] →
     * id → byte tersimpan). Dipakai UI untuk menawarkan Resume setelah proses
     * mati (state unduhan hilang tapi part masih ada). Sinkron dan murah.
     */
    fun resumableSizes(): Map<String, Long> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".part") }
            ?.associate { f -> f.name.removeSuffix(".part") to f.length() }
            ?: emptyMap()

    /**
     * Inti unduhan dengan dukungan resume HTTP Range:
     *  - part lama + meta cocok → request "Range: bytes=<offset>-" (plus
     *    "If-Range: <etag>" bila ada) → 206 → APPEND dari offset;
     *  - server jawab 200 (Range diabaikan / If-Range mismatch) → part
     *    di-truncate dan ditulis ulang dari nol;
     *  - 416 + part persis selengkap meta.totalBytes → langsung finalisasi;
     *  - 416 lain / total Content-Range ≠ meta.totalBytes → part dibuang dan
     *    GET biasa diulang dari nol (loop restart, dibatasi MAX_HTTP_RESTARTS).
     */
    private suspend fun doDownload(model: CatalogModel, cancelled: AtomicBoolean, paused: AtomicBoolean) {
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

        var attempt = 0
        while (true) {
            attempt++
            var restartRequested = false

            val part = partFile(model.id)
            var startOffset = 0L
            var meta: DownloadMeta? = null
            if (part.isFile && part.length() > 0L) {
                val saved = readMeta(model.id)
                if (saved != null && saved.url != model.url) {
                    // Sumber berubah sejak part ditulis → part tak bisa dipercaya.
                    removePartFile(model.id)
                    deleteMeta(model.id)
                } else {
                    startOffset = part.length()
                    meta = saved
                }
            }

            val request = Request.Builder().url(model.url).apply {
                if (startOffset > 0L) {
                    header("Range", "bytes=$startOffset-")
                    meta?.etag?.let { header("If-Range", it) }
                }
            }.build()
            val call = DownloadHttp.newCall(request)
            calls[model.id] = call

            try {
                call.execute().use { resp ->
                    if (paused.get()) throw PauseSignal()
                    if (cancelled.get()) throw IOException("Download cancelled")
                    val code = resp.code
                    val body = resp.body
                    when {
                        // 416 + part persis selengkap total tersimpan → unduhan
                        // sempat selesai tepat saat pause; finalisasi langsung.
                        code == 416 &&
                            meta != null && meta.totalBytes > 0L &&
                            part.length() == meta.totalBytes -> finalizeDownload(model)

                        // 416 lainnya: offset tidak valid / part korup → buang,
                        // ulangi GET biasa dari nol.
                        code == 416 -> {
                            removePartFile(model.id)
                            deleteMeta(model.id)
                            restartRequested = true
                        }

                        // 206 Partial Content: server menghormati Range → APPEND.
                        code == 206 && startOffset > 0L -> {
                            val declaredTotal = parseContentRangeTotal(resp.header("Content-Range"))
                            if (meta != null && meta.totalBytes > 0L &&
                                declaredTotal != null && declaredTotal != meta.totalBytes
                            ) {
                                // Isi sumber berubah sejak pause (total ≠ meta)
                                // → part basi, buang dan mulai ulang dari nol.
                                removePartFile(model.id)
                                deleteMeta(model.id)
                                restartRequested = true
                            } else {
                                copyBodyToFile(
                                    model, resp,
                                    body ?: throw IOException("Empty response body"),
                                    part, startOffset, meta, append = true, cancelled, paused
                                )
                            }
                        }

                        // 200 (atau 2xx lain): server abaikan Range / If-Range
                        // mismatch → truncate part, tulis ulang dari nol.
                        resp.isSuccessful -> copyBodyToFile(
                            model, resp,
                            body ?: throw IOException("Empty response body"),
                            part, 0L, null, append = false, cancelled, paused
                        )

                        else -> throw IOException("HTTP $code while downloading ${model.name}")
                    }
                }
            } catch (e: IOException) {
                // call.cancel() (pause maupun cancel) memicu IOException di read
                // blocking: bedakan pause → cancel → error asli.
                if (paused.get()) throw PauseSignal()
                if (cancelled.get()) throw IOException("Download cancelled")
                throw e
            }

            if (!restartRequested) break
            if (attempt >= MAX_HTTP_RESTARTS) {
                throw IOException("Server response kept changing while downloading ${model.name}")
            }
        }
    }

    /**
     * Copy body respons ke part file (append bila resume dari offset), simpan
     * meta sidecar SEBELUM copy, emit progress per chunk (throttle), lalu
     * finalisasi (rename "<id>.part" → "<id>.gguf").
     */
    private fun copyBodyToFile(
        model: CatalogModel,
        resp: okhttp3.Response,
        body: okhttp3.ResponseBody,
        part: File,
        startOffset: Long,
        meta: DownloadMeta?,
        append: Boolean,
        cancelled: AtomicBoolean,
        paused: AtomicBoolean
    ) {
        val contentLength = body.contentLength()
        val total = when {
            resp.code == 206 ->
                contentLength.takeIf { it > 0 }?.let { it + startOffset }
                    ?: meta?.totalBytes?.takeIf { it > 0 }
                    ?: model.sizeBytes
            else -> contentLength.takeIf { it > 0 } ?: model.sizeBytes
        }

        // Simpan meta sebelum copy agar pause/proses mati kapan pun bisa resume
        // dengan total & ETag yang konsisten dengan part yang sudah tertulis.
        writeMeta(
            model.id,
            DownloadMeta(url = model.url, etag = resp.header("ETag"), totalBytes = total)
        )

        // Emit awal: saat resume, progress mulai dari startOffset (bukan 0).
        setDownloadState(model.id, DownloadState(DownloadPhase.DOWNLOADING, startOffset, total))

        var progress = startOffset
        var lastEmit = 0L
        body.byteStream().use { input ->
            FileOutputStream(part, append).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    // Cek flag per chunk: pause dulu, baru cancel.
                    if (paused.get()) throw PauseSignal()
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

        finalizeDownload(model)
    }

    /** Finalisasi unduhan: rename "<id>.part" → "<id>.gguf" + bersihkan meta. */
    private fun finalizeDownload(model: CatalogModel) {
        val part = partFile(model.id)
        val target = downloadedFile(model.id)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            throw IOException("Failed to finalize download of ${model.name}")
        }
        deleteMeta(model.id)
    }

    /** Parse header "Content-Range: bytes 100-999/1234" → 1234 (null bila "*"/rusak). */
    private fun parseContentRangeTotal(value: String?): Long? {
        if (value == null) return null
        return value.substringAfterLast('/').trim().toLongOrNull()
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

    /** Hapus model [id] (unduhan berjalan dibatalkan; part + meta ikut dibersihkan). */
    fun delete(id: String) {
        cancelDownload(id)
        downloadedFile(id).delete()
        partFile(id).delete()
        deleteMeta(id)
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

    /** Emit status PAUSED dengan progress terakhir (part + meta dipertahankan). */
    private fun emitPaused(model: CatalogModel) {
        val part = partFile(model.id)
        setDownloadState(
            model.id,
            DownloadState(
                state = DownloadPhase.PAUSED,
                progressBytes = if (part.isFile) part.length() else 0L,
                totalBytes = readMeta(model.id)?.totalBytes?.takeIf { it > 0 } ?: model.sizeBytes
            )
        )
    }

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

    private fun metaFile(id: String) = File(dir, "$id.meta.json")

    /** Baca meta sidecar resume (null bila tidak ada / JSON korup). */
    private fun readMeta(id: String): DownloadMeta? = runCatching {
        val file = metaFile(id)
        if (!file.isFile) return@runCatching null
        json.decodeFromString<DownloadMeta>(file.readText())
    }.getOrNull()

    /** Tulis meta sidecar resume (url + ETag + total byte). */
    private fun writeMeta(id: String, meta: DownloadMeta) {
        metaFile(id).writeText(json.encodeToString(DownloadMeta.serializer(), meta))
    }

    /** Hapus meta sidecar resume (abaikan bila tidak ada). */
    private fun deleteMeta(id: String) {
        metaFile(id).delete()
    }

    private fun removePartFile(id: String) {
        partFile(id).delete()
    }

    private companion object {
        const val CATALOG_ASSET = "models.json"
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 200L

        /** Batas restart GET biasa bila respons server tak cocok dengan part. */
        const val MAX_HTTP_RESTARTS = 3

        /** Magic bytes "GGUF" (0x47 0x47 0x55 0x46) untuk validasi file impor. */
        val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    }
}
