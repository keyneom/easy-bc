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
import kotlinx.serialization.encodeToString

/**
 * Session-scoped encrypted cloud autosync.
 *
 * This deliberately lives at the Activity layer, not Application scope, because
 * both Google authorization and Credential Manager passkey PRF prompts need a
 * foreground Activity. No cloud encryption key is cached; every sync evaluates
 * the passkey PRF, merges, writes, and then clears the secret in
 * [CloudSyncCoordinator].
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
    private var lastSyncedFingerprint: String? = null

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true

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

    private suspend fun syncIfChanged() {
        val local = store.localPayload()
        val fingerprint = fingerprint(local)
        if (fingerprint == lastSyncedFingerprint) return

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
