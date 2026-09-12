package com.openchai.core.linux

import com.openchai.core.agent.CommandResult
import com.openchai.core.agent.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scheduler cron in-app untuk userspace Linux (v1.8.0).
 *
 * - Loop tick tiap 60 detik di Dispatchers.IO, dijaga [AtomicBoolean] agar
 *   tidak dobel-start bila start() dipanggil berulang.
 * - Membaca crontab <rootfs>/var/spool/cron/crontabs/root; bila tidak ada,
 *   tick langsung selesai tanpa melakukan apa pun.
 * - Sintaks field yang didukung: "*", langkah tiap-n (bintang + "/n",
 *   mis. tiap 15 menit), rentang "a-b", kombinasi dipisah koma, dan
 *   angka tunggal. dom & dow mengikuti aturan OR standar cron bila
 *   KEDUANYA dibatasi (bukan "*"); selain itu AND biasa.
 * - Dedup eksekusi per menit agar satu entri tidak jalan dua kali dalam
 *   menit yang sama; key lama dibersihkan tiap tick.
 * - Log eksekusi ditulis ke <rootfs>/var/log/openchai-cron.log; bila file
 *   melebihi 256 KB, dipotong dari depan (disimpan tail 128 KB).
 */
class LinuxCron(
    private val env: LinuxEnvManager,
    private val runner: CommandRunner,
    parentScope: CoroutineScope
) {

    /** Scope anak dari parentScope; semua IO tick berjalan di Dispatchers.IO. */
    private val scope =
        CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)

    /** Guard scheduler: true berarti loop tick sedang berjalan. */
    private val running = AtomicBoolean(false)

    private var loopJob: Job? = null

    /** Dedup eksekusi per menit: key "yyyy-MM-dd-HH:mm#<indexEntri>". */
    private val executedKeys = ConcurrentHashMap<String, Boolean>()

    /** Satu entri crontab hasil parse: 5 field waktu + command. */
    private data class CronEntry(
        val minute: String,
        val hour: String,
        val dom: String,
        val month: String,
        val dow: String,
        val command: String
    )

    // ------------------------------------------------------------------
    // Kontrol scheduler
    // ------------------------------------------------------------------

    /** Mulai loop tick 60 detik; no-op bila sudah berjalan. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        loopJob = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /** Hentikan loop tick; aman dipanggil walau belum berjalan. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        loopJob?.cancel()
        loopJob = null
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    /** Satu tick: baca crontab, cocokkan dengan waktu sekarang, eksekusi. */
    private suspend fun tick() {
        val crontabFile = File(env.rootfsPath(), CRONTAB_REL_PATH)
        if (!crontabFile.isFile) return // tanpa crontab → skip
        val entries = try {
            parseCrontab(crontabFile.readText())
        } catch (_: Exception) {
            return // crontab tidak terbaca — lewati tick ini
        }
        if (entries.isEmpty()) return

        val now = Calendar.getInstance()
        val minuteKey = buildMinuteKey(now)

        // Bersihkan dedup key menit-menit sebelumnya tiap tick.
        val keyIter = executedKeys.keys.iterator()
        while (keyIter.hasNext()) {
            if (!keyIter.next().startsWith(minuteKey)) keyIter.remove()
        }

        entries.forEachIndexed { index, entry ->
            if (!matches(entry, now)) return@forEachIndexed
            val key = "$minuteKey#$index"
            if (executedKeys.putIfAbsent(key, true) != null) return@forEachIndexed
            executeEntry(entry.command, now)
        }
    }

    /** Jalankan satu entri cron dan catat hasilnya ke log. */
    private suspend fun executeEntry(command: String, now: Calendar) {
        // cwd dikosongkan (null): runner (LinuxProcessSupervisor) selalu
        // mengeksekusi di /home/user/workspace di dalam proot.
        val result: CommandResult = try {
            runner.run(command, null, CRON_TIMEOUT_MS)
        } catch (e: Exception) {
            appendLog(now, -1, command, e.message ?: "eksekusi gagal")
            return
        }
        // Sertakan stderr ringkas hanya bila rc != 0.
        val errBrief = if (!result.success && result.stderr.isNotBlank()) {
            compactStderr(result.stderr)
        } else {
            null
        }
        appendLog(now, result.exitCode, command, errBrief)
    }

    // ------------------------------------------------------------------
    // Parsing crontab
    // ------------------------------------------------------------------

    /**
     * Parse crontab sederhana: baris kosong / komentar "#" / baris env
     * "NAME=value" dilewati; sisanya dipecah whitespace menjadi 5 field
     * waktu + command (sisa token digabung ulang dengan spasi).
     */
    private fun parseCrontab(text: String): List<CronEntry> {
        val entries = mutableListOf<CronEntry>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // Baris environment crontab, mis. SHELL=/bin/bash atau PATH=...
            val firstToken = line.split(WHITESPACE, limit = 2).first()
            if (ENV_LINE.matches(firstToken)) continue
            val fields = line.split(WHITESPACE)
            if (fields.size < 6) continue // butuh 5 field waktu + >= 1 token command
            entries.add(
                CronEntry(
                    minute = fields[0],
                    hour = fields[1],
                    dom = fields[2],
                    month = fields[3],
                    dow = fields[4],
                    command = fields.drop(5).joinToString(" ")
                )
            )
        }
        return entries
    }

    // ------------------------------------------------------------------
    // Pencocokan waktu
    // ------------------------------------------------------------------

    private fun matches(entry: CronEntry, now: Calendar): Boolean {
        val minute = now.get(Calendar.MINUTE)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val month = now.get(Calendar.MONTH) + 1   // Calendar 0-based → cron 1-12
        val dom = now.get(Calendar.DAY_OF_MONTH)
        // Calendar: SUNDAY=1..SATURDAY=7 → cron: 0=Minggu..6=Sabtu
        val dow = now.get(Calendar.DAY_OF_WEEK) - 1

        if (!fieldMatches(entry.minute, minute, 0)) return false
        if (!fieldMatches(entry.hour, hour, 0)) return false
        if (!fieldMatches(entry.month, month, 1)) return false

        // dom & dow: bila KEDUANYA dibatasi (bukan "*") → OR standar cron
        // (match bila salah satu cocok); selain itu AND biasa.
        val domRestricted = entry.dom.trim() != "*"
        val dowRestricted = entry.dow.trim() != "*"
        val domMatch = fieldMatches(entry.dom, dom, 1)
        // dow 0 dan 7 sama-sama Minggu: saat hari Minggu, cocokkan keduanya.
        val dowMatch = if (dow == 0) {
            fieldMatches(entry.dow, 0, 0) || fieldMatches(entry.dow, 7, 0)
        } else {
            fieldMatches(entry.dow, dow, 0)
        }
        val dayMatch =
            if (domRestricted && dowRestricted) domMatch || dowMatch else domMatch && dowMatch
        return dayMatch
    }

    /** Cocokkan satu field waktu (bisa berisi kombinasi dipisah koma). */
    private fun fieldMatches(expr: String, value: Int, min: Int): Boolean {
        val trimmed = expr.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "*") return true
        return trimmed.split(',').any { part -> partMatches(part, value, min) }
    }

    /**
     * Cocokkan satu part field: "*", langkah tiap-n (bintang + "/n"),
     * rentang "a-b" (boleh dengan langkah "a-b/n"), angka "n",
     * dan angka berlangkah "n/n".
     * Untuk "*", fase langkah dihitung dari batas bawah field ([min])
     * agar langkah 2 pada dom (1-31) berarti 1,3,5,... seperti cron standar.
     */
    private fun partMatches(part: String, value: Int, min: Int): Boolean {
        val p = part.trim()
        if (p.isEmpty() || p == "*") return p == "*"
        val slashIdx = p.indexOf('/')
        val base = if (slashIdx >= 0) p.take(slashIdx) else p
        val step = if (slashIdx >= 0) p.substring(slashIdx + 1).toIntOrNull() else null
        if (slashIdx >= 0 && (step == null || step <= 0)) return false
        return when {
            base == "*" -> {
                if (step == null) true else (value - min) % step == 0
            }
            base.contains('-') -> {
                val range = base.split('-', limit = 2)
                val lo = range[0].trim().toIntOrNull() ?: return false
                val hi = range[1].trim().toIntOrNull() ?: return false
                if (value in lo..hi) step == null || (value - lo) % step == 0 else false
            }
            else -> {
                val n = base.toIntOrNull() ?: return false
                if (step == null) n == value else value >= n && (value - n) % step == 0
            }
        }
    }

    // ------------------------------------------------------------------
    // Log eksekusi
    // ------------------------------------------------------------------

    /**
     * Append satu baris log eksekusi: "[yyyy-MM-dd HH:mm] rc=<exitCode>
     * cmd=<command>" (+ " err=..." ringkas bila rc != 0). Bila log melebihi
     * 256 KB, dipotong dari depan dan disimpan tail 128 KB.
     */
    private fun appendLog(now: Calendar, exitCode: Int, command: String, errBrief: String?) {
        try {
            val line = buildString {
                append('[').append(buildLogStamp(now)).append("] rc=").append(exitCode)
                append(" cmd=").append(command)
                if (!errBrief.isNullOrBlank()) append(" err=").append(errBrief)
            }
            val logFile = File(env.rootfsPath(), LOG_REL_PATH)
            logFile.parentFile?.let { dir -> if (!dir.exists()) dir.mkdirs() }
            var content = if (logFile.isFile) logFile.readText() else ""
            content += line + "\n"
            if (content.length > MAX_LOG_CHARS) {
                // Truncate dari depan: simpan hanya tail 128 KB.
                content = content.substring(content.length - KEEP_LOG_CHARS)
            }
            logFile.writeText(content)
        } catch (_: Exception) {
            // Kegagalan menulis log tidak boleh menghentikan scheduler.
        }
    }

    /** Ringkas stderr untuk log: gabung whitespace, maksimal 200 karakter. */
    private fun compactStderr(stderr: String): String =
        stderr.replace(Regex("\\s+"), " ").trim().take(MAX_ERR_CHARS)

    // ------------------------------------------------------------------
    // Format waktu manual (menghindari SimpleDateFormat non-thread-safe)
    // ------------------------------------------------------------------

    private fun two(v: Int): String = if (v < 10) "0$v" else v.toString()

    /** Key dedup menit: "yyyy-MM-dd-HH:mm". */
    private fun buildMinuteKey(now: Calendar): String =
        "${now.get(Calendar.YEAR)}-${two(now.get(Calendar.MONTH) + 1)}-" +
            "${two(now.get(Calendar.DAY_OF_MONTH))}-${two(now.get(Calendar.HOUR_OF_DAY))}:" +
            two(now.get(Calendar.MINUTE))

    /** Stempel log: "yyyy-MM-dd HH:mm". */
    private fun buildLogStamp(now: Calendar): String =
        "${now.get(Calendar.YEAR)}-${two(now.get(Calendar.MONTH) + 1)}-" +
            "${two(now.get(Calendar.DAY_OF_MONTH))} ${two(now.get(Calendar.HOUR_OF_DAY))}:" +
            two(now.get(Calendar.MINUTE))

    private companion object {
        const val TICK_INTERVAL_MS = 60_000L
        const val CRON_TIMEOUT_MS = 600_000L
        const val MAX_LOG_CHARS = 256 * 1024   // batas log 256 KB
        const val KEEP_LOG_CHARS = 128 * 1024  // tail yang disimpan 128 KB
        const val MAX_ERR_CHARS = 200

        /** Crontab relatif terhadap rootfs proot. */
        const val CRONTAB_REL_PATH = "var/spool/cron/crontabs/root"

        /** File log relatif terhadap rootfs proot. */
        const val LOG_REL_PATH = "var/log/openchai-cron.log"

        val WHITESPACE = Regex("\\s+")
        val ENV_LINE = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
    }
}
