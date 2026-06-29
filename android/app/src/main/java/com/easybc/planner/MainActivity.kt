package com.easybc.planner

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.easybc.planner.sync.CloudAutoSyncSession
import com.easybc.planner.sync.SyncPayloadStore
import com.easybc.planner.ui.navigation.AppNavigation
import com.easybc.planner.ui.theme.EasyBCTheme
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
    private var pendingCloudAutoSyncAuthorization: CancellableContinuation<Intent?>? = null
    private val cloudAutoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cloudAutoSyncAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pending = pendingCloudAutoSyncAuthorization ?: return@registerForActivityResult
        pendingCloudAutoSyncAuthorization = null
        pending.resume(if (result.resultCode == Activity.RESULT_OK) result.data else null)
    }

    private val cloudAutoSyncSession: CloudAutoSyncSession by lazy {
        val app = application as EasyBCApp
        CloudAutoSyncSession(
            activity = this,
            repo = app.repository,
            store = SyncPayloadStore(app.database),
            resolveAuthorization = ::resolveCloudAutoSyncAuthorization,
        )
    }

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
        cloudAutoSyncSession.start(cloudAutoSyncScope)
    }

    override fun onStart() {
        super.onStart()
        (application as EasyBCApp).cloudSyncActivityStarted()
        cloudAutoSyncSession.onForeground()
    }

    override fun onStop() {
        (application as EasyBCApp).cloudSyncActivityStopped()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (shouldRouteToReconcile(intent)) {
            initialReconcileRequest.value = true
        }
    }

    override fun onDestroy() {
        pendingCloudAutoSyncAuthorization?.cancel()
        pendingCloudAutoSyncAuthorization = null
        cloudAutoSyncScope.cancel()
        super.onDestroy()
    }

    private fun shouldRouteToReconcile(intent: Intent?): Boolean =
        intent?.getBooleanExtra(ReminderScheduler.EXTRA_OPEN_RECONCILE, false) == true

    private suspend fun resolveCloudAutoSyncAuthorization(pendingIntent: PendingIntent): Intent? =
        suspendCancellableCoroutine { continuation ->
            if (pendingCloudAutoSyncAuthorization != null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            pendingCloudAutoSyncAuthorization = continuation
            continuation.invokeOnCancellation {
                if (pendingCloudAutoSyncAuthorization === continuation) {
                    pendingCloudAutoSyncAuthorization = null
                }
            }
            runCatching {
                cloudAutoSyncAuthorizationLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                )
            }.onFailure {
                if (pendingCloudAutoSyncAuthorization === continuation) {
                    pendingCloudAutoSyncAuthorization = null
                }
                continuation.resume(null)
            }
        }
}
