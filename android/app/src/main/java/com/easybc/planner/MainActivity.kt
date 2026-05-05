package com.easybc.planner

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.easybc.planner.notify.ReminderScheduler
import com.easybc.planner.ui.navigation.AppNavigation
import com.easybc.planner.ui.theme.EasyBCTheme

class MainActivity : ComponentActivity() {
    /**
     * Single-source-of-truth flag the Compose tree watches to know whether
     * to deep-link to the Reconcile screen at startup (or after `onNewIntent`
     * when the activity is already running and gets re-delivered).
     *
     * A plain `mutableStateOf` is fine here — we want the old value to be
     * reset immediately once navigation consumes it, so snapshot state keeps
     * things in sync across recompositions.
     */
    private val initialReconcileRequest = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initialReconcileRequest.value = shouldRouteToReconcile(intent)

        setContent {
            EasyBCTheme {
                var pendingReconcile by remember { initialReconcileRequest }
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(
                        pendingReconcileDeepLink = pendingReconcile,
                        onReconcileDeepLinkConsumed = { pendingReconcile = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (shouldRouteToReconcile(intent)) {
            initialReconcileRequest.value = true
        }
    }

    private fun shouldRouteToReconcile(intent: Intent?): Boolean =
        intent?.getBooleanExtra(ReminderScheduler.EXTRA_OPEN_RECONCILE, false) == true
}
