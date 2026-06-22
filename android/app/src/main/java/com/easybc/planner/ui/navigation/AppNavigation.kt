package com.easybc.planner.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.easybc.planner.ui.calendar.CalendarScreen
import com.easybc.planner.ui.history.HistoryScreen
import com.easybc.planner.ui.planner.PlannerScreen
import com.easybc.planner.ui.reconcile.ReconcileScreen
import com.easybc.planner.ui.settings.SettingsScreen

enum class Screen(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Calendar("calendar", "Calendar", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    Planner("planner", "Plan", Icons.Filled.Timeline, Icons.Outlined.Timeline),
    History("history", "History", Icons.Filled.History, Icons.Outlined.History),
    Settings("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@Composable
fun AppNavigation(
    /** True when the app was opened from the reminder notification. */
    pendingReconcileDeepLink: Boolean = false,
    /** Called once we've navigated to the reconcile screen. */
    onReconcileDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Handle the reminder notification deep-link. We push the reconcile
    // route onto the back stack so hitting back still returns to the
    // Calendar tab, which is the mental model the user expects.
    androidx.compose.runtime.LaunchedEffect(pendingReconcileDeepLink) {
        if (pendingReconcileDeepLink) {
            navController.navigate("reconcile")
            onReconcileDeepLinkConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.label,
                            )
                        },
                        label = { Text(screen.label) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Calendar.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Calendar.route) {
                CalendarScreen(onOpenReconcile = { navController.navigate("reconcile") })
            }
            composable(Screen.Planner.route) { PlannerScreen() }
            composable(Screen.History.route) { HistoryScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
            // "reconcile" isn't a bottom-nav destination — it's a full-screen
            // child pushed from the Calendar screen's chip.
            composable("reconcile") {
                ReconcileScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
