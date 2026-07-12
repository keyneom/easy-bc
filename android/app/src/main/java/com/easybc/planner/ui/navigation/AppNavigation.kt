package com.easybc.planner.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.easybc.planner.ui.settings.AboutScreen
import com.easybc.planner.ui.settings.BackupScreen
import com.easybc.planner.ui.settings.DeviceCalendarScreen
import com.easybc.planner.ui.settings.PlanBasicsScreen
import com.easybc.planner.ui.settings.ProtectionScreen
import com.easybc.planner.ui.settings.RemindersScreen
import com.easybc.planner.ui.settings.RiskComfortScreen
import com.easybc.planner.ui.settings.SettingsScreen
import com.easybc.planner.ui.settings.StorageSharingScreen
import com.easybc.planner.ui.update.UpdateAvailableBanner

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
    /** True when a sharing join/response link should open Encrypted Sync settings. */
    pendingSettingsDeepLink: Boolean = false,
    onSettingsDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Activity-scoped view model backing the global profile chip. Screens keep
    // their own back-stack-scoped instances; refreshing on every navigation
    // change keeps the chip in step with mutations made inside those screens.
    val chipVm: com.easybc.planner.ui.settings.SettingsViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    androidx.compose.runtime.LaunchedEffect(navBackStackEntry) {
        chipVm.refreshSharedState()
    }

    // Handle the reminder notification deep-link. We push the reconcile
    // route onto the back stack so hitting back still returns to the
    // Calendar tab, which is the mental model the user expects.
    androidx.compose.runtime.LaunchedEffect(pendingReconcileDeepLink) {
        if (pendingReconcileDeepLink) {
            navController.navigate("reconcile")
            onReconcileDeepLinkConsumed()
        }
    }

    androidx.compose.runtime.LaunchedEffect(pendingSettingsDeepLink) {
        if (pendingSettingsDeepLink) {
            // Land on the sharing screen (where join/response links are
            // handled), with the settings hub beneath it on the back stack.
            navController.navigate(Screen.Settings.route) {
                launchSingleTop = true
            }
            navController.navigate("settings/storage") {
                launchSingleTop = true
            }
            onSettingsDeepLinkConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == screen.route ||
                            (screen == Screen.Settings && it.route?.startsWith("settings/") == true)
                    } == true
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            UpdateAvailableBanner()
            ProfileChipHost(
                vm = chipVm,
                onOpenManageProfiles = {
                    navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                    navController.navigate("settings/storage") { launchSingleTop = true }
                },
            )
            NavHost(
                navController = navController,
                startDestination = Screen.Calendar.route,
                modifier = Modifier.weight(1f),
            ) {
            composable(Screen.Calendar.route) {
                CalendarScreen(onOpenReconcile = { navController.navigate("reconcile") })
            }
            composable(Screen.Planner.route) { PlannerScreen() }
            composable(Screen.History.route) { HistoryScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(onOpen = { route -> navController.navigate(route) })
            }
            // Settings sub-screens (docs/settings-profiles-redesign.md §2).
            composable("settings/basics") {
                PlanBasicsScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/protection") {
                ProtectionScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/risk") {
                RiskComfortScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/storage") {
                StorageSharingScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/reminders") {
                RemindersScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/device-calendar") {
                DeviceCalendarScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/backup") {
                BackupScreen(onBack = { navController.popBackStack() })
            }
            composable("settings/about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            // "reconcile" isn't a bottom-nav destination — it's a full-screen
            // child pushed from the Calendar screen's chip.
            composable("reconcile") {
                ReconcileScreen(onBack = { navController.popBackStack() })
            }
        }
        }
    }
}
