package com.easybc.planner.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.data.ProtectedDayMethod
import com.easybc.planner.data.RecommendedAction
import com.easybc.planner.data.WithdrawalMode
import com.easybc.planner.ui.theme.*
import java.time.DayOfWeek
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onOpenReconcile: () -> Unit = {},
    vm: CalendarViewModel = viewModel(),
) {
    val currentMonth by vm.currentMonth.collectAsState()
    val viewMode by vm.viewMode.collectAsState()
    val monthCells by vm.monthCells.collectAsState()
    val weekCells by vm.weekCells.collectAsState()
    val selectedDate by vm.selectedDate.collectAsState()
    val showDetail by vm.showDayDetail.collectAsState()
    val selectedDetail by vm.selectedDayDetail.collectAsState()
    val settings by vm.settings.collectAsState()
    val plan by vm.plannerResult.collectAsState()
    val unreconciledCount by vm.unreconciledCount.collectAsState()
    val cycleLedger by vm.currentCycleLedger.collectAsState()

    Scaffold(
        topBar = {
            Column {
                // Month/year header with navigation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (viewMode == CalendarViewMode.MONTH) vm.navigateMonth(-1)
                        else vm.navigateWeek(-1)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous")
                    }

                    Text(
                        text = currentMonth.month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault()) +
                            " ${currentMonth.year}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )

                    IconButton(onClick = {
                        if (viewMode == CalendarViewMode.MONTH) vm.navigateMonth(1)
                        else vm.navigateWeek(1)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next")
                    }
                }

                // View mode toggle + today button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = viewMode == CalendarViewMode.MONTH,
                            onClick = { vm.setViewMode(CalendarViewMode.MONTH) },
                            label = { Text("Month") },
                            leadingIcon = { Icon(Icons.Default.GridView, null, Modifier.size(16.dp)) },
                        )
                        FilterChip(
                            selected = viewMode == CalendarViewMode.WEEK,
                            onClick = { vm.setViewMode(CalendarViewMode.WEEK) },
                            label = { Text("Week") },
                            leadingIcon = { Icon(Icons.Default.CalendarViewWeek, null, Modifier.size(16.dp)) },
                        )
                    }
                    TextButton(onClick = { vm.goToToday() }) {
                        Icon(Icons.Default.Today, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Today")
                    }
                }

                // Day-of-week headers
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                ) {
                    for (dow in listOf(
                        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
                    )) {
                        Text(
                            text = dow.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Mock data banner
                if (!vm.bridgeIsNative) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Using sample data — native core not loaded",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }

                if (settings?.onboardingComplete != true) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Set up your profile in Settings to see your personalized plan",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // Open-period nudge — appears when there's a period whose
                // predicted end is already past. Tapping selects today and
                // opens the day-detail sheet so the user can confirm the
                // end date in one move.
                val openPeriodPast by vm.openPeriodPastPrediction.collectAsState()
                if (openPeriodPast != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.selectDate(java.time.LocalDate.now())
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Period still open past predicted end. Tap today to confirm or extend.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "Confirm →",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Reconciliation nudge — appears only when there's concrete
                // work (past U/W/C days the user hasn't confirmed yet). Tapping
                // it opens the batch-reconcile screen.
                if (unreconciledCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenReconcile),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Reconcile $unreconciledCount past " +
                                    if (unreconciledCount == 1) "day" else "days",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "Review →",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Horizontal swipe to advance the calendar: left swipe = next
            // month/week, right swipe = previous. Only fires after a real
            // horizontal drag, so cell taps still pass through.
            val density = LocalDensity.current
            val swipeThresholdPx = with(density) { 64.dp.toPx() }
            val dragAccumulator = remember { mutableFloatStateOf(0f) }
            Box(
                modifier = Modifier.pointerInput(viewMode) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccumulator.floatValue = 0f },
                        onDragEnd = {
                            val dx = dragAccumulator.floatValue
                            when {
                                dx <= -swipeThresholdPx ->
                                    if (viewMode == CalendarViewMode.MONTH) vm.navigateMonth(1)
                                    else vm.navigateWeek(1)
                                dx >= swipeThresholdPx ->
                                    if (viewMode == CalendarViewMode.MONTH) vm.navigateMonth(-1)
                                    else vm.navigateWeek(-1)
                            }
                            dragAccumulator.floatValue = 0f
                        },
                        onDragCancel = { dragAccumulator.floatValue = 0f },
                    ) { _, dragAmount ->
                        dragAccumulator.floatValue += dragAmount
                    }
                },
            ) {
                when (viewMode) {
                    CalendarViewMode.MONTH -> MonthGrid(
                        cells = monthCells,
                        selectedDate = selectedDate,
                        onDateClick = { vm.selectDate(it.date) },
                    )
                    CalendarViewMode.WEEK -> WeekStrip(
                        cells = weekCells,
                        selectedDate = selectedDate,
                        onDateClick = { vm.selectDate(it.date) },
                    )
                }
            }

            // Derive which action types are active based on the plan result
            val activeActions = remember(plan) {
                plan?.years?.flatMap { y -> y.dayWeights.map { it.recommendedAction } }
                    ?.toSet()
                    ?: setOf(RecommendedAction.U, RecommendedAction.C, RecommendedAction.A)
            }

            // Legend — dynamic based on which actions the planner is using
            ActionLegend(
                activeActions = activeActions,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Cycle risk ledger — shows logged risk, current replanned cycle
            // risk, and abstinence credits. Only appears when we have a plan +
            // today falls inside a known cycle.
            cycleLedger?.let { ledger ->
                CycleLedgerCard(
                    ledger = ledger,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // Day detail bottom sheet
        if (showDetail && selectedDetail != null) {
            val activeActions = remember(plan) {
                plan?.years?.flatMap { y -> y.dayWeights.map { it.recommendedAction } }
                    ?.toSet()
                    ?: setOf(RecommendedAction.U, RecommendedAction.C, RecommendedAction.A)
            }

            val signalsDefaultExpanded by vm.hasEverLoggedObservations.collectAsState()
            DayDetailSheet(
                cell = selectedDetail!!,
                activeActions = activeActions,
                signalsDefaultExpanded = signalsDefaultExpanded,
                onDismiss = { vm.dismissDayDetail() },
                onLogPeriodStart = { vm.logPeriodStart(selectedDetail!!.date) },
                onClearPeriodStart = { vm.clearPeriodStart(selectedDetail!!.date) },
                onLogPeriodEnd = { vm.endCurrentPeriod(selectedDetail!!.date) },
                onClearPeriodEnd = { vm.clearPeriodEnd(selectedDetail!!.date) },
                onLogAction = { action -> vm.logDayAction(selectedDetail!!.date, action) },
                onLogObservations = { mucus, bbt, opk, mitt, tender ->
                    vm.logDayObservations(
                        date = selectedDetail!!.date,
                        mucus = mucus,
                        bbtCelsius = bbt,
                        opk = opk,
                        mittelschmerz = mitt,
                        breastTender = tender,
                    )
                },
            )
        }
    }
}

@Composable
private fun MonthGrid(
    cells: List<DayCellData>,
    selectedDate: java.time.LocalDate,
    onDateClick: (DayCellData) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(cells, key = { it.date.toEpochDay() }) { cell ->
            DayCell(
                cell = cell,
                isSelected = cell.date == selectedDate,
                compact = true,
                onClick = { onDateClick(cell) },
            )
        }
    }
}

@Composable
private fun WeekStrip(
    cells: List<DayCellData>,
    selectedDate: java.time.LocalDate,
    onDateClick: (DayCellData) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            cells.forEach { cell ->
                Box(modifier = Modifier.weight(1f)) {
                    DayCell(
                        cell = cell,
                        isSelected = cell.date == selectedDate,
                        compact = false,
                        onClick = { onDateClick(cell) },
                    )
                }
            }
        }
    }
}

