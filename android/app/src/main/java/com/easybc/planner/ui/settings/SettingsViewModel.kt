package com.easybc.planner.ui.settings

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easybc.planner.EasyBCApp
import com.easybc.planner.data.db.UserSettingsEntity
import com.easybc.planner.io.DataBackup
import com.easybc.planner.notify.ReminderScheduler
import com.easybc.planner.sync.AuthorizationStep
import com.easybc.planner.sync.CloudSyncCoordinator
import com.easybc.planner.sync.CloudSyncOperation
import com.easybc.planner.sync.EasyBcSyncRuntime
import com.easybc.planner.sync.GoogleAuthorization
import com.easybc.planner.sync.SyncPayloadStore
import com.easybc.planner.sync.shared.ProfileRecord
import com.easybc.planner.sync.shared.PendingSharedJoin
import com.easybc.planner.sync.shared.datasetPartLabel
import com.easybc.planner.sync.shared.SharedSyncCoordinator
import com.easybc.planner.sync.shared.SharedSyncState
import com.easybc.planner.sync.shared.profileKey
import com.easybc.planner.sync.shared.shouldLoadRemoteBeforePublish
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val SYNC_LOG_TAG = "EasyBcSync"

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EasyBCApp
    private val repo = app.repository
    private val cycleCalc = app.cycleCalculator
    private val calendarSync = app.calendarSync
    private val syncStore = SyncPayloadStore(app.database)
    private val legacyCloudSync = CloudSyncCoordinator(syncStore)
    private val sharedSync = SharedSyncCoordinator(app, app.database, syncStore)
    private val googleAuthorization = GoogleAuthorization()

    val settings: StateFlow<UserSettingsEntity?> = repo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _draft = MutableStateFlow(UserSettingsEntity())
    val draft: StateFlow<UserSettingsEntity> = _draft

    sealed class SyncStatus {
        object Idle : SyncStatus()
        object Running : SyncStatus()
        data class Success(val message: String) : SyncStatus()
        data class Error(val message: String) : SyncStatus()
    }

    private val _calendarStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val calendarStatus: StateFlow<SyncStatus> = _calendarStatus

    private val _backupStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val backupStatus: StateFlow<SyncStatus> = _backupStatus

    private val _cloudStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val cloudStatus: StateFlow<SyncStatus> = _cloudStatus

    private val _cloudConnected = MutableStateFlow(false)
    val cloudConnected: StateFlow<Boolean> = _cloudConnected

    private val _sharedSyncConfigured = MutableStateFlow(false)
    val sharedSyncConfigured: StateFlow<Boolean> = _sharedSyncConfigured

    private val _legacySyncPresent = MutableStateFlow(false)
    val legacySyncPresent: StateFlow<Boolean> = _legacySyncPresent

    private val _lastCloudSync = MutableStateFlow<String?>(null)
    val lastCloudSync: StateFlow<String?> = _lastCloudSync

    private val _sharedSyncState = MutableStateFlow<SharedSyncState?>(null)
    val sharedSyncState: StateFlow<SharedSyncState?> = _sharedSyncState

    private val _joinUrl = MutableStateFlow<String?>(null)
    val joinUrl: StateFlow<String?> = _joinUrl
    private val _responseLink = MutableStateFlow<String?>(null)
    val responseLink: StateFlow<String?> = _responseLink

    private val _pendingResponses = MutableStateFlow<List<SharedSyncCoordinator.PendingResponse>>(emptyList())
    val pendingResponses: StateFlow<List<SharedSyncCoordinator.PendingResponse>> = _pendingResponses

    private val _profileParticipants =
        MutableStateFlow<List<SharedSyncCoordinator.ProfileParticipant>>(emptyList())
    val profileParticipants: StateFlow<List<SharedSyncCoordinator.ProfileParticipant>> =
        _profileParticipants

    init {
        viewModelScope.launch {
            val existing = repo.getSettings()
            _draft.value = existing ?: UserSettingsEntity()
            _responseLink.value = PendingSharedJoin.producedResponse(app)
            sharedSync.ensureProfileState()
            refreshSharedSyncState()
        }
    }

    /** Reload registry-backed state; the global profile chip calls this on navigation changes. */
    fun refreshSharedState() {
        viewModelScope.launch { refreshSharedSyncState() }
    }

    private suspend fun refreshSharedSyncState() {
        sharedSync.clearIncompleteSetup()
        val state = sharedSync.loadState()
        val configured = sharedSync.isConfigured()
        _sharedSyncState.value = state
        _sharedSyncConfigured.value = configured
        _legacySyncPresent.value = syncStore.fileId() != null
        _cloudConnected.value = configured || syncStore.fileId() != null
        val active = state?.profiles?.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
        }
        _lastCloudSync.value = if (configured) {
            active?.lastSyncedAt ?: syncStore.lastSyncedAt()
        } else {
            syncStore.lastSyncedAt()
        }
        if (active == null || com.easybc.planner.sync.shared.isLocalProfile(active)) {
            _profileParticipants.value = emptyList()
        } else {
            // Keep the access list live whenever registry/profile state is
            // refreshed. Failure is intentionally quiet here; the explicit
            // retry action can renew Google authorization and report errors.
            runCatching {
                sharedSync.listActiveParticipantsFromRememberedAuthorization()
            }.onSuccess { _profileParticipants.value = it }
        }
    }

    fun updateDraft(transform: (UserSettingsEntity) -> UserSettingsEntity) {
        _draft.value = transform(_draft.value)
    }

    fun save() {
        viewModelScope.launch {
            repo.saveSettings(_draft.value.copy(onboardingComplete = true))
        }
    }

    fun resetToDefaults() {
        _draft.value = UserSettingsEntity()
    }

    // ── Device calendar export/update ────────────────────────────────────

    fun calendarPermissionGranted(): Boolean = calendarSync.hasPermission()

    /**
     * Resync the device calendar. Caller must have already obtained
     * calendar permissions (see [calendarPermissionGranted]).
     */
    fun syncCalendar() {
        if (!calendarSync.hasPermission()) {
            _calendarStatus.value = SyncStatus.Error("Calendar permission not granted")
            return
        }
        _calendarStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                val currentSettings = repo.getSettings() ?: run {
                    _calendarStatus.value = SyncStatus.Error("Set up your profile before updating the device calendar.")
                    return@launch
                }
                val periods = repo.periodsFlow.first()
                val plan = repo.calendarPlannerResultFlow.first()
                val result = calendarSync.syncEvents(
                    periods = periods,
                    plan = plan,
                    settings = currentSettings,
                    cycleCalc = cycleCalc,
                )
                _calendarStatus.value = SyncStatus.Success(
                    "Updated ${result.eventCount} device-calendar events " +
                        "(${result.periodDays} period, " +
                        "${result.fertileDays} fertile, " +
                        "${result.actionDays} plan days)."
                )
            } catch (e: SecurityException) {
                _calendarStatus.value = SyncStatus.Error("Calendar permission denied.")
            } catch (e: Exception) {
                _calendarStatus.value = SyncStatus.Error(e.message ?: "Device calendar update failed.")
            }
        }
    }

    fun removeCalendar() {
        _calendarStatus.value = SyncStatus.Running
        viewModelScope.launch {
            // Also flip the persisted sync-enabled flag off so auto-sync
            // stops — otherwise re-enabling-then-disabling would leave the
            // flag on and the calendar would reappear on next data change.
            repo.getSettings()?.let { repo.saveSettings(it.copy(calendarSyncEnabled = false)) }
            try {
                val removed = calendarSync.removeCalendar()
                _calendarStatus.value = if (removed) {
                    SyncStatus.Success("EasyBC calendar removed from device.")
                } else {
                    SyncStatus.Success("Calendar was already absent.")
                }
            } catch (e: SecurityException) {
                _calendarStatus.value = SyncStatus.Error("Calendar permission denied.")
            } catch (e: Exception) {
                _calendarStatus.value = SyncStatus.Error(e.message ?: "Remove failed.")
            }
        }
    }

    /**
     * Flip the persisted auto-sync flag. When turning on, caller should
     * already have ensured calendar permission is granted (see the UI's
     * permission launcher). Turning on also triggers an immediate first
     * sync via [syncCalendar].
     */
    fun setCalendarSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = repo.getSettings() ?: return@launch
            if (current.calendarSyncEnabled == enabled) return@launch
            repo.saveSettings(current.copy(calendarSyncEnabled = enabled))
            // Reflect in draft so the toggle stays consistent if the user
            // also edits other fields before saving.
            _draft.value = _draft.value.copy(calendarSyncEnabled = enabled)
            if (enabled) syncCalendar()
        }
    }

    // ── Backup file export/import ────────────────────────────────────────

    fun exportBackup(uri: Uri) {
        _backupStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                val bytes = DataBackup.exportTo(app, uri, app.database)
                _backupStatus.value = SyncStatus.Success("Backup file exported ($bytes bytes).")
            } catch (e: Exception) {
                _backupStatus.value = SyncStatus.Error(e.message ?: "Backup file export failed.")
            }
        }
    }

    fun importBackup(uri: Uri) {
        _backupStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                val summary = DataBackup.importFrom(app, uri, app.database)
                // Refresh draft so the UI reflects imported settings.
                repo.getSettings()?.let {
                    _draft.value = it
                    applyReminderSchedule(it)
                }
                _backupStatus.value = SyncStatus.Success(
                    "Imported ${summary.periodsImported} periods, " +
                        "${summary.dayLogsImported} day logs" +
                        (if (summary.settingsImported) ", settings." else ".")
                )
            } catch (e: IllegalArgumentException) {
                _backupStatus.value = SyncStatus.Error(e.message ?: "Invalid backup file.")
            } catch (e: Exception) {
                _backupStatus.value = SyncStatus.Error(e.message ?: "Backup file import failed.")
            }
        }
    }

    fun dismissCalendarStatus() {
        _calendarStatus.value = SyncStatus.Idle
    }

    fun dismissBackupStatus() {
        _backupStatus.value = SyncStatus.Idle
    }

    // ── Encrypted cloud sync ─────────────────────────────────────────────

    suspend fun beginCloudAuthorization(activity: Activity): AuthorizationStep =
        googleAuthorization.begin(activity)

    fun finishCloudAuthorization(activity: Activity, data: Intent?): String =
        googleAuthorization.finish(activity, data)

    fun runCloudOperation(activity: Activity, operation: CloudSyncOperation, accessToken: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                val message = when (operation) {
                    CloudSyncOperation.SETUP -> {
                        val state = sharedSync.loadState()
                        val active = state?.let {
                            it.profiles.firstOrNull { profile ->
                                profileKey(profile.ownerEmail, profile.datasetId) == it.activeProfileKey
                            }
                        }
                        if (
                            active != null &&
                            com.easybc.planner.sync.shared.isLocalProfile(active) &&
                            sharedSync.isConfigured()
                        ) {
                            sharedSync.connectActiveLocalProfile(accessToken)
                        } else {
                            sharedSync.setup(accessToken)
                        }
                        "Encrypted sync is set up. Your data lives in a Drive folder labeled with your email."
                    }
                    CloudSyncOperation.ENABLE -> {
                        if (sharedSync.loadState() == null && syncStore.fileId() != null) {
                            legacyCloudSync.enableOrForgetIfMissing(activity, accessToken)
                        }
                        sharedSync.setup(accessToken)
                        "Encrypted sync is enabled and the latest records were merged."
                    }
                    CloudSyncOperation.SYNC -> {
                        if (sharedSync.isConfigured()) {
                            sharedSync.sync(accessToken)
                            "Encrypted sync data is up to date."
                        } else {
                            try {
                                legacyCloudSync.execute(activity, operation, accessToken)
                                "Encrypted sync data is up to date."
                            } catch (error: Exception) {
                                if (CloudSyncCoordinator.isNotFound(error)) {
                                    syncStore.forgetSync()
                                    EasyBcSyncRuntime.lock()
                                    throw IllegalArgumentException(
                                        "The legacy encrypted cloud snapshot was not found on Google Drive. " +
                                            "Local sync metadata was cleared. Use Set up encrypted sync to start fresh.",
                                        error,
                                    )
                                } else {
                                    throw error
                                }
                            }
                        }
                    }
                    CloudSyncOperation.RESET -> {
                        sharedSync.reset(accessToken)
                        "Encrypted sync was reset with this device's local data."
                    }
                    CloudSyncOperation.DELETE -> {
                        if (sharedSync.loadState() != null) {
                            sharedSync.forget()
                            "Encrypted sync was removed from this device."
                        } else {
                            legacyCloudSync.execute(activity, operation, accessToken)
                        }
                    }
                }
                repo.getSettings()?.let {
                    _draft.value = it
                    applyReminderSchedule(it)
                }
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success(message)
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Cloud operation $operation", error, "Encrypted sync failed")
            }
        }
    }

    /** Log the full stack trace and surface a message that always identifies the error. */
    private fun cloudFailure(what: String, error: Exception, fallback: String): SyncStatus.Error {
        Log.e(SYNC_LOG_TAG, "$what failed", error)
        return SyncStatus.Error(
            error.message ?: "$fallback (${error.javaClass.simpleName}).",
        )
    }

    fun inviteParticipant(
        accessToken: String,
        email: String,
        role: String,
        /** Split profiles: dataset part -> role (the invite presets). */
        grants: Map<String, String>? = null,
    ) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                val url = sharedSync.inviteForLink(accessToken, email.trim(), role, grants)
                _joinUrl.value = url
                _cloudStatus.value = SyncStatus.Success(
                    "Shared the folder with $email. Send them the join link; they'll send back a " +
                        "response link for you to accept here.",
                )
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Invite", error, "Invite failed")
            }
        }
    }

    /** Joiner side: run a join link, producing a response link to send to the owner. */
    fun joinFromLink(accessToken: String, joinLinkUrl: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                val response = withTimeout(90_000) {
                    sharedSync.joinFromLink(accessToken, joinLinkUrl)
                }
                PendingSharedJoin.setProducedResponse(app, response)
                PendingSharedJoin.clearJoinLink(app)
                _responseLink.value = response
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success(
                    "Access granted. Send the response link back to the owner to finish joining.",
                )
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Join", error, "Join failed")
            }
        }
    }

    /** Owner side: accept a recipient's response link. */
    fun acceptResponseLink(accessToken: String, responseLinkUrl: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                withTimeout(90_000) {
                    sharedSync.acceptResponseFromLink(accessToken, responseLinkUrl)
                }
                PendingSharedJoin.clearResponseToAccept(app)
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success("Recipient added. They can now sync this profile.")
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Accept response", error, "Accept failed")
            }
        }
    }

    fun joinSharedSync(
        accessToken: String,
        invitationFileId: String,
        ownerFolderId: String,
        ownerEmail: String,
    ) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                sharedSync.join(accessToken, invitationFileId, ownerFolderId, ownerEmail)
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success(
                    "Join request submitted. The owner must accept before you can sync.",
                )
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Join", error, "Join failed")
            }
        }
    }

    fun switchProfile(accessToken: String?, profileKeyValue: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                sharedSync.switchActiveProfile(accessToken, profileKeyValue)
                repo.getSettings()?.let {
                    _draft.value = it
                    applyReminderSchedule(it)
                }
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success("Switched encrypted sync profile.")
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Profile switch", error, "Profile switch failed")
            }
        }
    }

    fun createLocalProfile(accessToken: String?, displayName: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                sharedSync.createLocalProfile(accessToken, displayName)
                repo.getSettings()?.let { _draft.value = it }
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success("Local profile created.")
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Create local profile", error, "Profile creation failed")
            }
        }
    }

    fun renameProfile(profileKeyValue: String, displayName: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                sharedSync.renameProfile(profileKeyValue, displayName)
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success("Profile renamed.")
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Rename profile", error, "Rename failed")
            }
        }
    }

    fun updateActiveProfileAvatar(uri: Uri?) {
        val state = _sharedSyncState.value ?: return
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                val encoded = uri?.let { selected ->
                    withContext(Dispatchers.IO) {
                        val descriptorLength = app.contentResolver
                            .openAssetFileDescriptor(selected, "r")
                            ?.use { it.length } ?: -1L
                        require(descriptorLength <= 20L * 1024L * 1024L || descriptorLength < 0L) {
                            "Choose a photo smaller than 20 MB."
                        }
                        val bytes = app.contentResolver.openInputStream(selected)?.use { input ->
                            input.readBytesLimited(20 * 1024 * 1024)
                        } ?: error("Could not read that photo.")
                        require(bytes.size <= 20 * 1024 * 1024) {
                            "Choose a photo smaller than 20 MB."
                        }
                        com.easybc.planner.ui.kit.encodeAvatarFromBytes(bytes)
                    }
                }
                sharedSync.updateProfileAvatar(state.activeProfileKey, encoded)
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success(
                    if (encoded == null) "Profile photo removed. Sync to share the change."
                    else "Profile photo updated. Sync to share it.",
                )
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Update profile photo", error, "Photo update failed")
            }
        }
    }

    fun enrollControlDataset(accessToken: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                _sharedSyncState.value = sharedSync.enrollActiveControlDataset(accessToken)
                _cloudStatus.value = SyncStatus.Success(
                    if (activeProfile()?.controlEnrollment == "enrolled") {
                        "Sharing coordination is ready."
                    } else {
                        "Coordination file created. Re-invite existing participants so they can enroll."
                    },
                )
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure(
                    "Set up sharing coordination",
                    error,
                    "Coordination setup failed",
                )
            }
        }
    }

    private val _migrationStatus =
        MutableStateFlow<SharedSyncCoordinator.MigrationAckStatus?>(null)
    val migrationStatus: StateFlow<SharedSyncCoordinator.MigrationAckStatus?> = _migrationStatus

    fun beginSplitMigration(accessToken: String, grants: Map<String, Map<String, String>>) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                _sharedSyncState.value = sharedSync.beginSplitMigration(accessToken, grants)
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success(
                    "The new files are live and shared. You'll see confirmations " +
                        "here as each person reselects them.",
                )
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Per-dataset upgrade", error, "Upgrade failed")
            }
        }
    }

    fun refreshMigrationStatus(accessToken: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                _migrationStatus.value = sharedSync.splitMigrationStatus(accessToken)
                _cloudStatus.value = SyncStatus.Idle
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Upgrade status", error, "Status check failed")
            }
        }
    }

    fun closeSplitMigration(accessToken: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                _sharedSyncState.value = sharedSync.closeSplitMigration(accessToken)
                _migrationStatus.value = null
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success(
                    "Upgrade complete — the old file is in Drive's trash.",
                )
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Finish upgrade", error, "Finish failed")
            }
        }
    }

    fun acknowledgeSplitMigration(accessToken: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                _sharedSyncState.value = sharedSync.acknowledgeSplitMigration(accessToken)
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success(
                    "You're on the reorganized profile now — everything synced.",
                )
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure(
                    "Finish reorganization",
                    error,
                    "Reorganization failed",
                )
            }
        }
    }

    fun upgradeProfileToSplit(accessToken: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                _sharedSyncState.value = sharedSync.upgradeActiveProfileToSplit(accessToken)
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success(
                    "Each data section now lives in its own encrypted file — " +
                        "invites and person cards control access per section.",
                )
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure(
                    "Per-dataset upgrade",
                    error,
                    "Upgrade failed",
                )
            }
        }
    }

    fun disconnectActiveProfileToLocal() {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                sharedSync.disconnectActiveProfileToLocal()
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success("This profile is now local only.")
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Disconnect profile", error, "Disconnect failed")
            }
        }
    }

    fun deleteProfile(
        accessToken: String?,
        profileKeyValue: String,
        deleteEverywhere: Boolean,
    ) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                sharedSync.deleteProfile(accessToken, profileKeyValue, deleteEverywhere)
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success(
                    if (deleteEverywhere) "Profile deleted everywhere." else "Profile removed.",
                )
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Delete profile", error, "Delete failed")
            }
        }
    }

    fun refreshProfileParticipants(accessToken: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                _profileParticipants.value = sharedSync.listActiveParticipants(accessToken)
                _cloudStatus.value = SyncStatus.Success("Profile access list refreshed.")
            } catch (error: Exception) {
                _cloudStatus.value =
                    cloudFailure("Refresh profile access", error, "Access refresh failed")
            }
        }
    }

    fun updateParticipantRole(accessToken: String, keyId: String, email: String, role: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                _profileParticipants.value =
                    sharedSync.updateParticipantRole(accessToken, keyId, email, role)
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success("Participant access updated.")
            } catch (error: Exception) {
                _cloudStatus.value =
                    cloudFailure("Update participant access", error, "Access update failed")
            }
        }
    }

    fun updateParticipantDatasetRole(
        accessToken: String,
        keyId: String,
        email: String,
        part: String,
        level: String,
    ) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                _profileParticipants.value =
                    sharedSync.updateParticipantDatasetRole(accessToken, keyId, email, part, level)
                refreshSharedSyncState()
                val label = datasetPartLabel(part)
                _cloudStatus.value = SyncStatus.Success(
                    if (level == "none") "Removed $label access."
                    else "$label set to ${if (level == "viewer") "View" else "Edit"}.",
                )
            } catch (error: Exception) {
                _cloudStatus.value =
                    cloudFailure("Update dataset access", error, "Access update failed")
            }
        }
    }

    fun revokeParticipant(accessToken: String, keyId: String, email: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                _profileParticipants.value = sharedSync.revokeParticipant(accessToken, keyId, email)
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success("Participant access removed.")
            } catch (error: Exception) {
                _cloudStatus.value =
                    cloudFailure("Remove participant access", error, "Access removal failed")
            }
        }
    }

    fun createOwnedProfile(accessToken: String, displayName: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                sharedSync.createOwnedProfile(accessToken, displayName)
                repo.getSettings()?.let {
                    _draft.value = it
                    applyReminderSchedule(it)
                }
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success("Created profile ${displayName.trim()}.")
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Profile creation", error, "Profile creation failed")
            }
        }
    }

    fun refreshPendingResponses(accessToken: String) {
        viewModelScope.launch {
            _pendingResponses.value = runCatching {
                sharedSync.listPendingResponses(accessToken)
            }.getOrDefault(emptyList())
        }
    }

    fun acceptPendingResponse(
        accessToken: String,
        response: SharedSyncCoordinator.PendingResponse,
        recipientEmail: String,
    ) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                sharedSync.acceptKeyResponse(
                    accessToken = accessToken,
                    invitationFileId = response.invitationFileId,
                    responseFileId = response.responseFileId,
                    recipientEmailAddress = recipientEmail.trim(),
                )
                refreshPendingResponses(accessToken)
                _cloudStatus.value = SyncStatus.Success("Participant accepted into encrypted sync.")
            } catch (error: Exception) {
                _cloudStatus.value = cloudFailure("Accept participant", error, "Accept failed")
            }
        }
    }

    fun activeProfile(): ProfileRecord? {
        val state = _sharedSyncState.value ?: return null
        return state.profiles.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
        }
    }

    fun isReadOnlyActiveProfile(): Boolean {
        val profile = activeProfile() ?: return false
        return shouldLoadRemoteBeforePublish(profile)
    }

    fun cloudError(message: String) {
        _cloudStatus.value = SyncStatus.Error(message)
    }

    fun cloudWaiting() {
        _cloudStatus.value = SyncStatus.Running
    }

    fun dismissCloudStatus() {
        _cloudStatus.value = SyncStatus.Idle
    }

    fun defaultBackupFilename(): String = DataBackup.defaultFilename()

    // ── Daily reminder ──────────────────────────────────────────────────

    /**
     * Flip the reminder toggle, persist, and reconcile the alarm state.
     * Caller (the UI) must ensure POST_NOTIFICATIONS has been requested on
     * Android 13+ before calling with [enabled]=true; if permission isn't
     * granted the alarm still schedules but the receiver will drop the
     * notification silently until the user flips it on in system settings.
     */
    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val current = repo.getSettings() ?: UserSettingsEntity()
            val updated = current.copy(reminderEnabled = enabled)
            repo.saveSettings(updated)
            _draft.value = _draft.value.copy(reminderEnabled = enabled)
            applyReminderSchedule(updated)
        }
    }

    /** Persist a new reminder time-of-day and re-schedule the alarm. */
    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            val current = repo.getSettings() ?: UserSettingsEntity()
            val updated = current.copy(
                reminderHour = hour.coerceIn(0, 23),
                reminderMinute = minute.coerceIn(0, 59),
            )
            repo.saveSettings(updated)
            _draft.value = _draft.value.copy(
                reminderHour = updated.reminderHour,
                reminderMinute = updated.reminderMinute,
            )
            applyReminderSchedule(updated)
        }
    }

    private fun applyReminderSchedule(settings: UserSettingsEntity) {
        if (settings.reminderEnabled) {
            ReminderScheduler.ensureChannel(app)
            ReminderScheduler.schedule(app, settings.reminderHour, settings.reminderMinute)
        } else {
            ReminderScheduler.cancel(app)
        }
    }
}

private fun InputStream.readBytesLimited(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "Choose a photo smaller than 20 MB." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
