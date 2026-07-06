package com.easybc.planner

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import com.easybc.planner.sync.GoogleAuthorization
import com.easybc.planner.sync.SyncPayloadStore
import com.easybc.planner.sync.shared.SharedSyncCoordinator
import com.easybc.planner.sync.shared.parseSharedJoinLink
import com.easybc.planner.ui.navigation.AppNavigation
import com.easybc.planner.ui.theme.EasyBCTheme
import kotlinx.coroutines.launch
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
        val store = SyncPayloadStore(app.database)
        CloudAutoSyncSession(
            activity = this,
            repo = app.repository,
            store = store,
            resolveAuthorization = ::resolveCloudAutoSyncAuthorization,
            sharedSync = SharedSyncCoordinator(app, app.database, store),
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
        handleSharedSyncJoinIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as EasyBCApp).cloudSyncActivityStarted()
        cloudAutoSyncSession.onForeground()
    }

    override fun onStop() {
        cloudAutoSyncSession.onBackground()
        (application as EasyBCApp).cloudSyncActivityStopped()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (shouldRouteToReconcile(intent)) {
            initialReconcileRequest.value = true
        }
        handleSharedSyncJoinIntent(intent)
    }

    private fun handleSharedSyncJoinIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val join = parseSharedJoinLink(data) ?: return
        cloudAutoSyncScope.launch {
            runCatching {
                val app = application as EasyBCApp
                val store = SyncPayloadStore(app.database)
                val sharedSync = SharedSyncCoordinator(app, app.database, store)
                val auth = GoogleAuthorization()
                val token = when (val step = auth.begin(this@MainActivity)) {
                    is com.easybc.planner.sync.AuthorizationStep.Authorized -> step.accessToken
                    is com.easybc.planner.sync.AuthorizationStep.NeedsResolution -> {
                        val result = resolveCloudAutoSyncAuthorization(step.pendingIntent)
                        auth.finish(this@MainActivity, result)
                    }
                }
                sharedSync.join(token, join.invitationFileId, join.ownerFolderId, join.ownerEmail)
            }.onSuccess {
                Toast.makeText(
                    this@MainActivity,
                    "Join request sent. The profile owner must accept it before this device can sync.",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure { error ->
                Log.e("EasyBcSync", "Join from link failed", error)
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "Join failed (${error.javaClass.simpleName}).",
                    Toast.LENGTH_LONG,
                ).show()
            }
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