@Composable
fun DayCell(
    cell: DayCellData,
    isSelected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = when {
        cell.isPeriod -> if (isSystemInDarkTheme()) PeriodBgDark else PeriodBg
        cell.plannerAction != null -> actionBackgroundColor(cell.plannerAction)
        else -> MaterialTheme.colorScheme.surface
    }

    val borderMod = if (isSelected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
    } else if (cell.isToday) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    } else {
        Modifier
    }

    val alpha = if (cell.isCurrentMonth) 1f else 0.35f

    Column(
        modifier = Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(borderMod)
            .background(bgColor.copy(alpha = alpha))
            .clickable(onClick = onClick)
            .padding(if (compact) 4.dp else 8.dp)
            .then(if (compact) Modifier.heightIn(min = 48.dp) else Modifier.heightIn(min = 80.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Date number
        Text(
            text = cell.dayOfMonth.toString(),
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )

        // Period dot. Filled when the bleed window is user-confirmed
        // (period.endDate set and this day is inside it). Hollow ring when
        // it's still predicted (open period whose end is extrapolated from
        // history) so users can tell at a glance which days they've
        // confirmed vs. which are guesses.
        if (cell.isPeriod) {
            if (cell.isPeriodPredicted) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .border(1.dp, PeriodColor, CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(PeriodColor),
                )
            }
        }

        // Action badge
        if (cell.plannerAction != null && !compact) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = cell.plannerAction.shortLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = actionForegroundColor(cell.plannerAction),
            )
        } else if (cell.plannerAction != null && compact) {
            Text(
                text = cell.plannerAction.shortLabel,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = actionForegroundColor(cell.plannerAction),
            )
        }

        // Risk score (week view only)
        if (!compact && cell.riskScore != null && cell.riskScore > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Risk ${cell.riskScore}",
                style = MaterialTheme.typography.labelSmall,
                color = riskColor(cell.riskScore),
            )
        }

        // Phase label (week view only)
        if (!compact && cell.phase != null) {
            Text(
                text = cell.phase.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Day log indicator
        if (cell.dayLog != null) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
        }
    }
}

