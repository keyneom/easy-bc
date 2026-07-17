package com.easybc.planner.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import com.easybc.planner.ui.kit.EbAvatar
import com.easybc.planner.ui.kit.EbBanner
import com.easybc.planner.ui.kit.EbBannerTone
import com.easybc.planner.ui.kit.EbGroupLabel
import com.easybc.planner.ui.kit.EbNavRow
import com.easybc.planner.ui.kit.EbProfileBadge
import com.easybc.planner.ui.kit.EbProfileHeaderCard
import com.easybc.planner.ui.kit.EbRowTone
import com.easybc.planner.ui.kit.EbStatusRow
import com.easybc.planner.ui.kit.EbStatusTone
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.data.PersistentMethod
import com.easybc.planner.data.ProtectedDayMethod
import com.easybc.planner.data.WithdrawalMode
import com.easybc.planner.io.DataBackup
import com.easybc.planner.sync.AuthorizationStep
import com.easybc.planner.sync.CloudSyncOperation
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    /** Navigate to a settings sub-route ("settings/basics", …). */
    onOpen: (String) -> Unit = {},
    /** Global profile chip, rendered inline with the title. */
    profileChip: @Composable () -> Unit = {},
) {
    val activity = LocalContext.current as ComponentActivity
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by vm.draft.collectAsState()
    val saved by vm.settings.collectAsState()
    val sharedState by vm.sharedSyncState.collectAsState()
    val lastSync by vm.lastCloudSync.collectAsState()
    val status by vm.cloudStatus.collectAsState()
    val themeMode by com.easybc.planner.ui.theme.ThemeModeStore.mode.collectAsState()
    val isFirstTime = saved?.onboardingComplete != true
    val busy = status is SettingsViewModel.SyncStatus.Running

    val state = sharedState
    val activeProfile = state?.profiles?.firstOrNull {
        com.easybc.planner.sync.shared.profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
    }

    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(vm::updateActiveProfileAvatar)
    }

    // The app-level scaffold already consumed the system bars; a default
    // TopAppBar would pad for the status bar a second time (the "wasted
    // whitespace above the title" bug), so nested bars declare zero insets.
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(if (isFirstTime) "Welcome to EasyBC" else "Settings") },
                actions = { profileChip() },
                windowInsets = WindowInsets(0.dp),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            if (isFirstTime) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Set up your plan to get personalized recommendations. " +
                                "All calculations happen on this device — your data stays private.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { onOpen("onboarding") }) {
                            Text("Start setup — five quick steps")
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // ── Active profile header ──
            if (state != null && activeProfile != null) {
                val label = com.easybc.planner.sync.shared.disambiguatedProfileLabel(state, activeProfile)
                EbProfileHeaderCard(
                    name = label,
                    meta = buildString {
                        append(hubProfileMeta(state, activeProfile))
                        lastSync?.let { append(" · synced ${formatSyncTime(it)}") }
                    },
                    colorKey = state.activeProfileKey,
                    badge = hubProfileBadge(state, activeProfile),
                    photoBase64 = activeProfile.avatarWebp,
                    actionLabel = "Manage",
                    onAction = { onOpen("settings/profiles") },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = {
                            avatarLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = !busy,
                    ) {
                        Text(if (activeProfile.avatarWebp == null) "Add photo" else "Change photo")
                    }
                    if (activeProfile.avatarWebp != null) {
                        TextButton(
                            onClick = { vm.updateActiveProfileAvatar(null) },
                            enabled = !busy,
                        ) { Text("Remove photo") }
                    }
                }
            }
            when (val s = status) {
                is SettingsViewModel.SyncStatus.Running ->
                    EbStatusRow(tone = EbStatusTone.BUSY, text = "Working…")
                is SettingsViewModel.SyncStatus.Error ->
                    EbBanner(tone = EbBannerTone.ERROR, text = s.message)
                else -> {}
            }

            // ── Profile settings ──
            EbGroupLabel("Profile")
            EbNavRow(
                title = "Plan basics",
                value = "Age ${draft.ageYears} · ${draft.cycleLengthDays}-day cycle",
                icon = Icons.Filled.Person,
                onClick = { onOpen("settings/basics") },
            )
            EbNavRow(
                title = "Protection",
                value = hubProtectionSummary(draft),
                icon = Icons.Filled.Favorite,
                onClick = { onOpen("settings/protection") },
            )
            EbNavRow(
                title = "Risk & comfort",
                value = "%.1f%% over %d years".format(
                    draft.targetCumulativeFailure * 100,
                    draft.horizonYears,
                ),
                icon = Icons.Filled.Tune,
                onClick = { onOpen("settings/risk") },
            )
            // ── Profiles ──
            EbGroupLabel("Profiles")
            EbNavRow(
                title = "Profiles",
                value = when {
                    state == null || activeProfile == null -> "Create, switch, join, or share"
                    else -> "${state.profiles.size} profile${if (state.profiles.size == 1) "" else "s"} · " +
                        hubProfileMeta(state, activeProfile)
                },
                icon = Icons.Filled.Group,
                tone = if (
                    activeProfile?.let { profile ->
                        state?.let { current ->
                            hubProfileBadge(current, profile) == EbProfileBadge.SHARED
                        }
                    } == true
                ) {
                    EbRowTone.SHARED
                } else {
                    EbRowTone.DEFAULT
                },
                onClick = { onOpen("settings/profiles") },
            )

            // ── This device ──
            EbGroupLabel("This device")
            EbNavRow(
                title = "Reminders",
                value = if (saved?.reminderEnabled == true) {
                    "Daily reconcile · ${hubFormatTime(saved?.reminderHour ?: 9, saved?.reminderMinute ?: 0)}"
                } else {
                    "Off"
                },
                icon = Icons.Filled.Notifications,
                onClick = { onOpen("settings/reminders") },
            )
            EbNavRow(
                title = "Device calendar",
                value = if (saved?.calendarSyncEnabled == true) "Auto-update on" else "Off",
                icon = Icons.Filled.CalendarMonth,
                onClick = { onOpen("settings/device-calendar") },
            )
            EbNavRow(
                title = "Backup & restore",
                value = "Export or import everything on this device",
                icon = Icons.Filled.Save,
                onClick = { onOpen("settings/backup") },
            )

            EbGroupLabel("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.easybc.planner.ui.theme.ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { com.easybc.planner.ui.theme.ThemeModeStore.set(context, mode) },
                        label = {
                            Text(
                                when (mode) {
                                    com.easybc.planner.ui.theme.ThemeMode.SYSTEM -> "System default"
                                    com.easybc.planner.ui.theme.ThemeMode.LIGHT -> "Light"
                                    com.easybc.planner.ui.theme.ThemeMode.DARK -> "Dark"
                                },
                            )
                        },
                    )
                }
            }

            EbGroupLabel("About")
            EbNavRow(
                title = "About EasyBC",
                value = "Version ${com.easybc.planner.BuildConfig.VERSION_NAME} · disclaimers",
                icon = Icons.Filled.Info,
                onClick = { onOpen("settings/about") },
            )
        }
    }

}

