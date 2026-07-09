package com.easybc.planner.sync

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.easybc.planner.BuildConfig
import com.easybc.planner.data.PlannerRepository
import com.easybc.planner.sync.shared.SharedSyncCoordinator
import com.easybc.planner.sync.shared.isLocalProfile
import com.easybc.planner.sync.shared.profileKey
import com.easybc.planner.sync.shared.shouldLoadRemoteBeforePublish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session-scoped encrypted sync autosync for shared Drive folders (and legacy
 * personal appData while migration is incomplete).
 */
@OptIn(FlowPreview::class)
class CloudAutoSyncSession(
    private val activity: Activity,
    private val repo: PlannerRepository,
    private val store: SyncPayloadStore,
    private val resolveAuthorization: suspend (PendingIntent) -> Intent?,
    private val sharedSync: SharedSyncCoordinator,
    private val legacyCoordinator: CloudSyncCoordinator = CloudSyncCoordinator(store),
    private val googleAuthorization: GoogleAuthorization = GoogleAuthorization(),
    private val debounceMs: Long = 1_800L,
) {
    private var started = false
    private var hasForegrounded = false
    private var hiddenAt: Long? = null
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
                    if (!isEnabled()) return@collect
                    if (isReadOnlyActiveProfile()) return@collect
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
            hiddenAt = null
            return
        }
        val wentDarkAt = hiddenAt
        hiddenAt = null
        if (wentDarkAt == null) return
        if (System.currentTimeMillis() - wentDarkAt < FOREGROUND_SYNC_MIN_HIDDEN_MS) return
        if (syncMutex.isLocked) return
        sessionScope?.launch {
            if (!isEnabled()) return@launch
            runCatching { syncIfChanged(force = true) }
                .onFailure { error ->
                    if (BuildConfig.DEBUG) Log.w(TAG, "Encrypted foreground sync failed", error)
                }
        }
    }

    fun onBackground() {
        hiddenAt = System.currentTimeMillis()
    }

    private suspend fun isEnabled(): Boolean =
        activeSharedSyncEnabled() || store.fileId() != null

    private suspend fun activeSharedSyncEnabled(): Boolean {
        val state = sharedSync.loadState() ?: return false
        val profile = state.profiles.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
        } ?: return false
        return !isLocalProfile(profile) && !profile.fileId.isNullOrBlank()
    }

    private suspend fun isReadOnlyActiveProfile(): Boolean {
        val state = sharedSync.loadState() ?: return false
        if (!sharedSync.isConfigured()) return false
        val profile = state.profiles.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
        } ?: return false
        return shouldLoadRemoteBeforePublish(profile)
    }

    private suspend fun syncIfChanged(force: Boolean = false) = syncMutex.withLock {
        val local = store.localPayload()
        val fingerprint = fingerprint(local)
        if (!force && fingerprint == lastSyncedFingerprint) return@withLock
        lastSyncedFingerprint = fingerprint

        val token = accessToken()
        if (activeSharedSyncEnabled()) {
            sharedSync.sync(token)
        } else if (store.fileId() != null) {
            try {
                legacyCoordinator.execute(activity, CloudSyncOperation.SYNC, token)
            } catch (error: Exception) {
                if (CloudSyncCoordinator.isNotFound(error)) {
                    store.forgetSync()
                    EasyBcSyncRuntime.lock()
                } else {
                    throw error
                }
            }
        }
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
        EasyBcSyncCodec.fingerprint(payload)

    companion object {
        private const val TAG = "CloudAutoSync"
        internal const val FOREGROUND_SYNC_MIN_HIDDEN_MS = 30_000L
    }
}
