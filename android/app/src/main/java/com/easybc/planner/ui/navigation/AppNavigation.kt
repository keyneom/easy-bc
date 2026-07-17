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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.easybc.planner.ui.settings.AcceptResponseScreen
import com.easybc.planner.ui.settings.BackupScreen
import com.easybc.planner.ui.settings.DeviceCalendarScreen
import com.easybc.planner.ui.settings.JoinProfileScreen
import com.easybc.planner.ui.settings.ManageProfilesScreen
import com.easybc.planner.ui.settings.PlanBasicsScreen
import com.easybc.planner.ui.settings.ProfileDetailScreen
import com.easybc.planner.ui.settings.ProtectionScreen
import com.easybc.planner.ui.settings.RemindersScreen
import com.easybc.planner.ui.settings.RiskComfortScreen
import com.easybc.planner.ui.settings.SettingsScreen
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
    /** True when a sharing join/response link should open the profile screens. */
    pendingSettingsDeepLink: Boolean = false,
    onSettingsDeepLinkConsumed: () -> Unit = {},
    /** Pull-to-refresh resync; null hides the gesture (e.g. previews). */
    onManualSync: (suspend () -> com.easybc.planner.sync.CloudAutoSyncSession.ManualSyncOutcome)? = null,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val context = LocalContext.current

    // Activity-scoped view model backing the global profile chip. Screens keep
    // their own back-stack-scoped instances; refreshing on every navigation
    // change keeps the chip in step with mutations made inside those screens.
    val chipVm: com.easybc.planner.ui.settings.SettingsViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    androidx.compose.runtime.LaunchedEffect(navBackStackEntry) {
        chipVm.refreshSharedState()
    }

    fun openManageProfiles() {
        navController.navigate(Screen.Settings.route) { launchSingleTop = true }
        navController.navigate("settings/profiles") { launchSingleTop = true }
    }

    // Every screen renders this in its own title row — profile identity stays
    // ambient without a dedicated bar of vertical space.
    val profileChip: @Composable () -> Unit = {
        ProfileChipAction(vm = chipVm, onOpenManageProfiles = ::openManageProfiles)
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
            // Join and response links each land on the screen that handles
            // them. Accepting a response is intentionally not scoped to the
            // active profile: the pending exchange identifies its profile.
            val isResponse = com.easybc.planner.sync.shared.PendingSharedJoin
                .responseToAccept(context) != null
            navController.navigate(Screen.Settings.route) { launchSingleTop = true }
            navController.navigate("settings/profiles") { launchSingleTop = true }
            navController.navigate(
                if (isResponse) "settings/accept" else "settings/join",
            ) { launchSingleTop = true }
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
            NavHost(
                navController = navController,
                startDestination = Screen.Calendar.route,
                modifier = Modifier.weight(1f),
            ) {
            composable(Screen.Calendar.route) {
                CalendarScreen(
                    onOpenReconcile = { navController.navigate("reconcile") },
                    onManualSync = onManualSync,
                    profileChip = profileChip,
                )
            }
            composable(Screen.Planner.route) { PlannerScreen(profileChip = profileChip) }
            composable(Screen.History.route) { HistoryScreen(profileChip = profileChip) }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onOpen = { route -> navController.navigate(route) },
                    profileChip = profileChip,
                )
            }
            // Settings sub-screens (docs/settings-profiles-redesign.md §2).
            composable("settings/basics") {
                PlanBasicsScreen(onBack = { navController.popBackStack() }, profileChip = profileChip)
            }
            composable("settings/protection") {
                ProtectionScreen(onBack = { navController.popBackStack() }, profileChip = profileChip)
            }
            composable("settings/risk") {
                RiskComfortScreen(onBack = { navController.popBackStack() }, profileChip = profileChip)
            }
            composable("onboarding") {
                com.easybc.planner.ui.settings.OnboardingWizardScreen(
                    onDone = { navController.popBackStack() },
                    onOpenStorage = {
                        navController.popBackStack()
                        navController.navigate("settings/storage") { launchSingleTop = true }
                    },
                )
            }
            // The profiles home: list, new, join. Tap a profile for detail.
            composable("settings/profiles") {
                ManageProfilesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenProfile = { key ->
                        navController.navigate(
                            "settings/profile/${android.net.Uri.encode(key)}",
                        ) { launchSingleTop = true }
                    },
                    onOpenJoin = {
                        navController.navigate("settings/join") { launchSingleTop = true }
                    },
                    profileChip = profileChip,
                )
            }
            composable("settings/profile/{profileKey}") { entry ->
                val key = entry.arguments?.getString("profileKey").orEmpty()
                ProfileDetailScreen(
                    profileKeyArg = android.net.Uri.decode(key),
                    onBack = { navController.popBackStack() },
                    profileChip = profileChip,
                )
            }
            composable("settings/join") {
                JoinProfileScreen(
                    onBack = { navController.popBackStack() },
                    profileChip = profileChip,
                )
            }
            composable("settings/accept") {
                AcceptResponseScreen(
                    onDone = { navController.popBackStack() },
                    profileChip = profileChip,
                )
            }
            // Legacy alias (old deep links / stored routes): the active
            // profile's detail screen replaced "Profiles & sharing".
            composable("settings/storage") {
                ActiveProfileDetailAlias(
                    navController = navController,
                    profileChip = profileChip,
                )
            }
            composable("settings/reminders") {
                RemindersScreen(onBack = { navController.popBackStack() }, profileChip = profileChip)
            }
            composable("settings/device-calendar") {
                DeviceCalendarScreen(onBack = { navController.popBackStack() }, profileChip = profileChip)
            }
            composable("settings/backup") {
                BackupScreen(onBack = { navController.popBackStack() }, profileChip = profileChip)
            }
            composable("settings/about") {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSetup = {
                        navController.navigate("onboarding") { launchSingleTop = true }
                    },
                    profileChip = profileChip,
                )
            }
            // "reconcile" isn't a bottom-nav destination — it's a full-screen
            // child pushed from the Calendar screen's chip.
            composable("reconcile") {
                ReconcileScreen(
                    onBack = { navController.popBackStack() },
                    profileChip = profileChip,
                )
            }
        }
        }
    }
}

/** Resolves the active profile at render time for the legacy storage route. */
@Composable
private fun ActiveProfileDetailAlias(
    navController: androidx.navigation.NavController,
    profileChip: @Composable () -> Unit,
) {
    val vm: com.easybc.planner.ui.settings.SettingsViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    val state by vm.sharedSyncState.collectAsState()
    val activeKey = state?.activeProfileKey
    if (activeKey == null) {
        // Registry still loading (or no profiles yet): fall back to the list.
        ManageProfilesScreen(
            onBack = { navController.popBackStack() },
            onOpenProfile = { key ->
                navController.navigate("settings/profile/${android.net.Uri.encode(key)}") {
                    launchSingleTop = true
                }
            },
            onOpenJoin = { navController.navigate("settings/join") { launchSingleTop = true } },
            profileChip = profileChip,
        )
    } else {
        ProfileDetailScreen(
            profileKeyArg = activeKey,
            onBack = { navController.popBackStack() },
            profileChip = profileChip,
        )
    }
}
