package com.openchai.core.linux

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.openchai.core.agent.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * OkHttpClient khusus unduhan rootfs (pola ModelManager): connect 15s agar
 * koneksi mati cepat terdeteksi, read 60s per chunk untuk jaringan lambat,
 * dan retry koneksi otomatis. Tidak memakai client provider AI.
 */
private val RootfsHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

/**
 * LinuxEnvManager — manajer lingkungan Linux userspace (proot + rootfs Ubuntu)
 * untuk Open Chat AI v1.8.0 (embedded Linux dev environment, tanpa root).
 *
 * Alur instalasi (semuanya NYATA, tanpa mock):
 *  1. DOWNLOADING   — unduh rootfs (tar.gz) via OkHttp ke "<id>.tar.gz.part",
 *     RESUMABLE (HTTP Range/If-Range + sidecar "<id>.meta.json",
 *     pola identik ModelManager.doDownload/copyBodyToFile),
 *  2. verifikasi SHA-256 streaming terhadap manifest (MessageDigest),
 *  3. EXTRACTING    — ekstrak tar.gz ke filesDir/linux/rootfs via commons-compress
 *     (TarArchiveInputStream + GzipCompressorInputStream), tolak path traversal,
 *     setelah selesai chmod +x best-effort untuk file di direktori bin (bin,
 *     usr/bin, sbin, usr/sbin, usr/local/bin),
 *  4. BOOTSTRAPPING — tulis resolv.conf/hosts/wrapper crontab/workspace via
 *     file I/O host, lalu "apt-get update -y" di dalam proot (execOnce),
 *  5. READY         — marker .openchai-ok ditulis; shell/proses agent berjalan
 *     melalui proot (prootArgs/envEnvVars/execOnce).
 *
 * Catatan platform: proot statis dibundel sebagai libproot.so di
 * nativeLibraryDir; targetSdk 28 memungkinkan execve binary di app data
 * (pendekatan standar Termux/UserLAnd). Tidak ada kerja jaringan di konstruktor.
 */
class LinuxEnvManager(private val context: Context, val scope: CoroutineScope) {

    /** Sidecar metadata unduhan ("<id>.meta.json") untuk resume yang aman. */
    @Serializable
    data class DownloadMeta(
        val url: String,
        val etag: String? = null,
        val totalBytes: Long = 0L
    )

    /** Sinyal internal pause (bukan error): ditangani khusus oleh job instalasi. */
    private class PauseSignal : Exception()

    /** Kegagalan instalasi dengan pesan Indonesia yang sudah final (ditampilkan apa adanya). */
    private class InstallFailure(message: String) : IOException(message)

    // ------------------------------------------------------------------
    // Properti publik (kontrak beku untuk UI / delegating layer)
    // ------------------------------------------------------------------

    /** Manifest rootfs terbeku (4 varian — URL/sha JANGAN diubah). */
    val variants: List<RootfsVariant> = DEFAULT_VARIANTS

    /** Hook teardown pemilik resource (shell/supervisor/cron) — dipanggil removeEnv(). */
    val teardownHooks = CopyOnWriteArrayList<() -> Unit>()

    private val _status = MutableStateFlow(LinuxEnvPhase.NOT_INSTALLED)
    val status: StateFlow<LinuxEnvPhase> = _status.asStateFlow()

