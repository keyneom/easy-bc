package com.easybc.planner.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import com.easybc.planner.ui.kit.EbAvatar
import com.easybc.planner.ui.kit.EbBanner
import com.easybc.planner.ui.kit.EbBannerTone
import com.easybc.planner.ui.kit.EbGroupLabel
import com.easybc.planner.ui.kit.EbNavRow
import com.easybc.planner.ui.kit.EbProfileBadge
import com.easybc.planner.ui.kit.EbProfileHeaderCard
import com.easybc.planner.ui.kit.EbRowTone
import com.easybc.planner.ui.kit.EbStatusRow
import com.easybc.planner.ui.kit.EbStatusTone
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.data.PersistentMethod
import com.easybc.planner.data.ProtectedDayMethod
import com.easybc.planner.data.WithdrawalMode
import com.easybc.planner.io.DataBackup
import com.easybc.planner.sync.AuthorizationStep
import com.easybc.planner.sync.CloudSyncOperation
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = viewModel(),
    /** Navigate to a settings sub-route ("settings/basics", …). */
    onOpen: (String) -> Unit = {},
) {
    val activity = LocalContext.current as ComponentActivity
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft by vm.draft.collectAsState()
    val saved by vm.settings.collectAsState()
    val sharedState by vm.sharedSyncState.collectAsState()
    val lastSync by vm.lastCloudSync.collectAsState()
    val status by vm.cloudStatus.collectAsState()
    val themeMode by com.easybc.planner.ui.theme.ThemeModeStore.mode.collectAsState()
    val isFirstTime = saved?.onboardingComplete != true
    val busy = status is SettingsViewModel.SyncStatus.Running
    var showSwitcher by remember { mutableStateOf(false) }
    var pendingSwitchKey by remember { mutableStateOf<String?>(null) }

    val state = sharedState
    val activeProfile = state?.profiles?.firstOrNull {
        com.easybc.planner.sync.shared.profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
    }

    val switchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val key = pendingSwitchKey
        pendingSwitchKey = null
        if (result.resultCode != Activity.RESULT_OK) {
            vm.cloudError("Google authorization was cancelled.")
            return@rememberLauncherForActivityResult
        }
        runCatching { vm.finishCloudAuthorization(activity, result.data) }
            .onSuccess { token -> key?.let { vm.switchProfile(token, it) } }
            .onFailure { vm.cloudError(it.message ?: "Google authorization failed.") }
    }
    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let(vm::updateActiveProfileAvatar)
    }

    fun switchTo(key: String) {
        val currentState = sharedState ?: return
        showSwitcher = false
        if (busy || key == currentState.activeProfileKey) return
        val target = currentState.profiles.firstOrNull {
            com.easybc.planner.sync.shared.profileKey(it.ownerEmail, it.datasetId) == key
        } ?: return
        scope.launch {
            try {
                val current = activeProfile
                if (
                    com.easybc.planner.sync.shared.isLocalProfile(target) &&
                    current != null &&
                    com.easybc.planner.sync.shared.isLocalProfile(current)
                ) {
                    vm.switchProfile(null, key)
                    return@launch
                }
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> vm.switchProfile(step.accessToken, key)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingSwitchKey = key
                        switchLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (isFirstTime) "Welcome to EasyBC" else "Settings") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            if (isFirstTime) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Set up your plan to get personalized recommendations. " +
                                "All calculations happen on this device — your data stays private.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { onOpen("settings/basics") }) { Text("Set up your plan") }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // ── Active profile header ──
            if (state != null && activeProfile != null) {
                val label = com.easybc.planner.sync.shared.profileDisplayLabel(state, activeProfile)
                EbProfileHeaderCard(
                    name = label,
                    meta = buildString {
                        append(hubProfileMeta(state, activeProfile))
                        lastSync?.let { append(" · synced ${formatSyncTime(it)}") }
                    },
                    colorKey = state.activeProfileKey,
                    badge = hubProfileBadge(state, activeProfile),
                    photoBase64 = activeProfile.avatarWebp,
                    actionLabel = "Switch",
                    onAction = { showSwitcher = true },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = {
                            avatarLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        enabled = !busy,
                    ) {
                        Text(if (activeProfile.avatarWebp == null) "Add photo" else "Change photo")
                    }
                    if (activeProfile.avatarWebp != null) {
                        TextButton(
                            onClick = { vm.updateActiveProfileAvatar(null) },
                            enabled = !busy,
                        ) { Text("Remove photo") }
                    }
                }
            }
            when (val s = status) {
                is SettingsViewModel.SyncStatus.Running ->
                    EbStatusRow(tone = EbStatusTone.BUSY, text = "Working…")
                is SettingsViewModel.SyncStatus.Error ->
                    EbBanner(tone = EbBannerTone.ERROR, text = s.message)
                else -> {}
            }

            // ── Profile settings ──
            EbGroupLabel("Profile")
            EbNavRow(
                title = "Plan basics",
                value = "Age ${draft.ageYears} · ${draft.cycleLengthDays}-day cycle",
                icon = Icons.Filled.Person,
                onClick = { onOpen("settings/basics") },
            )
            EbNavRow(
                title = "Protection",
                value = hubProtectionSummary(draft),
                icon = Icons.Filled.Favorite,
                onClick = { onOpen("settings/protection") },
            )
            EbNavRow(
                title = "Risk & comfort",
                value = "%.1f%% over %d years".format(
                    draft.targetCumulativeFailure * 100,
                    draft.horizonYears,
                ),
                icon = Icons.Filled.Tune,
                onClick = { onOpen("settings/risk") },
            )
            EbNavRow(
                title = "Profiles & sharing",
                value = when {
                    state == null || activeProfile == null -> "Set up sync and sharing"
                    else -> "${state.profiles.size} profile${if (state.profiles.size == 1) "" else "s"} · " +
                        hubProfileMeta(state, activeProfile)
                },
                icon = Icons.Filled.Group,
                tone = if (
                    activeProfile?.let { profile ->
                        state?.let { current ->
                            hubProfileBadge(current, profile) == EbProfileBadge.SHARED
                        }
                    } == true
                ) {
                    EbRowTone.SHARED
                } else {
                    EbRowTone.DEFAULT
                },
                onClick = { onOpen("settings/storage") },
            )

            // ── Profiles ──
            EbGroupLabel("Profiles")
            EbNavRow(
                title = "Manage profiles",
                value = state?.profiles?.size?.let { count ->
                    "$count profile${if (count == 1) "" else "s"} on this device"
                } ?: "Create, switch, or join profiles",
                icon = Icons.Filled.Person,
                onClick = { onOpen("settings/profiles") },
            )

            // ── This device ──
            EbGroupLabel("This device")
            EbNavRow(
                title = "Reminders",
                value = if (saved?.reminderEnabled == true) {
                    "Daily reconcile · ${hubFormatTime(saved?.reminderHour ?: 9, saved?.reminderMinute ?: 0)}"
                } else {
                    "Off"
                },
                icon = Icons.Filled.Notifications,
                onClick = { onOpen("settings/reminders") },
            )
            EbNavRow(
                title = "Device calendar",
                value = if (saved?.calendarSyncEnabled == true) "Auto-update on" else "Off",
                icon = Icons.Filled.CalendarMonth,
                onClick = { onOpen("settings/device-calendar") },
            )
            EbNavRow(
                title = "Backup & restore",
                value = "Export or import everything on this device",
                icon = Icons.Filled.Save,
                onClick = { onOpen("settings/backup") },
            )

            EbGroupLabel("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.easybc.planner.ui.theme.ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { com.easybc.planner.ui.theme.ThemeModeStore.set(context, mode) },
                        label = {
                            Text(
                                when (mode) {
                                    com.easybc.planner.ui.theme.ThemeMode.SYSTEM -> "System default"
                                    com.easybc.planner.ui.theme.ThemeMode.LIGHT -> "Light"
                                    com.easybc.planner.ui.theme.ThemeMode.DARK -> "Dark"
                                },
                            )
                        },
                    )
                }
            }

            EbGroupLabel("About")
            EbNavRow(
                title = "About EasyBC",
                value = "Version ${com.easybc.planner.BuildConfig.VERSION_NAME} · disclaimers",
                icon = Icons.Filled.Info,
                onClick = { onOpen("settings/about") },
            )
        }
    }

    if (showSwitcher && state != null) {
        ModalBottomSheet(onDismissRequest = { showSwitcher = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    "PROFILES",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                state.profiles.forEach { profile ->
                    val key = com.easybc.planner.sync.shared.profileKey(profile.ownerEmail, profile.datasetId)
                    val isActive = key == state.activeProfileKey
                    val label = com.easybc.planner.sync.shared.profileDisplayLabel(state, profile)
                    Surface(
                        onClick = { switchTo(key) },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            EbAvatar(
                                name = label,
                                colorKey = key,
                                size = 36.dp,
                                badge = hubProfileBadge(state, profile),
                                photoBase64 = profile.avatarWebp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    hubProfileMeta(state, profile),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isActive) {
                                Text(
                                    "✓",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(
                    onClick = {
                        showSwitcher = false
                        onOpen("settings/profiles")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Manage profiles — new, join, storage & sharing") }
            }
        }
    }
}

internal fun hubProfileMeta(
    state: com.easybc.planner.sync.shared.SharedSyncState,
    profile: com.easybc.planner.sync.shared.ProfileRecord,
): String = when {
    com.easybc.planner.sync.shared.isLocalProfile(profile) -> "Local only · this device"
    !profile.ownerEmail.equals(state.ownerEmail, ignoreCase = true) ->
        "Shared with you · ${profile.role}"
    profile.participantEmails.orEmpty().isNotEmpty() ->
        "Shared · ${profile.participantEmails.orEmpty().size} " +
            if (profile.participantEmails.orEmpty().size == 1) "person" else "people"
    else -> "Private encrypted · your devices"
}

internal fun hubProfileBadge(
    state: com.easybc.planner.sync.shared.SharedSyncState,
    profile: com.easybc.planner.sync.shared.ProfileRecord,
): EbProfileBadge = when {
    profile.needsInitialLoad -> EbProfileBadge.WAITING
    com.easybc.planner.sync.shared.isLocalProfile(profile) -> EbProfileBadge.LOCAL
    !profile.ownerEmail.equals(state.ownerEmail, ignoreCase = true) ->
        if (!com.easybc.planner.sync.shared.canPublishRole(profile.role)) EbProfileBadge.READ_ONLY
        else EbProfileBadge.SHARED
    profile.participantEmails.orEmpty().isNotEmpty() -> EbProfileBadge.SHARED
    else -> EbProfileBadge.PRIVATE
}

internal fun hubProtectionSummary(draft: com.easybc.planner.data.db.UserSettingsEntity): String {
    val persistent = PersistentMethod.entries.firstOrNull {
        it.name.equals(draft.persistentMethod, ignoreCase = true)
    } ?: PersistentMethod.None
    val protected = ProtectedDayMethod.entries.firstOrNull {
        it.name.equals(draft.protectedDayMethod, ignoreCase = true)
    } ?: ProtectedDayMethod.ExternalCondom
    val withdrawal = WithdrawalMode.entries.firstOrNull {
        it.name.equals(draft.withdrawalMode, ignoreCase = true)
    } ?: WithdrawalMode.None
    return buildString {
        if (persistent != PersistentMethod.None) append("${persistent.label} · ")
        append(protected.label)
        if (protected == ProtectedDayMethod.ExternalCondom) append(" (${draft.condomMode})")
        if (withdrawal != WithdrawalMode.None) append(" + withdrawal")
    }
}

private fun hubFormatTime(hour: Int, minute: Int): String {
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return "%d:%02d %s".format(h12, minute, if (hour < 12) "AM" else "PM")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EncryptedSyncSection(vm: SettingsViewModel) {
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val status by vm.cloudStatus.collectAsState()
    val connected by vm.cloudConnected.collectAsState()
    val sharedConfigured by vm.sharedSyncConfigured.collectAsState()
    val lastSync by vm.lastCloudSync.collectAsState()
    val sharedState by vm.sharedSyncState.collectAsState()
    val legacyPresent by vm.legacySyncPresent.collectAsState()
    val joinUrl by vm.joinUrl.collectAsState()
    val responseLink by vm.responseLink.collectAsState()
    val profileParticipants by vm.profileParticipants.collectAsState()
    val migrationAckStatus by vm.migrationStatus.collectAsState()
    var responseLinkInput by remember { mutableStateOf("") }
    var deepLinkResponse by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    var pendingOperation by remember { mutableStateOf<CloudSyncOperation?>(null) }
    var pendingProfileKey by remember { mutableStateOf<String?>(null) }
    var pendingProfileDisplayName by remember { mutableStateOf<String?>(null) }
    var pendingProfileDeleteKey by remember { mutableStateOf<String?>(null) }
    var pendingProfileParticipantsRefresh by remember { mutableStateOf(false) }
    var pendingControlEnrollment by remember { mutableStateOf(false) }
    var pendingSplitUpgrade by remember { mutableStateOf(false) }
    var splitUpgradeConfirm by remember { mutableStateOf(false) }
    // Hard-cutover migration ceremony (docs/sync-kit-multi-file-datasets.md).
    var pendingMigrationBegin by remember {
        mutableStateOf<Map<String, Map<String, String>>?>(null)
    }
    var pendingMigrationStatus by remember { mutableStateOf(false) }
    var pendingMigrationClose by remember { mutableStateOf(false) }
    var pendingMigrationAck by remember { mutableStateOf(false) }
    var migrationSetupOpen by remember { mutableStateOf(false) }
    var migrationGrants by remember {
        mutableStateOf<Map<String, Map<String, String>>>(emptyMap())
    }
    var migrationBeginConfirm by remember { mutableStateOf(false) }
    var migrationCloseConfirm by remember { mutableStateOf(false) }
    var pendingParticipantRoleChange by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var pendingParticipantRevoke by remember { mutableStateOf<Pair<String, String>?>(null) }
    var participantRevokeConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var newProfileName by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    var profileActionConfirm by remember { mutableStateOf<String?>(null) }
    var pendingInvite by remember {
        mutableStateOf<Triple<String, String, Map<String, String>?>?>(null)
    }
    var invitePresetId by remember { mutableStateOf("cycle-only") }
    // "custom" preset composes arbitrary grants via the per-dataset grid.
    var customGrants by remember { mutableStateOf<Map<String, String>>(mapOf("cycle" to "viewer")) }
    // Which participant's per-dataset access grid is expanded (keyId).
    var expandedParticipant by remember { mutableStateOf<String?>(null) }
    // [keyId, email, part, level] carried across a Google auth resolution.
    var pendingParticipantDatasetRoleChange by remember { mutableStateOf<List<String>?>(null) }
    var dangerZoneOpen by remember { mutableStateOf(false) }
    var pendingJoin by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var pendingLinkJoin by remember { mutableStateOf<String?>(null) }
    var pendingLinkAccept by remember { mutableStateOf<String?>(null) }
    var joinLinkInput by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf<CloudSyncOperation?>(null) }
    var inviteEmail by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf("viewer") }
    val pendingLinkRevision by com.easybc.planner.sync.shared.PendingSharedJoin.revision.collectAsState()

    // Deep links are stored durably because Android may kill the process while
    // Google auth or the browser Picker is open.
    LaunchedEffect(pendingLinkRevision) {
        val pending = com.easybc.planner.sync.shared.PendingSharedJoin
        pending.joinLink(activity)?.let { joinLinkInput = it }
        pending.responseToAccept(activity)?.let { responseLinkInput = it }
        pending.producedResponse(activity)?.let { deepLinkResponse = it }
    }

    LaunchedEffect(sharedState?.activeProfileKey) {
        val state = sharedState ?: return@LaunchedEffect
        val profile = state.profiles.firstOrNull {
            com.easybc.planner.sync.shared.profileKey(it.ownerEmail, it.datasetId) ==
                state.activeProfileKey
        } ?: return@LaunchedEffect
        profileName = com.easybc.planner.sync.shared.profileDisplayLabel(state, profile)
    }

    val resolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val operation = pendingOperation
        val profileKey = pendingProfileKey
        val profileDisplayName = pendingProfileDisplayName
        val profileDeleteKey = pendingProfileDeleteKey
        val refreshParticipants = pendingProfileParticipantsRefresh
        val controlEnrollment = pendingControlEnrollment
        val splitUpgrade = pendingSplitUpgrade
        val migrationBegin = pendingMigrationBegin
        val migrationStatusCheck = pendingMigrationStatus
        val migrationClose = pendingMigrationClose
        val migrationAck = pendingMigrationAck
        val participantRoleChange = pendingParticipantRoleChange
        val participantDatasetRoleChange = pendingParticipantDatasetRoleChange
        val participantRevoke = pendingParticipantRevoke
        val invite = pendingInvite
        val join = pendingJoin
        val linkJoin = pendingLinkJoin
        val linkAccept = pendingLinkAccept
        pendingOperation = null
        pendingProfileKey = null
        pendingProfileDisplayName = null
        pendingProfileDeleteKey = null
        pendingProfileParticipantsRefresh = false
        pendingControlEnrollment = false
        pendingSplitUpgrade = false
        pendingMigrationBegin = null
        pendingMigrationStatus = false
        pendingMigrationClose = false
        pendingMigrationAck = false
        pendingParticipantRoleChange = null
        pendingParticipantDatasetRoleChange = null
        pendingParticipantRevoke = null
        pendingInvite = null
        pendingJoin = null
        pendingLinkJoin = null
        pendingLinkAccept = null
        if (result.resultCode != Activity.RESULT_OK) {
            vm.cloudError("Google authorization was cancelled.")
            return@rememberLauncherForActivityResult
        }
        runCatching { vm.finishCloudAuthorization(activity, result.data) }
            .onSuccess { token ->
                when {
                    profileDisplayName != null -> {
                        vm.createLocalProfile(token, profileDisplayName)
                        newProfileName = ""
                    }
                    profileDeleteKey != null -> vm.deleteProfile(token, profileDeleteKey, false)
                    controlEnrollment -> vm.enrollControlDataset(token)
                    splitUpgrade -> vm.upgradeProfileToSplit(token)
                    migrationBegin != null -> vm.beginSplitMigration(token, migrationBegin)
                    migrationStatusCheck -> vm.refreshMigrationStatus(token)
                    migrationClose -> vm.closeSplitMigration(token)
                    migrationAck -> vm.acknowledgeSplitMigration(token)
                    refreshParticipants -> vm.refreshProfileParticipants(token)
                    participantRoleChange != null ->
                        vm.updateParticipantRole(
                            token,
                            participantRoleChange.first,
                            participantRoleChange.second,
                            participantRoleChange.third,
                        )
                    participantDatasetRoleChange != null ->
                        vm.updateParticipantDatasetRole(
                            token,
                            participantDatasetRoleChange[0],
                            participantDatasetRoleChange[1],
                            participantDatasetRoleChange[2],
                            participantDatasetRoleChange[3],
                        )
                    participantRevoke != null ->
                        vm.revokeParticipant(token, participantRevoke.first, participantRevoke.second)
                    profileKey != null -> vm.switchProfile(token, profileKey)
                    invite != null ->
                        vm.inviteParticipant(token, invite.first, invite.second, invite.third)
                    join != null -> vm.joinSharedSync(token, join.first, join.second, join.third)
                    linkJoin != null -> vm.joinFromLink(token, linkJoin)
                    linkAccept != null -> vm.acceptResponseLink(token, linkAccept)
                    operation != null -> vm.runCloudOperation(activity, operation, token)
                    else -> vm.cloudError("Google authorization completed with no pending action.")
                }
            }
            .onFailure { vm.cloudError(it.message ?: "Google authorization failed.") }
    }

    fun authorizeAndRun(operation: CloudSyncOperation) {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> vm.runCloudOperation(activity, operation, step.accessToken)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingOperation = operation
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build()
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    // Same parser the deep-link handler in MainActivity consumes.
    fun parseJoinLink(raw: String): Triple<String, String, String>? {
        val join = com.easybc.planner.sync.shared.parseSharedJoinLink(raw) ?: return null
        return Triple(join.invitationFileId, join.ownerFolderId, join.ownerEmail)
    }

    fun authorizeAndJoin(params: Triple<String, String, String>) {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized ->
                        vm.joinSharedSync(step.accessToken, params.first, params.second, params.third)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingJoin = params
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build()
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndJoinLink(url: String) {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> vm.joinFromLink(step.accessToken, url)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingLinkJoin = url
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build()
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndAcceptLink(url: String) {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> vm.acceptResponseLink(step.accessToken, url)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingLinkAccept = url
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build()
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndChangeParticipantRole(keyId: String, email: String, role: String) {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized ->
                        vm.updateParticipantRole(step.accessToken, keyId, email, role)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingParticipantRoleChange = Triple(keyId, email, role)
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndEnrollControlDataset() {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> vm.enrollControlDataset(step.accessToken)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingControlEnrollment = true
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndUpgradeSplit() {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> vm.upgradeProfileToSplit(step.accessToken)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingSplitUpgrade = true
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndBeginMigration(grants: Map<String, Map<String, String>>) {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized ->
                        vm.beginSplitMigration(step.accessToken, grants)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingMigrationBegin = grants
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndCheckMigrationStatus() {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> vm.refreshMigrationStatus(step.accessToken)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingMigrationStatus = true
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndCloseMigration() {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> vm.closeSplitMigration(step.accessToken)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingMigrationClose = true
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndAckMigration() {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized ->
                        vm.acknowledgeSplitMigration(step.accessToken)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingMigrationAck = true
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndChangeParticipantDatasetRole(
        keyId: String,
        email: String,
        part: String,
        level: String,
    ) {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized ->
                        vm.updateParticipantDatasetRole(step.accessToken, keyId, email, part, level)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingParticipantDatasetRoleChange = listOf(keyId, email, part, level)
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    fun authorizeAndRevokeParticipant(keyId: String, email: String) {
        vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized ->
                        vm.revokeParticipant(step.accessToken, keyId, email)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingParticipantRevoke = keyId to email
                        resolutionLauncher.launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }

    val busy = status is SettingsViewModel.SyncStatus.Running
    val selectedProfile = vm.activeProfile()
    val selectedIsLocal = selectedProfile?.let {
        com.easybc.planner.sync.shared.isLocalProfile(it)
    } == true
    val isSharedWithYou = sharedState != null && selectedProfile != null &&
        !selectedProfile.ownerEmail.equals(sharedState!!.ownerEmail, ignoreCase = true)
    val participantCount = selectedProfile?.participantEmails.orEmpty().size

    // ── Where does this profile live? (docs/settings-profiles-redesign.md §6.1)
    val currentMode = when {
        selectedIsLocal || selectedProfile == null -> com.easybc.planner.ui.kit.EbStorageMode.LOCAL
        isSharedWithYou || participantCount > 0 -> com.easybc.planner.ui.kit.EbStorageMode.SHARED
        else -> com.easybc.planner.ui.kit.EbStorageMode.PRIVATE
    }
    com.easybc.planner.ui.kit.EbModeCard(
        mode = com.easybc.planner.ui.kit.EbStorageMode.LOCAL,
        title = "This device",
        description = "Stays on this phone. No account needed.",
        selected = currentMode == com.easybc.planner.ui.kit.EbStorageMode.LOCAL,
        pending = busy,
        enabled = !isSharedWithYou,
        onSelect = {
            if (currentMode != com.easybc.planner.ui.kit.EbStorageMode.LOCAL) {
                profileActionConfirm = "disconnect"
            }
        },
    )
    com.easybc.planner.ui.kit.EbModeCard(
        mode = com.easybc.planner.ui.kit.EbStorageMode.PRIVATE,
        title = "Private cloud",
        description = "Encrypted in your Google Drive; your other devices unlock it " +
            "with your passkey. Only you.",
        selected = currentMode == com.easybc.planner.ui.kit.EbStorageMode.PRIVATE,
        pending = busy,
        enabled = !isSharedWithYou,
        onSelect = {
            if (currentMode == com.easybc.planner.ui.kit.EbStorageMode.LOCAL) {
                authorizeAndRun(CloudSyncOperation.SETUP)
            }
        },
    )
    com.easybc.planner.ui.kit.EbModeCard(
        mode = com.easybc.planner.ui.kit.EbStorageMode.SHARED,
        title = "Shared",
        description = "Private cloud, plus invited people can view or edit what you choose.",
        selected = currentMode == com.easybc.planner.ui.kit.EbStorageMode.SHARED,
        pending = busy,
        enabled = !isSharedWithYou,
        onSelect = {
            if (currentMode == com.easybc.planner.ui.kit.EbStorageMode.LOCAL) {
                authorizeAndRun(CloudSyncOperation.SETUP)
            }
        },
    )
    if (isSharedWithYou) {
        Text(
            "Storage is controlled by the owner of this shared profile.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (currentMode == com.easybc.planner.ui.kit.EbStorageMode.PRIVATE) {
        Text(
            "To share this profile, invite someone below — you choose exactly what they see.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))

    if (
        selectedProfile != null &&
        !selectedIsLocal &&
        selectedProfile.role == "owner" &&
        !com.easybc.planner.sync.shared.isSplitProfile(selectedProfile)
    ) {
        if (selectedProfile.participantEmails.orEmpty().isEmpty()) {
            EbBanner(
                tone = EbBannerTone.INFO,
                title = "Upgrade to per-dataset sharing",
                text = "Splits this profile into four encrypted files — cycle, plan, " +
                    "intimacy, sensitive — each with its own keys, so you can share " +
                    "each section separately. The old single file is replaced.",
                actionLabel = if (busy) "Upgrading…" else "Upgrade",
                onAction = { if (!busy) splitUpgradeConfirm = true },
            )
        } else {
            EbBanner(
                tone = EbBannerTone.INFO,
                title = "Upgrade to per-dataset sharing",
                text = "Your data splits into four encrypted files so you control " +
                    "exactly what each person sees. Everyone keeps their access — " +
                    "their app asks them to reselect the new files in Google, the " +
                    "one step Google requires. No re-invites.",
                actionLabel = if (migrationSetupOpen) "Cancel" else "Choose access…",
                onAction = {
                    if (busy) return@EbBanner
                    if (migrationSetupOpen) {
                        migrationSetupOpen = false
                    } else {
                        migrationGrants = profileParticipants
                            .filter { !it.isCurrentDevice && it.role != "owner" }
                            .associate { participant ->
                                participant.keyId to
                                    com.easybc.planner.sync.shared.DATASET_PARTS
                                        .associateWith { participant.role }
                            }
                        migrationSetupOpen = true
                        if (profileParticipants.none { !it.isCurrentDevice && it.role != "owner" }) {
                            scope.launch {
                                try {
                                    when (val step = vm.beginCloudAuthorization(activity)) {
                                        is AuthorizationStep.Authorized ->
                                            vm.refreshProfileParticipants(step.accessToken)
                                        is AuthorizationStep.NeedsResolution -> {
                                            pendingProfileParticipantsRefresh = true
                                            resolutionLauncher.launch(
                                                IntentSenderRequest.Builder(
                                                    step.pendingIntent.intentSender,
                                                ).build(),
                                            )
                                        }
                                    }
                                } catch (error: Exception) {
                                    vm.cloudError(error.message ?: "Google authorization failed.")
                                }
                            }
                        }
                    }
                },
            )
            if (migrationSetupOpen) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "What each person will see after the upgrade",
                    style = MaterialTheme.typography.labelLarge,
                )
                val migrationPeople = profileParticipants.filter {
                    !it.isCurrentDevice && it.role != "owner"
                }
                if (migrationPeople.isEmpty()) {
                    Text(
                        "Loading people with access…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                migrationPeople.forEach { participant ->
                    val email = participant.emailAddress ?: "Key ${participant.keyId.take(10)}…"
                    // Seed anyone who appeared after the panel opened.
                    if (migrationGrants[participant.keyId] == null) {
                        migrationGrants = migrationGrants + (
                            participant.keyId to
                                com.easybc.planner.sync.shared.DATASET_PARTS
                                    .associateWith { participant.role }
                            )
                    }
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(email, style = MaterialTheme.typography.titleSmall)
                        com.easybc.planner.sync.shared.DATASET_PARTS.forEach { part ->
                            val role = migrationGrants[participant.keyId]?.get(part)
                            com.easybc.planner.ui.kit.EbDatasetRow(
                                dataset = when (part) {
                                    com.easybc.planner.sync.shared.PART_CYCLE ->
                                        com.easybc.planner.ui.kit.EbDataset.CYCLE
                                    com.easybc.planner.sync.shared.PART_INTIMACY ->
                                        com.easybc.planner.ui.kit.EbDataset.INTIMACY
                                    com.easybc.planner.sync.shared.PART_SENSITIVE ->
                                        com.easybc.planner.ui.kit.EbDataset.SENSITIVE
                                    else -> com.easybc.planner.ui.kit.EbDataset.PLAN
                                },
                                title = com.easybc.planner.sync.shared.datasetPartLabel(part),
                                summary = com.easybc.planner.sync.shared.datasetPartSummary(part),
                                modifier = Modifier.padding(vertical = 2.dp),
                                trailing = {
                                    com.easybc.planner.ui.kit.EbAccessSegmented(
                                        value = when (role) {
                                            "writer", "admin" ->
                                                com.easybc.planner.ui.kit.EbAccessLevel.EDIT
                                            "viewer" ->
                                                com.easybc.planner.ui.kit.EbAccessLevel.VIEW
                                            else ->
                                                com.easybc.planner.ui.kit.EbAccessLevel.NONE
                                        },
                                        enabled = !busy,
                                        onChange = { level ->
                                            val grants =
                                                migrationGrants[participant.keyId].orEmpty()
                                                    .toMutableMap()
                                            when (level) {
                                                com.easybc.planner.ui.kit.EbAccessLevel.NONE ->
                                                    grants.remove(part)
                                                com.easybc.planner.ui.kit.EbAccessLevel.VIEW ->
                                                    grants[part] = "viewer"
                                                com.easybc.planner.ui.kit.EbAccessLevel.EDIT ->
                                                    grants[part] = "writer"
                                            }
                                            migrationGrants =
                                                migrationGrants + (participant.keyId to grants)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                Button(
                    onClick = { if (!busy) migrationBeginConfirm = true },
                    enabled = !busy && migrationPeople.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Reorganizing…" else "Start upgrade") }
                Text(
                    "Creates the new files with fresh keys, shares them to everyone's " +
                        "existing keys per your choices, and freezes the old file. The old " +
                        "file is kept until everyone confirms, then moved to Drive's trash.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // Owner: an announced migration waiting on acknowledgements.
    if (
        selectedProfile != null &&
        !selectedIsLocal &&
        selectedProfile.role == "owner" &&
        selectedProfile.openMigrationId != null
    ) {
        EbBanner(
            tone = EbBannerTone.INFO,
            title = "Upgrade in progress",
            text = migrationAckStatus?.let { status ->
                if (status.pending.isEmpty()) {
                    "Everyone has reselected the new files — you can finish now."
                } else {
                    "Waiting on " + status.pending.joinToString(", ") { (keyId, email) ->
                        email ?: "key ${keyId.take(8)}…"
                    } + " to reselect the new files."
                }
            } ?: "Waiting for people to reselect the new files in Google. " +
                "Their edits pause until they do.",
            actionLabel = if (busy) "Working…" else "Check status",
            onAction = { if (!busy) authorizeAndCheckMigrationStatus() },
        )
        if (migrationAckStatus?.pending?.isEmpty() == true) {
            TextButton(
                onClick = { if (!busy) migrationCloseConfirm = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Finish upgrade — move the old file to Drive's trash") }
        }
        Spacer(Modifier.height(8.dp))
    }

    // Participant: the owner reorganized this profile; reselect + confirm.
    selectedProfile?.pendingMigration?.let { pending ->
        if (!selectedIsLocal) {
            EbBanner(
                tone = EbBannerTone.INFO,
                title = "${selectedProfile.ownerEmail} reorganized this profile",
                text = "Pick the new files in Google to keep your access — nothing else " +
                    "changes, and your edits pause until you do. Grant access in the " +
                    "browser first, then finish here.",
                actionLabel = "Open browser",
                onAction = {
                    if (busy) return@EbBanner
                    val targets = pending.targets.filter {
                        pending.requiredFileIds.contains(it.fileId)
                    }
                    val json = kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(
                            com.easybc.planner.sync.shared.MigrationTargetRecord.serializer(),
                        ),
                        targets,
                    )
                    val encoded = android.util.Base64.encodeToString(
                        json.toByteArray(Charsets.UTF_8),
                        android.util.Base64.URL_SAFE or
                            android.util.Base64.NO_PADDING or
                            android.util.Base64.NO_WRAP,
                    )
                    val grantUrl = android.net.Uri
                        .parse(com.easybc.planner.sync.shared.EASY_BC_JOIN_LANDING_URL)
                        .buildUpon()
                        .appendQueryParameter("grant-files", "1")
                        .appendQueryParameter("owner", selectedProfile.ownerEmail)
                        .appendQueryParameter("sk-mfiles", encoded)
                        .build()
                        .toString()
                    if (!com.easybc.planner.util.launchGrantInBrowser(activity, grantUrl)) {
                        clipboard.setText(AnnotatedString(grantUrl))
                        vm.cloudError(
                            "No browser was available. The file-access link was copied; " +
                                "paste it into Chrome, select the files, then return here.",
                        )
                    }
                },
            )
            OutlinedButton(
                onClick = { if (!busy) authorizeAndAckMigration() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Working…" else "I granted access — finish reorganization") }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (
        selectedProfile != null &&
        !selectedIsLocal &&
        (selectedProfile.role == "owner" || selectedProfile.role == "admin") &&
        selectedProfile.controlEnrollment != "enrolled"
    ) {
        EbBanner(
            tone = EbBannerTone.INFO,
            title = "Set up sharing coordination",
            text = "This encrypted control file coordinates verified membership and future migrations.",
            actionLabel = if (busy) "Setting up…" else "Set up",
            onAction = { if (!busy) authorizeAndEnrollControlDataset() },
        )
        Spacer(Modifier.height(8.dp))
    }

    // Status + Sync now
    if (!selectedIsLocal) {
        com.easybc.planner.ui.kit.EbStatusRow(
            tone = if (busy) {
                com.easybc.planner.ui.kit.EbStatusTone.BUSY
            } else if (lastSync != null) {
                com.easybc.planner.ui.kit.EbStatusTone.OK
            } else {
                com.easybc.planner.ui.kit.EbStatusTone.WARN
            },
            text = when {
                busy -> "Working…"
                lastSync != null -> "Last encrypted update ${formatSyncTime(lastSync!!)}"
                else -> "No encrypted sync has completed on this device."
            },
        )
        if (sharedConfigured) {
            Button(
                onClick = { authorizeAndRun(CloudSyncOperation.SYNC) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Sync, null)
                Spacer(Modifier.width(8.dp))
                Text("Sync now")
            }
        }
    }

    // Contextual recovery paths — rendered only while their condition holds.
    if (legacyPresent && !sharedConfigured) {
        com.easybc.planner.ui.kit.EbBanner(
            tone = com.easybc.planner.ui.kit.EbBannerTone.WARN,
            title = "Legacy encrypted sync found",
            text = "This device has records from the older encrypted sync. Migrating merges " +
                "them into the current format — nothing is lost.",
            actionLabel = "Migrate",
            onAction = { if (!busy) authorizeAndRun(CloudSyncOperation.ENABLE) },
        )
    }
    if (connected && !sharedConfigured) {
        com.easybc.planner.ui.kit.EbBanner(
            tone = com.easybc.planner.ui.kit.EbBannerTone.ERROR,
            title = "A cloud copy exists that this device can't unlock",
            text = "Reset deletes that copy and starts fresh with this device's data.",
            actionLabel = "Reset",
            onAction = { if (!busy) confirming = CloudSyncOperation.RESET },
        )
    }
    Spacer(Modifier.height(4.dp))
    sharedState?.let { state ->
        Spacer(Modifier.height(8.dp))
        Text("Profile management", style = MaterialTheme.typography.titleMedium)
        Text(
            "Each profile can stay local, sync privately across your devices, or be shared. " +
                "Storage and sharing choices are independent for every profile.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        state.profiles.forEach { profile ->
            val key = com.easybc.planner.sync.shared.profileKey(profile.ownerEmail, profile.datasetId)
            val active = key == state.activeProfileKey
            val label = com.easybc.planner.sync.shared.profileDisplayLabel(state, profile)
            OutlinedButton(
                onClick = {
                    if (active || busy) return@OutlinedButton
                    scope.launch {
                        try {
                            val current = vm.activeProfile()
                            if (
                                com.easybc.planner.sync.shared.isLocalProfile(profile) &&
                                current != null &&
                                com.easybc.planner.sync.shared.isLocalProfile(current)
                            ) {
                                vm.switchProfile(null, key)
                                return@launch
                            }
                            when (val step = vm.beginCloudAuthorization(activity)) {
                                is AuthorizationStep.Authorized ->
                                    vm.switchProfile(step.accessToken, key)
                                is AuthorizationStep.NeedsResolution -> {
                                    pendingProfileKey = key
                                    resolutionLauncher.launch(
                                        IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                                    )
                                }
                            }
                        } catch (error: Exception) {
                            vm.cloudError(error.message ?: "Google authorization failed.")
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    buildString {
                        append(if (active) "● " else "○ ")
                        append(label)
                        append(" · ")
                        append(
                            when {
                                com.easybc.planner.sync.shared.isLocalProfile(profile) ->
                                    "Local only · this device"
                                !profile.ownerEmail.equals(state.ownerEmail, ignoreCase = true) ->
                                    "Shared with you · ${profile.role}"
                                profile.participantEmails.orEmpty().isNotEmpty() ->
                                    "Shared encrypted · ${profile.participantEmails.orEmpty().size} people"
                                else -> "Private encrypted · your devices"
                            },
                        )
                        if (profile.needsInitialLoad) {
                            append(" · waiting for owner")
                        } else if (!com.easybc.planner.sync.shared.canPublishRole(profile.role)) {
                            append(" · read-only")
                        }
                    },
                )
            }
        }
        if (vm.isReadOnlyActiveProfile() && vm.activeProfile()?.needsInitialLoad != true) {
            Text(
                "Viewing a shared profile in read-only mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Split profiles: exactly what this device can see, per dataset part.
        vm.activeProfile()
            ?.takeIf {
                com.easybc.planner.sync.shared.isSplitProfile(it) &&
                    !com.easybc.planner.sync.shared.isLocalProfile(it)
            }
            ?.let { split ->
                Spacer(Modifier.height(8.dp))
                Text(
                    if (split.role == "owner") "What this profile stores" else "What you can see",
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                com.easybc.planner.sync.shared.DATASET_PARTS.forEach { part ->
                    val role = com.easybc.planner.sync.shared.partRole(split, part)
                    com.easybc.planner.ui.kit.EbDatasetRow(
                        dataset = when (part) {
                            com.easybc.planner.sync.shared.PART_CYCLE ->
                                com.easybc.planner.ui.kit.EbDataset.CYCLE
                            com.easybc.planner.sync.shared.PART_INTIMACY ->
                                com.easybc.planner.ui.kit.EbDataset.INTIMACY
                            com.easybc.planner.sync.shared.PART_SENSITIVE ->
                                com.easybc.planner.ui.kit.EbDataset.SENSITIVE
                            else -> com.easybc.planner.ui.kit.EbDataset.PLAN
                        },
                        title = com.easybc.planner.sync.shared.datasetPartLabel(part),
                        summary = when {
                            role == null -> "Not shared with you"
                            role == "owner" -> "Yours"
                            com.easybc.planner.sync.shared.canPublishRole(role) -> "You can edit"
                            else -> "View only"
                        },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (com.easybc.planner.sync.shared.restrictedParts(split).isNotEmpty()) {
                    Text(
                        "Sections that aren't shared with you stay hidden across the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        vm.activeProfile()?.takeIf { it.needsInitialLoad }?.let {
            Text(
                "Waiting for the owner to accept this profile. After they finish, tap " +
                    "Merge encrypted changes to load their data without merging another profile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        vm.activeProfile()?.let { profile ->
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text("Profile name") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(
                onClick = { vm.renameProfile(state.activeProfileKey, profileName) },
                enabled = !busy && profileName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Rename profile") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = newProfileName,
            onValueChange = { newProfileName = it },
            label = { Text("New profile name") },
            placeholder = { Text("Daughter") },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val name = newProfileName.trim()
                if (name.isEmpty() || busy) return@OutlinedButton
                scope.launch {
                    try {
                        val active = vm.activeProfile()
                        if (active == null || com.easybc.planner.sync.shared.isLocalProfile(active)) {
                            vm.createLocalProfile(null, name)
                            newProfileName = ""
                            return@launch
                        }
                        when (val step = vm.beginCloudAuthorization(activity)) {
                            is AuthorizationStep.Authorized -> {
                                vm.createLocalProfile(step.accessToken, name)
                                newProfileName = ""
                            }
                            is AuthorizationStep.NeedsResolution -> {
                                pendingProfileDisplayName = name
                                resolutionLauncher.launch(
                                    IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                                )
                            }
                        }
                    } catch (error: Exception) {
                        vm.cloudError(error.message ?: "Google authorization failed.")
                    }
                }
            },
            enabled = !busy && newProfileName.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("New local profile")
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (sharedConfigured && !selectedIsLocal) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    try {
                        when (val step = vm.beginCloudAuthorization(activity)) {
                            is AuthorizationStep.Authorized ->
                                vm.refreshProfileParticipants(step.accessToken)
                            is AuthorizationStep.NeedsResolution -> {
                                pendingProfileParticipantsRefresh = true
                                resolutionLauncher.launch(
                                    IntentSenderRequest.Builder(
                                        step.pendingIntent.intentSender,
                                    ).build(),
                                )
                            }
                        }
                    } catch (error: Exception) {
                        vm.cloudError(error.message ?: "Google authorization failed.")
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Refresh people with access") }
        if (profileParticipants.isNotEmpty()) {
            Text("People with access", style = MaterialTheme.typography.labelLarge)
            profileParticipants.forEach { participant ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    val participantEmail = participant.emailAddress
                    Text(
                        buildString {
                            append(
                                participantEmail
                                    ?: if (participant.isCurrentDevice) "You (this identity)"
                                    else "Key ${participant.keyId.take(10)}…",
                            )
                            append(" · ")
                            append(
                                participant.datasetRoles?.entries?.joinToString(" · ") { entry ->
                                    "${com.easybc.planner.sync.shared.datasetPartLabel(entry.key)} " +
                                        "(${entry.value})"
                                } ?: participant.role,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (participant.role != "owner" && !participant.isCurrentDevice) {
                        com.easybc.planner.ui.kit.EbTrustBadge(
                            if (participant.accountVerified) {
                                com.easybc.planner.ui.kit.EbTrust.VERIFIED
                            } else {
                                com.easybc.planner.ui.kit.EbTrust.INVITE
                            },
                        )
                    }
                    if (
                        participant.role != "owner" &&
                        !participant.isCurrentDevice &&
                        (vm.activeProfile()?.role == "owner" || vm.activeProfile()?.role == "admin")
                    ) {
                        if (participantEmail.isNullOrBlank()) {
                            Text(
                                "Email is unknown on this device, so EasyBC cannot safely update the Drive ACL here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (participant.datasetRoles != null) {
                            // Split profile: per-dataset access grid.
                            val expanded = expandedParticipant == participant.keyId
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        expandedParticipant =
                                            if (expanded) null else participant.keyId
                                    },
                                    enabled = !busy,
                                ) { Text(if (expanded) "Done" else "Manage access") }
                                TextButton(
                                    onClick = {
                                        participantRevokeConfirm = participant.keyId to participantEmail
                                    },
                                    enabled = !busy,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) { Text("Remove") }
                            }
                            if (expanded) {
                                com.easybc.planner.sync.shared.DATASET_PARTS.forEach { part ->
                                    val role = participant.datasetRoles?.get(part)
                                    val has = role != null
                                    com.easybc.planner.ui.kit.EbDatasetRow(
                                        dataset = when (part) {
                                            com.easybc.planner.sync.shared.PART_CYCLE ->
                                                com.easybc.planner.ui.kit.EbDataset.CYCLE
                                            com.easybc.planner.sync.shared.PART_INTIMACY ->
                                                com.easybc.planner.ui.kit.EbDataset.INTIMACY
                                            com.easybc.planner.sync.shared.PART_SENSITIVE ->
                                                com.easybc.planner.ui.kit.EbDataset.SENSITIVE
                                            else -> com.easybc.planner.ui.kit.EbDataset.PLAN
                                        },
                                        title = com.easybc.planner.sync.shared.datasetPartLabel(part),
                                        summary = com.easybc.planner.sync.shared.datasetPartSummary(part),
                                        modifier = Modifier.padding(vertical = 2.dp),
                                        trailing = {
                                            com.easybc.planner.ui.kit.EbAccessSegmented(
                                                value = when (role) {
                                                    "writer", "admin", "owner" ->
                                                        com.easybc.planner.ui.kit.EbAccessLevel.EDIT
                                                    "viewer" ->
                                                        com.easybc.planner.ui.kit.EbAccessLevel.VIEW
                                                    else ->
                                                        com.easybc.planner.ui.kit.EbAccessLevel.NONE
                                                },
                                                enabled = !busy && has,
                                                onChange = { level ->
                                                    authorizeAndChangeParticipantDatasetRole(
                                                        participant.keyId,
                                                        participantEmail,
                                                        part,
                                                        when (level) {
                                                            com.easybc.planner.ui.kit.EbAccessLevel.NONE -> "none"
                                                            com.easybc.planner.ui.kit.EbAccessLevel.VIEW -> "viewer"
                                                            com.easybc.planner.ui.kit.EbAccessLevel.EDIT -> "writer"
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                                Text(
                                    "To add a dataset this person has never received, invite " +
                                        "them again with that dataset — sharing can't add a file " +
                                        "they hold no key for.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (participant.role != "viewer") {
                                    TextButton(
                                        onClick = {
                                            authorizeAndChangeParticipantRole(
                                                participant.keyId,
                                                participantEmail,
                                                "viewer",
                                            )
                                        },
                                        enabled = !busy,
                                    ) { Text("Make viewer") }
                                }
                                if (participant.role != "writer") {
                                    TextButton(
                                        onClick = {
                                            authorizeAndChangeParticipantRole(
                                                participant.keyId,
                                                participantEmail,
                                                "writer",
                                            )
                                        },
                                        enabled = !busy,
                                    ) { Text("Make writer") }
                                }
                                TextButton(
                                    onClick = {
                                        participantRevokeConfirm = participant.keyId to participantEmail
                                    },
                                    enabled = !busy,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
        if (
            sharedState != null &&
            (vm.activeProfile()?.role == "owner" || vm.activeProfile()?.role == "admin")
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = inviteEmail,
                onValueChange = { inviteEmail = it },
                label = { Text("Invite email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            val inviteProfileIsSplit = vm.activeProfile()
                ?.let { com.easybc.planner.sync.shared.isSplitProfile(it) } == true
            if (inviteProfileIsSplit) {
                Text("What can they see?", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.easybc.planner.sync.shared.SHARING_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = invitePresetId == preset.id,
                            onClick = { invitePresetId = preset.id },
                            label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    FilterChip(
                        selected = invitePresetId == "custom",
                        onClick = { invitePresetId = "custom" },
                        label = { Text("Custom…", style = MaterialTheme.typography.labelSmall) },
                    )
                }
                if (invitePresetId == "custom") {
                    com.easybc.planner.sync.shared.DATASET_PARTS.forEach { part ->
                        val role = customGrants[part]
                        com.easybc.planner.ui.kit.EbDatasetRow(
                            dataset = when (part) {
                                com.easybc.planner.sync.shared.PART_CYCLE ->
                                    com.easybc.planner.ui.kit.EbDataset.CYCLE
                                com.easybc.planner.sync.shared.PART_INTIMACY ->
                                    com.easybc.planner.ui.kit.EbDataset.INTIMACY
                                com.easybc.planner.sync.shared.PART_SENSITIVE ->
                                    com.easybc.planner.ui.kit.EbDataset.SENSITIVE
                                else -> com.easybc.planner.ui.kit.EbDataset.PLAN
                            },
                            title = com.easybc.planner.sync.shared.datasetPartLabel(part),
                            summary = com.easybc.planner.sync.shared.datasetPartSummary(part),
                            modifier = Modifier.padding(vertical = 2.dp),
                            trailing = {
                                com.easybc.planner.ui.kit.EbAccessSegmented(
                                    value = when (role) {
                                        "writer", "admin", "owner" ->
                                            com.easybc.planner.ui.kit.EbAccessLevel.EDIT
                                        "viewer" -> com.easybc.planner.ui.kit.EbAccessLevel.VIEW
                                        else -> com.easybc.planner.ui.kit.EbAccessLevel.NONE
                                    },
                                    enabled = !busy,
                                    onChange = { level ->
                                        customGrants = customGrants.toMutableMap().apply {
                                            when (level) {
                                                com.easybc.planner.ui.kit.EbAccessLevel.NONE -> remove(part)
                                                com.easybc.planner.ui.kit.EbAccessLevel.VIEW -> put(part, "viewer")
                                                com.easybc.planner.ui.kit.EbAccessLevel.EDIT -> put(part, "writer")
                                            }
                                        }
                                    },
                                )
                            },
                        )
                    }
                } else {
                    Text(
                        com.easybc.planner.sync.shared.SHARING_PRESETS
                            .firstOrNull { it.id == invitePresetId }?.description.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { inviteRole = "viewer" },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (inviteRole == "viewer") "● Viewer" else "Viewer") }
                    OutlinedButton(
                        onClick = { inviteRole = "writer" },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (inviteRole == "writer") "● Writer" else "Writer") }
                }
                Text(
                    "This profile predates per-dataset sharing — invites cover everything in it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = {
                    if (inviteEmail.isBlank()) return@OutlinedButton
                    val presetGrants = when {
                        !inviteProfileIsSplit -> null
                        invitePresetId == "custom" -> customGrants
                        else -> com.easybc.planner.sync.shared.SHARING_PRESETS
                            .firstOrNull { it.id == invitePresetId }?.grants
                    }
                    if (inviteProfileIsSplit && presetGrants.isNullOrEmpty()) {
                        vm.cloudError("Pick at least one dataset to share.")
                        return@OutlinedButton
                    }
                    val effectiveRole = presetGrants
                        ?.let { com.easybc.planner.sync.shared.highestGrantedRole(it) }
                        ?: inviteRole
                    scope.launch {
                        try {
                            when (val step = vm.beginCloudAuthorization(activity)) {
                                is AuthorizationStep.Authorized ->
                                    vm.inviteParticipant(
                                        step.accessToken,
                                        inviteEmail,
                                        effectiveRole,
                                        presetGrants,
                                    )
                                is AuthorizationStep.NeedsResolution -> {
                                    pendingInvite = Triple(inviteEmail, effectiveRole, presetGrants)
                                    resolutionLauncher.launch(
                                        IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                                    )
                                }
                            }
                        } catch (error: Exception) {
                            vm.cloudError(error.message ?: "Google authorization failed.")
                        }
                    }
                },
                enabled = !busy && inviteEmail.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Invite by email") }
            joinUrl?.let { url ->
                Text(
                    "Google's share email is often filtered as spam — send this join link " +
                        "to the invitee directly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    IconButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
                        Icon(Icons.Default.ContentCopy, "Copy join link")
                    }
                    IconButton(onClick = {
                        activity.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                },
                                "Share join link",
                            ),
                        )
                    }) {
                        Icon(Icons.Default.Share, "Share join link")
                    }
                }
            }
        }
    }
    // The setup/enable/migrate/reset entry points now live in the mode
    // selector cards and contextual banners at the top of this screen.

    // ── Danger zone — every action names its blast radius.
    Spacer(Modifier.height(8.dp))
    com.easybc.planner.ui.kit.EbExpanderRow(
        label = "Danger zone — disconnect, reset, delete",
        tone = com.easybc.planner.ui.kit.EbExpanderTone.DANGER,
        expanded = dangerZoneOpen,
        onToggle = { dangerZoneOpen = !dangerZoneOpen },
    ) {
        if (selectedIsLocal) {
            if ((sharedState?.profiles?.size ?: 0) > 1) {
                com.easybc.planner.ui.kit.EbDangerTextButton(
                    label = "Delete local profile (this device only)",
                    onClick = { profileActionConfirm = "delete-local" },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            com.easybc.planner.ui.kit.EbDangerTextButton(
                label = "Keep local copy & disconnect (cloud copy untouched)",
                onClick = { profileActionConfirm = "disconnect" },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!selectedIsLocal && vm.activeProfile()?.role == "owner") {
            com.easybc.planner.ui.kit.EbDangerTextButton(
                label = "Reset encrypted sync (replaces the Drive copy)",
                onClick = { confirming = CloudSyncOperation.RESET },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // Joining a profile someone shared with you is always available — with or
    // without your own encrypted sync — so it lives in its own subsection.
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text("Join a shared profile", style = MaterialTheme.typography.labelLarge)
    Text(
        "Paste a join link someone sent you. First grant access to the shared " +
            "*.sync-kit.json file in the browser, then join here. EasyBC keeps it separate " +
            "from every other profile on this device and saves existing local data as My data.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = joinLinkInput,
        onValueChange = { joinLinkInput = it },
        label = { Text("Join link") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    TextButton(
        onClick = {
            val grantUrl = android.net.Uri.parse(joinLinkInput.trim())
                .buildUpon()
                .appendQueryParameter("grant-files", "1")
                .build()
                .toString()
            if (!com.easybc.planner.util.launchGrantInBrowser(activity, grantUrl)) {
                clipboard.setText(AnnotatedString(grantUrl))
                vm.cloudError(
                    "No browser was available. The file-access link was copied; paste it " +
                        "into Chrome, select the shared sync file, then return here.",
                )
            }
        },
        enabled = !busy && joinLinkInput.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Grant shared file access (opens browser)") }
    OutlinedButton(
        onClick = { authorizeAndJoinLink(joinLinkInput.trim()) },
        enabled = !busy && joinLinkInput.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Join shared profile") }

    // The response link a join produced (in-app or via a deep link) to send back.
    (responseLink ?: deepLinkResponse)?.let { link ->
        Spacer(Modifier.height(8.dp))
        Text(
            "Send this response link back to the owner to finish joining:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                link,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(link)) }) {
                Icon(Icons.Default.ContentCopy, "Copy response link")
            }
            IconButton(onClick = {
                activity.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                        },
                        "Share response link",
                    ),
                )
            }) { Icon(Icons.Default.Share, "Share response link") }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("Finish a share you sent", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
        value = responseLinkInput,
        onValueChange = { responseLinkInput = it },
        label = { Text("Response link") },
        placeholder = { Text("Paste the response link the recipient sent back") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedButton(
        onClick = { authorizeAndAcceptLink(responseLinkInput.trim()) },
        enabled = !busy && responseLinkInput.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Accept response link") }
    StatusRow(status = status, onDismiss = vm::dismissCloudStatus)
    Text(
        "Encrypted sync locks after EasyBC has been in the background for 15 minutes or its process ends.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    participantRevokeConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { participantRevokeConfirm = null },
            title = { Text("Remove this participant?") },
            text = {
                Text(
                    "EasyBC will remove this participant from future encrypted revisions and remove " +
                        "their direct Google Drive access to this profile file. This cannot remove " +
                        "copies they already downloaded.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        participantRevokeConfirm = null
                        authorizeAndRevokeParticipant(target.first, target.second)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { participantRevokeConfirm = null }) { Text("Cancel") }
            },
        )
    }

    if (migrationBeginConfirm) {
        AlertDialog(
            onDismissRequest = { migrationBeginConfirm = false },
            title = { Text("Reorganize this profile into per-dataset files?") },
            text = {
                Text(
                    "Everyone keeps access according to your choices — no re-invites. " +
                        "Their app will ask them to reselect the new files in Google; " +
                        "their edits pause until they do. The old file stays until " +
                        "everyone confirms, then moves to Drive's trash.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        migrationBeginConfirm = false
                        migrationSetupOpen = false
                        authorizeAndBeginMigration(migrationGrants)
                    },
                ) { Text("Start upgrade") }
            },
            dismissButton = {
                TextButton(onClick = { migrationBeginConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (migrationCloseConfirm) {
        AlertDialog(
            onDismissRequest = { migrationCloseConfirm = false },
            title = { Text("Finish the upgrade?") },
            text = {
                Text(
                    "The old combined file moves to your Drive trash (recoverable for " +
                        "about 30 days). Everyone is already on the new files.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        migrationCloseConfirm = false
                        authorizeAndCloseMigration()
                    },
                ) { Text("Finish") }
            },
            dismissButton = {
                TextButton(onClick = { migrationCloseConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (splitUpgradeConfirm) {
        AlertDialog(
            onDismissRequest = { splitUpgradeConfirm = false },
            title = { Text("Upgrade to per-dataset sharing?") },
            text = {
                Text(
                    "Your data is split into four encrypted files (plan, cycle, intimacy, " +
                        "sensitive), each with fresh keys, and the old single cloud file is " +
                        "replaced. Your data is merged and preserved. Other devices signed " +
                        "into this profile pick the change up on their next sync.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        splitUpgradeConfirm = false
                        authorizeAndUpgradeSplit()
                    },
                ) { Text("Upgrade") }
            },
            dismissButton = {
                TextButton(onClick = { splitUpgradeConfirm = false }) { Text("Cancel") }
            },
        )
    }

    profileActionConfirm?.let { action ->
        AlertDialog(
            onDismissRequest = { profileActionConfirm = null },
            title = {
                Text(if (action == "disconnect") "Disconnect this profile?" else "Delete this profile?")
            },
            text = {
                Text(
                    if (action == "disconnect") {
                        "EasyBC will keep the current data as a local-only profile on this device. " +
                            "The encrypted cloud copy and other participants are not changed."
                    } else {
                        "This local-only profile will be deleted from this device. This cannot be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val key = sharedState?.activeProfileKey
                        if (action == "disconnect") {
                            vm.disconnectActiveProfileToLocal()
                        } else if (key != null) {
                            val state = sharedState
                            val fallbackEncrypted = state?.profiles?.any {
                                com.easybc.planner.sync.shared.profileKey(
                                    it.ownerEmail,
                                    it.datasetId,
                                ) != key && !com.easybc.planner.sync.shared.isLocalProfile(it)
                            } == true && state.profiles.none {
                                com.easybc.planner.sync.shared.profileKey(
                                    it.ownerEmail,
                                    it.datasetId,
                                ) != key && com.easybc.planner.sync.shared.isLocalProfile(it)
                            }
                            if (!fallbackEncrypted) {
                                vm.deleteProfile(null, key, false)
                            } else {
                                scope.launch {
                                    try {
                                        when (val step = vm.beginCloudAuthorization(activity)) {
                                            is AuthorizationStep.Authorized ->
                                                vm.deleteProfile(step.accessToken, key, false)
                                            is AuthorizationStep.NeedsResolution -> {
                                                pendingProfileDeleteKey = key
                                                resolutionLauncher.launch(
                                                    IntentSenderRequest.Builder(
                                                        step.pendingIntent.intentSender,
                                                    ).build(),
                                                )
                                            }
                                        }
                                    } catch (error: Exception) {
                                        vm.cloudError(
                                            error.message ?: "Google authorization failed.",
                                        )
                                    }
                                }
                            }
                        }
                        profileActionConfirm = null
                    },
                ) { Text(if (action == "disconnect") "Keep local copy" else "Delete") }
            },
            dismissButton = {
                TextButton(onClick = { profileActionConfirm = null }) { Text("Cancel") }
            },
        )
    }

    confirming?.let { operation ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(if (operation == CloudSyncOperation.RESET) "Replace the encrypted cloud copy?" else "Stop encrypted sync on this device?")
            },
            text = {
                Text(
                    if (operation == CloudSyncOperation.RESET) {
                        "This deletes the encrypted Drive snapshot — including one this device can't unlock — and recreates it from this device's local data. Your sharing identity (in Drive app data) is kept, so your other devices stay in sync."
                    } else {
                        "This disconnects encrypted sync on this device only. Your data stays on this device, and the encrypted cloud copy and your other devices are unaffected. You can set up or join again later."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    authorizeAndRun(operation)
                }) { Text(if (operation == CloudSyncOperation.RESET) "Replace" else "Stop syncing") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }
}

internal fun formatSyncTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value)


/**
 * Opt-in daily reminder. The notification always prompts about **yesterday**
 * because we can't know what happened today until it's fully elapsed —
 * hence the morning-after default time and the copy below.
 */
@Composable
internal fun ReminderSection(vm: SettingsViewModel) {
    val saved by vm.settings.collectAsState()
    val enabled = saved?.reminderEnabled == true
    val hour = saved?.reminderHour ?: 9
    val minute = saved?.reminderMinute ?: 0

    // Android 13+ requires POST_NOTIFICATIONS at runtime. Older API levels
    // don't surface a launcher at all — the permission is implicitly granted.
    var pendingEnableAfterPermission by remember { mutableStateOf(false) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingEnableAfterPermission) {
            vm.setReminderEnabled(true)
        }
        pendingEnableAfterPermission = false
    }

    Text(
        "Ask me each morning to confirm what actually happened yesterday. " +
            "Keeps the plan accurate without any daily effort from you — " +
            "the notification only fires about a day that's already complete.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Daily reconcile reminder", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (enabled) "On — asks about the previous day."
                else "Off — no notifications are scheduled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { wantOn ->
                if (!wantOn) {
                    vm.setReminderEnabled(false)
                    return@Switch
                }
                // Android 13+: request POST_NOTIFICATIONS before scheduling.
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    pendingEnableAfterPermission = true
                    notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    vm.setReminderEnabled(true)
                }
            },
        )
    }

    if (enabled) {
        Spacer(Modifier.height(8.dp))
        Text("Time of day", style = MaterialTheme.typography.labelLarge)
        IntField(
            label = "Hour (0–23, 24-hour clock)",
            value = hour,
            range = 0..23,
            onValueChange = { h -> vm.setReminderTime(h, minute) },
        )
        IntField(
            label = "Minute",
            value = minute,
            range = 0..59,
            onValueChange = { m -> vm.setReminderTime(hour, m) },
        )
        Text(
            "Alarm uses inexact scheduling (±10 min) to avoid draining your " +
                "battery. Reboot your phone and the alarm re-arms itself.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DeviceCalendarSection(vm: SettingsViewModel) {
    val status by vm.calendarStatus.collectAsState()
    val saved by vm.settings.collectAsState()
    val syncEnabled = saved?.calendarSyncEnabled == true

    // "enable-after-permission" trampoline: if the user flips the switch on
    // before granting calendar permission, we remember the intent and turn
    // it on as soon as the grant comes back.
    var pendingEnableAfterPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ok = grants[Manifest.permission.READ_CALENDAR] == true &&
            grants[Manifest.permission.WRITE_CALENDAR] == true
        if (ok) {
            if (pendingEnableAfterPermission) vm.setCalendarSyncEnabled(true)
            else vm.syncCalendar()
        }
        pendingEnableAfterPermission = false
    }

    fun ensurePermissionThen(block: () -> Unit) {
        if (vm.calendarPermissionGranted()) {
            block()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                ),
            )
        }
    }

    Text(
        "Write and update an \"EasyBC Planner\" calendar on this device from your " +
            "logged periods, predicted cycles, fertile windows, and daily " +
            "planner recommendations. It can update automatically whenever your " +
            "data changes. No data leaves your phone — sharing it onward to " +
            "Google Calendar etc. is your call.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    // Primary toggle — owns the persistent on/off switch.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Automatically update device calendar", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (syncEnabled) "Device-calendar events update automatically."
                else "Off — turn on to create the calendar and keep it fresh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = syncEnabled,
            onCheckedChange = { wantOn ->
                if (wantOn) {
                    if (vm.calendarPermissionGranted()) {
                        vm.setCalendarSyncEnabled(true)
                    } else {
                        pendingEnableAfterPermission = true
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR,
                            ),
                        )
                    }
                } else {
                    vm.setCalendarSyncEnabled(false)
                }
            },
        )
    }
    if (syncEnabled) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Using Google Calendar? Events live in a local \"EasyBC Planner\" " +
                "calendar that Google Calendar hides by default. Open Google " +
                "Calendar → menu → Settings → tap \"EasyBC Planner\" and turn " +
                "on calendar visibility / show in calendar list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { ensurePermissionThen { vm.syncCalendar() } },
            enabled = status !is SettingsViewModel.SyncStatus.Running,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Sync, null)
            Spacer(Modifier.width(8.dp))
            Text("Update device calendar now")
        }
        OutlinedButton(
            onClick = { vm.removeCalendar() },
            enabled = status !is SettingsViewModel.SyncStatus.Running,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Delete, null)
            Spacer(Modifier.width(8.dp))
            Text("Remove device calendar")
        }
    }

    StatusRow(status = status, onDismiss = { vm.dismissCalendarStatus() })

    // Privacy: customizable event labels.
    Spacer(Modifier.height(8.dp))
    val draft by vm.draft.collectAsState()
    var showLabels by remember { mutableStateOf(false) }
    TextButton(
        onClick = { showLabels = !showLabels },
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Text(if (showLabels) "Hide event labels" else "Customize event labels")
    }
    if (showLabels) {
        Text(
            "These are the exact strings the device calendar shows for each " +
                "kind of event. Defaults are single letters so a glance at " +
                "your calendar by someone else doesn't reveal anything — " +
                "change or blank them however you like. Edits apply on the " +
                "next device-calendar update.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LabelField("Period (logged or predicted)", draft.calendarLabelPeriod) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelPeriod = v) }
        }
        LabelField("Fertile window", draft.calendarLabelFertile) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelFertile = v) }
        }
        LabelField("Plan action: U (unprotected)", draft.calendarLabelActionU) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelActionU = v) }
        }
        LabelField("Plan action: C (protected)", draft.calendarLabelActionC) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelActionC = v) }
        }
        LabelField("Plan action: A (abstain)", draft.calendarLabelActionA) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelActionA = v) }
        }
        LabelField("Plan action: W (withdrawal)", draft.calendarLabelActionW) { v ->
            vm.updateDraft { d -> d.copy(calendarLabelActionW = v) }
        }
        Text(
            "Remember to tap Save at the top of the screen, then Update " +
                "device calendar now to write the new labels to your device calendar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabelField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
internal fun BackupRestoreSection(vm: SettingsViewModel) {
    val status by vm.backupStatus.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(DataBackup.MIME_TYPE),
    ) { uri -> uri?.let { vm.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importBackup(it) } }

    var confirmImport by remember { mutableStateOf(false) }

    Text(
        "Save all your cycle data, planner settings, and day logs to a backup " +
            "JSON file, or restore from one. Use this to move between devices — " +
            "no account required. The backup file is not encrypted, so keep " +
            "it private. Importing a backup file replaces everything on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { exportLauncher.launch(vm.defaultBackupFilename()) },
            enabled = status !is SettingsViewModel.SyncStatus.Running,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Upload, null)
            Spacer(Modifier.width(8.dp))
            Text("Export backup file")
        }
        OutlinedButton(
            onClick = { confirmImport = true },
            enabled = status !is SettingsViewModel.SyncStatus.Running,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Download, null)
            Spacer(Modifier.width(8.dp))
            Text("Import backup file")
        }
    }

    StatusRow(status = status, onDismiss = { vm.dismissBackupStatus() })

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Import backup file and replace all data?") },
            text = {
                Text(
                    "Importing a backup file wipes this device's period logs, day " +
                        "logs, and settings and replaces them with the backup. " +
                        "Your device calendar will not be changed — use \"Update " +
                        "device calendar now\" afterward to write the imported data to your " +
                        "device calendar.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    importLauncher.launch(arrayOf(DataBackup.MIME_TYPE, "*/*"))
                }) { Text("Choose backup file") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusRow(
    status: SettingsViewModel.SyncStatus,
    onDismiss: () -> Unit,
) {
    when (status) {
        is SettingsViewModel.SyncStatus.Running -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            }
        }
        is SettingsViewModel.SyncStatus.Success -> {
            AssistChip(
                onClick = onDismiss,
                label = { Text(status.message, style = MaterialTheme.typography.labelSmall) },
            )
        }
        is SettingsViewModel.SyncStatus.Error -> {
            AssistChip(
                onClick = onDismiss,
                label = {
                    Text(
                        status.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
        SettingsViewModel.SyncStatus.Idle -> { /* no-op */ }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider()
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun IntField(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toIntOrNull()?.let { v ->
                if (v in range) onValueChange(v)
            }
        },
        label = { Text(label) },
        supportingText = { Text("${range.first}–${range.last}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
internal fun DoubleField(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onValueChange: (Double) -> Unit,
) {
    var text by remember(value) { mutableStateOf("%.2f".format(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toDoubleOrNull()?.let { v ->
                if (v in range) onValueChange(v)
            }
        },
        label = { Text(label) },
        supportingText = { Text("${"%.2f".format(range.start)}–${"%.2f".format(range.endInclusive)}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
internal fun SliderField(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    format: (Double) -> String,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(format(value), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat().coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}
