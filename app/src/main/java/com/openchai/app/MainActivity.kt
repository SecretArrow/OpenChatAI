package com.openchai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openchai.app.ui.navigation.AppNavGraph
import com.openchai.app.ui.theme.OpenChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OpenChatApp).container
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            OpenChatTheme(themeMode = settings.themeMode) {
                AppNavGraph(container = container)
            }
        }
    }
}
