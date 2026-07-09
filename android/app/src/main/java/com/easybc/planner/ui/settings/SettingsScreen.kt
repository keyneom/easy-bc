package com.easybc.planner.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
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
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val draft by vm.draft.collectAsState()
    val saved by vm.settings.collectAsState()
    val isFirstTime = saved?.onboardingComplete != true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isFirstTime) "Welcome — Set Up Your Profile" else "Settings") },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.save() },
                icon = { Icon(Icons.Default.Save, null) },
                text = { Text(if (isFirstTime) "Start Planning" else "Save") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isFirstTime) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Configure your profile to get a personalized cycle plan. " +
                            "All calculations are done on-device — your data stays private.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Profile ──
            SectionHeader("Profile")

            IntField("Age", draft.ageYears, 15..55) { v ->
                vm.updateDraft { d -> d.copy(ageYears = v) }
            }
            IntField("Typical cycle length (days)", draft.cycleLengthDays, 21..45) { v ->
                vm.updateDraft { d -> d.copy(cycleLengthDays = v) }
            }

            // ── Risk Target ──
            SectionHeader("Risk Target")

            SliderField(
                label = "Cumulative failure target",
                value = draft.targetCumulativeFailure,
                range = 0.005f..0.5f,
                format = { "%.1f%%".format(it * 100) },
                onValueChange = { v -> vm.updateDraft { d -> d.copy(targetCumulativeFailure = v.toDouble()) } },
            )
            IntField("Horizon (years)", draft.horizonYears, 1..40) { v ->
                vm.updateDraft { d -> d.copy(horizonYears = v) }
            }

            // ── Behavior ──
            SectionHeader("Behavior")

            DoubleField("Acts per week", draft.actsPerWeek, 0.0..14.0) { v ->
                vm.updateDraft { d -> d.copy(actsPerWeek = v) }
            }

            // ── Method Library ──
            SectionHeader("Contraceptive Methods")

            // Persistent method
            Text("Persistent / background method", style = MaterialTheme.typography.labelLarge)
            Text(
                "An always-on method that reduces baseline risk for all days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val currentPersistent = try {
                PersistentMethod.entries.first { it.name.equals(draft.persistentMethod, ignoreCase = true) }
            } catch (_: Exception) { PersistentMethod.None }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersistentMethod.entries.forEach { method ->
                    FilterChip(
                        selected = currentPersistent == method,
                        onClick = {
                            vm.updateDraft { d -> d.copy(persistentMethod = method.name.lowercase()) }
                        },
                        label = { Text(method.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Protected day method
            Text("Protected-day method", style = MaterialTheme.typography.labelLarge)
            Text(
                "Barrier method used on days marked 'C' (protected). Controls what 'condom' means in the plan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val currentProtected = try {
                ProtectedDayMethod.entries.first { it.name.equals(draft.protectedDayMethod, ignoreCase = true) }
            } catch (_: Exception) { ProtectedDayMethod.ExternalCondom }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProtectedDayMethod.entries.forEach { method ->
                    FilterChip(
                        selected = currentProtected == method,
                        onClick = {
                            vm.updateDraft { d -> d.copy(protectedDayMethod = method.name.lowercase()) }
                        },
                        label = { Text(method.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            // Condom calibration (only if protected day method is external condom)
            if (currentProtected == ProtectedDayMethod.ExternalCondom) {
                Spacer(Modifier.height(4.dp))
                Text("Condom use quality", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("perfect", "typical", "custom").forEach { mode ->
                        FilterChip(
                            selected = draft.condomMode == mode,
                            onClick = { vm.updateDraft { d -> d.copy(condomMode = mode) } },
                            label = { Text(mode.replaceFirstChar { c -> c.uppercase() }) },
                        )
                    }
                }

                if (draft.condomMode == "custom") {
                    DoubleField("Custom condom residual", draft.customCondomResidual, 0.0..1.0) { v ->
                        vm.updateDraft { d -> d.copy(customCondomResidual = v) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Withdrawal
            Text("Withdrawal", style = MaterialTheme.typography.labelLarge)
            Text(
                "If enabled, the planner can recommend withdrawal (W) on moderate-risk days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val currentWithdrawal = try {
                WithdrawalMode.entries.first { it.name.equals(draft.withdrawalMode, ignoreCase = true) }
            } catch (_: Exception) { WithdrawalMode.None }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WithdrawalMode.entries.forEach { mode ->
                    FilterChip(
                        selected = currentWithdrawal == mode,
                        onClick = {
                            vm.updateDraft { d -> d.copy(withdrawalMode = mode.name.lowercase()) }
                        },
                        label = { Text(mode.label) },
                    )
                }
            }

            if (currentWithdrawal == WithdrawalMode.Custom) {
                DoubleField("Withdrawal relative risk", draft.withdrawalRelativeRisk, 0.0..1.0) { v ->
                    vm.updateDraft { d -> d.copy(withdrawalRelativeRisk = v) }
                }
            }

            // Combined method layering
            if (currentProtected != ProtectedDayMethod.None && currentWithdrawal != WithdrawalMode.None) {
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    label = "Layer withdrawal on protected days",
                    checked = draft.useWithdrawalBackupOnProtectedDays,
                    onCheckedChange = { checked ->
                        vm.updateDraft { d -> d.copy(useWithdrawalBackupOnProtectedDays = checked) }
                    },
                )
                if (draft.useWithdrawalBackupOnProtectedDays) {
                    SliderField(
                        label = "Combined method independence",
                        value = draft.combinedMethodIndependence,
                        range = 0f..1f,
                        format = { v ->
                            when {
                                v < 0.2 -> "%.0f%% — Conservative".format(v * 100)
                                v > 0.7 -> "%.0f%% — Assumes high independence".format(v * 100)
                                else -> "%.0f%%".format(v * 100)
                            }
                        },
                        onValueChange = { v -> vm.updateDraft { d -> d.copy(combinedMethodIndependence = v.toDouble()) } },
                    )
                }
            }

            // ── Preferences ──
            SectionHeader("Preferences")

            SliderField(
                label = "Streak aversion",
                value = draft.streakAversion,
                range = 0f..1f,
                format = { pct ->
                    when {
                        pct < 0.33 -> "%.0f%% — Fewer total abstinence days".format(pct * 100)
                        pct > 0.66 -> "%.0f%% — Shorter abstinence streaks".format(pct * 100)
                        else -> "%.0f%% — Balanced".format(pct * 100)
                    }
                },
                onValueChange = { v -> vm.updateDraft { d -> d.copy(streakAversion = v.toDouble()) } },
            )

            // ── Reminders ──
            SectionHeader("Reminders")
            ReminderSection(vm)

            // ── Device Calendar Export ──
            SectionHeader("Device Calendar Export")
            DeviceCalendarSection(vm)

            // ── Encrypted cloud sync ──
            SectionHeader("Encrypted Cloud Sync")
            EncryptedSyncSection(vm)

            // ── Backup file ──
            SectionHeader("Backup File")
            BackupRestoreSection(vm)

            // ── Advanced ──
            SectionHeader("Advanced")

            DoubleField("Ovulation SD (days)", draft.ovulationSdDays, 0.5..15.0) { v ->
                vm.updateDraft { d -> d.copy(ovulationSdDays = v) }
            }

            SwitchRow(
                label = "Hold lifecycle constant",
                checked = draft.holdLifecycleConstant,
                onCheckedChange = { checked -> vm.updateDraft { d -> d.copy(holdLifecycleConstant = checked) } },
            )

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { vm.resetToDefaults() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.RestartAlt, null)
                Spacer(Modifier.width(8.dp))
                Text("Reset to defaults")
            }

            // ── Disclaimers ──
            SectionHeader("Disclaimers")

            Text(
                text = "This is not FDA-cleared as contraception. " +
                    "Calculations assume regular cycles. " +
                    "Consult a healthcare provider for medical advice. " +
                    "Plan effectiveness depends on adherence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(80.dp)) // Room for FAB
        }
    }
}

@Composable
private fun EncryptedSyncSection(vm: SettingsViewModel) {
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
    var responseLinkInput by remember { mutableStateOf("") }
    var deepLinkResponse by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    var pendingOperation by remember { mutableStateOf<CloudSyncOperation?>(null) }
    var pendingProfileKey by remember { mutableStateOf<String?>(null) }
    var pendingProfileDisplayName by remember { mutableStateOf<String?>(null) }
    var pendingProfileDeleteKey by remember { mutableStateOf<String?>(null) }
    var pendingProfileParticipantsRefresh by remember { mutableStateOf(false) }
    var pendingParticipantRoleChange by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var pendingParticipantRevoke by remember { mutableStateOf<Pair<String, String>?>(null) }
    var participantRevokeConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var newProfileName by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    var profileActionConfirm by remember { mutableStateOf<String?>(null) }
    var pendingInvite by remember { mutableStateOf<Pair<String, String>?>(null) }
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
        val participantRoleChange = pendingParticipantRoleChange
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
        pendingParticipantRoleChange = null
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
                    refreshParticipants -> vm.refreshProfileParticipants(token)
                    participantRoleChange != null ->
                        vm.updateParticipantRole(
                            token,
                            participantRoleChange.first,
                            participantRoleChange.second,
                            participantRoleChange.third,
                        )
                    participantRevoke != null ->
                        vm.revokeParticipant(token, participantRevoke.first, participantRevoke.second)
                    profileKey != null -> vm.switchProfile(token, profileKey)
                    invite != null -> vm.inviteParticipant(token, invite.first, invite.second)
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

    Text(
        "Encrypt planner settings, period records, and day logs in your own Google Drive folder " +
            "(EasyBC — you@email). Share read or write access with others by email. " +
            "Open a join link on this device to accept someone else's share.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(if (sharedConfigured) Icons.Default.Cloud else Icons.Default.Key, null)
            Column {
                Text(
                    when {
                        sharedConfigured -> "Encrypted sync enabled on this device"
                        legacyPresent -> "Legacy encrypted sync on this device"
                        else -> "Passkey-protected encrypted sync"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    when {
                        sharedConfigured || legacyPresent -> lastSync?.let { "Last encrypted update ${formatSyncTime(it)}" }
                            ?: "No encrypted sync has completed on this device."
                        else -> "No encrypted sync has completed on this device."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    val busy = status is SettingsViewModel.SyncStatus.Running
    val selectedProfile = vm.activeProfile()
    val selectedIsLocal = selectedProfile?.let {
        com.easybc.planner.sync.shared.isLocalProfile(it)
    } == true
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
            if (com.easybc.planner.sync.shared.isLocalProfile(profile)) {
                if (state.profiles.size > 1) {
                    TextButton(
                        onClick = { profileActionConfirm = "delete-local" },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete local profile") }
                }
            } else {
                OutlinedButton(
                    onClick = { profileActionConfirm = "disconnect" },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Keep local copy & disconnect") }
            }
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
        Button(
            onClick = { authorizeAndRun(CloudSyncOperation.SYNC) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Sync, null)
            Spacer(Modifier.width(8.dp))
            Text("Merge encrypted changes")
        }
        Spacer(Modifier.height(8.dp))
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
                            append(participant.role)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
            OutlinedButton(
                onClick = {
                    if (inviteEmail.isBlank()) return@OutlinedButton
                    scope.launch {
                        try {
                            when (val step = vm.beginCloudAuthorization(activity)) {
                                is AuthorizationStep.Authorized ->
                                    vm.inviteParticipant(step.accessToken, inviteEmail, inviteRole)
                                is AuthorizationStep.NeedsResolution -> {
                                    pendingInvite = inviteEmail to inviteRole
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
        if (vm.activeProfile()?.role == "owner") {
            OutlinedButton(
                onClick = { confirming = CloudSyncOperation.RESET },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset encrypted sync") }
        }
    } else if (selectedIsLocal) {
        Button(
            onClick = { authorizeAndRun(CloudSyncOperation.SETUP) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Key, null)
            Spacer(Modifier.width(8.dp))
            Text("Enable private encrypted sync")
        }
    } else if (legacyPresent) {
        // This device still has legacy snapshot metadata: migrating is the
        // one correct action, so it is the only one offered.
        Button(
            onClick = { authorizeAndRun(CloudSyncOperation.ENABLE) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Sync, null)
            Spacer(Modifier.width(8.dp))
            Text("Migrate legacy encrypted sync")
        }
        Text(
            "This device has records from the older encrypted sync. Migrating merges them " +
                "into the current format — nothing is lost.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Button(
            onClick = { authorizeAndRun(CloudSyncOperation.SETUP) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Key, null)
            Spacer(Modifier.width(8.dp))
            Text("Set up encrypted sync")
        }
    }
    if (connected && !sharedConfigured) {
        // Escape hatch for an un-adoptable cloud copy (a dataset owned by an
        // identity this device can't produce — e.g. an orphan from before the
        // app-data identity scheme). "Set up" keeps failing on it; Reset deletes
        // it and recreates from this device's data.
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { confirming = CloudSyncOperation.RESET },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset encrypted sync") }
        Text(
            "If setup says a cloud copy already exists that this device can't " +
                "unlock, Reset deletes that copy and starts fresh with this " +
                "device's data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

private fun formatSyncTime(value: String): String = runCatching {
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
private fun ReminderSection(vm: SettingsViewModel) {
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
private fun DeviceCalendarSection(vm: SettingsViewModel) {
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
private fun BackupRestoreSection(vm: SettingsViewModel) {
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
private fun SwitchRow(
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
private fun IntField(
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
private fun DoubleField(
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
private fun SliderField(
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
