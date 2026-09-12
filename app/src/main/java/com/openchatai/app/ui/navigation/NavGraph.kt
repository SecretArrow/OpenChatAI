package com.openchatai.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openchatai.app.AppContainer
import com.openchatai.app.background.SessionDeepLink
import com.openchatai.app.ui.chat.ChatScreen
import com.openchatai.app.ui.chat.ChatViewModel
import com.openchatai.app.ui.history.HistoryScreen
import com.openchatai.app.ui.linux.LinuxSetupScreen
import com.openchatai.app.ui.models.ModelsScreen
import com.openchatai.app.ui.projects.ProjectsScreen
import com.openchatai.app.ui.runtime.RuntimeScreen
import com.openchatai.app.ui.sessions.SessionsDrawer
import com.openchatai.app.ui.settings.SettingsScreen
import com.openchatai.app.ui.workspace.WorkspaceSetupScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Routes {
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val MODELS = "models"

    /** Layar kelola runtime llama.cpp & modul (akses dari Settings). */
    const val RUNTIME = "runtime"

    /** Layar setup lingkungan Linux (proot + rootfs Ubuntu) — akses dari Settings & banner terminal. */
    const val LINUX_SETUP = "linux_setup"

    /** Layar setup workspace (wajib sebelum chat Agent mode). */
    const val WORKSPACE_SETUP = "workspace_setup"
}

@Composable
fun AppNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // ViewModel chat dibuat di level activity agar state dibagi antar tab
    // (History membuka percakapan ke tab Chat yang sama).
    val chatViewModel: ChatViewModel = viewModel()

    // Drawer "Chats" (daftar sesi paralel) + scope untuk buka/tutup drawer.
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Deep-link dari notifikasi background generation: pindah ke sesi yang
    // dinotifikasi, lalu kosongkan agar tidak terpicu ulang saat recompose.
    LaunchedEffect(Unit) {
        SessionDeepLink.pendingSessionId.collect { sid ->
            if (sid != null) {
                chatViewModel.selectConversation(sid)
                navigateTo(navController, Routes.CHAT)
                SessionDeepLink.pendingSessionId.value = null
            }
        }
    }

    // Gating first-run: chat Agent mode WAJIB workspace aktif. Beri jeda singkat
    // agar restore workspace di AppContainer (DataStore async) sempat jalan;
    // bila tetap kosong → arahkan ke layar setup workspace.
    LaunchedEffect(Unit) {
        delay(400)
        if (container.activeProject.value == null) {
            navController.navigate(Routes.WORKSPACE_SETUP) {
                // Start destination (Chat) tetap di back stack — back dari setup
                // kembali ke Chat yang menampilkan ajakan membuka setup lagi.
                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionsDrawer(
                chatViewModel = chatViewModel,
                drawerState = drawerState,
                scope = scope,
                onNavigateChat = { navigateTo(navController, Routes.CHAT) }
            )
        }
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.CHAT,
                        onClick = { navigateTo(navController, Routes.CHAT) },
                        icon = { Icon(Icons.Filled.Home, contentDescription = "Chat") },
                        label = { Text("Chat") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.HISTORY,
                        onClick = { navigateTo(navController, Routes.HISTORY) },
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = "History") },
                        label = { Text("History") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.PROJECTS,
                        onClick = { navigateTo(navController, Routes.PROJECTS) },
                        icon = { Icon(Icons.Filled.Build, contentDescription = "Projects") },
                        label = { Text("Projects") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.MODELS,
                        onClick = { navigateTo(navController, Routes.MODELS) },
                        icon = { Icon(Icons.Filled.List, contentDescription = "Models") },
                        label = { Text("Models") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = { navigateTo(navController, Routes.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.CHAT,
                modifier = Modifier.padding(padding)
            ) {
                composable(Routes.CHAT) {
                    ChatScreen(
                        chatViewModel = chatViewModel,
                        onOpenSessions = { scope.launch { drawerState.open() } },
                        onOpenProjects = {
                            navController.navigate(Routes.PROJECTS) { launchSingleTop = true }
                        },
                        onOpenSettings = {
                            navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                        },
                        // Kontrak subagent 6-a: param bernama onOpenWorkspaceSetup
                        // dengan default {} — dipakai banner/CTA "workspace belum siap".
                        onOpenWorkspaceSetup = {
                            navController.navigate(Routes.WORKSPACE_SETUP) { launchSingleTop = true }
                        },
                        onOpenLinuxSetup = {
                            navController.navigate(Routes.LINUX_SETUP) { launchSingleTop = true }
                        }
                    )
                }
                composable(Routes.HISTORY) {
                    HistoryScreen(
                        chatViewModel = chatViewModel,
                        onOpenConversation = {
                            navigateTo(navController, Routes.CHAT)
                        }
                    )
                }
                composable(Routes.PROJECTS) {
                    ProjectsScreen(
                        onProjectSelected = {
                            navigateTo(navController, Routes.CHAT)
                        },
                        onNewWorkspace = {
                            navController.navigate(Routes.WORKSPACE_SETUP) { launchSingleTop = true }
                        }
                    )
                }
                composable(Routes.WORKSPACE_SETUP) {
                    WorkspaceSetupScreen(
                        onWorkspaceReady = {
                            // Workspace aktif sudah di-set VM → balik ke Chat
                            // (dedup ke start destination, tanpa menumpuk stack).
                            navigateTo(navController, Routes.CHAT)
                        }
                    )
                }
                composable(Routes.MODELS) {
                    ModelsScreen(
                        onOpenChat = {
                            navigateTo(navController, Routes.CHAT)
                        }
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenRuntime = {
                            navController.navigate(Routes.RUNTIME) { launchSingleTop = true }
                        },
                        // Param onOpenLinuxSetup diimplementasikan subagent 8-d
                        // dengan default {} — di sini cukup dipanggil.
                        onOpenLinuxSetup = {
                            navController.navigate(Routes.LINUX_SETUP) { launchSingleTop = true }
                        }
                    )
                }
                composable(Routes.RUNTIME) {
                    RuntimeScreen()
                }
                composable(Routes.LINUX_SETUP) {
                    LinuxSetupScreen(
                        onDone = {
                            navigateTo(navController, Routes.CHAT)
                        }
                    )
                }
            }
        }
    }
}

private fun navigateTo(navController: androidx.navigation.NavController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
