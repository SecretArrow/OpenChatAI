package com.openchai.core.llm

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/**
 * Satu pack runtime/modul dalam manifest (dipetakan langsung dari manifest.json).
 * [kind] = "RUNTIME" (berisi libopenchai_llama.so) atau "MODULE" (library pendukung).
 * [abi] non-blank hanya ditampilkan bila termasuk Build.SUPPORTED_ABIS perangkat;
 * [sha256] hex (kosong = verifikasi dilewati); [required] hanya relevan untuk MODULE.
 */
@Serializable
data class RuntimePack(
    val id: String,
    val kind: String,
    val name: String,
    val version: String,
    val abi: String? = null,
    val feature: String = "",
    val required: Boolean = false,
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val url: String,
    val description: String = ""
)

/** Manifest runtime yang ditarik dari URL konstanta dan di-cache ke runtime_cache/manifest.json. */
@Serializable
data class RuntimeManifest(
    val schema: Int = 1,
    val version: String = "",
    val packs: List<RuntimePack> = emptyList()
)

/** Fase instalasi satu pack (PAUSED = dijeda user; part + meta dipertahankan). */
enum class InstallPhase { IDLE, DOWNLOADING, PAUSED, EXTRACTING, DONE, FAILED }

/** Snapshot status instalasi satu pack. */
data class InstallState(
    val phase: InstallPhase = InstallPhase.IDLE,
    val progressBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null
)

/** Status kartu pack pada UI. */
enum class PackStatus { BUNDLED, INSTALLED, UPDATABLE, AVAILABLE }

/**
 * Baris kartu pack untuk UI. [installPhase]/[progressBytes]/[totalBytes]/[error]
 * adalah mirror [RuntimeManager.installStates] untuk id terkait (IDLE bila tidak aktif).
 */
data class PackView(
    val id: String,
    val kind: String,
    val name: String,
    val description: String,
    val status: PackStatus,
    val installedVersion: String?,
    val availableVersion: String?,
    val sizeBytes: Long,
    val installPhase: InstallPhase,
    val progressBytes: Long,
    val totalBytes: Long,
    val error: String?,
    val removable: Boolean
)

/**
 * Marker keberhasilan instalasi — ditulis sebagai "installed.json" di dalam dir
 * pack setelah ekstraksi sukses (keberadaan file ini = pack terpasang).
 */
@Serializable
data class InstalledInfo(
    val id: String,
    val version: String,
    val sha256: String = "",
    val abi: String = "",
    val installedAtEpochMs: Long = 0L
)

/** OkHttpClient khusus tarik manifest (connect cepat gagal, read longgar). */
private val ManifestHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/** OkHttpClient khusus unduhan pack (pola sama dengan engine unduhan ModelManager). */
private val InstallHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/**
 * Katalog + manajemen pack runtime/modul on-device:
 *  - manifest ditarik dari URL konstanta (kotlinx.serialization), di-cache ke
 *    filesDir/runtime_cache/manifest.json untuk fallback offline;
 *  - unduhan streaming OkHttp ke "<id>.part" di dalam dir pack dengan sidecar
 *    "<id>.meta.json" (url/ETag/total) — pola adaptif dari engine ModelManager:
 *    HTTP Range/If-Range, 206 append, 200 truncate, 416 finalisasi/restart;
 *  - finalisasi: verifikasi SHA-256 → ekstrak zip (guard zip-slip) → tulis
 *    installed.json → hapus part/meta → pasang [LlamaBridge.overrideLibPath]
 *    bila pack RUNTIME ber-ABI cocok;
 *  - pause/resume/cancel via [pauseInstall]/[install]/[cancelInstall],
 *    hapus pack terpasang via [removePack];
 *  - auto-install (opsional, [autoInstall]) hanya untuk MODULE required yang
 *    belum terpasang + update RUNTIME yang sudah terpasang — pack opsional yang
 *    belum pernah dipasang TIDAK PERNAH diunduh otomatis.
 *
 * Konstruktor murah & sinkron (dipanggil dari AppContainer di main thread):
 * hanya scan direktori + set override LlamaBridge; semua kerja jaringan/berat
 * berjalan via [scope] di Dispatchers.IO.
 */
class RuntimeManager(private val context: Context, private val scope: CoroutineScope) {

    /** Sidecar metadata unduhan ("<id>.meta.json") untuk integritas resume. */
    @Serializable
    data class DownloadMeta(
        val url: String,
        val etag: String? = null,
        val totalBytes: Long = 0L
    )

    /** Sinyal internal pause (bukan error): ditangkap khusus oleh job instalasi. */
    private class PauseSignal : Exception()

    /** Error finalisasi/instalasi dengan pesan siap-tampilkan (checksum/zip/pack). */
    private class PackInstallException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    /** Direktori pack RUNTIME terpasang: filesDir/runtime/<packId>/. */
    private val runtimeDir: File = File(context.filesDir, "runtime")