    private val _installState = MutableStateFlow<LinuxInstallState?>(null)
    val installState: StateFlow<LinuxInstallState?> = _installState.asStateFlow()

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }
    private val pauseFlag = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    private val currentCall = AtomicReference<okhttp3.Call?>(null)

    @Volatile
    private var installJob: Job? = null

    @Volatile
    private var bootstrapJob: Job? = null

    init {
        // Status awal: TANPA kerja jaringan — hanya cek ABI/RAM/disk + marker lokal.
        val cap = deviceCapability()
        _status.value = when {
            !cap.supported -> LinuxEnvPhase.NOT_SUPPORTED
            isInstalled() -> LinuxEnvPhase.READY
            else -> LinuxEnvPhase.NOT_INSTALLED
        }
    }

    // ------------------------------------------------------------------
    // Kapabilitas perangkat
    // ------------------------------------------------------------------

    /**
     * Periksa kelayakan perangkat: ABI harus arm64-v8a/x86_64, RAM total
     * >= 2,5 GB, penyimpanan bebas di filesDir >= 1,5 GB. Sinkron & murah.
     */
    fun deviceCapability(): DeviceCapability {
        // ABI: pilih yang didukung dari daftar ABI perangkat.
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: Build.SUPPORTED_ABIS.firstOrNull()
            ?: "tidak diketahui"
        // RAM total via ActivityManager (pola sama dengan ModelManager.deviceRamMb).
        val totalRamMb = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am == null) {
                0L
            } else {
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                info.totalMem / (1024L * 1024L)
            }
        }.getOrDefault(0L)
        // Penyimpanan bebas di lokasi internal app (rootfs hidup di filesDir/linux).
        val availStorageMb = runCatching {
            StatFs(context.filesDir.path).availableBytes / (1024L * 1024L)
        }.getOrDefault(0L)

        val supported = abi in SUPPORTED_ABIS_SET &&
            totalRamMb >= MIN_RAM_MB &&
            availStorageMb >= MIN_STORAGE_MB

        val reason = when {
            abi !in SUPPORTED_ABIS_SET ->
                "Arsitektur CPU ($abi) tidak didukung — dibutuhkan arm64-v8a atau x86_64; " +
                    "proot + rootfs tidak tersedia untuk ABI ini."
            totalRamMb < MIN_RAM_MB ->
                "RAM total $totalRamMb MB di bawah minimum 2560 MB (2,5 GB) — " +
                    "lingkungan Linux berisiko kehabisan memori."
            availStorageMb < MIN_STORAGE_MB ->
                "Penyimpanan bebas $availStorageMb MB di bawah minimum 1536 MB (1,5 GB) — " +
                    "tidak cukup untuk rootfs Ubuntu dan paket dev."
            else ->
                "Perangkat didukung — ABI $abi, RAM $totalRamMb MB, penyimpanan bebas $availStorageMb MB."
        }
        return DeviceCapability(
            supported = supported,
            reason = reason,
            abi = abi,
            totalRamMb = totalRamMb,
            availStorageMb = availStorageMb
        )
    }

    // ------------------------------------------------------------------
    // Jalur & status dasar
    // ------------------------------------------------------------------

    /** Direktori rootfs di dalam storage app (host view). */
    fun rootfsPath(): File = File(context.filesDir, "linux/rootfs")

    /** Binary proot statis dari jniLibs (libproot.so di nativeLibraryDir). */
    fun prootBinary(): File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    /** Direktori unduhan (part + meta sidecar). */
    fun downloadDir(): File = File(context.filesDir, "linux/dl")

    /** Rootfs terpasang & lengkap: bash ada + marker .openchai-ok ada. */
    fun isInstalled(): Boolean {
        val rootfs = rootfsPath()
        return File(rootfs, "bin/bash").isFile && File(rootfs, ".openchai-ok").isFile
    }

    // ------------------------------------------------------------------
    // Instalasi / pause / cancel / remove
    // ------------------------------------------------------------------

    /**
     * Mulai instalasi varian rootfs [variantId]. Bila job masih berjalan →
     * abaikan. Bila part lama ada (pause/proses mati) → unduh otomatis
     * lanjut dari offset terakhir (HTTP Range).
     */
    fun install(variantId: String) {
        // 0) Job berjalan → abaikan (satu instalasi pada satu waktu).
        if (installJob?.isActive == true) return
        // Perangkat tidak didukung → tolak sebelum menyentuh jaringan/disk.
        if (!deviceCapability().supported) {
            emitFailed("Perangkat tidak didukung: ${deviceCapability().reason}")
            return
        }
        val variant = variants.firstOrNull { it.id == variantId } ?: run {
            emitFailed("Varian rootfs tidak dikenal: $variantId")
            return
        }
        pauseFlag.set(false)
        cancelFlag.set(false)
        installJob = scope.launch(Dispatchers.IO) { runInstall(variant) }
    }

    /**
     * Jeda instalasi: flag + call.cancel + job.cancel. Part + meta
     * DIPERTAHANKAN — lanjutkan dengan [install] (auto-resume dari offset).
     */
    fun pauseInstall() {
        val job = installJob ?: return
        if (!job.isActive) return
        pauseFlag.set(true)
        currentCall.get()?.cancel()
        job.cancel()
    }

    /**
     * Batalkan instalasi: buang part + meta, status kembali NOT_INSTALLED.
     * Bila job masih berjalan → dibatalkan dan dibersihkan oleh handler di
     * [runInstall]; bila tidak ada job (mis. PAUSED / sisa part) → buang langsung.
     */
    fun cancelInstall() {
        cancelFlag.set(true)
        currentCall.get()?.cancel()
        val job = installJob
        if (job != null && job.isActive) {
            job.cancel()
        } else {
            discardDownloadArtifacts()
            _installState.value = null
            _status.value = LinuxEnvPhase.NOT_INSTALLED
        }
    }

    /**
     * Hapus seluruh lingkungan Linux: panggil semua [teardownHooks] dulu
     * (shell/supervisor/cron mematikan sesi), lalu delete rekursif rootfs +
     * direktori unduhan. Status kembali NOT_INSTALLED.
     */
    fun removeEnv() {
        // Hentikan job instalasi/bootstrap yang mungkin masih berjalan.
        cancelFlag.set(true)
        currentCall.get()?.cancel()
        installJob?.cancel()
        bootstrapJob?.cancel()
        // Hook teardown PENTING dipanggil sebelum rootfs hilang dari bawah kaki.
        for (hook in teardownHooks) runCatching { hook() }
        rootfsPath().deleteRecursively()
        downloadDir().deleteRecursively()
        _installState.value = null
        _status.value = LinuxEnvPhase.NOT_INSTALLED
    }

    /**
     * Bila rootfs sudah terpasang tapi marker bootstrap belum ada (mis. instal
     * dibatalkan saat apt-get update), jalankan ulang apt-get update di latar
     * dan tulis marker bila sukses. Gagal → append log filesDir/linux/bootstrap.log.
     */
    fun ensureBootstrapAsync() {
        if (!isInstalled()) return
        if (File(rootfsPath(), ".openchai-bootstrapped").isFile) return
        if (bootstrapJob?.isActive == true) return
        bootstrapJob = scope.launch(Dispatchers.IO) {
            val result = execOnce("apt-get update -y", "/home/user/workspace", BOOTSTRAP_TIMEOUT_MS)
            if (result.exitCode == 0) {
                runCatching {
                    File(rootfsPath(), ".openchai-bootstrapped").writeText("ok\n")
                }
            } else {
                runCatching {
                    val log = File(context.filesDir, "linux/bootstrap.log")
                    log.parentFile?.mkdirs()
                    log.appendText(
                        "apt-get update gagal (rc=${result.exitCode}): " +
                            (result.stderr.ifBlank { result.stdout }).take(2000) + "\n"
                    )
                }
            }
        }
    }

    /** Satu siklus instalasi penuh (dijalankan di Dispatchers.IO). */
    private fun runInstall(variant: RootfsVariant) {
        try {
            val rootfs = rootfsPath()
            // 0) Rootfs ada tapi tidak valid → delete rekursif (mulai bersih).
            if (rootfs.exists()) {
                if (isInstalled()) {
                    // Sudah terpasang — tidak ada yang perlu dilakukan.
                    _installState.value = null
                    _status.value = LinuxEnvPhase.READY
                    return
                }
                rootfs.deleteRecursively()
            }

            // 1) DOWNLOADING — unduh resumable ke <id>.tar.gz.part (+ meta).
            val part = partFile(variant.id)
            val resumeFrom = if (part.isFile) part.length() else 0L
            val resumeTotal = if (resumeFrom > 0L) {
                readMeta(variant.id)?.totalBytes?.takeIf { it > 0L } ?: variant.sizeBytes
            } else {
                variant.sizeBytes
            }
            _status.value = LinuxEnvPhase.DOWNLOADING
            _installState.value = LinuxInstallState(
                phase = LinuxEnvPhase.DOWNLOADING,
                progressBytes = resumeFrom,
                totalBytes = resumeTotal,
                message = "Mengunduh ${variant.name}…"
            )
            doDownload(variant, cancelFlag, pauseFlag)
            ensureNotCancelled()

            // 2) Verifikasi checksum SHA-256 (streaming).
            _installState.value = LinuxInstallState(
                phase = LinuxEnvPhase.DOWNLOADING,
                progressBytes = part.length(),
                totalBytes = part.length(),
                message = "Memverifikasi checksum SHA-256…"
            )
            if (!sha256Hex(part).equals(variant.sha256, ignoreCase = true)) {
                throw InstallFailure("Checksum tidak cocok")
            }
            ensureNotCancelled()

            // 3) EXTRACTING — tar.gz → rootfs (tolak path traversal).
            _status.value = LinuxEnvPhase.EXTRACTING
            _installState.value = LinuxInstallState(
                phase = LinuxEnvPhase.EXTRACTING,
                progressBytes = part.length(),
                totalBytes = part.length(),
                message = "Mengekstrak rootfs…"
            )
            extractRootfs(part)
            ensureNotCancelled()
            // Tarball tidak diperlukan lagi setelah ekstraksi berhasil.
            part.delete()
            deleteMeta(variant.id)

            // 4) BOOTSTRAPPING — konfigurasi dasar via file I/O host.
            _status.value = LinuxEnvPhase.BOOTSTRAPPING
            _installState.value = LinuxInstallState(
                phase = LinuxEnvPhase.BOOTSTRAPPING,
                progressBytes = 0L,
                totalBytes = 0L,
                message = "Menyiapkan konfigurasi dasar (resolv/hosts/crontab/workspace)…"
            )
            bootstrapRootfs(rootfs)
            _installState.value = LinuxInstallState(
                phase = LinuxEnvPhase.BOOTSTRAPPING,
                progressBytes = 0L,
                totalBytes = 0L,
                message = "Menjalankan apt-get update — bisa memakan waktu beberapa menit…"
            )
            val apt = execOnce("apt-get update -y", "/home/user/workspace", BOOTSTRAP_TIMEOUT_MS)
            if (apt.exitCode == 0) {
                // Marker bootstrap (HOST file di dalam direktori rootfs).
                File(rootfs, ".openchai-bootstrapped").writeText("ok\n")
            } else {
                // Bukan fatal — user bisa jalankan manual di shell Linux.
                _installState.value = LinuxInstallState(
                    phase = LinuxEnvPhase.BOOTSTRAPPING,
                    progressBytes = 0L,
                    totalBytes = 0L,
                    message = "apt update gagal — coba manual: apt-get update"
                )
            }
            ensureNotCancelled()

            // 5) READY — tulis marker sukses; installState kembali idle (null).
            File(rootfs, ".openchai-ok").writeText("ok\n")
            _installState.value = null
            _status.value = LinuxEnvPhase.READY
        } catch (sig: PauseSignal) {
            // Pause eksplisit: part + meta DIPERTAHANKAN untuk resume.
            emitPaused()
        } catch (ce: CancellationException) {
            if (pauseFlag.get()) {
                emitPaused()
            } else {
                // Cancel lama = buang part + meta, kembali NOT_INSTALLED.
                discardDownloadArtifacts()
                _installState.value = null
                _status.value = LinuxEnvPhase.NOT_INSTALLED
            }
            throw ce
        } catch (e: Exception) {
            when {
                // call.cancel() saat pause memicu IOException di read blocking
                // → cek flag pause lebih dulu sebelum dianggap gagal.
                pauseFlag.get() -> emitPaused()
                cancelFlag.get() -> {
                    discardDownloadArtifacts()
                    _installState.value = null
                    _status.value = LinuxEnvPhase.NOT_INSTALLED
                }
                else -> {
                    // Setiap langkah gagal → bersihkan part; rootfs DIBIARKAN
                    // untuk diagnosa (instal ulang menghapusnya di langkah 0).
                    discardDownloadArtifacts()
                    val message = when (e) {
                        is InstallFailure -> e.message ?: "Instalasi gagal"
                        else -> "Instalasi gagal: ${e.message ?: e.javaClass.simpleName}"
                    }
                    emitFailed(message)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Unduhan resumable (pola ModelManager)
    // ------------------------------------------------------------------

    /**
     * Inti unduhan dengan dukungan resume HTTP Range:
     *  - part lama + meta cocok → "Range: bytes=<offset>-" (+ "If-Range" bila
     *    ada ETag) → 206 → APPEND dari offset;
     *  - 200 (Range diabaikan / If-Range mismatch) → truncate, tulis dari nol;
     *  - 416 + part persis selengkap meta.totalBytes → finalisasi (part utuh);
     *  - 416 lain → buang part, GET biasa dari nol (maks [MAX_HTTP_RESTARTS]).
     */
    private fun doDownload(
        variant: RootfsVariant,
        cancelled: AtomicBoolean,
        paused: AtomicBoolean
    ) {
        downloadDir().mkdirs()
        try {
            var attempt = 0
            while (true) {
                attempt++
                var restartRequested = false

                val part = partFile(variant.id)
                var startOffset = 0L
                var meta: DownloadMeta? = null
                if (part.isFile && part.length() > 0L) {
                    val saved = readMeta(variant.id)
                    if (saved == null || saved.url != variant.url) {
                        // Sumber berubah / meta rusak → part tidak bisa dipercaya.
                        part.delete()
                        deleteMeta(variant.id)
                    } else {
                        startOffset = part.length()
                        meta = saved
                    }
                }

                val request = Request.Builder().url(variant.url).apply {
                    if (startOffset > 0L) {
                        header("Range", "bytes=$startOffset-")
                        meta?.etag?.let { header("If-Range", it) }
                    }
                }.build()
                val call = RootfsHttp.newCall(request)
                currentCall.set(call)

                try {
                    call.execute().use { resp ->
                        if (paused.get()) throw PauseSignal()
                        if (cancelled.get()) throw IOException("Instalasi dibatalkan")
                        val code = resp.code
                        val body = resp.body
                        when {
                            // 416 + part persis selengkap total tersimpan →
                            // unduhan sempat selesai (tepat saat pause) → finalisasi.
                            code == 416 && meta != null && meta.totalBytes > 0L &&
                                part.length() == meta.totalBytes -> Unit

                            // 416 lainnya: offset tidak valid / part korup → buang,
                            // ulangi GET biasa dari nol.
                            code == 416 -> {
                                part.delete()
                                deleteMeta(variant.id)
                                restartRequested = true
                            }

                            // 206 Partial Content: server menghormati Range → APPEND.
                            code == 206 && startOffset > 0L -> {
                                val declaredTotal =
                                    parseContentRangeTotal(resp.header("Content-Range"))
                                if (meta != null && meta.totalBytes > 0L &&
                                    declaredTotal != null && declaredTotal != meta.totalBytes
                                ) {
                                    // Isi sumber berubah sejak pause → part basi.
                                    part.delete()
                                    deleteMeta(variant.id)
                                    restartRequested = true
                                } else {
                                    copyBodyToFile(
                                        variant, resp,
                                        body ?: throw IOException("Respons kosong dari server"),
                                        part, startOffset, meta, append = true, cancelled, paused
                                    )
                                }
                            }

                            // 200 (atau 2xx lain): server abaikan Range / If-Range
                            // mismatch → truncate part, tulis ulang dari nol.
                            resp.isSuccessful -> copyBodyToFile(
                                variant, resp,
                                body ?: throw IOException("Respons kosong dari server"),
                                part, 0L, null, append = false, cancelled, paused
                            )

                            else -> throw IOException(
                                "Gagal mengunduh ${variant.name}: HTTP $code"
                            )
                        }
                    }
                } catch (e: IOException) {
                    // call.cancel() (pause maupun cancel) memicu IOException di
                    // read blocking: bedakan pause → cancel → error asli.
                    if (paused.get()) throw PauseSignal()
                    if (cancelled.get()) throw IOException("Instalasi dibatalkan")
                    throw e
                }

                if (!restartRequested) break
                if (attempt >= MAX_HTTP_RESTARTS) {
                    throw IOException(
                        "Respons server terus berubah saat mengunduh ${variant.name}"
                    )
                }
            }
        } finally {
            currentCall.set(null)
        }
    }

    /**
     * Salin body respons ke part file (append bila resume), simpan meta sidecar
     * SEBELUM copy, emit progress per chunk dengan throttle ~200 ms.
     */
    private fun copyBodyToFile(
        variant: RootfsVariant,
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
        // Total byte konten: Content-Length (206 → +offset) → meta → manifest.
        val total = when {
            resp.code == 206 ->
                contentLength.takeIf { it > 0L }?.let { it + startOffset }
                    ?: meta?.totalBytes?.takeIf { it > 0L }
                    ?: variant.sizeBytes
            else ->
                contentLength.takeIf { it > 0L } ?: variant.sizeBytes
        }

        // Simpan meta sebelum copy agar pause/proses mati kapan pun bisa resume
        // dengan total & ETag yang konsisten dengan part yang sudah tertulis.
        writeMeta(
            variant.id,
            DownloadMeta(url = variant.url, etag = resp.header("ETag"), totalBytes = total)
        )

        // Emit awal: saat resume, progress mulai dari startOffset (bukan 0).
        emitDownloading(variant, startOffset, total)

        var progress = startOffset
        var lastEmit = 0L
        body.byteStream().use { input ->
            FileOutputStream(part, append).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                while (true) {
                    // Cek flag per chunk: pause dulu, baru cancel.
                    if (paused.get()) throw PauseSignal()
                    if (cancelled.get()) throw IOException("Instalasi dibatalkan")
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    progress += read
                    // Throttle emit ~200 ms agar StateFlow tidak membanjiri UI.
                    val now = System.nanoTime() / 1_000_000L
                    if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                        lastEmit = now
                        emitDownloading(variant, progress, total)
                    }
                }
                output.flush()
                emitDownloading(variant, progress, if (total > 0L) total else progress)
            }
        }
    }

    /** Parse header "Content-Range: bytes 100-999/1234" → 1234 (null bila rusak). */
    private fun parseContentRangeTotal(value: String?): Long? {
        if (value == null) return null
        return value.substringAfterLast('/').trim().toLongOrNull()
    }

    // ------------------------------------------------------------------
    // Checksum & ekstraksi
    // ------------------------------------------------------------------

    /** SHA-256 file secara streaming (hemat memori, file ~28 MB aman). */
    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Ekstrak tar.gz [part] ke rootfs dengan pemeriksaan keamanan jalur:
     * entry absolut / berisi ".." ditolak (InstallFailure), plus cek kanonik
     * sebagai bela diri kedua terhadap symlink yang sudah terekstrak.
     */
    private fun extractRootfs(part: File) {
        val rootfs = rootfsPath()
        rootfs.mkdirs()
        val rootfsCanon = rootfs.canonicalPath
        val rootfsCanonPrefix = rootfsCanon + File.separator

        TarArchiveInputStream(GzipCompressorInputStream(FileInputStream(part))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val target = resolveSafeTarget(rootfs, rootfsCanon, rootfsCanonPrefix, entry.name)
                    ?: throw InstallFailure("Rootfs tidak aman (path traversal)")
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                // Pastikan direktori induk ada untuk semua jenis entry non-dir.
                target.parentFile?.mkdirs()
                when {
                    // Symlink (mis. /bin → usr/bin pada ubuntu-base) — WAJIB
                    // dibuat agar bin/bash ditemukan; proot mengikuti symlink OS.
                    entry.isSymbolicLink -> extractSymlink(target, entry.linkName)

                    // Hardlink → tautkan ke file sumber di dalam rootfs.
                    entry.isLink -> extractHardlink(
                        target, entry.linkName, rootfs, rootfsCanon, rootfsCanonPrefix
                    )

                    // File biasa → salin isi entry (stream otomatis berhenti
                    // di akhir entry, ditutup via copy).
                    entry.isFile -> extractRegularFile(tar, target)

                    // Jenis lain (fifo/perangkat) tidak ada pada ubuntu-base.
                    else -> Unit
                }
            }
        }
        makeBinariesExecutable(rootfs)
    }

    /**
     * Normalisasi nama entry & validasi keamanan: tolak nama absolut dan
     * segmen ".." → null (dianggap path traversal). Hasil: File tujuan yang
     * garis kanoniknya DIJAMIN tetap berada di dalam rootfs.
     */
    private fun resolveSafeTarget(
        rootfs: File,
        rootfsCanon: String,
        rootfsCanonPrefix: String,
        entryName: String
    ): File? {
        // Nama absolut ("/etc/passwd") → tolak.
        if (entryName.startsWith("/")) return null
        // Normalisasi: buang "./" dan segmen kosong; ".." → tolak.
        val parts = entryName.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        val target = File(rootfs, parts.joinToString(File.separator))
        // Cek kanonik: resolusi (termasuk lewat symlink induk yang sudah ada)
        // tidak boleh keluar dari rootfs.
        val canon = runCatching { target.canonicalPath }.getOrNull() ?: return null
        if (canon != rootfsCanon && !canon.startsWith(rootfsCanonPrefix)) return null
        return target
    }

    /** Entry file biasa: hapus bekas (file/symlink) lalu salin isi entry. */
    private fun extractRegularFile(tar: TarArchiveInputStream, target: File) {
        if (target.exists() || Files.isSymbolicLink(target.toPath())) {
            runCatching { Files.deleteIfExists(target.toPath()) }
        }
        FileOutputStream(target).use { output ->
            // TarArchiveInputStream.read() dibatasi ukuran entry saat ini.
            tar.copyTo(output, COPY_BUFFER_SIZE)
        }
    }

    /** Entry symlink: buat symlink nyata (wajib — mis. /bin → usr/bin). */
    private fun extractSymlink(target: File, linkName: String) {
        runCatching {
            if (Files.isSymbolicLink(target.toPath()) || target.exists()) {
                Files.deleteIfExists(target.toPath())
            }
        }
        try {
            Files.createSymbolicLink(target.toPath(), Paths.get(linkName))
        } catch (e: Exception) {
            // Filesystem tanpa dukungan symlink → gagalkan instalasi daripada
            // menghasilkan rootfs patah (bash tidak akan ditemukan).
            throw InstallFailure("Gagal membuat symlink ${target.path} → $linkName")
        }
    }

    /** Entry hardlink: taut ke file sumber; bila gagal → salin isi (fallback). */
    private fun extractHardlink(
        target: File,
        linkName: String,
        rootfs: File,
        rootfsCanon: String,
        rootfsCanonPrefix: String
    ) {
        val source = resolveSafeTarget(rootfs, rootfsCanon, rootfsCanonPrefix, linkName)
        if (source == null || !source.isFile) {
            // Sumber belum ada / tak valid → lewati (best-effort).
            return
        }
        runCatching {
            if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                Files.deleteIfExists(target.toPath())
            }
        }
        try {
            Files.createLink(target.toPath(), source.toPath())
        } catch (e: Exception) {
            // Hardlink tidak wajib — salin isi file sumber sebagai fallback.
            runCatching {
                FileInputStream(source).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, COPY_BUFFER_SIZE)
                    }
                }
            }
        }
    }

    /** Best-effort: chmod +x untuk file langsung di direktori bin rootfs. */
    private fun makeBinariesExecutable(rootfs: File) {
        for (dirName in EXECUTABLE_DIRS) {
            val dir = File(rootfs, dirName)
            if (!dir.isDirectory) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isFile) runCatching { child.setExecutable(true, false) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Bootstrap (file I/O host — rootfs belum berjalan)
    // ------------------------------------------------------------------

    /** Tulis konfigurasi dasar rootfs: DNS, hosts, workspace, wrapper crontab. */
    private fun bootstrapRootfs(rootfs: File) {
        // DNS & hosts (ditulis via file I/O host).
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n")
        // Struktur kerja (mkdir -p).
        File(rootfs, "home/user/workspace").mkdirs()
        File(rootfs, "var/spool/cron/crontabs").mkdirs()
        File(rootfs, "var/log").mkdirs()
        // Wrapper crontab — scheduler cron in-app menulis ke
        // /var/spool/cron/crontabs/root melalui skrip ini.
        val crontab = File(rootfs, "usr/local/bin/crontab")
        crontab.parentFile?.mkdirs()
        crontab.writeText(CRONTAB_SCRIPT)
        crontab.setExecutable(true, false)
    }

    // ------------------------------------------------------------------
    // Eksekusi proot (satu proses sekali jalan)
    // ------------------------------------------------------------------

    /** Argumen proot standar (format "--rootfs <path>" = dua argumen terpisah). */
    fun prootArgs(cwdInEnv: String): List<String> = listOf(
        "--kill-on-exit", "--link2symlink",
        "--rootfs", rootfsPath().absolutePath,
        "-0", "-w", cwdInEnv,
        "-b", "/dev", "-b", "/proc", "-b", "/sys"
    )

    /** Variabel lingkungan dasar di dalam userspace Linux. */
    fun envEnvVars(): Map<String, String> = mapOf(
        "HOME" to "/home/user",
        "USER" to "user",
        "SHELL" to "/bin/bash",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        // PS1 = "${'$'} " (prompt bash '$'); "\\${'$'}" menghasilkan literal backslash + $.
        "PS1" to "\\${'$'} "
    )

    /**
     * Jalankan [command] sekali di dalam userspace Linux via proot:
     * proot <args> /bin/bash -lc "<command>; __oca_rc=$?; echo __OCA_EXIT_<rc>".
     *
     * stdout & stderr dibaca paralel (dua thread), masing-masing di-cap
     * [OUTPUT_CAP_BYTES] (sisanya dibuang agar pipe tidak macet). Exit code
     * diambil dari sentinel __OCA_EXIT_<rc> TERAKHIR di stdout; bila sentinel
     * tidak ditemukan (proot gagal start) → fallback process.exitValue() / -1.
     * Timeout → destroyForcibly() → CommandResult(-1, stdout, "Timeout ... ms").
     */
    fun execOnce(
        command: String,
        cwdInEnv: String = "/home/user/workspace",
        timeoutMs: Long = 120_000L
    ): CommandResult {
        val proot = prootBinary()
        if (!proot.isFile) {
            return CommandResult(-1, "", "proot tidak ditemukan di ${proot.absolutePath}")
        }
        // Bungkus command: simpan exit code lalu cetak sentinel.
        val wrapped = command + "; __oca_rc=${'$'}?; echo \"__OCA_EXIT_${'$'}__oca_rc\""
        val argv = mutableListOf(proot.absolutePath)
        argv.addAll(prootArgs(cwdInEnv))
        argv.add("/bin/bash")
        argv.add("-lc")
        argv.add(wrapped)

        val pb = ProcessBuilder(*argv.toTypedArray())
        pb.environment().putAll(envEnvVars())
        pb.redirectErrorStream(false)
        val proc = pb.start()

        // Baca stdout & stderr paralel agar pipe tidak penuh (deadlock).
        val stdoutRef = AtomicReference<ByteArray>(ByteArray(0))
        val stderrRef = AtomicReference<ByteArray>(ByteArray(0))
        val outThread = Thread {
            stdoutRef.set(drainCapped(proc.inputStream, OUTPUT_CAP_BYTES))
        }.apply { name = "oca-exec-once-stdout"; isDaemon = true }.also { it.start() }
        val errThread = Thread {
            stderrRef.set(drainCapped(proc.errorStream, OUTPUT_CAP_BYTES))
        }.apply { name = "oca-exec-once-stderr"; isDaemon = true }.also { it.start() }

        val finished = try {
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            proc.destroyForcibly()
            // Beri kesempatan thread pembaca menutup setelah stream tertutup.
            outThread.join(2_000L)
            errThread.join(2_000L)
            val stdout = stdoutRef.get().toString(Charsets.UTF_8)
            return CommandResult(-1, stdout, "Timeout ${timeoutMs} ms")
        }
        outThread.join()
        errThread.join()

        val stdout = stdoutRef.get().toString(Charsets.UTF_8)
        val stderr = stderrRef.get().toString(Charsets.UTF_8)
        val rc = lastExitSentinel(stdout)
        if (rc != null) return CommandResult(rc, stdout, stderr)
        // Sentinel tidak ditemukan (proot gagal start dsb.) → exit code proses host.
        val fallback = try {
            proc.exitValue()
        } catch (e: IllegalThreadStateException) {
            -1
        }
        return CommandResult(fallback, stdout, stderr)
    }

    /**
     * Baca [input] sampai habis sambil menampung maksimal [capBytes] byte
     * (sisanya tetap dibaca lalu dibuang). Robust terhadap stream yang
     * ditutup mendadak (proses dimatikan) — kembalikan apa yang sudah terbaca.
     */
    private fun drainCapped(input: InputStream, capBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(DRAIN_CHUNK_SIZE)
        try {
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (out.size() < capBytes) {
                    out.write(chunk, 0, minOf(read, capBytes - out.size()))
                }
                // Byte di atas cap: dibaca (drain) lalu dibuang.
            }
        } catch (e: IOException) {
            // Stream ditutup (mis. destroyForcibly) — cukup kembalikan yang ada.
        }
        return out.toByteArray()
    }

    /** Ambil exit code dari sentinel __OCA_EXIT_<rc> TERAKHIR di stdout. */
    private fun lastExitSentinel(stdout: String): Int? {
        val matches = EXIT_SENTINEL.findAll(stdout).toList()
        val last = matches.lastOrNull() ?: return null
        return last.groupValues.getOrNull(1)?.toIntOrNull()
    }

    // ------------------------------------------------------------------
    // Emit status
    // ------------------------------------------------------------------

    private fun emitDownloading(variant: RootfsVariant, progress: Long, total: Long) {
        _installState.value = LinuxInstallState(
            phase = LinuxEnvPhase.DOWNLOADING,
            progressBytes = progress,
            totalBytes = total,
            message = "Mengunduh ${variant.name}…"
        )
    }

    private fun emitPaused() {
        val cur = _installState.value
        _installState.value = LinuxInstallState(
            phase = LinuxEnvPhase.PAUSED,
            progressBytes = cur?.progressBytes ?: 0L,
            totalBytes = cur?.totalBytes ?: 0L,
            message = "Instalasi dijeda — unduhan (.part) tetap tersimpan"
        )
        _status.value = LinuxEnvPhase.PAUSED
    }

    private fun emitFailed(message: String) {
        val cur = _installState.value
        _installState.value = LinuxInstallState(
            phase = LinuxEnvPhase.FAILED,
            progressBytes = cur?.progressBytes ?: 0L,
            totalBytes = cur?.totalBytes ?: 0L,
            message = message
        )
        _status.value = LinuxEnvPhase.FAILED
    }

    /** Lempar PauseSignal/IOException bila flag pause/cancel aktif. */
    private fun ensureNotCancelled() {
        if (pauseFlag.get()) throw PauseSignal()
        if (cancelFlag.get()) throw IOException("Instalasi dibatalkan")
    }

    // ------------------------------------------------------------------
    // File part & meta sidecar
    // ------------------------------------------------------------------

    private fun partFile(id: String): File = File(downloadDir(), "$id.tar.gz.part")

    private fun metaFile(id: String): File = File(downloadDir(), "$id.meta.json")

    /** Baca meta sidecar resume (null bila tidak ada / JSON korup). */
    private fun readMeta(id: String): DownloadMeta? = runCatching {
        val file = metaFile(id)
        if (!file.isFile) return@runCatching null
        json.decodeFromString(DownloadMeta.serializer(), file.readText())
    }.getOrNull()

    /** Tulis meta sidecar resume (url + ETag + total byte). */
    private fun writeMeta(id: String, meta: DownloadMeta) {
        metaFile(id).writeText(json.encodeToString(DownloadMeta.serializer(), meta))
    }

    private fun deleteMeta(id: String) {
        metaFile(id).delete()
    }

    /** Buang seluruh artefak unduhan (*.part + *.meta.json) di direktori dl. */
    private fun discardDownloadArtifacts() {
        downloadDir().listFiles()?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".part") || f.name.endsWith(".meta.json"))) {
                f.delete()
            }
        }
    }

    private companion object {
        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val DRAIN_CHUNK_SIZE = 8 * 1024

        /** Throttle emit progress unduhan (ms). */
        const val PROGRESS_INTERVAL_MS = 200L

        /** Batas restart GET biasa bila respons server tidak cocok dengan part. */
        const val MAX_HTTP_RESTARTS = 3

        /** Cap output stdout/stderr per proses (256 KB, sisanya dibuang). */
        const val OUTPUT_CAP_BYTES = 256 * 1024

        /** Timeout apt-get update saat bootstrap (8 menit). */
        const val BOOTSTRAP_TIMEOUT_MS = 480_000L

        /** Syarat minimum perangkat: RAM 2,5 GB, penyimpanan bebas 1,5 GB. */
        const val MIN_RAM_MB = 2560L
        const val MIN_STORAGE_MB = 1536L

        val SUPPORTED_ABIS_SET = setOf("arm64-v8a", "x86_64")

        /** Direktori bin yang filenya di-chmod +x setelah ekstraksi (best-effort). */
        val EXECUTABLE_DIRS = listOf("bin", "usr/bin", "sbin", "usr/sbin", "usr/local/bin")

        /** Pola sentinel exit code dari /bin/bash -lc. */
        val EXIT_SENTINEL = Regex("__OCA_EXIT_(\\d+)")

        /**
         * Skrip wrapper crontab (ditulis persis ke rootfs/usr/local/bin/crontab).
         * Di raw string Kotlin, tanda $ di-escape menjadi ${'$'}.
         */
        val CRONTAB_SCRIPT: String = """
            #!/bin/bash
            # Wrapper crontab Open Chat AI — menyimpan di /var/spool/cron/crontabs/root
            CRON_DIR=/var/spool/cron/crontabs
            CRON_FILE=${'$'}CRON_DIR/root
            mkdir -p "${'$'}CRON_DIR"
            case "${'$'}1" in
              -l) [ -f "${'$'}CRON_FILE" ] && cat "${'$'}CRON_FILE" || echo "no crontab for root" ;;
              -r) rm -f "${'$'}CRON_FILE"; echo "crontab removed" ;;
              -e) if [ -n "${'$'}EDITOR" ] && command -v "${'$'}EDITOR" >/dev/null 2>&1; then "${'$'}EDITOR" "${'$'}CRON_FILE"; else echo "Tidak ada editor interaktif. Gunakan: echo '*/5 * * * * command' | crontab -"; [ -f "${'$'}CRON_FILE" ] && echo "--- crontab saat ini ---" && cat "${'$'}CRON_FILE"; fi ;;
              "") cat > "${'$'}CRON_FILE"; echo "crontab installed" ;;
              *) if [ -f "${'$'}1" ]; then cp "${'$'}1" "${'$'}CRON_FILE"; echo "crontab installed from ${'$'}1"; else echo "usage: crontab [-l|-r|-e|file] atau echo '...' | crontab -"; exit 1; fi ;;
            esac
        """.trimIndent() + "\n"
    }
}

