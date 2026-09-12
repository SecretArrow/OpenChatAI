package com.openchatai.app.ui.terminal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openchatai.app.OpenChatApp
import com.openchatai.app.ui.theme.OpenChatTheme

/**
 * Activity terminal layar penuh (dibuka dari ikon Terminal di header
 * ChatScreen) — menggantikan panel terminal embedded di chat agar area chat
 * tidak berkurang.
 *
 * - Tema mengikuti setelan aplikasi (themeMode dari container).
 * - Judul top bar: "Terminal · <workspace aktif>" (fallback "no workspace").
 * - Konten: [TerminalContent] dengan [TerminalViewModel] milik activity ini.
 * - Sesi shell pertama dibuat otomatis bila belum ada (LaunchedEffect);
 *   [TerminalContent] punya guard serupa, jadi tepat SATU sesi yang terbentuk.
 */
@OptIn(ExperimentalMaterial3Api::class)
class TerminalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Container diakses langsung dari Application (pola MainActivity).
        val container = (application as OpenChatApp).container
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            val activeProject by container.activeProject.collectAsStateWithLifecycle()
            OpenChatTheme(themeMode = settings.themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val workspaceName = activeProject?.name?.takeIf { it.isNotBlank() }
                        ?: "no workspace"
                    val vm: TerminalViewModel = viewModel()

                    // Sesi pertama otomatis bila belum ada sesi terminal.
                    LaunchedEffect(Unit) {
                        if (vm.sessions.value.isEmpty()) {
                            vm.newSession()
                        }
                    }

                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = "Terminal · $workspaceName",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(
                                            Icons.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        TerminalContent(
                            vm = vm,
                            modifier = Modifier
                                .padding(innerPadding)
                                .fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** Intent eksplisit untuk membuka terminal layar penuh. */
        fun intent(context: Context): Intent = Intent(context, TerminalActivity::class.java)
    }
}
