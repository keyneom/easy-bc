package com.easybc.planner.sync

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.easybc.planner.BuildConfig
import com.easybc.planner.data.PlannerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * Session-scoped encrypted cloud autosync.
 *
 * This deliberately lives at the Activity layer, not Application scope, because
 * both Google authorization and Credential Manager passkey PRF prompts need a
 * foreground Activity. [CloudSyncCoordinator] retains only the derived content
 * key in process memory after the first passkey unlock, so subsequent changes
 * in the same foreground session sync without another passkey prompt.
 */
@OptIn(FlowPreview::class)
class CloudAutoSyncSession(
    private val activity: Activity,
    private val repo: PlannerRepository,
    private val store: SyncPayloadStore,
    private val resolveAuthorization: suspend (PendingIntent) -> Intent?,
    private val coordinator: CloudSyncCoordinator = CloudSyncCoordinator(store),
    private val googleAuthorization: GoogleAuthorization = GoogleAuthorization(),
    private val debounceMs: Long = 1_800L,
) {
    private var started = false
    private var hasForegrounded = false
    private var lastSyncedFingerprint: String? = null
    private var sessionScope: CoroutineScope? = null
    private val syncMutex = Mutex()

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        sessionScope = scope

        scope.launch {
            combine(
                repo.settingsFlow,
                repo.periodsFlow,
                repo.dayLogsFlow,
                repo.dayEventsFlow,
            ) { _, _, _, _ -> Unit }
                .debounce(debounceMs)
                .collect {
                    if (store.fileId() == null) return@collect
                    runCatching { syncIfChanged() }
                        .onFailure { error ->
                            if (BuildConfig.DEBUG) Log.w(TAG, "Encrypted autosync failed", error)
                        }
                }
        }
    }

    fun onForeground() {
        if (!hasForegrounded) {
            hasForegrounded = true
            return
        }
        sessionScope?.launch {
            if (store.fileId() == null) return@launch
            runCatching { syncIfChanged(force = true) }
                .onFailure { error ->
                    if (BuildConfig.DEBUG) Log.w(TAG, "Encrypted foreground sync failed", error)
                }
        }
    }

    private suspend fun syncIfChanged(force: Boolean = false) = syncMutex.withLock {
        val local = store.localPayload()
        val fingerprint = fingerprint(local)
        if (!force && fingerprint == lastSyncedFingerprint) return@withLock

        // Do not repeatedly prompt for the same unchanged snapshot if the user
        // cancels Google/passkey auth. The next local data change will retry.
        lastSyncedFingerprint = fingerprint

        val token = accessToken()
        coordinator.execute(activity, CloudSyncOperation.SYNC, token)
        lastSyncedFingerprint = fingerprint(store.localPayload())
    }

    private suspend fun accessToken(): String =
        when (val step = googleAuthorization.begin(activity)) {
            is AuthorizationStep.Authorized -> step.accessToken
            is AuthorizationStep.NeedsResolution -> {
                val data = resolveAuthorization(step.pendingIntent)
                googleAuthorization.finish(activity, data)
            }
        }

    private fun fingerprint(payload: SyncPayloadV1): String =
        SyncCrypto.json.encodeToString(payload.copy(exportedAt = SYNC_EPOCH))

    companion object {
        private const val TAG = "CloudAutoSync"
    }
}
