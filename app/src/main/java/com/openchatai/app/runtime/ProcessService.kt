package com.openchatai.app.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openchatai.app.R

/**
 * Foreground service (type dataSync) yang menahan sistem agar tidak
 * menghentikan aplikasi saat ada proses development (npm run dev, node
 * server, dsb.) berjalan di latar belakang. Dihidupkan/dimatikan oleh
 * [com.openchai.runtime.AndroidProcessManager].
 */
class ProcessService : Service() {

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun service(): ProcessService = this@ProcessService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_runtime),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_runtime_title))
            .setContentText(getString(R.string.notification_runtime_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "runtime"
        private const val NOTIFICATION_ID = 1001
    }
}
