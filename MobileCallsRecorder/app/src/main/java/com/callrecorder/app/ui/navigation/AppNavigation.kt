package com.callrecorder.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.callrecorder.app.ui.screen.RecordingsScreen
import com.callrecorder.app.ui.screen.SettingsScreen

sealed class Screen(val route: String) {
    data object Recordings : Screen("recordings")
    data object Settings : Screen("settings")
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Recordings.route
    ) {
        composable(Screen.Recordings.route) {
            RecordingsScreen(
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.navigateUp()
                }
            )
        }
    }
}
