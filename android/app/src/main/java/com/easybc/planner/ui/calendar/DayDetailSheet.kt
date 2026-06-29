package com.easybc.planner.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.easybc.planner.data.RecommendedAction
import com.easybc.planner.data.db.DayEventEntity
import com.easybc.planner.ui.theme.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailSheet(
    cell: DayCellData,
    /** Which action types the planner is actually using (drives which log buttons appear). */
    activeActions: Set<RecommendedAction>,
    /** If true, auto-expand the optional Body Signals section. */
    signalsDefaultExpanded: Boolean,
    onDismiss: () -> Unit,
    onLogPeriodStart: () -> Unit,
    onClearPeriodStart: () -> Unit,
    onLogPeriodEnd: () -> Unit,
    onClearPeriodEnd: () -> Unit,
    onLogAction: (RecommendedAction) -> Unit,
    onClearAction: () -> Unit,
    onLogEvent: (kind: String, ecType: String?, hoursFromAct: Double?) -> Unit,
    onDeleteEvent: (DayEventEntity) -> Unit,
    onLogObservations: (
        mucus: String?,
        bbtCelsius: Double?,
        opk: String?,
        mittelschmerz: Boolean,
        breastTender: Boolean,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = cell.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (cell.cycleDay != null) {
                        Text(
                            text = "Cycle day ${cell.cycleDay}" +
                                (if (cell.cycleLengthDays != null) " of ${cell.cycleLengthDays}" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close")
                }
            }

            // Phase & period status
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cell.phase != null) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(cell.phase.label) },
                    )
                }
                if (cell.isPeriod) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Period") },
                        icon = { Icon(Icons.Default.WaterDrop, null, Modifier.size(16.dp), tint = PeriodColor) },
                    )
                }
                if (cell.cycleFlag != null) {
                    val (label, tooltip) = atypicalChipCopy(cell.cycleFlag)
                    SuggestionChip(
                        onClick = {},
                        label = { Text(label) },
                    )
                    // Keep `tooltip` around so the compiler doesn't strip it —
                    // a future polish pass can move this into a PlainTooltipBox.
                    @Suppress("UNUSED_EXPRESSION") tooltip
                }
            }

            // Planner recommendation
            if (cell.plannerAction != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = actionBackgroundColor(cell.plannerAction),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Recommendation",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = cell.plannerAction.label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = actionForegroundColor(cell.plannerAction),
                        )

                        if (cell.riskScore != null) {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Risk score",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                LinearProgressIndicator(
                                    progress = { cell.riskScore / 100f },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = riskColor(cell.riskScore),
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                )
                                Text(
                                    text = "${cell.riskScore}/100",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = riskColor(cell.riskScore),
                                )
                            }
                        }
                    }
                }
            }

            // Override cost
            if (cell.overrideCost != null && cell.overrideCost.overrideAction != null) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "If you use less protection",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = cell.overrideCost.recoveryNote(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (cell.overrideCost.condoms > 0 || cell.overrideCost.abstinenceDays > 0) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (cell.overrideCost.condoms > 0) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "+${cell.overrideCost.condoms}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = ActionCondom,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text("recovery protected days", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (cell.overrideCost.abstinenceDays > 0) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "+${cell.overrideCost.abstinenceDays}",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = ActionAbstain,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text("recovery abstinence days", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // Log what actually happened
            Text(
                text = "Log what happened",
                style = MaterialTheme.typography.titleSmall,
            )

            // Build the list of action buttons dynamically based on active methods.
            // U and A are always available; W and C depend on the user's method config.
            val logActions = buildList {
                add(RecommendedAction.U)
                if (RecommendedAction.W in activeActions) add(RecommendedAction.W)
                if (RecommendedAction.C in activeActions) add(RecommendedAction.C)
                add(RecommendedAction.A)
            }

            var showClearActionConfirm by remember(cell.date) { mutableStateOf(false) }
            val loggedActionInRow = logActions.any { it.shortLabel == cell.dayLog?.actualAction }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                logActions.forEach { action ->
                    val isActive = cell.dayLog?.actualAction == action.shortLabel
                    ActionLogButton(
                        action = action,
                        isActive = isActive,
                        modifier = Modifier.weight(1f),
                    ) {
                        // Tapping the already-logged action asks to clear it;
                        // tapping any other action just switches the log.
                        if (isActive) showClearActionConfirm = true
                        else onLogAction(action)
                    }
                }
            }

            if (loggedActionInRow) {
                Text(
                    text = "Tap the highlighted action to change or clear it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "This sets the day's overall pattern. For a one-off incident — " +
                    "a broken condom, or unprotected sex on a planned-abstain day — add " +
                    "an Event below instead. Incidents are counted per act, so they carry " +
                    "more weight than a whole-day pattern.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (showClearActionConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearActionConfirm = false },
                    title = { Text("Clear logged action?") },
                    text = {
                        Text(
                            "This removes what you logged for this day. Any body " +
                                "signals you've recorded stay. Your plan updates automatically.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            onClearAction()
                            showClearActionConfirm = false
                        }) { Text("Clear") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearActionConfirm = false }) { Text("Cancel") }
                    },
                )
            }

            HorizontalDivider()

            DayEventsSection(
                events = cell.events,
                onLogEvent = onLogEvent,
                onDeleteEvent = onDeleteEvent,
            )

            HorizontalDivider()

            // ── Period logging ──
            Text(
                text = "Period tracking",
                style = MaterialTheme.typography.titleSmall,
            )

            if (cell.hasPeriodRecord) {
                if (cell.isPeriodStartDay) {
                    PeriodConfirmedRow(
                        label = "Period started this day",
                        onUndo = onClearPeriodStart,
                    )
                }
                if (cell.isPeriodEndDay) {
                    PeriodConfirmedRow(
                        label = "Period ended this day",
                        onUndo = onClearPeriodEnd,
                    )
                } else {
                    FilledTonalButton(
                        onClick = onLogPeriodEnd,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = PeriodColor.copy(alpha = 0.15f),
                        ),
                    ) {
                        Icon(Icons.Default.WaterDrop, null, Modifier.size(18.dp), tint = PeriodColor)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (cell.isPeriodPredicted) "Confirm period ended on this day"
                            else "Mark period ended on this day"
                        )
                    }
                }
            } else {
                // Either a non-period day or a predicted future bleed day —
                // both reduce to "log a period start here" if the user
                // wants to commit the prediction (or correct it).
                OutlinedButton(
                    onClick = onLogPeriodStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.WaterDrop, null, Modifier.size(18.dp), tint = PeriodColor)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (cell.isPeriod) "Confirm period started on this day"
                        else "Mark period start"
                    )
                }
            }

            HorizontalDivider()

            // ── Optional body signals ──
            //
            // Always present but collapsed by default for new users so the
            // sheet stays minimal. Once a user has ever logged a signal we
            // auto-expand (via [signalsDefaultExpanded]) so repeat use is
            // one tap away. We never nag or hint — per the "super low
            // maintenance" design constraint.
            BodySignalsSection(
                dayLog = cell.dayLog,
                defaultExpanded = signalsDefaultExpanded,
                onSave = onLogObservations,
            )

            if (cell.dayLog != null && cell.dayLog.actualAction.isNotBlank()) {
                Text(
                    text = "Logged: ${cell.dayLog.actualAction}" +
                        (cell.dayLog.notes?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DayEventsSection(
    events: List<DayEventEntity>,
    onLogEvent: (kind: String, ecType: String?, hoursFromAct: Double?) -> Unit,
    onDeleteEvent: (DayEventEntity) -> Unit,
) {
    var addingKind by remember { mutableStateOf<String?>(null) }
    var ecType by remember { mutableStateOf("levonorgestrel") }
    var hoursText by remember { mutableStateOf("") }

    Text("Events", style = MaterialTheme.typography.titleSmall)
    Text(
        "Log incidents independently of the planned action.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    events.forEach { event ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (event.kind) {
                        "condom_broke" -> "Condom broke"
                        "unplanned_unprotected" -> "Unplanned unprotected"
                        "plan_b_taken" -> when (event.ecType) {
                            "ulipristal" -> "ella (ulipristal)"
                            "copper_iud" -> "Copper IUD as emergency contraception"
                            else -> "Plan B (levonorgestrel)"
                        }
                        else -> event.kind
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                event.hoursFromAct?.let {
                    Text(
                        "${it.toDisplayHours()} hours after the act",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { onDeleteEvent(event) }) { Text("Remove") }
        }
    }

    if (addingKind == null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { addingKind = "condom_broke" },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("+ Condom broke") }
            OutlinedButton(
                onClick = { addingKind = "unplanned_unprotected" },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("+ Unplanned unprotected") }
            OutlinedButton(
                onClick = { addingKind = "plan_b_taken" },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("+ Emergency contraception") }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                when (addingKind) {
                    "condom_broke" -> "Condom broke"
                    "unplanned_unprotected" -> "Unplanned unprotected"
                    else -> "Emergency contraception"
                },
                fontWeight = FontWeight.SemiBold,
            )
            if (addingKind == "plan_b_taken") {
                Text("Type", style = MaterialTheme.typography.labelMedium)
                listOf(
                    "levonorgestrel" to "Plan B",
                    "ulipristal" to "ella",
                    "copper_iud" to "Copper IUD",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = ecType == value,
                        onClick = { ecType = value },
                        label = { Text(label) },
                    )
                }
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Hours since the act (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val hours = hoursText.toDoubleOrNull()?.coerceIn(0.0, 120.0)
                    onLogEvent(
                        addingKind!!,
                        ecType.takeIf { addingKind == "plan_b_taken" },
                        hours,
                    )
                    addingKind = null
                    hoursText = ""
                }) { Text("Save event") }
                TextButton(onClick = {
                    addingKind = null
                    hoursText = ""
                }) { Text("Cancel") }
            }
            if (addingKind == "plan_b_taken") {
                Text(
                    "EC is kept in cycle history, but EasyBC does not assign it a numeric " +
                        "efficacy credit. Effectiveness depends on timing and clinical factors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Double.toDisplayHours(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

/**
 * Confirmed-period row with an inline Undo. Used for both the recorded
 * start and the recorded end day so a mistaken tap is reversible without
 * leaving the sheet.
 */
@Composable
private fun PeriodConfirmedRow(label: String, onUndo: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = {},
            modifier = Modifier.weight(1f),
            enabled = false,
            colors = ButtonDefaults.filledTonalButtonColors(
                disabledContainerColor = PeriodColor.copy(alpha = 0.15f),
                disabledContentColor = PeriodColor,
            ),
        ) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = PeriodColor)
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
        OutlinedButton(onClick = onUndo) {
            Text("Undo")
        }
    }
}

