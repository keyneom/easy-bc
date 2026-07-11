package com.easybc.planner.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Per-act event entry — the same options as the day sheet (condom broke /
 * unplanned unprotected / emergency contraception), reusable from the
 * Reconcile screen and anywhere else a day's events need recording.
 */
@Composable
fun DayEventDialog(
    title: String,
    onLog: (kind: String, ecType: String?, hoursFromAct: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf("condom_broke") }
    var ecType by remember { mutableStateOf("levonorgestrel") }
    var hoursText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == "condom_broke",
                        onClick = { kind = "condom_broke" },
                        label = { Text("Condom broke", style = MaterialTheme.typography.labelSmall) },
                    )
                    FilterChip(
                        selected = kind == "unplanned_unprotected",
                        onClick = { kind = "unplanned_unprotected" },
                        label = { Text("Unprotected", style = MaterialTheme.typography.labelSmall) },
                    )
                    FilterChip(
                        selected = kind == "plan_b_taken",
                        onClick = { kind = "plan_b_taken" },
                        label = { Text("EC taken", style = MaterialTheme.typography.labelSmall) },
                    )
                }
                if (kind == "plan_b_taken") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = ecType == "levonorgestrel",
                            onClick = { ecType = "levonorgestrel" },
                            label = { Text("Plan B", style = MaterialTheme.typography.labelSmall) },
                        )
                        FilterChip(
                            selected = ecType == "ulipristal",
                            onClick = { ecType = "ulipristal" },
                            label = { Text("Ella", style = MaterialTheme.typography.labelSmall) },
                        )
                        FilterChip(
                            selected = ecType == "copper_iud",
                            onClick = { ecType = "copper_iud" },
                            label = { Text("Copper IUD", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { hoursText = it },
                        label = { Text("Hours since the act") },
                        supportingText = {
                            Text("Needed for the dose to affect the risk estimate.")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onLog(
                    kind,
                    ecType.takeIf { kind == "plan_b_taken" },
                    hoursText.toDoubleOrNull().takeIf { kind == "plan_b_taken" },
                )
                onDismiss()
            }) { Text("Log event") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