internal fun hubProfileMeta(
    state: com.easybc.planner.sync.shared.SharedSyncState,
    profile: com.easybc.planner.sync.shared.ProfileRecord,
): String = when {
    com.easybc.planner.sync.shared.isLocalProfile(profile) -> "Local only · this device"
    !profile.ownerEmail.equals(state.ownerEmail, ignoreCase = true) ->
        "Shared with you · ${profile.role}"
    profile.participantEmails.orEmpty().isNotEmpty() ->
        "Shared · ${profile.participantEmails.orEmpty().size} " +
            if (profile.participantEmails.orEmpty().size == 1) "person" else "people"
    else -> "Private encrypted · your devices"
}

internal fun hubProfileBadge(
    state: com.easybc.planner.sync.shared.SharedSyncState,
    profile: com.easybc.planner.sync.shared.ProfileRecord,
): EbProfileBadge = when {
    profile.needsInitialLoad -> EbProfileBadge.WAITING
    com.easybc.planner.sync.shared.isLocalProfile(profile) -> EbProfileBadge.LOCAL
    !profile.ownerEmail.equals(state.ownerEmail, ignoreCase = true) ->
        if (!com.easybc.planner.sync.shared.canPublishRole(profile.role)) EbProfileBadge.READ_ONLY
        else EbProfileBadge.SHARED
    profile.participantEmails.orEmpty().isNotEmpty() -> EbProfileBadge.SHARED
    else -> EbProfileBadge.PRIVATE
}

internal fun hubProtectionSummary(draft: com.easybc.planner.data.db.UserSettingsEntity): String {
    val persistent = PersistentMethod.entries.firstOrNull {
        it.name.equals(draft.persistentMethod, ignoreCase = true)
    } ?: PersistentMethod.None
    val protected = ProtectedDayMethod.entries.firstOrNull {
        it.name.equals(draft.protectedDayMethod, ignoreCase = true)
    } ?: ProtectedDayMethod.ExternalCondom
    val withdrawal = WithdrawalMode.entries.firstOrNull {
        it.name.equals(draft.withdrawalMode, ignoreCase = true)
    } ?: WithdrawalMode.None
    return buildString {
        if (persistent != PersistentMethod.None) append("${persistent.label} · ")
        append(protected.label)
        if (protected == ProtectedDayMethod.ExternalCondom) append(" (${draft.condomMode})")
        if (withdrawal != WithdrawalMode.None) append(" + withdrawal")
    }
}

