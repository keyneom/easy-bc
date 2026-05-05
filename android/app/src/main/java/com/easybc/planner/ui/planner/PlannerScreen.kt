package com.easybc.planner.ui.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.data.ActionCounts
import com.easybc.planner.data.PlannerResult
import com.easybc.planner.data.YearOutput
import com.easybc.planner.ui.theme.*

@Composable
fun PlannerScreen(vm: PlannerViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    if (state.settings?.onboardingComplete != true) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "No plan yet",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Set up your profile in Settings to generate a plan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val result = state.result
    if (result == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Cumulative risk summary
        item { RiskSummaryCard(result) }

        // Warnings
        if (result.warnings.isNotEmpty()) {
            item { WarningsSection(result) }
        }

        // Bridge indicator
        if (!state.isNativeBridge) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Text(
                        "Using sample data — build native core for real plans",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Year-by-year timeline
        item {
            Text(
                "Year-by-Year Plan",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        items(result.years) { year ->
            YearCard(year)
        }
    }
}

@Composable
private fun RiskSummaryCard(result: PlannerResult) {
    val projectedCumulativeRisk = totalProjectedRisk(result)
    val target = result.optionsUsed.targetCumulativeFailure
    val targetMet = projectedCumulativeRisk <= target + 1e-9
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (targetMet) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (targetMet) Icons.Default.CheckCircle else Icons.Default.Warning,
                    null,
                    tint = if (targetMet) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = if (targetMet) "Target met" else "Target not met",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RiskStat(
                    label = "Achieved",
                    value = formatPercent(projectedCumulativeRisk),
                )
                RiskStat(
                    label = "Target",
                    value = formatPercent(target),
                )
                RiskStat(
                    label = "Horizon",
                    value = "${result.years.size} yr",
                )
            }

            Spacer(Modifier.height(12.dp))

            // Progress bar
            val progress = if (target > 0.0) {
                (projectedCumulativeRisk / target).coerceIn(0.0, 1.5).toFloat()
            } else if (projectedCumulativeRisk > 0.0) {
                1.5f
            } else {
                0f
            }
            Column {
                LinearProgressIndicator(
                    progress = { progress.coerceAtMost(1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (targetMet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Budget used: ${(progress * 100).toInt().coerceAtMost(999)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun totalProjectedRisk(result: PlannerResult): Double {
    val realized = result.optionsUsed.realizedCumulativeRisk.coerceIn(0.0, 1.0)
    val planned = result.achievedCumulativeRisk.coerceIn(0.0, 1.0)
    return (1.0 - (1.0 - realized) * (1.0 - planned)).coerceIn(0.0, 1.0)
}

@Composable
private fun RiskStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WarningsSection(result: PlannerResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        result.warnings.forEach { warning ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = warning.message,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun YearCard(year: YearOutput) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Age ${year.age}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${year.cycleLengthDays}-day cycle",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Annual: ${formatPercent(year.annualRisk)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = "Per-cycle: ${formatPercent(year.cycleRisk)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Stacked action bar
            ActionBar(year.counts, year.cycleLengthDays)

            Spacer(Modifier.height(8.dp))

            // Count labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CountChip(year.counts.unprotected, "U", ActionUnprotected)
                if (year.counts.withdrawal > 0) {
                    CountChip(year.counts.withdrawal, "W", ActionWithdrawal)
                }
                CountChip(year.counts.condom, "C", ActionCondom)
                CountChip(year.counts.abstain, "A", ActionAbstain)
            }
        }
    }
}

@Composable
private fun ActionBar(counts: ActionCounts, cycleLengthDays: Int) {
    val total = cycleLengthDays.toFloat().coerceAtLeast(1f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        var x = 0f
        val segments = listOf(
            counts.unprotected to ActionUnprotected,
            counts.withdrawal to ActionWithdrawal,
            counts.condom to ActionCondom,
            counts.abstain to ActionAbstain,
        )
        for ((count, color) in segments) {
            if (count <= 0) continue
            val w = size.width * (count / total)
            drawRect(color, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

@Composable
private fun CountChip(count: Int, label: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatPercent(value: Double): String {
    val pct = value * 100
    return if (pct < 0.01) "<0.01%" else if (pct < 1) "%.2f%%".format(pct) else "%.1f%%".format(pct)
}