@Composable
fun ActionLegend(
    activeActions: Set<RecommendedAction>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        if (RecommendedAction.U in activeActions) {
            LegendItem(color = ActionUnprotected, label = "Unprotected")
        }
        if (RecommendedAction.W in activeActions) {
            LegendItem(color = ActionWithdrawal, label = "Withdrawal")
        }
        if (RecommendedAction.C in activeActions) {
            LegendItem(color = ActionCondom, label = "Protected")
        }
        if (RecommendedAction.A in activeActions) {
            LegendItem(color = ActionAbstain, label = "Abstain")
        }
        LegendItem(color = PeriodColor, label = "Period")
    }
}

/**
 * Small at-a-glance card showing the current cycle's logged risk vs. the
 * current replanned cycle risk, plus risk saved/spent against the unlocked
 * baseline plan for logged days in this cycle.
 *
 * The visual is a linear progress bar colored green when the replanned
 * horizon meets target and red when the target can no longer be met.
 */
@Composable
private fun CycleLedgerCard(
    ledger: CycleLedger,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Cycle risk ledger",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Day ${ledger.currentDayInCycle} of ${ledger.cycleLengthDays}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            val fraction = when {
                ledger.plannedCycleRisk > 0 ->
                    (ledger.realizedSoFar / ledger.plannedCycleRisk).toFloat().coerceIn(0f, 1f)
                ledger.realizedSoFar > 0 -> 1f
                else -> 0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (ledger.overBudget) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Logged ${"%.2f".format(ledger.realizedSoFar * 100)}%; plan ${"%.2f".format(ledger.plannedCycleRisk * 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (ledger.savedRiskVsBaseline > 1e-12) {
                    Text(
                        "Saved ${formatLedgerPercent(ledger.savedRiskVsBaseline)} vs plan",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                } else if (ledger.extraRiskVsBaseline > 1e-12) {
                    Text(
                        "Spent ${formatLedgerPercent(ledger.extraRiskVsBaseline)} vs plan",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (ledger.cycleOverPlan && ledger.targetMet) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Reserve covering cycle overage",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (!ledger.targetMet) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Horizon over target: ${"%.2f".format(ledger.horizonRisk * 100)}% of ${"%.2f".format(ledger.horizonTarget * 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun formatLedgerPercent(value: Double): String {
    val pct = value * 100.0
    return if (pct < 0.01) "<0.01%" else "%.2f%%".format(pct)
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun isSystemInDarkTheme(): Boolean = androidx.compose.foundation.isSystemInDarkTheme()