/**
 * Expandable section for mucus / BBT / OPK / Mittelschmerz / breast
 * tenderness. All fields optional. Editing any one field and tapping Save
 * merges into the existing day log without touching action or notes.
 */
@Composable
private fun BodySignalsSection(
    dayLog: com.easybc.planner.data.db.DayLog?,
    defaultExpanded: Boolean,
    onSave: (
        mucus: String?,
        bbtCelsius: Double?,
        opk: String?,
        mittelschmerz: Boolean,
        breastTender: Boolean,
    ) -> Unit,
) {
    // Expanded state is keyed by the log identity so switching days keeps
    // the collapse state sticky per-day. The default comes from whether
    // the user has ever logged *any* signal across their history.
    var expanded by remember(dayLog?.date, defaultExpanded) {
        mutableStateOf(defaultExpanded || dayLog?.hasAnySignal() == true)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Body signals (optional)",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            if (expanded) "Hide" else "Add",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (!expanded) return

    // Initial values come from the log so re-opening a day shows prior entries.
    var mucus by remember(dayLog?.date) { mutableStateOf(dayLog?.mucus) }
    var bbtText by remember(dayLog?.date) {
        mutableStateOf(dayLog?.bbtCelsius?.let { "%.2f".format(it) } ?: "")
    }
    var opk by remember(dayLog?.date) { mutableStateOf(dayLog?.opk) }
    var mittelschmerz by remember(dayLog?.date) {
        mutableStateOf(dayLog?.mittelschmerz ?: false)
    }
    var breastTender by remember(dayLog?.date) {
        mutableStateOf(dayLog?.breastTender ?: false)
    }

    // Mucus — exclusive single-select. Order is rising fertility.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Cervical mucus", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (opt in listOf("dry", "sticky", "creamy", "eggwhite", "spotting")) {
                FilterChip(
                    selected = mucus == opt,
                    onClick = { mucus = if (mucus == opt) null else opt },
                    label = { Text(opt, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }

    // OPK — three-state.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Ovulation test (LH)", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (opt in listOf("negative", "positive", "peak")) {
                FilterChip(
                    selected = opk == opt,
                    onClick = { opk = if (opk == opt) null else opt },
                    label = { Text(opt, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }

    // BBT — manual °C entry. Digits + one decimal.
    OutlinedTextField(
        value = bbtText,
        onValueChange = { input ->
            // Permit partial edits but only digits + one decimal separator
            if (input.matches(Regex("^\\d{0,3}(\\.\\d{0,2})?$"))) bbtText = input
        },
        label = { Text("Basal body temp (°C)") },
        placeholder = { Text("e.g. 36.65") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = mittelschmerz,
            onClick = { mittelschmerz = !mittelschmerz },
            label = { Text("Ovulation pain", style = MaterialTheme.typography.labelSmall) },
        )
        FilterChip(
            selected = breastTender,
            onClick = { breastTender = !breastTender },
            label = { Text("Breast tender", style = MaterialTheme.typography.labelSmall) },
        )
    }

    FilledTonalButton(
        onClick = {
            onSave(
                mucus,
                bbtText.toDoubleOrNull(),
                opk,
                mittelschmerz,
                breastTender,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Save signals")
    }
}

private fun com.easybc.planner.data.db.DayLog.hasAnySignal(): Boolean =
    mucus != null || bbtCelsius != null || opk != null || mittelschmerz || breastTender

/**
 * Short chip label + longer tooltip copy for an atypical-cycle flag. Kept
 * here (UI layer) rather than in [CycleCalculator] so the core stays pure.
 */
private fun atypicalChipCopy(flag: com.easybc.planner.util.CycleCalculator.CycleFlag): Pair<String, String> =
    when (flag) {
        com.easybc.planner.util.CycleCalculator.CycleFlag.NORMAL ->
            "" to ""
        com.easybc.planner.util.CycleCalculator.CycleFlag.ATYPICAL_LENGTH ->
            "Atypical length" to
                "Cycle length fell outside your usual range — fertile window widened for this cycle."
        com.easybc.planner.util.CycleCalculator.CycleFlag.ATYPICAL_BLEED ->
            "Atypical bleeding" to
                "Bleeding duration fell outside your usual range — fertile window widened for this cycle."
        com.easybc.planner.util.CycleCalculator.CycleFlag.ATYPICAL_BOTH ->
            "Atypical cycle" to
                "Cycle length and bleeding both fell outside your usual range — fertile window widened for this cycle."
    }

@Composable
private fun ActionLogButton(
    action: RecommendedAction,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = if (isActive) {
        ButtonDefaults.filledTonalButtonColors(
            containerColor = actionForegroundColor(action).copy(alpha = 0.2f),
        )
    } else {
        ButtonDefaults.outlinedButtonColors()
    }

    if (isActive) {
        FilledTonalButton(onClick = onClick, modifier = modifier, colors = colors) {
            Text(action.label, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(action.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
