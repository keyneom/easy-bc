package com.easybc.planner.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.BuildConfig
import com.easybc.planner.data.PersistentMethod
import com.easybc.planner.data.ProtectedDayMethod
import com.easybc.planner.data.WithdrawalMode
import com.easybc.planner.diagnostics.DeveloperLog
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
    profileChip: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val exit = {
        saveOnExit?.invoke()
        onBack()
    }
    BackHandler { exit() }
    // Zero insets: the app-level scaffold already consumed the system bars.
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = exit) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { profileChip() },
                windowInsets = WindowInsets(0.dp),
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
fun PlanBasicsScreen(
    onBack: () -> Unit,
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val draft by vm.draft.collectAsState()
    SettingsSubScreen("Plan basics", onBack, saveOnExit = rememberSaveOnExit(vm), profileChip = profileChip) {
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
fun ProtectionScreen(
    onBack: () -> Unit,
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val draft by vm.draft.collectAsState()
    SettingsSubScreen("Protection", onBack, saveOnExit = rememberSaveOnExit(vm), profileChip = profileChip) {
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
fun RiskComfortScreen(
    onBack: () -> Unit,
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val draft by vm.draft.collectAsState()
    var advancedOpen by remember { mutableStateOf(false) }
    SettingsSubScreen("Risk & comfort", onBack, saveOnExit = rememberSaveOnExit(vm), profileChip = profileChip) {
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

/* ---------- Device sections ---------- */

@Composable
fun RemindersScreen(
    onBack: () -> Unit,
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    SettingsSubScreen("Reminders", onBack, profileChip = profileChip) {
        ReminderSection(vm)
    }
}

@Composable
fun DeviceCalendarScreen(
    onBack: () -> Unit,
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    SettingsSubScreen("Device calendar", onBack, profileChip = profileChip) {
        DeviceCalendarSection(vm)
    }
}

@Composable
fun BackupScreen(
    onBack: () -> Unit,
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    SettingsSubScreen("Backup & restore", onBack, profileChip = profileChip) {
        BackupRestoreSection(vm)
    }
}

/* ---------- About ---------- */

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenSetup: () -> Unit = {},
    profileChip: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val developerLog = remember { DeveloperLog(context.applicationContext) }
    var logEntries by remember { mutableStateOf(developerLog.entries()) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    SettingsSubScreen("About EasyBC", onBack, profileChip = profileChip) {
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
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
            Text("Re-run setup walkthrough")
        }
        Spacer(Modifier.height(12.dp))
        EbExpanderRow(
            label = "Developer diagnostics (${logEntries.size})",
            expanded = diagnosticsExpanded,
            onToggle = { diagnosticsExpanded = !diagnosticsExpanded },
        ) {
            Text(
                "Redacted sync and migration decisions stored only on this device. " +
                    "The log excludes access tokens, private keys, and decrypted profile data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(developerLog.formatted(logEntries)))
                    Toast.makeText(context, "Diagnostic log copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy diagnostic log")
            }
            OutlinedButton(
                onClick = {
                    developerLog.clear()
                    logEntries = emptyList()
                    Toast.makeText(context, "Diagnostic log cleared", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Clear diagnostic log")
            }
            Text(
                developerLog.formatted(logEntries),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ---------- Onboarding wizard (docs/settings-profiles-redesign.md §7) ---------- */

/**
 * Five short steps, all skippable (mockup phone 3): who + avatar, cycle
 * basics, protection, risk comfort, and where the data lives. Every step
 * writes through the same draft the full sub-screens edit; finishing (or
 * skipping) saves the draft, which marks onboarding complete. A profile is
 * always safely local until the user chooses otherwise in Storage & sharing.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun OnboardingWizardScreen(
    onDone: () -> Unit,
    /** Step 5's "set up cloud or sharing" door. */
    onOpenStorage: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val draft by vm.draft.collectAsState()
    val sharedState by vm.sharedSyncState.collectAsState()
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    val avatarLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::updateActiveProfileAvatar) }

    fun applyName() {
        val trimmed = name.trim()
        val state = sharedState ?: return
        if (trimmed.isEmpty()) return
        vm.renameProfile(state.activeProfileKey, trimmed)
    }

    fun finish(openStorage: Boolean) {
        vm.save()
        if (openStorage) onOpenStorage() else onDone()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(title = { Text("Set up") }, windowInsets = WindowInsets(0.dp))
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            com.easybc.planner.ui.kit.EbStepDots(
                count = 5,
                active = step,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                listOf(
                    "Who is this profile for?",
                    "Cycle basics",
                    "Protection",
                    "Risk & comfort",
                    "Where should it live?",
                )[step],
                style = MaterialTheme.typography.headlineSmall,
            )
            when (step) {
                0 -> {
                    Text(
                        "Setting this up for your daughter? Use her name and age — every " +
                            "profile keeps its own settings, data, and sharing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val state = sharedState
                    val active = state?.let { findActive ->
                        findActive.profiles.firstOrNull {
                            com.easybc.planner.sync.shared.profileKey(it.ownerEmail, it.datasetId) ==
                                findActive.activeProfileKey
                        }
                    }
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        com.easybc.planner.ui.kit.EbAvatar(
                            name = name.trim().ifEmpty {
                                active?.let { profile ->
                                    com.easybc.planner.sync.shared.disambiguatedProfileLabel(state!!, profile)
                                } ?: "Me"
                            },
                            colorKey = state?.activeProfileKey ?: "me",
                            size = 52.dp,
                            photoBase64 = active?.avatarWebp,
                        )
                        OutlinedButton(
                            onClick = {
                                avatarLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract
                                            .ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        ) { Text("Add photo") }
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        placeholder = { Text("Emma") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    IntField("Age", draft.ageYears, 15..55) { v ->
                        vm.updateDraft { d -> d.copy(ageYears = v) }
                    }
                }
                1 -> {
                    IntField("Typical cycle length (days)", draft.cycleLengthDays, 21..45) { v ->
                        vm.updateDraft { d -> d.copy(cycleLengthDays = v) }
                    }
                    Text(
                        "A rough guess is fine — the plan recalibrates as you log periods.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                2 -> {
                    Text("Persistent / background method", style = MaterialTheme.typography.labelLarge)
                    val currentPersistent = try {
                        PersistentMethod.entries.first {
                            it.name.equals(draft.persistentMethod, ignoreCase = true)
                        }
                    } catch (_: Exception) {
                        PersistentMethod.None
                    }
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PersistentMethod.entries.forEach { method ->
                            FilterChip(
                                selected = currentPersistent == method,
                                onClick = {
                                    vm.updateDraft { d ->
                                        d.copy(persistentMethod = method.name.lowercase())
                                    }
                                },
                                label = { Text(method.label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Text("Protected-day method", style = MaterialTheme.typography.labelLarge)
                    val currentProtected = try {
                        ProtectedDayMethod.entries.first {
                            it.name.equals(draft.protectedDayMethod, ignoreCase = true)
                        }
                    } catch (_: Exception) {
                        ProtectedDayMethod.ExternalCondom
                    }
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProtectedDayMethod.entries.forEach { method ->
                            FilterChip(
                                selected = currentProtected == method,
                                onClick = {
                                    vm.updateDraft { d ->
                                        d.copy(protectedDayMethod = method.name.lowercase())
                                    }
                                },
                                label = { Text(method.label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
                3 -> {
                    SliderField(
                        label = "Cumulative failure target",
                        value = draft.targetCumulativeFailure,
                        range = 0.005f..0.5f,
                        format = { "%.1f%%".format(it * 100) },
                        onValueChange = { v ->
                            vm.updateDraft { d -> d.copy(targetCumulativeFailure = v.toDouble()) }
                        },
                    )
                    Text(
                        "If 100 couples followed this plan for ${draft.horizonYears} years, about " +
                            "${"%.0f".format(draft.targetCumulativeFailure * 100)} would expect a " +
                            "pregnancy. Everything else can be tuned later in Risk & comfort.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        "Your data stays on this phone unless you choose otherwise. You can " +
                            "turn on private encrypted cloud sync — or share selected sections " +
                            "with someone — any time in Storage & sharing. A failed cloud setup " +
                            "always leaves the profile safely local, never lost.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (step < 4) {
                androidx.compose.material3.Button(
                    onClick = {
                        if (step == 0) applyName()
                        step += 1
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue") }
            } else {
                androidx.compose.material3.Button(
                    onClick = { finish(openStorage = false) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Finish — keep it on this phone") }
                OutlinedButton(
                    onClick = { finish(openStorage = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Finish & open Storage & sharing") }
            }
            androidx.compose.material3.TextButton(
                onClick = { finish(openStorage = false) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use defaults & skip setup") }
        }
    }
}
