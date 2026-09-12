package com.openchai.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openchai.app.AppContainer
import com.openchai.app.ui.chat.ChatScreen
import com.openchai.app.ui.chat.ChatViewModel
import com.openchai.app.ui.history.HistoryScreen
import com.openchai.app.ui.models.ModelsScreen
import com.openchai.app.ui.projects.ProjectsScreen
import com.openchai.app.ui.settings.SettingsScreen

object Routes {
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val MODELS = "models"
}

@Composable
fun AppNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // ViewModel chat dibuat di level activity agar state dibagi antar tab
    // (History membuka percakapan ke tab Chat yang sama).
    val chatViewModel: ChatViewModel = viewModel()

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
                    onOpenProjects = {
                        navController.navigate(Routes.PROJECTS) { launchSingleTop = true }
                    },
                    onOpenSettings = {
                        navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
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
                SettingsScreen()
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