private fun hubFormatTime(hour: Int, minute: Int): String {
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "%d:%02d %s".format(h12, minute, if (hour < 12) "AM" else "PM")
}

internal fun formatSyncTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value)


/**
 * Opt-in daily reminder. The notification always prompts about **yesterday**
 * because we can't know what happened today until it's fully elapsed —
 * hence the morning-after default time and the copy below.
 */
@Composable
internal fun ReminderSection(vm: SettingsViewModel) {
    val saved by vm.settings.collectAsState()
    val enabled = saved?.reminderEnabled == true
    val hour = saved?.reminderHour ?: 9
    val minute = saved?.reminderMinute ?: 0

    // Android 13+ requires POST_NOTIFICATIONS at runtime. Older API levels
    // don't surface a launcher at all — the permission is implicitly granted.
    var pendingEnableAfterPermission by remember { mutableStateOf(false) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingEnableAfterPermission) {
            vm.setReminderEnabled(true)
        }
        pendingEnableAfterPermission = false
    }

    Text(
        "Ask me each morning to confirm what actually happened yesterday. " +
            "Keeps the plan accurate without any daily effort from you — " +
            "the notification only fires about a day that's already complete.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Daily reconcile reminder", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (enabled) "On — asks about the previous day."
                else "Off — no notifications are scheduled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { wantOn ->
                if (!wantOn) {
                    vm.setReminderEnabled(false)
                    return@Switch
                }
                // Android 13+: request POST_NOTIFICATIONS before scheduling.
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    pendingEnableAfterPermission = true
                    notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    vm.setReminderEnabled(true)
                }
            },
        )
    }

    if (enabled) {
        Spacer(Modifier.height(8.dp))
        Text("Time of day", style = MaterialTheme.typography.labelLarge)
        IntField(
            label = "Hour (0–23, 24-hour clock)",
            value = hour,
            range = 0..23,
            onValueChange = { h -> vm.setReminderTime(h, minute) },
        )
        IntField(
            label = "Minute",
            value = minute,
            range = 0..59,
            onValueChange = { m -> vm.setReminderTime(hour, m) },
        )
        Text(
            "Alarm uses inexact scheduling (±10 min) to avoid draining your " +
                "battery. Reboot your phone and the alarm re-arms itself.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DeviceCalendarSection(vm: SettingsViewModel) {
    val status by vm.calendarStatus.collectAsState()
    val saved by vm.settings.collectAsState()
    val syncEnabled = saved?.calendarSyncEnabled == true

    // "enable-after-permission" trampoline: if the user flips the switch on
    // before granting calendar permission, we remember the intent and turn
    // it on as soon as the grant comes back.
    var pendingEnableAfterPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ok = grants[Manifest.permission.READ_CALENDAR] == true &&
            grants[Manifest.permission.WRITE_CALENDAR] == true
        if (ok) {
            if (pendingEnableAfterPermission) vm.setCalendarSyncEnabled(true)
            else vm.syncCalendar()
        }
        pendingEnableAfterPermission = false
    }

    fun ensurePermissionThen(block: () -> Unit) {
        if (vm.calendarPermissionGranted()) {
            block()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                ),
            )
        }
    }

    Text(
        "Write and update an \"EasyBC Planner\" calendar on this device from your " +
            "logged periods, predicted cycles, fertile windows, and daily " +
            "planner recommendations. It can update automatically whenever your " +
            "data changes. No data leaves your phone — sharing it onward to " +
            "Google Calendar etc. is your call.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    // Primary toggle — owns the persistent on/off switch.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Automatically update device calendar", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (syncEnabled) "Device-calendar events update automatically."
                else "Off — turn on to create the calendar and keep it fresh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = syncEnabled,
            onCheckedChange = { wantOn ->
                if (wantOn) {
                    if (vm.calendarPermissionGranted()) {
                        vm.setCalendarSyncEnabled(true)
                    } else {
                        pendingEnableAfterPermission = true
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR,
                            ),
                        )
                    }
                } else {
                    vm.setCalendarSyncEnabled(false)
                }
            },
        )
    }
    if (syncEnabled) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Using Google Calendar? Events live in a local \"EasyBC Planner\" " +
                "calendar that Google Calendar hides by default. Open Google " +
                "Calendar → menu → Settings → tap \"EasyBC Planner\" and turn " +
                "on calendar visibility / show in calendar list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { ensurePermissionThen { vm.syncCalendar() } },
            enabled = status !is SettingsViewModel.SyncStatus.Running,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Sync, null)
            Spacer(Modifier.width(8.dp))
            Text("Update device calendar now")
        }
        OutlinedButton(
            onClick = { vm.removeCalendar() },
            enabled = status !is SettingsViewModel.SyncStatus.Running,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Delete, null)
            Spacer(Modifier.width(8.dp))
            Text("Remove device calendar")
        }
    }

    StatusRow(status = status, onDismiss = { vm.dismissCalendarStatus() })

    // Privacy: customizable event labels.
    Spacer(Modifier.height(8.dp))
    val draft by vm.draft.collectAsState()
    var showLabels by remember { mutableStateOf(false) }
    TextButton(
        onClick = { showLabels = !showLabels },
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(if (showLabels) "Hide event labels" else "Customize event labels")
    }
    if (showLabels) {
        Text(
            "These are the exact strings the device calendar shows for each " +
                "kind of event. Defaults are single letters so a glance at " +
                "your calendar by someone else doesn't reveal anything — " +
                "change or blank them however you like. Edits apply on the " +
                "next device-calendar update.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LabelField("Period (logged or predicted)", draft.calendarLabelPeriod) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelPeriod = v) }
        }
        LabelField("Fertile window", draft.calendarLabelFertile) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelFertile = v) }
        }
        LabelField("Plan action: U (unprotected)", draft.calendarLabelActionU) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelActionU = v) }
        }
        LabelField("Plan action: C (protected)", draft.calendarLabelActionC) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelActionC = v) }
        }
        LabelField("Plan action: A (abstain)", draft.calendarLabelActionA) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelActionA = v) }
        }
        LabelField("Plan action: W (withdrawal)", draft.calendarLabelActionW) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelActionW = v) }
        }
        Text(
            "Remember to tap Save at the top of the screen, then Update " +
                "device calendar now to write the new labels to your device calendar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabelField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
internal fun BackupRestoreSection(vm: SettingsViewModel) {
    val status by vm.backupStatus.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DataBackup.MIME_TYPE),
    ) { uri -> uri?.let { vm.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importBackup(it) } }

    var confirmImport by remember { mutableStateOf(false) }

    Text(
        "Save all your cycle data, planner settings, and day logs to a backup " +
            "JSON file, or restore from one. Use this to move between devices — " +
            "no account required. The backup file is not encrypted, so keep " +
            "it private. Importing a backup file replaces everything on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { exportLauncher.launch(vm.defaultBackupFilename()) },
            enabled = status !is SettingsViewModel.SyncStatus.Running,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Upload, null)
            Spacer(Modifier.width(8.dp))
            Text("Export backup file")
        }
        OutlinedButton(
            onClick = { confirmImport = true },
            enabled = status !is SettingsViewModel.SyncStatus.Running,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Download, null)
            Spacer(Modifier.width(8.dp))
            Text("Import backup file")
        }
    }

    StatusRow(status = status, onDismiss = { vm.dismissBackupStatus() })

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import backup file and replace all data?") },
            text = {
                Text(
                    "Importing a backup file wipes this device's period logs, day " +
                        "logs, and settings and replaces them with the backup. " +
                        "Your device calendar will not be changed — use \"Update " +
                        "device calendar now\" afterward to write the imported data to your " +
                        "device calendar.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importLauncher.launch(arrayOf(DataBackup.MIME_TYPE, "*/*"))
                }) { Text("Choose backup file") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusRow(
    status: SettingsViewModel.SyncStatus,
    onDismiss: () -> Unit,
) {
    when (status) {
        is SettingsViewModel.SyncStatus.Running -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            }
        }
        is SettingsViewModel.SyncStatus.Success -> {
            AssistChip(
                onClick = onDismiss,
                label = { Text(status.message, style = MaterialTheme.typography.labelSmall) },
            )
        }
        is SettingsViewModel.SyncStatus.Error -> {
            AssistChip(
                onClick = onDismiss,
                label = {
                    Text(
                        status.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
        SettingsViewModel.SyncStatus.Idle -> { /* no-op */ }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider()
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun IntField(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toIntOrNull()?.let { v ->
                if (v in range) onValueChange(v)
            }
        },
        label = { Text(label) },
        supportingText = { Text("${range.first}–${range.last}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
internal fun DoubleField(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onValueChange: (Double) -> Unit,
) {
    var text by remember(value) { mutableStateOf("%.2f".format(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toDoubleOrNull()?.let { v ->
                if (v in range) onValueChange(v)
            }
        },
        label = { Text(label) },
        supportingText = { Text("${"%.2f".format(range.start)}–${"%.2f".format(range.endInclusive)}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
internal fun SliderField(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    format: (Double) -> String,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(format(value), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat().coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}
