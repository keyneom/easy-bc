package com.easybc.planner.ui.settings

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val draft by vm.draft.collectAsState()
    val saved by vm.settings.collectAsState()
    val isFirstTime = saved?.onboardingComplete != true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isFirstTime) "Welcome — Set Up Your Profile" else "Settings") },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.save() },
                icon = { Icon(Icons.Default.Save, null) },
                text = { Text(if (isFirstTime) "Start Planning" else "Save") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isFirstTime) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Configure your profile to get a personalized cycle plan. " +
                            "All calculations are done on-device — your data stays private.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Profile ──
            SectionHeader("Profile")

            IntField("Age", draft.ageYears, 15..55) { v ->
                vm.updateDraft { d -> d.copy(ageYears = v) }
            }
            IntField("Typical cycle length (days)", draft.cycleLengthDays, 21..45) { v ->
                vm.updateDraft { d -> d.copy(cycleLengthDays = v) }
            }

            // ── Risk Target ──
            SectionHeader("Risk Target")

            SliderField(
                label = "Cumulative failure target",
                value = draft.targetCumulativeFailure,
                range = 0.005f..0.5f,
                format = { "%.1f%%".format(it * 100) },
                onValueChange = { v -> vm.updateDraft { d -> d.copy(targetCumulativeFailure = v.toDouble()) } },
            )
            IntField("Horizon (years)", draft.horizonYears, 1..40) { v ->
                vm.updateDraft { d -> d.copy(horizonYears = v) }
            }

            // ── Behavior ──
            SectionHeader("Behavior")

            DoubleField("Acts per week", draft.actsPerWeek, 0.0..14.0) { v ->
                vm.updateDraft { d -> d.copy(actsPerWeek = v) }
            }

            // ── Method Library ──
            SectionHeader("Contraceptive Methods")

            // Persistent method
            Text("Persistent / background method", style = MaterialTheme.typography.labelLarge)
            Text(
                "An always-on method that reduces baseline risk for all days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val currentPersistent = try {
                PersistentMethod.entries.first { it.name.equals(draft.persistentMethod, ignoreCase = true) }
            } catch (_: Exception) { PersistentMethod.None }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersistentMethod.entries.forEach { method ->
                    FilterChip(
                        selected = currentPersistent == method,
                        onClick = {
                            vm.updateDraft { d -> d.copy(persistentMethod = method.name.lowercase()) }
                        },
                        label = { Text(method.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Protected day method
            Text("Protected-day method", style = MaterialTheme.typography.labelLarge)
            Text(
                "Barrier method used on days marked 'C' (protected). Controls what 'condom' means in the plan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val currentProtected = try {
                ProtectedDayMethod.entries.first { it.name.equals(draft.protectedDayMethod, ignoreCase = true) }
            } catch (_: Exception) { ProtectedDayMethod.ExternalCondom }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProtectedDayMethod.entries.forEach { method ->
                    FilterChip(
                        selected = currentProtected == method,
                        onClick = {
                            vm.updateDraft { d -> d.copy(protectedDayMethod = method.name.lowercase()) }
                        },
                        label = { Text(method.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            // Condom calibration (only if protected day method is external condom)
            if (currentProtected == ProtectedDayMethod.ExternalCondom) {
                Spacer(Modifier.height(4.dp))
                Text("Condom use quality", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("perfect", "typical", "custom").forEach { mode ->
                        FilterChip(
                            selected = draft.condomMode == mode,
                            onClick = { vm.updateDraft { d -> d.copy(condomMode = mode) } },
                            label = { Text(mode.replaceFirstChar { c -> c.uppercase() }) },
                        )
                    }
                }

                if (draft.condomMode == "custom") {
                    DoubleField("Custom condom residual", draft.customCondomResidual, 0.0..1.0) { v ->
                        vm.updateDraft { d -> d.copy(customCondomResidual = v) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Withdrawal
            Text("Withdrawal", style = MaterialTheme.typography.labelLarge)
            Text(
                "If enabled, the planner can recommend withdrawal (W) on moderate-risk days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val currentWithdrawal = try {
                WithdrawalMode.entries.first { it.name.equals(draft.withdrawalMode, ignoreCase = true) }
            } catch (_: Exception) { WithdrawalMode.None }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WithdrawalMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentWithdrawal == mode,
                        onClick = {
                            vm.updateDraft { d -> d.copy(withdrawalMode = mode.name.lowercase()) }
                        },
                        label = { Text(mode.label) },
                    )
                }
            }

            if (currentWithdrawal == WithdrawalMode.Custom) {
                DoubleField("Withdrawal relative risk", draft.withdrawalRelativeRisk, 0.0..1.0) { v ->
                    vm.updateDraft { d -> d.copy(withdrawalRelativeRisk = v) }
                }
            }

            // Combined method layering
            if (currentProtected != ProtectedDayMethod.None && currentWithdrawal != WithdrawalMode.None) {
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    label = "Layer withdrawal on protected days",
                    checked = draft.useWithdrawalBackupOnProtectedDays,
                    onCheckedChange = { checked ->
                        vm.updateDraft { d -> d.copy(useWithdrawalBackupOnProtectedDays = checked) }
                    },
                )
                if (draft.useWithdrawalBackupOnProtectedDays) {
                    SliderField(
                        label = "Combined method independence",
                        value = draft.combinedMethodIndependence,
                        range = 0f..1f,
                        format = { v ->
                            when {
                                v < 0.2 -> "%.0f%% — Conservative".format(v * 100)
                                v > 0.7 -> "%.0f%% — Assumes high independence".format(v * 100)
                                else -> "%.0f%%".format(v * 100)
                            }
                        },
                        onValueChange = { v -> vm.updateDraft { d -> d.copy(combinedMethodIndependence = v.toDouble()) } },
                    )
                }
            }

            // ── Preferences ──
            SectionHeader("Preferences")

            SliderField(
                label = "Streak aversion",
                value = draft.streakAversion,
                range = 0f..1f,
                format = { pct ->
                    when {
                        pct < 0.33 -> "%.0f%% — Fewer total abstinence days".format(pct * 100)
                        pct > 0.66 -> "%.0f%% — Shorter abstinence streaks".format(pct * 100)
                        else -> "%.0f%% — Balanced".format(pct * 100)
                    }
                },
                onValueChange = { v -> vm.updateDraft { d -> d.copy(streakAversion = v.toDouble()) } },
            )

            // ── Reminders ──
            SectionHeader("Reminders")
            ReminderSection(vm)

            // ── Device Calendar Export ──
            SectionHeader("Device Calendar Export")
            DeviceCalendarSection(vm)

            // ── Encrypted cloud sync ──
            SectionHeader("Encrypted Cloud Sync")
            EncryptedSyncSection(vm)

            // ── Backup file ──
            SectionHeader("Backup File")
            BackupRestoreSection(vm)

            // ── Advanced ──
            SectionHeader("Advanced")

            DoubleField("Ovulation SD (days)", draft.ovulationSdDays, 0.5..15.0) { v ->
                vm.updateDraft { d -> d.copy(ovulationSdDays = v) }
            }

            SwitchRow(
                label = "Hold lifecycle constant",
                checked = draft.holdLifecycleConstant,
                onCheckedChange = { checked -> vm.updateDraft { d -> d.copy(holdLifecycleConstant = checked) } },
            )

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { vm.resetToDefaults() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.RestartAlt, null)
                Spacer(Modifier.width(8.dp))
                Text("Reset to defaults")
            }

            // ── Disclaimers ──
            SectionHeader("Disclaimers")

            Text(
                text = "This is not FDA-cleared as contraception. " +
                    "Calculations assume regular cycles. " +
                    "Consult a healthcare provider for medical advice. " +
                    "Plan effectiveness depends on adherence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(80.dp)) // Room for FAB
        }
    }
}

@Composable
private fun EncryptedSyncSection(vm: SettingsViewModel) {
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val status by vm.cloudStatus.collectAsState()
    val connected by vm.cloudConnected.collectAsState()
    val lastSync by vm.lastCloudSync.collectAsState()
    var pendingOperation by remember { mutableStateOf<CloudSyncOperation?>(null) }
    var confirming by remember { mutableStateOf<CloudSyncOperation?>(null) }

    val resolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val operation = pendingOperation
        pendingOperation = null
        if (result.resultCode != Activity.RESULT_OK || operation == null) {
            vm.cloudError("Google authorization was cancelled.")
            return@rememberLauncherForActivityResult
        }
        runCatching { vm.finishCloudAuthorization(activity, result.data) }
            .onSuccess { vm.runCloudOperation(activity, operation, it) }
            .onFailure { vm.cloudError(it.message ?: "Google authorization failed.") }
    }

    fun authorizeAndRun(operation: CloudSyncOperation) {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> vm.runCloudOperation(activity, operation, step.accessToken)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingOperation = operation
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build()
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    Text(
        "Merge planner settings, period records, and day logs through an encrypted Google Drive snapshot. " +
            "EasyBC encrypts the snapshot before upload and only requests access to its own hidden app-data file. " +
            "After passkey unlock, the encryption key stays only in app memory for automatic sync.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (connected) Icons.Default.Cloud else Icons.Default.Key, null)
            Column {
                Text(
                    if (connected) "Encrypted cloud sync enabled on this device" else "Passkey-protected encrypted cloud copy",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    lastSync?.let { "Last encrypted cloud update ${formatSyncTime(it)}" }
                        ?: "No encrypted cloud sync has completed on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    val busy = status is SettingsViewModel.SyncStatus.Running
    if (connected) {
        Button(
            onClick = { authorizeAndRun(CloudSyncOperation.SYNC) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Sync, null)
            Spacer(Modifier.width(8.dp))
            Text("Merge encrypted cloud data")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { confirming = CloudSyncOperation.RESET },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Replace cloud passkey") }
            OutlinedButton(
                onClick = { confirming = CloudSyncOperation.DELETE },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Delete encrypted cloud copy") }
        }
    } else {
        Button(
            onClick = { authorizeAndRun(CloudSyncOperation.SETUP) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Key, null)
            Spacer(Modifier.width(8.dp))
            Text("Set up encrypted cloud sync")
        }
        OutlinedButton(
            onClick = { authorizeAndRun(CloudSyncOperation.ENABLE) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enable encrypted cloud sync on this device") }
    }
    StatusRow(status = status, onDismiss = vm::dismissCloudStatus)
    Text(
        "Encrypted sync locks after EasyBC has been in the background for 15 minutes or its process ends.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    confirming?.let { operation ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(if (operation == CloudSyncOperation.RESET) "Replace passkey and encrypted cloud copy?" else "Delete encrypted cloud copy?")
            },
            text = {
                Text(
                    if (operation == CloudSyncOperation.RESET) {
                        "This replaces the encrypted Drive snapshot with this device's local data and a new passkey. Other devices must enable encrypted cloud sync with the new passkey."
                    } else {
                        "This permanently deletes the encrypted EasyBC cloud snapshot from Drive. Local data stays on this device."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    authorizeAndRun(operation)
                }) { Text(if (operation == CloudSyncOperation.RESET) "Replace" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

private fun formatSyncTime(value: String): String = runCatching {
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
private fun ReminderSection(vm: SettingsViewModel) {
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
private fun DeviceCalendarSection(vm: SettingsViewModel) {
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
private fun BackupRestoreSection(vm: SettingsViewModel) {
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
private fun SwitchRow(
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
private fun IntField(
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
private fun DoubleField(
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
private fun SliderField(
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
