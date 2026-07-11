package com.easybc.planner.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.BuildConfig
import com.easybc.planner.data.PersistentMethod
import com.easybc.planner.data.ProtectedDayMethod
import com.easybc.planner.data.WithdrawalMode
import com.easybc.planner.ui.kit.EbExpanderRow

/*
 * Focused settings sub-screens behind the hub (docs/settings-profiles-redesign.md
 * §2/§10). Plan screens save on back with a toast; device/sharing screens wrap
 * the existing self-contained sections from SettingsScreen.kt unchanged.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    saveOnExit: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val exit = {
        saveOnExit?.invoke()
        onBack()
    }
    BackHandler { exit() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = exit) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

/** Saves plan-affecting draft changes when leaving a plan screen. */
@Composable
private fun rememberSaveOnExit(vm: SettingsViewModel): () -> Unit {
    val context = LocalContext.current
    return {
        val saved = vm.settings.value
        if (saved == null || vm.draft.value != saved) {
            vm.save()
            Toast.makeText(
                context,
                "Plan settings saved — the plan will recompute.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

/* ---------- Plan basics ---------- */

@Composable
fun PlanBasicsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val draft by vm.draft.collectAsState()
    SettingsSubScreen("Plan basics", onBack, saveOnExit = rememberSaveOnExit(vm)) {
        Text(
            "The two numbers everything else builds on. Changes apply to the active profile only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IntField("Age", draft.ageYears, 15..55) { v ->
            vm.updateDraft { d -> d.copy(ageYears = v) }
        }
        IntField("Typical cycle length (days)", draft.cycleLengthDays, 21..45) { v ->
            vm.updateDraft { d -> d.copy(cycleLengthDays = v) }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { vm.resetToDefaults() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.RestartAlt, null)
            Spacer(Modifier.width(8.dp))
            Text("Reset plan settings to defaults")
        }
        Text(
            "Resets every plan setting (basics, protection, risk & comfort) for this profile. " +
                "Your logged data is untouched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ---------- Protection ---------- */

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProtectionScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val draft by vm.draft.collectAsState()
    SettingsSubScreen("Protection", onBack, saveOnExit = rememberSaveOnExit(vm)) {
        Text("Persistent / background method", style = MaterialTheme.typography.labelLarge)
        Text(
            "An always-on method that reduces baseline risk for all days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val currentPersistent = try {
            PersistentMethod.entries.first { it.name.equals(draft.persistentMethod, ignoreCase = true) }
        } catch (_: Exception) {
            PersistentMethod.None
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PersistentMethod.entries.forEach { method ->
                FilterChip(
                    selected = currentPersistent == method,
                    onClick = { vm.updateDraft { d -> d.copy(persistentMethod = method.name.lowercase()) } },
                    label = { Text(method.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Protected-day method", style = MaterialTheme.typography.labelLarge)
        Text(
            "Barrier method used on days marked 'C' (protected). Controls what 'condom' means in the plan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val currentProtected = try {
            ProtectedDayMethod.entries.first { it.name.equals(draft.protectedDayMethod, ignoreCase = true) }
        } catch (_: Exception) {
            ProtectedDayMethod.ExternalCondom
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProtectedDayMethod.entries.forEach { method ->
                FilterChip(
                    selected = currentProtected == method,
                    onClick = { vm.updateDraft { d -> d.copy(protectedDayMethod = method.name.lowercase()) } },
                    label = { Text(method.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

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
        Text("Withdrawal", style = MaterialTheme.typography.labelLarge)
        Text(
            "If enabled, the planner can recommend withdrawal (W) on moderate-risk days.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val currentWithdrawal = try {
            WithdrawalMode.entries.first { it.name.equals(draft.withdrawalMode, ignoreCase = true) }
        } catch (_: Exception) {
            WithdrawalMode.None
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WithdrawalMode.entries.forEach { mode ->
                FilterChip(
                    selected = currentWithdrawal == mode,
                    onClick = { vm.updateDraft { d -> d.copy(withdrawalMode = mode.name.lowercase()) } },
                    label = { Text(mode.label) },
                )
            }
        }
        if (currentWithdrawal == WithdrawalMode.Custom) {
            DoubleField("Withdrawal relative risk", draft.withdrawalRelativeRisk, 0.0..1.0) { v ->
                vm.updateDraft { d -> d.copy(withdrawalRelativeRisk = v) }
            }
        }

        // Combined method layering — only relevant when both are in play.
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
                    onValueChange = { v ->
                        vm.updateDraft { d -> d.copy(combinedMethodIndependence = v.toDouble()) }
                    },
                )
            }
        }
    }
}

/* ---------- Risk & comfort ---------- */

@Composable
fun RiskComfortScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val draft by vm.draft.collectAsState()
    var advancedOpen by remember { mutableStateOf(false) }
    SettingsSubScreen("Risk & comfort", onBack, saveOnExit = rememberSaveOnExit(vm)) {
        SliderField(
            label = "Cumulative failure target",
            value = draft.targetCumulativeFailure,
            range = 0.005f..0.5f,
            format = { "%.1f%%".format(it * 100) },
            onValueChange = { v -> vm.updateDraft { d -> d.copy(targetCumulativeFailure = v.toDouble()) } },
        )
        Text(
            "If 100 couples followed this plan for ${draft.horizonYears} years, about " +
                "${"%.0f".format(draft.targetCumulativeFailure * 100)} would expect a pregnancy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DoubleField("Acts per week", draft.actsPerWeek, 0.0..14.0) { v ->
            vm.updateDraft { d -> d.copy(actsPerWeek = v) }
        }
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
        Spacer(Modifier.height(8.dp))
        EbExpanderRow(
            label = "Advanced — horizon, ovulation SD, lifecycle",
            expanded = advancedOpen,
            onToggle = { advancedOpen = !advancedOpen },
        ) {
            IntField("Horizon (years)", draft.horizonYears, 1..40) { v ->
                vm.updateDraft { d -> d.copy(horizonYears = v) }
            }
            DoubleField("Ovulation SD (days)", draft.ovulationSdDays, 0.5..15.0) { v ->
                vm.updateDraft { d -> d.copy(ovulationSdDays = v) }
            }
            SwitchRow(
                label = "Hold lifecycle constant",
                checked = draft.holdLifecycleConstant,
                onCheckedChange = { checked ->
                    vm.updateDraft { d -> d.copy(holdLifecycleConstant = checked) }
                },
            )
        }
    }
}

/* ---------- Profiles, storage & sharing ---------- */

@Composable
fun StorageSharingScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    SettingsSubScreen("Profiles & sharing", onBack) {
        EncryptedSyncSection(vm)
    }
}

/* ---------- Device sections ---------- */

@Composable
fun RemindersScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    SettingsSubScreen("Reminders", onBack) {
        ReminderSection(vm)
    }
}

@Composable
fun DeviceCalendarScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    SettingsSubScreen("Device calendar", onBack) {
        DeviceCalendarSection(vm)
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    SettingsSubScreen("Backup & restore", onBack) {
        BackupRestoreSection(vm)
    }
}

/* ---------- About ---------- */

@Composable
fun AboutScreen(onBack: () -> Unit) {
    SettingsSubScreen("About EasyBC", onBack) {
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Text("Disclaimers", style = MaterialTheme.typography.labelLarge)
        Text(
            "This is not FDA-cleared as contraception. " +
                "Calculations assume regular cycles. " +
                "Consult a healthcare provider for medical advice. " +
                "Plan effectiveness depends on adherence.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