    /** Direktori pack MODULE terpasang: filesDir/modules/<packId>/. */
    private val modulesDir: File = File(context.filesDir, "modules")

    /** Direktori cache manifest untuk fallback offline. */
    private val cacheDir: File = File(context.filesDir, "runtime_cache")

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _packs = MutableStateFlow<List<PackView>>(emptyList())

    /** Kartu pack untuk UI — selalu berisi kartu "bundled" (runtime bawaan APK) di posisi pertama. */
    val packs: StateFlow<List<PackView>> = _packs.asStateFlow()

    private val _installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())

    /** Status instalasi per pack id yang sedang DOWNLOADING/PAUSED/EXTRACTING/DONE/FAILED. */
    val installStates: StateFlow<Map<String, InstallState>> = _installStates.asStateFlow()

    private val _autoInstall = MutableStateFlow(prefs.getBoolean(KEY_AUTO_INSTALL, true))

    /** Preferensi auto-install (persist via SharedPreferences). */
    val autoInstall: StateFlow<Boolean> = _autoInstall.asStateFlow()

    private val _manifestInfo = MutableStateFlow<String?>(null)

    /** Ringkasan manifest terakhir, contoh "Manifest v1.7.0 · 2 pack", atau pesan error/offline. */
    val manifestInfo: StateFlow<String?> = _manifestInfo.asStateFlow()

    private val manifestCache = AtomicReference<RuntimeManifest?>(null)

    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val pauseFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()

    init {
        // Sinkron & murah: scan runtime terpasang lalu pasang override SEBELUM
        // LlamaEngine/LlamaBridge dipakai pertama kali (AppContainer membuat
        // RuntimeManager sebelum modelManager/llamaEngine).
        installedRuntimeLibPath()?.let { LlamaBridge.overrideLibPath = it }
        recomputePackViews()
        autoEnsure()
    }

    // ----------------------------------------------------------------------
    // Preferensi
    // ----------------------------------------------------------------------

    /** Set preferensi auto-install: simpan ke prefs + emit ke StateFlow. */
    fun setAutoInstall(enabled: Boolean) {
        _autoInstall.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_INSTALL, enabled).apply()
    }

    // ----------------------------------------------------------------------
    // Manifest
    // ----------------------------------------------------------------------

    /**
     * Tarik ulang manifest (IO): GET URL konstanta → parse → cache ke disk.
     * Bila gagal network → fallback file cache → bila kosong → manifest kosong
     * + [manifestInfo] pesan offline. Setelah hasil apa pun → recompute packs.
     */
    fun refreshManifest() {
        scope.launch(Dispatchers.IO) { refreshManifestInternal() }
    }

    /** Inti refresh manifest (dijalankan di Dispatchers.IO) — dipakai juga oleh [autoEnsure]. */
    private suspend fun refreshManifestInternal() = withContext(Dispatchers.IO) {
        val fetched = fetchManifest()
        val manifest: RuntimeManifest
        val info: String
        if (fetched != null) {
            manifest = fetched
            info = "Manifest v${fetched.version.ifBlank { "?" }} · ${fetched.packs.size} pack"
        } else {
            val cached = readCachedManifest()
            if (cached != null) {
                manifest = cached
                info = "Manifest offline — memakai cache v${cached.version.ifBlank { "?" }} · ${cached.packs.size} pack"
            } else {
                manifest = RuntimeManifest()
                info = "Manifest tidak dapat dimuat (offline)"
            }
        }
        manifestCache.set(manifest)
        _manifestInfo.value = info
        recomputePackViews()
    }

    /** GET manifest dari jaringan; null bila gagal (network/HTTP/parse) — cache disk hanya ditulis saat sukses. */
    private fun fetchManifest(): RuntimeManifest? = runCatching {
        val request = Request.Builder().url(MANIFEST_URL).build()
        ManifestHttp.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val text = resp.body?.string() ?: return@runCatching null
            val manifest = json.decodeFromString(RuntimeManifest.serializer(), text)
            runCatching {
                cacheDir.mkdirs()
                File(cacheDir, MANIFEST_CACHE_FILE).writeText(text)
            }
            manifest
        }
    }.getOrNull()

    /** Baca manifest dari cache disk (null bila tidak ada / JSON korup). */
    private fun readCachedManifest(): RuntimeManifest? = runCatching {
        val file = File(cacheDir, MANIFEST_CACHE_FILE)
        if (!file.isFile) return@runCatching null
        json.decodeFromString(RuntimeManifest.serializer(), file.readText())
    }.getOrNull()

    // ----------------------------------------------------------------------
    // Instalasi (unduh + ekstrak)
    // ----------------------------------------------------------------------

    /**
     * Mulai / lanjutkan instalasi [packId] (abaikan bila job sudah berjalan).
     * Resume otomatis dari .part tersisa (pola ModelManager). Bila packId tidak
     * ada di manifest → state FAILED dengan pesan.
     */
    fun install(packId: String) {
        if (jobs.containsKey(packId)) return
        val cancelled = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        cancelFlags[packId] = cancelled
        pauseFlags[packId] = paused
        jobs[packId] = scope.launch(Dispatchers.IO) {
            // Resolve pack lebih dulu (manifest in-memory, fallback cache disk)
            // agar handler pause/gagal punya konteks totalBytes.
            var resolved = manifestCache.get()?.packs?.firstOrNull { it.id == packId }
            if (resolved == null && manifestCache.get() == null) {
                readCachedManifest()?.let { cached ->
                    manifestCache.set(cached)
                    resolved = cached.packs.firstOrNull { it.id == packId }
                }
            }
            try {
                val pack = resolved
                    ?: throw PackInstallException("Pack tidak ditemukan di manifest")
                if (!abiMatches(pack.abi)) {
                    throw PackInstallException("Pack tidak kompatibel dengan ABI perangkat")
                }
                runInstall(pack, cancelled, paused)
            } catch (sig: PauseSignal) {
                // Pause eksplisit: part + meta DIPERTAHANKAN agar Resume bisa
                // lanjut dari offset terakhir.
                emitPaused(packId, resolved?.sizeBytes ?: 0L)
            } catch (ce: CancellationException) {
                if (pauseFlags[packId]?.get() == true) {
                    // pauseInstall() membatalkan job → perlakukan sebagai pause.
                    emitPaused(packId, resolved?.sizeBytes ?: 0L)
                } else {
                    // Cancel user: buang part/meta, hapus entri state.
                    cleanupDownloadArtifacts(packId)
                    _installStates.value = _installStates.value - packId
                    recomputePackViews()
                }
                throw ce
            } catch (e: Exception) {
                when {
                    // call.cancel() saat pause memicu IOException di read blocking
                    // → cek flag pause lebih dulu sebelum dianggap gagal.
                    pauseFlags[packId]?.get() == true ->
                        emitPaused(packId, resolved?.sizeBytes ?: 0L)

                    // Dibatalkan user (flag/call.cancel) → buang artefak + state.
                    cancelFlags[packId]?.get() == true -> {
                        cleanupDownloadArtifacts(packId)
                        _installStates.value = _installStates.value - packId
                        recomputePackViews()
                    }

                    // Gagal (jaringan/checksum/zip/dll): part + meta untuk error
                    // unduhan DIPERTAHANKAN agar Retry otomatis resume.
                    else -> setInstallState(
                        packId,
                        InstallState(InstallPhase.FAILED, error = e.message ?: "Instalasi gagal")
                    )
                }
            } finally {
                jobs.remove(packId)
                calls.remove(packId)
                cancelFlags.remove(packId)
                pauseFlags.remove(packId)
            }
        }
    }

    /** Jeda instalasi [packId]: flag pause + call.cancel() + job.cancel(); part + meta DIPERTAHANKAN. */
    fun pauseInstall(packId: String) {
        if (!jobs.containsKey(packId)) return
        pauseFlags.getOrPut(packId) { AtomicBoolean(false) }.set(true)
        calls[packId]?.cancel()
        jobs[packId]?.cancel()
    }

    /**
     * Batalkan instalasi [packId]: bila job berjalan → flag cancel + call.cancel()
     * dan job membersihkan part/meta + entri state; bila tidak (PAUSED / part
     * tertinggal) → hapus part/meta langsung + hapus entri installStates
     * (kartu kembali AVAILABLE/INSTALLED).
     */
    fun cancelInstall(packId: String) {
        cancelFlags.getOrPut(packId) { AtomicBoolean(false) }.set(true)
        calls[packId]?.cancel()
        val job = jobs[packId]
        if (job != null) {
            job.cancel()
        } else {
            cleanupDownloadArtifacts(packId)
            _installStates.value = _installStates.value - packId
            recomputePackViews()
        }
    }

    /**
     * Hapus pack terpasang [packId] (IO): hentikan job bila berjalan, delete
     * rekursif dir pack (installed.json ikut terhapus), lalu recompute. Bila
     * pack RUNTIME yang dihapus adalah sumber [LlamaBridge.overrideLibPath],
     * override dikembalikan ke null — CATATAN: berlaku penuh setelah restart
     * proses; .so yang sudah dimuat ke memori tidak bisa di-unload, jadi
     * sampai restart fitur lokal tetap memakai lib lama yang masih ter-mmap.
     */
    fun removePack(packId: String) {
        scope.launch(Dispatchers.IO) {
            // Hentikan job instalasi yang mungkin masih berjalan; join agar job
            // tidak menulis installed.json setelah dir dihapus.
            cancelInstall(packId)
            jobs[packId]?.cancelAndJoin()
            val overrideBefore = LlamaBridge.overrideLibPath
            listOf(KIND_RUNTIME, KIND_MODULE).forEach { kind ->
                packDir(kind, packId).deleteRecursively()
            }
            if (overrideBefore != null && !File(overrideBefore).exists()) {
                LlamaBridge.overrideLibPath = null
            }
            _installStates.value = _installStates.value - packId
            recomputePackViews()
        }
    }

    /** Jalankan satu instalasi penuh: cek ruang → unduh → finalisasi (verifikasi + ekstrak). */
    private fun runInstall(pack: RuntimePack, cancelled: AtomicBoolean, paused: AtomicBoolean) {
        val dir = packDir(pack.kind, pack.id)
        dir.mkdirs()
        // Cek ruang kosong: butuh ~1.2× ukuran pack (zip + hasil ekstrak).
        if (pack.sizeBytes > 0L) {
            val required = (pack.sizeBytes * 12L) / 10L
            if (dir.usableSpace < required) {
                throw PackInstallException(
                    "Ruang penyimpanan tidak cukup — perlu ${humanBytes(required)}, " +
                        "tersedia ${humanBytes(dir.usableSpace)}"
                )
            }
        }
        doDownload(pack, cancelled, paused)
        finalizeInstall(pack)
    }

    /**
     * Inti unduhan dengan dukungan resume HTTP Range (duplikasi adaptif dari
     * engine ModelManager — file battle-tested itu sengaja tidak disentuh):
     *  - part lama + meta cocok → "Range: bytes=<offset>-" (+ "If-Range" bila
     *    ada etag) → 206 → APPEND dari offset;
     *  - 200 (Range diabaikan / If-Range mismatch) → part di-truncate, tulis
     *    ulang dari nol;
     *  - 416 + part persis selengkap meta.totalBytes → unduhan selesai, lanjut
     *    finalisasi;
     *  - 416 lain / total Content-Range ≠ meta.totalBytes → part dibuang dan
     *    GET biasa diulang dari nol (maks [MAX_HTTP_RESTARTS] restart).
     */
    private fun doDownload(pack: RuntimePack, cancelled: AtomicBoolean, paused: AtomicBoolean) {
        val dir = packDir(pack.kind, pack.id)
        dir.mkdirs()
        var attempt = 0
        while (true) {
            attempt++
            var restartRequested = false
            var downloadComplete = false

            val part = partFile(pack)
            var startOffset = 0L
            var meta: DownloadMeta? = null
            if (part.isFile && part.length() > 0L) {
                val saved = readMeta(pack)
                if (saved != null && saved.url != pack.url) {
                    // Sumber berubah sejak part ditulis → part tak bisa dipercaya.
                    removePartFile(pack)
                    deleteMeta(pack)
                } else {
                    startOffset = part.length()
                    meta = saved
                }
            }

            val request = Request.Builder().url(pack.url).apply {
                if (startOffset > 0L) {
                    header("Range", "bytes=$startOffset-")
                    meta?.etag?.let { header("If-Range", it) }
                }
            }.build()
            val call = InstallHttp.newCall(request)
            calls[pack.id] = call

            try {
                call.execute().use { resp ->
                    if (paused.get()) throw PauseSignal()
                    if (cancelled.get()) throw IOException("Instalasi dibatalkan")
                    val code = resp.code
                    val body = resp.body
                    when {
                        // 416 + part persis selengkap total tersimpan → unduhan
                        // sempat selesai tepat saat pause/gagal; finalisasi nanti.
                        code == 416 &&
                            meta != null && meta.totalBytes > 0L &&
                            part.length() == meta.totalBytes -> downloadComplete = true

                        // 416 lainnya: offset tidak valid / part korup → buang,
                        // ulangi GET biasa dari nol.
                        code == 416 -> {
                            removePartFile(pack)
                            deleteMeta(pack)
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
                                removePartFile(pack)
                                deleteMeta(pack)
                                restartRequested = true
                            } else {
                                copyBodyToFile(
                                    pack, resp,
                                    body ?: throw IOException("Response body kosong"),
                                    part, startOffset, meta, append = true, cancelled, paused
                                )
                            }
                        }

                        // 200 (atau 2xx lain): server abaikan Range / If-Range
                        // mismatch → truncate part, tulis ulang dari nol.
                        resp.isSuccessful -> copyBodyToFile(
                            pack, resp,
                            body ?: throw IOException("Response body kosong"),
                            part, 0L, null, append = false, cancelled, paused
                        )

                        else -> throw IOException("HTTP $code saat mengunduh ${pack.name}")
                    }
                }
            } catch (e: IOException) {
                // call.cancel() (pause maupun cancel) memicu IOException di read
                // blocking: bedakan pause → cancel → error asli.
                if (paused.get()) throw PauseSignal()
                if (cancelled.get()) throw IOException("Instalasi dibatalkan")
                throw e
            }

            if (downloadComplete || !restartRequested) break
            if (attempt >= MAX_HTTP_RESTARTS) {
                throw IOException("Respons server terus berubah saat mengunduh ${pack.name}")
            }
        }
    }

    /**
     * Copy body respons ke part file (append bila resume dari offset), simpan
     * meta sidecar SEBELUM copy, emit progress per chunk (throttle ~200 ms).
     */
    private fun copyBodyToFile(
        pack: RuntimePack,
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
                    ?: pack.sizeBytes
            else -> contentLength.takeIf { it > 0 } ?: pack.sizeBytes
        }

        // Simpan meta sebelum copy agar pause/proses mati kapan pun bisa resume
        // dengan total & ETag yang konsisten dengan part yang sudah tertulis.
        writeMeta(pack, DownloadMeta(url = pack.url, etag = resp.header("ETag"), totalBytes = total))

        // Emit awal: saat resume, progress mulai dari startOffset (bukan 0).
        setInstallState(pack.id, InstallState(InstallPhase.DOWNLOADING, startOffset, total))

        var progress = startOffset
        var lastEmit = 0L
        body.byteStream().use { input ->
            FileOutputStream(part, append).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    // Cek flag per chunk: pause dulu, baru cancel.
                    if (paused.get()) throw PauseSignal()
                    if (cancelled.get()) throw IOException("Instalasi dibatalkan")
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    progress += read
                    // Update progress per chunk, di-throttle agar StateFlow
                    // tidak membanjiri recomposition UI.
                    val now = System.nanoTime() / 1_000_000L
                    if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                        lastEmit = now
                        setInstallState(
                            pack.id,
                            InstallState(InstallPhase.DOWNLOADING, progress, total)
                        )
                    }
                }
                output.flush()
                setInstallState(
                    pack.id,
                    InstallState(InstallPhase.DOWNLOADING, progress, if (total > 0) total else progress)
                )
            }
        }
    }

    /** Parse header "Content-Range: bytes 100-999/1234" → 1234 (null bila "*"/rusak). */
    private fun parseContentRangeTotal(value: String?): Long? {
        if (value == null) return null
        return value.substringAfterLast('/').trim().toLongOrNull()
    }

    /**
     * Finalisasi instalasi setelah unduhan lengkap:
     *  (a) verifikasi SHA-256 streaming dari part (mismatch → buang part+meta, gagal);
     *  (b) emit EXTRACTING;
     *  (c) ekstrak ZipInputStream ke dir pack dengan guard zip-slip;
     *  (d) tulis installed.json;
     *  (e) hapus part + meta;
     *  (f) bila pack RUNTIME ber-ABI cocok → pasang LlamaBridge.overrideLibPath
     *      (zip tanpa libopenchai_llama.so → dir dibersihkan + gagal);
     *  (g) emit DONE;
     *  (h) recompute packs.
     */
    private fun finalizeInstall(pack: RuntimePack) {
        val part = partFile(pack)
        val dir = packDir(pack.kind, pack.id)

        // (a) Verifikasi checksum SHA-256 (dilewati bila sha256 kosong).
        if (pack.sha256.isNotBlank()) {
            val actual = sha256Hex(part)
            if (!actual.equals(pack.sha256.trim(), ignoreCase = true)) {
                removePartFile(pack)
                deleteMeta(pack)
                throw PackInstallException("Checksum SHA-256 tidak cocok")
            }
        }

        // (b) Masuk fase ekstraksi (progress penuh terhadap ukuran zip).
        val zipBytes = part.length()
        setInstallState(
            pack.id,
            InstallState(
                InstallPhase.EXTRACTING,
                zipBytes,
                maxOf(zipBytes, pack.sizeBytes)
            )
        )

        // (c) Ekstrak zip ke dir pack (return path .so bila ditemukan).
        val libFile = extractZip(part, dir, cancelFlags[pack.id], pauseFlags[pack.id])

        // (d) Tulis marker keberhasilan.
        val info = InstalledInfo(
            id = pack.id,
            version = pack.version,
            sha256 = pack.sha256,
            abi = pack.abi.orEmpty(),
            installedAtEpochMs = System.currentTimeMillis()
        )
        File(dir, INSTALLED_FILE).writeText(json.encodeToString(InstalledInfo.serializer(), info))

        // (e) Bersihkan artefak unduhan.
        removePartFile(pack)
        deleteMeta(pack)

        // (f) Pack RUNTIME wajib memuat .so; bila ABI cocok → pasang override.
        if (pack.kind == KIND_RUNTIME) {
            if (libFile == null) {
                dir.deleteRecursively()
                throw PackInstallException("Pack tidak memuat libopenchai_llama.so")
            }
            if (abiMatches(pack.abi)) {
                LlamaBridge.overrideLibPath = libFile.absolutePath
            }
        }

        // (g) + (h) Emit DONE lalu recompute kartu pack.
        setInstallState(pack.id, InstallState(InstallPhase.DONE, zipBytes, zipBytes))
    }

    /**
     * Ekstrak zip [zip] ke [targetDir]. Return file libopenchai_llama.so hasil
     * ekstraksi (null bila tidak ada entry .so). Guard zip-slip: path canonical
     * setiap entry WAJIB tetap di dalam dir pack, else gagal dengan pesan.
     * Entry directory dilewati; flag pause/cancel dicek per entry.
     */
    private fun extractZip(
        zip: File,
        targetDir: File,
        cancelled: AtomicBoolean?,
        paused: AtomicBoolean?
    ): File? {
        targetDir.mkdirs()
        val canonicalRoot = targetDir.canonicalPath + File.separator
        var libFile: File? = null
        ZipInputStream(FileInputStream(zip)).use { zis ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val entry = zis.nextEntry ?: break
                // Lewati entry directory (folder kosong tidak dibutuhkan).
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                if (paused?.get() == true) throw PauseSignal()
                if (cancelled?.get() == true) throw IOException("Instalasi dibatalkan")
                val target = File(targetDir, entry.name)
                if (!target.canonicalPath.startsWith(canonicalRoot)) {
                    // Path traversal: buang seluruh hasil ekstraksi parsial.
                    zis.closeEntry()
                    targetDir.deleteRecursively()
                    throw PackInstallException("Zip tidak aman (path traversal)")
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    while (true) {
                        val read = zis.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                }
                if (target.name == LIB_NAME) libFile = target
                zis.closeEntry()
            }
        }
        return libFile
    }

    /** Hitung SHA-256 hex sebuah file secara streaming (buffer 64KB). */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    // ----------------------------------------------------------------------
    // Auto-install (bootstrap)
    // ----------------------------------------------------------------------

    /**
     * Bootstrap otomatis (diluncurkan dari konstruktor, IO): refresh manifest
     * lalu bila [autoInstall] aktif:
     *  - semua pack MODULE required=true yang belum terpasang & ABI cocok → install;
     *  - semua pack RUNTIME yang sudah terpasang dengan versi ≠ manifest & ABI
     *    cocok → install (update).
     * Pack opsional yang belum pernah dipasang TIDAK PERNAH diunduh otomatis.
     */
    private fun autoEnsure() {
        scope.launch(Dispatchers.IO) {
            refreshManifestInternal()
            if (!_autoInstall.value) return@launch
            val manifest = manifestCache.get() ?: return@launch
            val installed = scanInstalled()
            manifest.packs.forEach { pack ->
                if (!abiMatches(pack.abi)) return@forEach
                val info = installed[pack.id]
                val shouldInstall = when (pack.kind) {
                    KIND_MODULE -> pack.required && info == null
                    KIND_RUNTIME -> info != null && info.version != pack.version
                    else -> false
                }
                if (shouldInstall) install(pack.id)
            }
        }
    }

    /** Peta id → InstalledInfo dari seluruh dir pack terpasang (runtime + modules). */
    private fun scanInstalled(): Map<String, InstalledInfo> {
        val result = HashMap<String, InstalledInfo>()
        listOf(KIND_RUNTIME to runtimeDir, KIND_MODULE to modulesDir).forEach { (_, root) ->
            root.listFiles { f -> f.isDirectory }?.forEach { dir ->
                readInstalledInfo(dir)?.let { result[it.id] = it }
            }
        }
        return result
    }

    // ----------------------------------------------------------------------
    // Runtime aktif
    // ----------------------------------------------------------------------

    /**
     * Path absolut "libopenchai_llama.so" dari pack RUNTIME terpasang yang
     * ABI-nya cocok dengan perangkat. Bila ada lebih dari satu, pilih versi
     * tertinggi secara leksikal (tie-break: waktu pasang terbaru). Sinkron & murah.
     */
    fun installedRuntimeLibPath(): String? {
        val candidates = runtimeDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val info = readInstalledInfo(dir) ?: return@mapNotNull null
                val lib = findLibFile(dir) ?: return@mapNotNull null
                if (!installedAbiMatches(info.abi)) return@mapNotNull null
                Triple(dir, info, lib)
            }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return candidates
            .maxWithOrNull(compareBy({ it.second.version }, { it.second.installedAtEpochMs }))
            ?.third
            ?.absolutePath
    }

    /**
     * Label runtime aktif untuk UI: "Runtime bawaan (APK)" bila tanpa override
     * (versi APK ditambahkan pihak UI), else "<pack.name> v<pack.version>".
     */
    fun activeRuntimeLabel(): String {
        val libOverride = LlamaBridge.overrideLibPath ?: return "Runtime bawaan (APK)"
        val dir = runtimeDir.listFiles { f -> f.isDirectory }
            ?.firstOrNull { d -> findLibFile(d)?.absolutePath == libOverride }
            ?: return "Runtime bawaan (APK)"
        val info = readInstalledInfo(dir) ?: return "Runtime bawaan (APK)"
        val pack = manifestCache.get()?.packs?.firstOrNull { it.id == info.id }
        val name = pack?.name ?: info.id
        return "$name v${info.version}"
    }

    // ----------------------------------------------------------------------
    // PackView computation
    // ----------------------------------------------------------------------

    /**
     * Hitung ulang daftar kartu pack: kartu "bundled" selalu pertama, lalu pack
     * manifest (difilter ABI), lalu pack terpasang yang sudah hilang dari
     * manifest (tetap tampil sebagai INSTALLED agar bisa dihapus). Duplikat id
     * digabung satu baris. Murah (scan dir + baca installed.json kecil).
     */
    private fun recomputePackViews() {
        val manifest = manifestCache.get()
        val installMap = _installStates.value
        val rows = LinkedHashMap<String, PackView>()

        // Kartu pertama: runtime bawaan APK — fallback permanen, tidak dapat dihapus.
        // installedVersion disengaja null: module core TIDAK boleh mengimpor
        // BuildConfig milik modul app (hindari dependensi siklik), versi APK
        // ditambahkan oleh pihak UI.
        rows[BUNDLED_ID] = PackView(
            id = BUNDLED_ID,
            kind = KIND_RUNTIME,
            name = "Runtime bawaan (APK)",
            description = "libopenchai_llama.so dari APK — selalu tersedia sebagai fallback",
            status = PackStatus.BUNDLED,
            installedVersion = null,
            availableVersion = null,
            sizeBytes = 0L,
            installPhase = InstallPhase.IDLE,
            progressBytes = 0L,
            totalBytes = 0L,
            error = null,
            removable = false
        )

        // Pack dari manifest yang ABI-nya cocok dengan perangkat.
        manifest?.packs?.forEach { pack ->
            if (!abiMatches(pack.abi)) return@forEach
            val info = readInstalledInfo(packDir(pack.kind, pack.id))
            rows[pack.id] = buildPackView(pack, info, installMap[pack.id])
        }

        // Pack terpasang yang tidak ada di manifest → tetap tampil (bisa dihapus).
        listOf(KIND_RUNTIME to runtimeDir, KIND_MODULE to modulesDir).forEach { (kind, root) ->
            root.listFiles { f -> f.isDirectory }?.forEach { dir ->
                val info = readInstalledInfo(dir) ?: return@forEach
                if (rows.containsKey(info.id)) return@forEach
                rows[info.id] = PackView(
                    id = info.id,
                    kind = kind,
                    name = info.id,
                    description = "Pack terpasang (tidak ada di manifest)",
                    status = PackStatus.INSTALLED,
                    installedVersion = info.version,
                    availableVersion = null,
                    sizeBytes = 0L,
                    installPhase = InstallPhase.IDLE,
                    progressBytes = 0L,
                    totalBytes = 0L,
                    error = null,
                    removable = true
                )
            }
        }

        _packs.value = rows.values.toList()
    }

    /** Bangun PackView satu pack manifest dari status terpasang + state instalasi aktif. */
    private fun buildPackView(pack: RuntimePack, info: InstalledInfo?, state: InstallState?): PackView {
        val status = when {
            info == null -> PackStatus.AVAILABLE
            info.version == pack.version -> PackStatus.INSTALLED
            else -> PackStatus.UPDATABLE
        }
        return PackView(
            id = pack.id,
            kind = pack.kind,
            name = pack.name,
            description = pack.description,
            status = status,
            installedVersion = info?.version,
            availableVersion = pack.version,
            sizeBytes = pack.sizeBytes,
            installPhase = state?.phase ?: InstallPhase.IDLE,
            progressBytes = state?.progressBytes ?: 0L,
            totalBytes = state?.totalBytes ?: 0L,
            error = state?.error,
            removable = info != null
        )
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /** Emit status PAUSED dengan progress terakhir (part + meta dipertahankan). */
    private fun emitPaused(packId: String, fallbackTotal: Long) {
        val part = findPartFile(packId)
        val meta = readMetaAny(packId)
        setInstallState(
            packId,
            InstallState(
                phase = InstallPhase.PAUSED,
                progressBytes = part?.length() ?: 0L,
                totalBytes = meta?.totalBytes?.takeIf { it > 0 } ?: fallbackTotal
            )
        )
    }

    /**
     * Set state instalasi satu pack; recompute kartu hanya saat FASE berubah —
     * progress per-chunk tidak memicu recompute agar tidak membanjiri UI
     * (UI membaca progress live dari [installStates]).
     */
    private fun setInstallState(id: String, state: InstallState) {
        val old = _installStates.value[id]
        _installStates.value = _installStates.value + (id to state)
        if (old?.phase != state.phase) recomputePackViews()
    }

    /**
     * Buang artefak unduhan/ekstraksi parsial: part + meta di kedua kind dir,
     * plus dir pack yang belum punya installed.json (ekstraksi parsial).
     */
    private fun cleanupDownloadArtifacts(packId: String) {
        listOf(KIND_RUNTIME, KIND_MODULE).forEach { kind ->
            val dir = packDir(kind, packId)
            if (dir.isDirectory && !File(dir, INSTALLED_FILE).exists()) {
                dir.deleteRecursively()
            } else {
                File(dir, "$packId.part").delete()
                File(dir, "$packId.meta.json").delete()
            }
        }
    }

    /** Dir pack berdasarkan kind: RUNTIME → filesDir/runtime/<id>, MODULE → filesDir/modules/<id>. */
    private fun packDir(kind: String, id: String): File =
        if (kind == KIND_RUNTIME) File(runtimeDir, id) else File(modulesDir, id)

    private fun partFile(pack: RuntimePack): File =
        File(packDir(pack.kind, pack.id), "${pack.id}.part")

    private fun metaFile(pack: RuntimePack): File =
        File(packDir(pack.kind, pack.id), "${pack.id}.meta.json")

    /** Cari part file "<id>.part" di kedua kind dir (untuk handler tanpa konteks pack). */
    private fun findPartFile(id: String): File? =
        listOf(KIND_RUNTIME, KIND_MODULE)
            .map { File(packDir(it, id), "$id.part") }
            .firstOrNull { it.isFile }

    /** Baca meta sidecar resume pack (null bila tidak ada / JSON korup). */
    private fun readMeta(pack: RuntimePack): DownloadMeta? =
        readMetaIn(packDir(pack.kind, pack.id), pack.id)

    /** Baca meta sidecar dari dir tertentu. */
    private fun readMetaIn(dir: File, id: String): DownloadMeta? = runCatching {
        val file = File(dir, "$id.meta.json")
        if (!file.isFile) return@runCatching null
        json.decodeFromString(DownloadMeta.serializer(), file.readText())
    }.getOrNull()

    /** Baca meta sidecar dari kind dir mana pun (untuk handler tanpa konteks pack). */
    private fun readMetaAny(id: String): DownloadMeta? =
        listOf(KIND_RUNTIME, KIND_MODULE).firstNotNullOfOrNull { readMetaIn(packDir(it, id), id) }

    /** Tulis meta sidecar resume (url + ETag + total byte). */
    private fun writeMeta(pack: RuntimePack, meta: DownloadMeta) {
        metaFile(pack).writeText(json.encodeToString(DownloadMeta.serializer(), meta))
    }

    private fun removePartFile(pack: RuntimePack) {
        partFile(pack).delete()
    }

    private fun deleteMeta(pack: RuntimePack) {
        metaFile(pack).delete()
    }

    /** Baca marker installed.json dari dir pack (null bila tidak ada / JSON korup). */
    private fun readInstalledInfo(dir: File): InstalledInfo? = runCatching {
        val file = File(dir, INSTALLED_FILE)
        if (!file.isFile) return@runCatching null
        json.decodeFromString(InstalledInfo.serializer(), file.readText())
    }.getOrNull()

    /** Cari file libopenchai_llama.so di dalam dir pack (rekursif, dir pack kecil). */
    private fun findLibFile(dir: File): File? =
        dir.walkTopDown().firstOrNull { it.isFile && it.name == LIB_NAME }

    /**
     * Filter ABI pack manifest: null/blank = berlaku untuk semua ABI; selain itu
     * harus termasuk Build.SUPPORTED_ABIS perangkat.
     */
    private fun abiMatches(abi: String?): Boolean {
        if (abi.isNullOrBlank()) return true
        return Build.SUPPORTED_ABIS.contains(abi)
    }

    /**
     * Filter ABI marker installed.json: blank (pack lama/tanpa ABI) dianggap
     * cocok; selain itu harus termasuk Build.SUPPORTED_ABIS perangkat.
     */
    private fun installedAbiMatches(abi: String): Boolean {
        if (abi.isBlank()) return true
        return Build.SUPPORTED_ABIS.contains(abi)
    }

    private companion object {
        /** URL manifest runtime (konstanta, sesuai kontrak Task 7). */
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/SecretArrow/OpenChatAI/main/runtime/manifest.json"

        const val PREFS_NAME = "runtime_prefs"
        const val KEY_AUTO_INSTALL = "auto_install"
        const val KIND_RUNTIME = "RUNTIME"
        const val KIND_MODULE = "MODULE"
        const val INSTALLED_FILE = "installed.json"
        const val MANIFEST_CACHE_FILE = "manifest.json"
        const val LIB_NAME = "libopenchai_llama.so"
        const val BUNDLED_ID = "bundled"
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 200L

        /** Batas restart GET biasa bila respons server tak cocok dengan part. */
        const val MAX_HTTP_RESTARTS = 3
    }
}
