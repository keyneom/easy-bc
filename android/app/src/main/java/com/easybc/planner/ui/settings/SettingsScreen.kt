package com.easybc.planner.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
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

            // Condom mode
            Text("Condom mode", style = MaterialTheme.typography.labelLarge)
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

            // ── Advanced ──
            SectionHeader("Advanced")

            DoubleField("Ovulation SD (days)", draft.ovulationSdDays, 0.5..15.0) { v ->
                vm.updateDraft { d -> d.copy(ovulationSdDays = v) }
            }

            DoubleField("Withdrawal relative risk", draft.withdrawalRelativeRisk, 0.0..1.0) { v ->
                vm.updateDraft { d -> d.copy(withdrawalRelativeRisk = v) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Hold lifecycle constant", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = draft.holdLifecycleConstant,
                    onCheckedChange = { checked -> vm.updateDraft { d -> d.copy(holdLifecycleConstant = checked) } },
                )
            }

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
