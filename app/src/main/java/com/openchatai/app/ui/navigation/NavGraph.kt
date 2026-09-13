package com.openchatai.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
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

    // CATATAN (v1.9.1): TIDAK ada lagi redirect otomatis ke layar setup workspace
    // saat startup. Chat adalah beranda yang SELALU tampil; kebutuhan workspace
    // di-mode AGENT ditandai banner non-blocking di ChatScreen (aksi setup satu
    // tap), dan workspace app-private dibuat otomatis saat user mengirim prompt
    // agent tanpa workspace. Ini akar perbaikan bug "chat tidak muncul — terus
    // di setup workspace": tidak ada lagi pintu yang memaksa user lewat setup.

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
                // NavigationBar M3 dengan pola ikon standar: tab TERPILIH pakai
                // varian Rounded (filled), tab TIDAK terpilih pakai varian Outlined.
                NavigationBar {
                    val chatSelected = currentRoute == Routes.CHAT
                    NavigationBarItem(
                        selected = chatSelected,
                        onClick = { navigateTo(navController, Routes.CHAT) },
                        icon = {
                            Icon(
                                imageVector = if (chatSelected) {
                                    Icons.Rounded.ChatBubble
                                } else {
                                    Icons.Outlined.ChatBubbleOutline
                                },
                                contentDescription = "Chat"
                            )
                        },
                        label = { Text("Chat") }
                    )
                    val historySelected = currentRoute == Routes.HISTORY
                    NavigationBarItem(
                        selected = historySelected,
                        onClick = { navigateTo(navController, Routes.HISTORY) },
                        icon = {
                            Icon(
                                imageVector = if (historySelected) {
                                    Icons.Rounded.History
                                } else {
                                    Icons.Outlined.History
                                },
                                contentDescription = "History"
                            )
                        },
                        label = { Text("History") }
                    )
                    val projectsSelected = currentRoute == Routes.PROJECTS
                    NavigationBarItem(
                        selected = projectsSelected,
                        onClick = { navigateTo(navController, Routes.PROJECTS) },
                        icon = {
                            Icon(
                                imageVector = if (projectsSelected) {
                                    Icons.Rounded.Folder
                                } else {
                                    Icons.Outlined.FolderOpen
                                },
                                contentDescription = "Projects"
                            )
                        },
                        label = { Text("Projects") }
                    )
                    val modelsSelected = currentRoute == Routes.MODELS
                    NavigationBarItem(
                        selected = modelsSelected,
                        onClick = { navigateTo(navController, Routes.MODELS) },
                        icon = {
                            Icon(
                                imageVector = if (modelsSelected) {
                                    Icons.Rounded.SmartToy
                                } else {
                                    Icons.Outlined.SmartToy
                                },
                                contentDescription = "Models"
                            )
                        },
                        label = { Text("Models") }
                    )
                    val settingsSelected = currentRoute == Routes.SETTINGS
                    NavigationBarItem(
                        selected = settingsSelected,
                        onClick = { navigateTo(navController, Routes.SETTINGS) },
                        icon = {
                            Icon(
                                imageVector = if (settingsSelected) {
                                    Icons.Rounded.Settings
                                } else {
                                    Icons.Outlined.Settings
                                },
                                contentDescription = "Settings"
                            )
                        },
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
