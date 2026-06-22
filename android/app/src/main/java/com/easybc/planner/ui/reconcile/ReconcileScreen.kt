package com.easybc.planner.ui.reconcile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.data.RecommendedAction
import com.easybc.planner.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Batch-reconciliation screen. Lets the user confirm what actually happened
 * on past planner days they haven't explicitly logged yet. Supports
 * single-row quick actions ("As planned", "Abstained", "Condom broke") and
 * multi-select bulk actions for common patterns like "we were traveling
 * this whole week, mark it all as abstained".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconcileScreen(
    onBack: () -> Unit,
    vm: ReconcileViewModel = viewModel(),
) {
    // null = planner still computing (show a spinner); emptyList = computed
    // and genuinely nothing to do (show "all caught up"); non-empty = rows.
    val rows by vm.unreconciled.collectAsState()
    val selected by vm.selectedDates.collectAsState()
    val dateFmt = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reconcile days") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    val loadedRows = rows
                    if (!loadedRows.isNullOrEmpty()) {
                        TextButton(onClick = {
                            if (selected.size == loadedRows.size) vm.clearSelection()
                            else vm.selectAll()
                        }) {
                            Text(if (selected.size == loadedRows.size) "Clear" else "All")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                SelectionActionBar(
                    count = selected.size,
                    onAsPlanned = vm::acceptSelectedAsPlanned,
                    onReconcile = vm::reconcileSelected,
                )
            }
        },
    ) { padding ->
        val loadedRows = rows
        when {
            loadedRows == null -> LoadingState(padding)
            loadedRows.isEmpty() -> EmptyState(padding)
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "Past days we haven't confirmed yet. Accept the plan or log what actually happened — bulk-select for weeks of travel, illness, etc.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(loadedRows, key = { it.date.toEpochDay() }) { row ->
                    ReconcileRow(
                        row = row,
                        selected = row.date.toEpochDay() in selected,
                        dateFmt = dateFmt,
                        onToggleSelect = { vm.toggleSelection(row.date) },
                        onAsPlanned = { vm.acceptAsPlanned(row.date) },
                        onReconcile = { action -> vm.reconcileOne(row.date, action) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingState(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "Checking your plan…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "All caught up",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "No past planner days waiting for reconciliation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReconcileRow(
    row: ReconcileViewModel.Row,
    selected: Boolean,
    dateFmt: DateTimeFormatter,
    onToggleSelect: () -> Unit,
    onAsPlanned: () -> Unit,
    onReconcile: (String) -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleSelect) {
                    Icon(
                        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (selected) "Deselect" else "Select",
                        tint = if (selected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        row.date.format(dateFmt),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Plan: ${row.plannerAction.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = actionForegroundColor(row.plannerAction),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalButton(
                    onClick = onAsPlanned,
                    enabled = row.canAcceptAsPlanned,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("As planned", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = { onReconcile("NONE") },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Abstained", style = MaterialTheme.typography.labelSmall)
                }
                if (row.plannerAction == RecommendedAction.C) {
                    OutlinedButton(
                        onClick = { onReconcile("CB") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cond. broke", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Bottom bar shown when one or more rows are selected. Groups the common
 * "apply to all of these at once" actions. We deliberately keep this narrow
 * — users who want a rare action pick it on the individual row.
 */
@Composable
private fun SelectionActionBar(
    count: Int,
    onAsPlanned: () -> Unit,
    onReconcile: (String) -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "$count selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onAsPlanned,
                    modifier = Modifier.weight(1f),
                ) { Text("As planned") }
                OutlinedButton(
                    onClick = { onReconcile("NONE") },
                    modifier = Modifier.weight(1f),
                ) { Text("Abstained") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Less-common overrides tucked into a second row so the primary
                // "As planned" / "Abstained" choices stay dominant.
                for (action in listOf(
                    RecommendedAction.U,
                    RecommendedAction.W,
                    RecommendedAction.C,
                    RecommendedAction.A,
                )) {
                    OutlinedButton(
                        onClick = { onReconcile(action.shortLabel) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Text(action.shortLabel, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
