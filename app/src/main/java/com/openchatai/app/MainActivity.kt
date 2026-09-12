package com.openchatai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchatai.app.background.GenerationForegroundService
import com.openchatai.app.background.SessionDeepLink
import com.openchatai.app.ui.navigation.AppNavGraph
import com.openchatai.app.ui.theme.OpenChatTheme

class MainActivity : ComponentActivity() {

    /**
     * Launcher izin notifikasi (Android 13+). Hasil diabaikan: notifikasi
     * hanya opsional — generasi tetap berjalan tanpa notifikasi bila ditolak.
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleDeepLink(intent)
        val container = (application as OpenChatApp).container
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            OpenChatTheme(themeMode = settings.themeMode) {
                AppNavGraph(container = container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /** Minta izin POST_NOTIFICATIONS tiap cold start sampai di-grant (Android 13+). */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Deep-link dari notifikasi background generation: taruh session_id ke
     * [SessionDeepLink.pendingSessionId] (NavGraph yang mengambil lalu
     * menavigasi). Intent tanpa extra dibiarkan — pending lama dipertahankan.
     */
    private fun handleDeepLink(intent: Intent?) {
        val sessionId = intent?.getStringExtra(GenerationForegroundService.EXTRA_SESSION_ID)
            ?: return
        SessionDeepLink.pendingSessionId.value = sessionId
    }
}
