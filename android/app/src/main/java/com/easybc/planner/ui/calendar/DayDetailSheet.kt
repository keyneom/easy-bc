package com.easybc.planner.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.easybc.planner.ui.theme.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailSheet(
    cell: DayCellData,
    onDismiss: () -> Unit,
    onLogPeriod: () -> Unit,
    onLogAction: (RecommendedAction) -> Unit,
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
                            text = "If you override this day",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = cell.overrideCost.note,
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
                                        Text("condom days", style = MaterialTheme.typography.labelSmall)
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
                                        Text("abstinence days", style = MaterialTheme.typography.labelSmall)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionLogButton(RecommendedAction.U, cell.dayLog?.actualAction == "U", Modifier.weight(1f)) {
                    onLogAction(RecommendedAction.U)
                }
                ActionLogButton(RecommendedAction.C, cell.dayLog?.actualAction == "C", Modifier.weight(1f)) {
                    onLogAction(RecommendedAction.C)
                }
                ActionLogButton(RecommendedAction.A, cell.dayLog?.actualAction == "A", Modifier.weight(1f)) {
                    onLogAction(RecommendedAction.A)
                }
            }

            // Period logging
            if (!cell.isPeriod) {
                OutlinedButton(
                    onClick = onLogPeriod,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.WaterDrop, null, Modifier.size(18.dp), tint = PeriodColor)
                    Spacer(Modifier.width(8.dp))
                    Text("Mark period start")
                }
            }

            if (cell.dayLog != null) {
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
