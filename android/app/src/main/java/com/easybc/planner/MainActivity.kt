package com.easybc.planner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Default sample matches web/WASM minimal test JSON shape (camelCase). */
private const val SAMPLE_JSON = """
{"ageYears":34,"horizonYears":2,"targetCumulativeFailure":0.05,"cycleLengthDays":28,
"actsPerWeek":3.5,"condomMode":"perfect","streakAversion":0.5,"holdLifecycleConstant":false,
"realizedCumulativeRisk":0,"withdrawalRelativeRisk":0.35,"ovulationSdDays":3}
"""

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var input by remember { mutableStateOf(SAMPLE_JSON.trimIndent().trim()) }
                    var output by remember { mutableStateOf<String?>(null) }
                    var error by remember { mutableStateOf<String?>(null) }
                    Column(
                        modifier =
                            Modifier
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "easy-bc planner — same JSON API as web/WASM. " +
                                "Place libplanner_core.so per jniLibs/README and add uniFFI Kotlin bindings.",
                        )
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("UserOptions JSON (camelCase)") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                            minLines = 6,
                        )
                        Button(
                            onClick = {
                                error = null
                                output =
                                    try {
                                        PlannerCoreBridge.planFertilityRiskPlannerJson(input.trim())
                                    } catch (e: Throwable) {
                                        error = e.message ?: e.toString()
                                        null
                                    }
                            },
                        ) {
                            Text("Plan (native)")
                        }
                        if (error != null) {
                            Text("Error: $error", color = MaterialTheme.colorScheme.error)
                        }
                        output?.let { Text(it) }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
