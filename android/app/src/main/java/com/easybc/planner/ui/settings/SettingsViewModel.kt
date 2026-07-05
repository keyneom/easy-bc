package com.easybc.planner.ui.settings

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easybc.planner.EasyBCApp
import com.easybc.planner.data.db.UserSettingsEntity
import com.easybc.planner.io.DataBackup
import com.easybc.planner.notify.ReminderScheduler
import com.easybc.planner.sync.AuthorizationStep
import com.easybc.planner.sync.CloudSyncCoordinator
import com.easybc.planner.sync.CloudSyncOperation
import com.easybc.planner.sync.GoogleAuthorization
import com.easybc.planner.sync.SyncPayloadStore
import com.easybc.planner.sync.shared.ProfileRecord
import com.easybc.planner.sync.shared.SharedSyncCoordinator
import com.easybc.planner.sync.shared.SharedSyncState
import com.easybc.planner.sync.shared.canPublishRole
import com.easybc.planner.sync.shared.profileKey
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

    private val _lastCloudSync = MutableStateFlow<String?>(null)
    val lastCloudSync: StateFlow<String?> = _lastCloudSync

    private val _sharedSyncState = MutableStateFlow<SharedSyncState?>(null)
    val sharedSyncState: StateFlow<SharedSyncState?> = _sharedSyncState

    private val _joinUrl = MutableStateFlow<String?>(null)
    val joinUrl: StateFlow<String?> = _joinUrl

    private val _pendingResponses = MutableStateFlow<List<SharedSyncCoordinator.PendingResponse>>(emptyList())
    val pendingResponses: StateFlow<List<SharedSyncCoordinator.PendingResponse>> = _pendingResponses

    init {
        viewModelScope.launch {
            val existing = repo.getSettings()
            _draft.value = existing ?: UserSettingsEntity()
            refreshSharedSyncState()
        }
    }

    private suspend fun refreshSharedSyncState() {
        val state = sharedSync.loadState()
        _sharedSyncState.value = state
        _cloudConnected.value = state != null || syncStore.fileId() != null
        val active = state?.profiles?.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
        }
        _lastCloudSync.value = active?.lastSyncedAt ?: syncStore.lastSyncedAt()
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
                        sharedSync.setup(accessToken)
                        "Encrypted sync is set up. Your data lives in a Drive folder labeled with your email."
                    }
                    CloudSyncOperation.ENABLE -> {
                        // Legacy personal appData enable, then migrate into shared sync.
                        if (sharedSync.loadState() == null && syncStore.fileId() != null) {
                            legacyCloudSync.execute(activity, CloudSyncOperation.ENABLE, accessToken)
                        }
                        sharedSync.setup(accessToken)
                        "Encrypted sync is enabled and the latest records were merged."
                    }
                    CloudSyncOperation.SYNC -> {
                        if (sharedSync.loadState() != null) {
                            sharedSync.sync(accessToken)
                            "Encrypted sync data is up to date."
                        } else {
                            legacyCloudSync.execute(activity, operation, accessToken)
                        }
                    }
                    CloudSyncOperation.RESET -> {
                        sharedSync.forget()
                        sharedSync.setup(accessToken)
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
                _cloudStatus.value = SyncStatus.Error(error.message ?: "Encrypted sync failed.")
            }
        }
    }

    fun inviteParticipant(accessToken: String, email: String, role: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                val url = sharedSync.invite(accessToken, email.trim(), role)
                _joinUrl.value = url
                _cloudStatus.value = SyncStatus.Success("Invitation created. Share the join link with $email.")
            } catch (error: Exception) {
                _cloudStatus.value = SyncStatus.Error(error.message ?: "Invite failed.")
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
                _cloudStatus.value = SyncStatus.Error(error.message ?: "Join failed.")
            }
        }
    }

    fun switchProfile(accessToken: String, profileKeyValue: String) {
        _cloudStatus.value = SyncStatus.Running
        viewModelScope.launch {
            try {
                sharedSync.setActiveProfile(profileKeyValue)
                sharedSync.loadActiveProfile(accessToken)
                repo.getSettings()?.let {
                    _draft.value = it
                    applyReminderSchedule(it)
                }
                refreshSharedSyncState()
                _cloudStatus.value = SyncStatus.Success("Switched encrypted sync profile.")
            } catch (error: Exception) {
                _cloudStatus.value = SyncStatus.Error(error.message ?: "Profile switch failed.")
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
                _cloudStatus.value = SyncStatus.Error(error.message ?: "Accept failed.")
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
        return !canPublishRole(profile.role)
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