// ----------------------------------------------------------------------
// Manifest rootfs (TERBEKU — URL & sha256 JANGAN diubah)
// ----------------------------------------------------------------------

private val DEFAULT_VARIANTS: List<RootfsVariant> = listOf(
    RootfsVariant(
        id = "ubuntu-22.04-arm64",
        name = "Ubuntu 22.04 LTS",
        abi = "arm64-v8a",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/" +
            "ubuntu-base-22.04-base-arm64.tar.gz",
        sha256 = "6dd67ec02fdc64b5bba4125066462d01e66a2ae14c4c9e571541fba617d7e721",
        sizeBytes = 27660560L,
        python2 = false,
        description = "Rootfs minimal Ubuntu 22.04 — apt, nodejs, npm, python3 via apt"
    ),
    RootfsVariant(
        id = "ubuntu-22.04-amd64",
        name = "Ubuntu 22.04 LTS (emulator)",
        abi = "x86_64",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/" +
            "ubuntu-base-22.04-base-amd64.tar.gz",
        sha256 = "df6fe77cee11bd216ac532f0ee082bdc4da3c0cc1f1d9cb20f3f743196bc4b07",
        sizeBytes = 29824980L,
        python2 = false,
        description = "Untuk emulator x86_64"
    ),
    RootfsVariant(
        id = "ubuntu-20.04-arm64",
        name = "Ubuntu 20.04 LTS (legacy)",
        abi = "arm64-v8a",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/" +
            "ubuntu-base-20.04.5-base-arm64.tar.gz",
        sha256 = "f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52",
        sizeBytes = 26257800L,
        python2 = true,
        description = "Termasuk python2 via apt (legacy/EOL)"
    ),
    RootfsVariant(
        id = "ubuntu-20.04-amd64",
        name = "Ubuntu 20.04 LTS legacy (emulator)",
        abi = "x86_64",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/" +
            "ubuntu-base-20.04.5-base-amd64.tar.gz",
        sha256 = "60e216b60947653dc8989be3821380268315b99b2959c29882781541bfe5a426",
        sizeBytes = 27763108L,
        python2 = true,
        description = "Termasuk python2; untuk emulator"
    )
)

// ----------------------------------------------------------------------
// Tipe publik kontrak (beku — dipakai UI LinuxSetupScreen/ViewModel)
// ----------------------------------------------------------------------

/** Fase lingkungan Linux untuk UI. */
enum class LinuxEnvPhase {
    NOT_INSTALLED, NOT_SUPPORTED, DOWNLOADING, PAUSED, EXTRACTING, BOOTSTRAPPING, READY, FAILED
}

/** Snapshot status instalasi (null = idle/tidak ada proses instalasi). */
data class LinuxInstallState(
    val phase: LinuxEnvPhase,
    val progressBytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null
)

/** Satu varian rootfs Ubuntu pada manifest terbeku. */
data class RootfsVariant(
    val id: String,
    val name: String,
    val abi: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val python2: Boolean,
    val description: String
)

/** Hasil pemeriksaan kelayakan perangkat untuk lingkungan Linux. */
data class DeviceCapability(
    val supported: Boolean,
    val reason: String,
    val abi: String,
    val totalRamMb: Long,
    val availStorageMb: Long
)
