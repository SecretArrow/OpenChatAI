package com.openchatai.app.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.openchatai.app.MainActivity
import com.openchatai.app.OpenChatApp
import com.openchatai.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service (type dataSync) yang menampilkan notifikasi progress
 * untuk semua generasi AI yang berjalan lewat [GenerationManager].
 *
 * Perilaku lifecycle (sesuai siklus hidup Android resmi):
 *  - [onStartCommand] SEGERA memanggil startForeground (via [ServiceCompat],
 *    type dataSync) — wajib segera setelah startForegroundService.
 *  - Satu notifikasi per sesi Running + satu summary (group) sebagai
 *    notifikasi foreground itu sendiri.
 *  - Update per-sesi di-throttle (≥32 char baru ATAU ≥700 ms sejak update
 *    terakhir) agar tidak men-spam NotificationManager per token.
 *  - Entri terminal (Done/Failed/Cancelled) → notifikasi per-sesi dicancel.
 *  - Tidak ada sesi Running lagi → stopForeground(REMOVE) + stopSelf().
 *  - START_NOT_STICKY: proses mati = state generasi mati; tidak di-restart
 *    otomatis oleh sistem.
 */
class GenerationForegroundService : Service() {

    /** Scope milik service; dibatalkan di onDestroy (no-leak). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Collector [GenerationManager.states]; di-guard agar tidak dobel. */
    private var collectorJob: Job? = null

    /** Cache judul percakapan per sessionId (dimuat async dari store). */
    private val titleCache = ConcurrentHashMap<String, String>()

    /** SessionId yang judulnya sedang dimuat (cegah load duplikat). */
    private val titleLoading = ConcurrentHashMap.newKeySet<String>()

    /** Throttle notifikasi per sesi: (timestamp terakhir, panjang teks terakhir). */
    private val lastNotif = ConcurrentHashMap<String, Pair<Long, Int>>()

    /** SessionId yang notifikasinya sudah dibersihkan karena state terminal. */
    private val terminalHandled = ConcurrentHashMap.newKeySet<String>()

    private var lastSummaryCount = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ---- SEGERA startForeground (wajib; <29 type diabaikan ServiceCompat) ----
        ServiceCompat.startForeground(
            this,
            SUMMARY_ID,
            buildSummaryNotification(0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        // ---- Aksi "Stop" dari notifikasi ----
        if (intent?.action == ACTION_STOP) {
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            if (sessionId != null) {
                GenerationManagerProvider.get(this).cancel(sessionId)
            }
            // Jangan stopSelf di sini — collector yang memutuskan berdasarkan state.
            return START_NOT_STICKY
        }

        // ---- Collector state manager → render notifikasi ----
        if (collectorJob?.isActive != true) {
            collectorJob = scope.launch {
                GenerationManagerProvider.get(this@GenerationForegroundService)
                    .states
                    .collect { st -> render(st) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collectorJob = null
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Render notifikasi
    // ------------------------------------------------------------------

    /** Gambar ulang notifikasi sesuai snapshot state manager. */
    private fun render(states: Map<String, SessionGenState>) {
        val nm = getSystemService(NotificationManager::class.java)
        val running = states.values.filterIsInstance<SessionGenState.Running>()

        // Entri terminal: cancel notifikasi per-sesi sekali per kejadian.
        states.forEach { (id, st) ->
            if (st is SessionGenState.Running) {
                terminalHandled.remove(id)
            } else if (terminalHandled.add(id)) {
                nm?.cancel(id.hashCode())
                titleCache.remove(id)
                lastNotif.remove(id)
            }
        }

        if (running.isEmpty()) {
            lastSummaryCount = -1
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Summary hanya di-update saat jumlah sesi berubah (teksnya hanya
        // bergantung pada count — hindari binder call per token).
        if (running.size != lastSummaryCount) {
            lastSummaryCount = running.size
            nm?.notify(SUMMARY_ID, buildSummaryNotification(running.size))
        }

        running.forEach { r ->
            val title = titleCache[r.sessionId] ?: run {
                requestTitle(r.sessionId)
                "Chat"
            }
            val last = lastNotif[r.sessionId]
            val now = System.currentTimeMillis()
            val shouldNotify = last == null ||
                (r.partialText.length - last.second) >= THROTTLE_MIN_CHARS ||
                (now - last.first) >= THROTTLE_MIN_INTERVAL_MS
            if (!shouldNotify) return@forEach

            lastNotif[r.sessionId] = now to r.partialText.length
            val text = r.partialText.lineSequence().lastOrNull { it.isNotBlank() }
                ?: "Menyiapkan balasan…"
            val clipped = if (text.length > PREVIEW_MAX) text.take(PREVIEW_MAX) + "…" else text
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(SMALL_ICON)
                .setContentTitle(title)
                .setContentText(clipped)
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(r.partialText.take(BIG_TEXT_MAX))
                )
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP_KEY)
                .setContentIntent(openIntent(this, r.sessionId))
                .addAction(0, "Stop", stopIntent(this, r.sessionId))
                .build()
            nm?.notify(r.sessionId.hashCode(), notif)
        }
    }

    /**
     * Muat judul percakapan async dari store; setelah itu render ulang agar
     * notifikasi menampilkan judul asli (sebelumnya sementara "Chat").
     */
    private fun requestTitle(sessionId: String) {
        if (titleCache.containsKey(sessionId)) return
        if (!titleLoading.add(sessionId)) return
        scope.launch(Dispatchers.IO) {
            val title = try {
                (application as OpenChatApp).container.conversationStore
                    .conversations()
                    .firstOrNull { it.id == sessionId }?.title
            } catch (_: Exception) {
                null
            }
            if (title != null) titleCache[sessionId] = title
            titleLoading.remove(sessionId)
            withContext(Dispatchers.Main.immediate) {
                render(GenerationManagerProvider.get(applicationContext).states.value)
            }
        }
    }

    /** Notifikasi summary (sekaligus notifikasi foreground). */
    private fun buildSummaryNotification(count: Int): Notification {
        val text =
            if (count <= 0) "Menyiapkan…"
            else "$count percakapan merespons di latar belakang"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle("Open Chat AI")
            .setContentText(text)
            .setOngoing(true)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(this))
            .build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI Generation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress balasan AI saat aplikasi berada di latar belakang"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------
    // Intent helpers & konstanta
    // ------------------------------------------------------------------

    companion object {
        const val ACTION_STOP = "com.openchai.action.STOP_GENERATION"
        const val EXTRA_SESSION_ID = "session_id"
        private const val GROUP_KEY = "com.openchai.GENERATION"
        private const val CHANNEL_ID = "generation"
        private const val SUMMARY_ID = 9001
        private val SMALL_ICON = R.drawable.ic_launcher_foreground
        private const val PREVIEW_MAX = 140
        private const val BIG_TEXT_MAX = 4000
        private const val THROTTLE_MIN_CHARS = 32
        private const val THROTTLE_MIN_INTERVAL_MS = 700L

        /** PendingIntent service: batalkan generasi [sessionId] dari notifikasi. */
        fun stopIntent(context: Context, sessionId: String): PendingIntent =
            PendingIntent.getService(
                context,
                sessionId.hashCode(),
                Intent(context, GenerationForegroundService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_SESSION_ID, sessionId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /** PendingIntent activity: buka chat sesi [sessionId] (deep-link). */
        fun openIntent(context: Context, sessionId: String): PendingIntent =
            PendingIntent.getActivity(
                context,
                sessionId.hashCode(),
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_SESSION_ID, sessionId)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /** PendingIntent activity: buka aplikasi tanpa extra. */
        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
