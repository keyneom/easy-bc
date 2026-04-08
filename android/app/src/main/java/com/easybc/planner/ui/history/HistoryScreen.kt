package com.easybc.planner.ui.history

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.EasyBCApp
import com.easybc.planner.data.db.PeriodRecord
import com.easybc.planner.ui.theme.PeriodColor
import com.easybc.planner.util.CycleCalculator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EasyBCApp
    private val repo = app.repository
    private val cycleCalc = app.cycleCalculator

    val periods: StateFlow<List<PeriodRecord>> = repo.periodsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    data class CycleStatsState(
        val averageLength: Double? = null,
        val sdDays: Double? = null,
        val count: Int = 0,
    )

    val stats: StateFlow<CycleStatsState> = periods.map { periodList ->
        val cycles = cycleCalc.deriveCycles(periodList)
        val s = cycleCalc.computeStats(cycles)
        CycleStatsState(s?.averageLength, s?.sdDays, s?.count ?: 0)
    }.stateIn(viewModelScope, SharingStarted.Lazily, CycleStatsState())

    fun deletePeriod(record: PeriodRecord) {
        viewModelScope.launch { repo.deletePeriod(record) }
    }
}

@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel()) {
    val periods by vm.periods.collectAsState()
    val stats by vm.stats.collectAsState()
    val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy")

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Stats summary
        item {
            Text(
                "Cycle History",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Cycle Statistics",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (stats.count < 2) {
                        Text(
                            "Log at least 2 periods to see cycle statistics.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StatItem("Cycles", stats.count.toString())
                            StatItem("Avg Length", stats.averageLength?.let { "%.1f d".format(it) } ?: "–")
                            StatItem("Variability", stats.sdDays?.let { "%.1f d SD".format(it) } ?: "–")
                        }
                    }
                }
            }
        }

        // Period list
        item {
            Text(
                "Period Records",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (periods.isEmpty()) {
            item {
                Text(
                    "No periods logged yet. Tap a day in the Calendar to log your period.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(periods.sortedByDescending { it.startDate }) { record ->
            val startDate = LocalDate.ofEpochDay(record.startDate)
            val endDate = record.endDate?.let { LocalDate.ofEpochDay(it) }
            val duration = if (endDate != null) {
                ChronoUnit.DAYS.between(startDate, endDate) + 1
            } else null

            // Cycle length to next period
            val nextPeriod = periods
                .filter { it.startDate > record.startDate }
                .minByOrNull { it.startDate }
            val cycleLengthToNext = nextPeriod?.let {
                ChronoUnit.DAYS.between(startDate, LocalDate.ofEpochDay(it.startDate))
            }

            var showDeleteConfirm by remember { mutableStateOf(false) }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = startDate.format(fmt) +
                                if (endDate != null) " – ${endDate.format(fmt)}" else " (ongoing)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (duration != null) {
                                Text(
                                    "${duration}d bleeding",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PeriodColor,
                                )
                            }
                            if (cycleLengthToNext != null) {
                                Text(
                                    "Cycle: ${cycleLengthToNext}d",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Default.Delete,
                            "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete period?") },
                    text = { Text("This will remove this period record. Your plan will update automatically.") },
                    confirmButton = {
                        TextButton(onClick = {
                            vm.deletePeriod(record)
                            showDeleteConfirm = false
                        }) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
