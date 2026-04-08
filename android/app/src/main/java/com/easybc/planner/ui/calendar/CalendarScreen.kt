package com.easybc.planner.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.data.RecommendedAction
import com.easybc.planner.ui.theme.*
import java.time.DayOfWeek
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: CalendarViewModel = viewModel()) {
    val currentMonth by vm.currentMonth.collectAsState()
    val viewMode by vm.viewMode.collectAsState()
    val monthCells by vm.monthCells.collectAsState()
    val weekCells by vm.weekCells.collectAsState()
    val selectedDate by vm.selectedDate.collectAsState()
    val showDetail by vm.showDayDetail.collectAsState()
    val selectedDetail by vm.selectedDayDetail.collectAsState()
    val settings by vm.settings.collectAsState()
    val plan by vm.plannerResult.collectAsState()

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
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
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

            // Legend
            ActionLegend(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        // Day detail bottom sheet
        if (showDetail && selectedDetail != null) {
            DayDetailSheet(
                cell = selectedDetail!!,
                onDismiss = { vm.dismissDayDetail() },
                onLogPeriod = { vm.logPeriodStart(selectedDetail!!.date) },
                onLogAction = { action -> vm.logDayAction(selectedDetail!!.date, action) },
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

        // Period dot
        if (cell.isPeriod) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(PeriodColor),
            )
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
fun ActionLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        LegendItem(color = ActionUnprotected, label = "Unprotected")
        LegendItem(color = ActionCondom, label = "Condom")
        LegendItem(color = ActionAbstain, label = "Abstain")
        LegendItem(color = PeriodColor, label = "Period")
    }
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
