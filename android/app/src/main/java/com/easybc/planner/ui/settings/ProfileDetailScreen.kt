package com.easybc.planner.ui.settings

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.sync.CloudSyncOperation
import com.easybc.planner.sync.shared.DATASET_PARTS
import com.easybc.planner.sync.shared.PART_CYCLE
import com.easybc.planner.sync.shared.PART_INTIMACY
import com.easybc.planner.sync.shared.PART_SENSITIVE
import com.easybc.planner.sync.shared.SHARING_PRESETS
import com.easybc.planner.sync.shared.SharedSyncCoordinator
import com.easybc.planner.sync.shared.canAdministerRole
import com.easybc.planner.sync.shared.canPublishRole
import com.easybc.planner.sync.shared.datasetPartLabel
import com.easybc.planner.sync.shared.datasetPartSummary
import com.easybc.planner.sync.shared.disambiguatedProfileLabel
import com.easybc.planner.sync.shared.grantedParts
import com.easybc.planner.sync.shared.highestGrantedRole
import com.easybc.planner.sync.shared.isLocalProfile
import com.easybc.planner.sync.shared.isSplitProfile
import com.easybc.planner.sync.shared.partRole
import com.easybc.planner.sync.shared.profileKey
import com.easybc.planner.sync.shared.restrictedParts
import com.easybc.planner.ui.kit.EbAccessLevel
import com.easybc.planner.ui.kit.EbAccessSegmented
import com.easybc.planner.ui.kit.EbBanner
import com.easybc.planner.ui.kit.EbBannerTone
import com.easybc.planner.ui.kit.EbDangerTextButton
import com.easybc.planner.ui.kit.EbDataset
import com.easybc.planner.ui.kit.EbDatasetRow
import com.easybc.planner.ui.kit.EbExpanderRow
import com.easybc.planner.ui.kit.EbExpanderTone
import com.easybc.planner.ui.kit.EbGroupLabel
import com.easybc.planner.ui.kit.EbModeCard
import com.easybc.planner.ui.kit.EbPersonCard
import com.easybc.planner.ui.kit.EbPresetChip
import com.easybc.planner.ui.kit.EbProfileHeaderCard
import com.easybc.planner.ui.kit.EbStatusRow
import com.easybc.planner.ui.kit.EbStatusTone
import com.easybc.planner.ui.kit.EbStorageMode
import com.easybc.planner.ui.kit.EbTrust
import com.easybc.planner.ui.kit.EbTrustBadge

private fun datasetForPart(part: String): EbDataset = when (part) {
    PART_CYCLE -> EbDataset.CYCLE
    PART_INTIMACY -> EbDataset.INTIMACY
    PART_SENSITIVE -> EbDataset.SENSITIVE
    else -> EbDataset.PLAN
}

private fun accessLevelForRole(role: String?): EbAccessLevel = when (role?.lowercase()) {
    "owner", "admin", "writer" -> EbAccessLevel.EDIT
    "viewer" -> EbAccessLevel.VIEW
    else -> EbAccessLevel.NONE
}

/**
 * One profile, in full (docs/settings-profiles-redesign.md §5/§6): identity,
 * where it lives, who can see it and exactly what, invitations, and the
 * danger zone. Feedback has one voice — a snackbar via [CloudStatusSnackbar]
 * plus per-button busy labels — instead of status fragments scattered around
 * the old combined screen.
 *
 * Sharing operations act on the registry's active profile, so a non-active
 * profile renders its cached summary with a "Switch to manage" gate.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileDetailScreen(
    profileKeyArg: String,
    onBack: () -> Unit,
    /** The chip stays visible even here so "whose data?" is never ambiguous. */
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val runner = rememberCloudActionRunner(vm, scope)
    val snackbar = remember { SnackbarHostState() }
    CloudStatusSnackbar(vm, snackbar)

    val status by vm.cloudStatus.collectAsState()
    val sharedState by vm.sharedSyncState.collectAsState()
    val sharedConfigured by vm.sharedSyncConfigured.collectAsState()
    val connected by vm.cloudConnected.collectAsState()
    val lastSync by vm.lastCloudSync.collectAsState()
    val participants by vm.profileParticipants.collectAsState()
    val migrationAckStatus by vm.migrationStatus.collectAsState()
    val joinUrl by vm.joinUrl.collectAsState()
    val ownershipTransferLink by vm.ownershipTransferLink.collectAsState()
    val busy = status is SettingsViewModel.SyncStatus.Running

    val state = sharedState
    val profile = state?.profiles?.firstOrNull {
        profileKey(it.ownerEmail, it.datasetId) == profileKeyArg
    }
    val isActive = state?.activeProfileKey == profileKeyArg
    val isLocal = profile?.let { isLocalProfile(it) } == true
    val isSharedWithYou = state != null && profile != null &&
        !profile.ownerEmail.equals(state.ownerEmail, ignoreCase = true)
    val canAdminister = profile != null && canAdministerRole(profile.role)
    val label = if (state != null && profile != null) {
        disambiguatedProfileLabel(state, profile)
    } else {
        "Profile"
    }

    // Dialog state — every destructive path confirms with its blast radius.
    var renameOpen by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmDeleteLocal by remember { mutableStateOf(false) }
    var confirmDeleteEverywhere by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmTransfer by remember {
        mutableStateOf<SharedSyncCoordinator.ProfileParticipant?>(null)
    }
    var confirmSplitUpgrade by remember { mutableStateOf(false) }
    var confirmMigrationBegin by remember { mutableStateOf(false) }
    var confirmMigrationClose by remember { mutableStateOf(false) }

    // Invite state.
    var inviteOpen by remember { mutableStateOf(false) }
    var inviteEmail by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf("viewer") }
    var invitePresetId by remember { mutableStateOf("cycle-only") }
    var customGrants by remember { mutableStateOf<Map<String, String>>(mapOf(PART_CYCLE to "viewer")) }

    // People state.
    var expandedParticipant by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(inviteOpen, isLocal) {
        if (inviteOpen && !isLocal) scrollState.animateScrollTo(scrollState.maxValue)
    }

    // Migration setup state (owner of a shared legacy profile).
    var migrationSetupOpen by remember { mutableStateOf(false) }
    var migrationGrants by remember { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }

    // Finish-a-share state (owner pastes/deep-links the recipient's reply).
    var responseLinkInput by remember { mutableStateOf("") }
    val pendingLinkRevision by com.easybc.planner.sync.shared.PendingSharedJoin.revision.collectAsState()
    LaunchedEffect(pendingLinkRevision) {
        com.easybc.planner.sync.shared.PendingSharedJoin.responseToAccept(activity)
            ?.let { responseLinkInput = it }
    }

    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::updateActiveProfileAvatar) }

    fun shareText(text: String, title: String) {
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                title,
            ),
        )
    }

    // Zero insets: the app-level scaffold already consumed the system bars.
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = { profileChip() },
                windowInsets = WindowInsets(0.dp),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state == null || profile == null) {
                Text(
                    "This profile is no longer on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Scaffold
            }

            // ── Identity ──
            EbProfileHeaderCard(
                name = label,
                meta = buildString {
                    append(hubProfileMeta(state, profile))
                    if (isActive) lastSync?.let { append(" · synced ${formatSyncTime(it)}") }
                },
                colorKey = profileKeyArg,
                badge = hubProfileBadge(state, profile),
                photoBase64 = profile.avatarWebp,
                actionLabel = if (isActive) null else "Switch",
                onAction = if (isActive) {
                    null
                } else {
                    {
                        val active = vm.activeProfile()
                        if (isLocal && active != null && isLocalProfile(active)) {
                            vm.switchProfile(null, profileKeyArg)
                        } else {
                            runner.run { token -> vm.switchProfile(token, profileKeyArg) }
                        }
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        renameValue = label
                        renameOpen = true
                    },
                    enabled = !busy,
                ) { Text("Rename") }
                if (isActive) {
                    TextButton(
                        onClick = {
                            avatarLauncher.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        enabled = !busy,
                    ) { Text(if (profile.avatarWebp == null) "Add photo" else "Change photo") }
                    if (profile.avatarWebp != null) {
                        TextButton(
                            onClick = { vm.updateActiveProfileAvatar(null) },
                            enabled = !busy,
                        ) { Text("Remove photo") }
                    }
                }
            }

            if (!isActive) {
                // Sharing operations run against the active profile only, so
                // everything below is a cached summary until a switch.
                EbBanner(
                    tone = EbBannerTone.INFO,
                    title = "Switch to manage",
                    text = "Storage, people, and sharing can be changed while this " +
                        "profile is active. Switching publishes your current profile first.",
                )
                EbGroupLabel("Summary")
                EbDatasetRow(
                    dataset = EbDataset.PLAN,
                    title = "Storage",
                    summary = hubProfileMeta(state, profile),
                )
                if (!isLocal) {
                    EbDatasetRow(
                        dataset = EbDataset.PLAN,
                        title = "Your access",
                        summary = when {
                            profile.role == "owner" -> "Owner"
                            canPublishRole(profile.role) -> "You can edit"
                            else -> "View only"
                        },
                    )
                    if (isSharedWithYou) {
                        EbDatasetRow(
                            dataset = EbDataset.PLAN,
                            title = "Owner",
                            summary = profile.ownerEmail,
                        )
                    } else {
                        val peopleCount = profile.participantEmails.orEmpty().size
                        EbDatasetRow(
                            dataset = EbDataset.PLAN,
                            title = "People",
                            summary = if (peopleCount == 0) {
                                "Only you"
                            } else {
                                "Shared with $peopleCount ${if (peopleCount == 1) "person" else "people"}"
                            },
                        )
                    }
                }
                DangerZone(
                    profile = profile,
                    state = state,
                    isActive = false,
                    busy = busy,
                    onDisconnect = { confirmDisconnect = true },
                    onDeleteLocal = { confirmDeleteLocal = true },
                    onDeleteEverywhere = { confirmDeleteEverywhere = true },
                    onReset = { confirmReset = true },
                )
            } else {
                // ── Where does this profile live? ──
                EbGroupLabel("Storage")
                val participantCount = profile.participantEmails.orEmpty().size
                val currentMode = when {
                    isLocal -> EbStorageMode.LOCAL
                    isSharedWithYou || participantCount > 0 -> EbStorageMode.SHARED
                    else -> EbStorageMode.PRIVATE
                }
                if (isSharedWithYou) {
                    EbBanner(
                        tone = EbBannerTone.INFO,
                        title = "Shared by ${profile.ownerEmail}",
                        text = "Your access is ${profile.role}. The owner controls storage and sharing.",
                    )
                } else {
                    EbModeCard(
                        mode = EbStorageMode.LOCAL,
                        title = "This device",
                        description = "Stays on this phone. No account needed.",
                        selected = currentMode == EbStorageMode.LOCAL,
                        pending = busy,
                        enabled = true,
                        onSelect = {
                            if (currentMode != EbStorageMode.LOCAL) confirmDisconnect = true
                        },
                    )
                    EbModeCard(
                    mode = EbStorageMode.PRIVATE,
                    title = "Private cloud",
                    description = "Encrypted in your Google Drive; your other devices " +
                        "unlock it with your passkey. Only you.",
                    selected = currentMode == EbStorageMode.PRIVATE,
                    pending = busy,
                    enabled = true,
                    onSelect = {
                        if (currentMode == EbStorageMode.LOCAL) {
                            runner.run { token ->
                                vm.runCloudOperation(activity, CloudSyncOperation.SETUP, token)
                            }
                        } else if (currentMode == EbStorageMode.SHARED) {
                            vm.cloudError(
                                "Remove everyone with access before making this profile private.",
                            )
                        }
                    },
                )
                    EbModeCard(
                    mode = EbStorageMode.SHARED,
                    title = "Shared",
                    description = "Private cloud, plus invited people can view or edit " +
                        "what you choose.",
                    selected = currentMode == EbStorageMode.SHARED,
                    pending = busy,
                    enabled = true,
                    onSelect = {
                        inviteOpen = true
                        if (currentMode == EbStorageMode.LOCAL) {
                            runner.run { token ->
                                vm.runCloudOperation(activity, CloudSyncOperation.SETUP, token)
                            }
                        }
                    },
                )
                }

                // ── Status + sync ──
                if (!isLocal) {
                    EbStatusRow(
                        tone = when {
                            busy -> EbStatusTone.BUSY
                            lastSync != null -> EbStatusTone.OK
                            else -> EbStatusTone.WARN
                        },
                        text = when {
                            busy -> "Working…"
                            lastSync != null -> "Last encrypted update ${formatSyncTime(lastSync!!)}"
                            else -> "No encrypted sync has completed on this device."
                        },
                    )
                    if (sharedConfigured) {
                        Button(
                            onClick = {
                                runner.run { token ->
                                    vm.runCloudOperation(activity, CloudSyncOperation.SYNC, token)
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Sync, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Sync now")
                        }
                    }
                    if (profile.needsInitialLoad) {
                        EbBanner(
                            tone = EbBannerTone.INFO,
                            title = "Waiting for the owner",
                            text = "After the owner accepts your reply, tap Sync now to " +
                                "load their data.",
                        )
                    }
                }

                // ── Contextual upgrade / ceremony banners ──
                if (!isLocal && profile.role == "owner" && !isSplitProfile(profile)) {
                    if (profile.participantEmails.orEmpty().isEmpty()) {
                        EbBanner(
                            tone = EbBannerTone.INFO,
                            title = "Upgrade to per-section sharing",
                            text = "Splits this profile into four encrypted files — cycle, " +
                                "plan, intimacy, sensitive — each with its own keys, so " +
                                "you can share each section separately.",
                            actionLabel = if (busy) "Upgrading…" else "Upgrade",
                            onAction = { if (!busy) confirmSplitUpgrade = true },
                        )
                    } else {
                        EbBanner(
                            tone = EbBannerTone.INFO,
                            title = "Upgrade to per-section sharing",
                            text = "Your data splits into four encrypted files so you control " +
                                "exactly what each person sees. Everyone keeps their access — " +
                                "their app asks them to reselect the new files in Google.",
                            actionLabel = if (migrationSetupOpen) "Cancel" else "Choose access…",
                            onAction = {
                                if (busy) return@EbBanner
                                if (migrationSetupOpen) {
                                    migrationSetupOpen = false
                                } else {
                                    migrationGrants = participants
                                        .filter { !it.isCurrentDevice && it.role != "owner" }
                                        .associate { participant ->
                                            participant.keyId to
                                                DATASET_PARTS.associateWith { participant.role }
                                        }
                                    migrationSetupOpen = true
                                    if (participants.none { !it.isCurrentDevice && it.role != "owner" }) {
                                        runner.run(waiting = false) { token ->
                                            vm.refreshProfileParticipants(token)
                                        }
                                    }
                                }
                            },
                        )
                        if (migrationSetupOpen) {
                            MigrationGrantsEditor(
                                participants = participants,
                                grants = migrationGrants,
                                busy = busy,
                                onGrantsChange = { migrationGrants = it },
                                onBegin = { confirmMigrationBegin = true },
                            )
                        }
                    }
                }

                if (!isLocal && profile.role == "owner" && profile.openMigrationId != null) {
                    EbBanner(
                        tone = EbBannerTone.INFO,
                        title = "Upgrade in progress",
                        text = migrationAckStatus?.let { ack ->
                            if (ack.pending.isEmpty()) {
                                "Everyone has reselected the new files — you can finish now."
                            } else {
                                "Waiting on " + ack.pending.joinToString(", ") { (keyId, email) ->
                                    email ?: "key ${keyId.take(8)}…"
                                } + " to reselect the new files."
                            }
                        } ?: "Waiting for people to reselect the new files in Google. " +
                            "Their edits pause until they do.",
                        actionLabel = if (busy) "Working…" else "Check status",
                        onAction = {
                            if (!busy) runner.run { token -> vm.refreshMigrationStatus(token) }
                        },
                    )
                    if (migrationAckStatus?.pending?.isEmpty() == true) {
                        TextButton(
                            onClick = { if (!busy) confirmMigrationClose = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Finish upgrade — move the old file to Drive's trash") }
                    }
                }

                profile.pendingMigration?.let { pending ->
                    if (!isLocal) {
                        EbBanner(
                            tone = EbBannerTone.INFO,
                            title = "${profile.ownerEmail} reorganized this profile",
                            text = "Pick the new files in Google to keep your access — " +
                                "nothing else changes, and your edits pause until you do.",
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
                                    .appendQueryParameter("owner", profile.ownerEmail)
                                    .appendQueryParameter("sk-mfiles", encoded)
                                    .build()
                                    .toString()
                                if (!com.easybc.planner.util.launchGrantInBrowser(activity, grantUrl)) {
                                    clipboard.setText(AnnotatedString(grantUrl))
                                    vm.cloudError(
                                        "No browser was available. The file-access link was " +
                                            "copied; paste it into Chrome, select the files, " +
                                            "then return here.",
                                    )
                                }
                            },
                        )
                        OutlinedButton(
                            onClick = {
                                if (!busy) {
                                    runner.run { token -> vm.acknowledgeSplitMigration(token) }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (busy) "Working…" else "I granted access — finish reorganization") }
                    }
                }

                if (!isLocal && canAdminister && profile.controlEnrollment != "enrolled") {
                    EbBanner(
                        tone = EbBannerTone.INFO,
                        title = "Set up sharing coordination",
                        text = "An encrypted control file that keeps verified membership — " +
                            "including everyone's email — in sync across devices.",
                        actionLabel = if (busy) "Setting up…" else "Set up",
                        onAction = {
                            if (!busy) runner.run { token -> vm.enrollControlDataset(token) }
                        },
                    )
                }

                // ── What this profile stores / what you can see ──
                if (!isLocal && isSplitProfile(profile)) {
                    EbGroupLabel(if (profile.role == "owner") "What this profile stores" else "What you can see")
                    DATASET_PARTS.forEach { part ->
                        val role = partRole(profile, part)
                        EbDatasetRow(
                            dataset = datasetForPart(part),
                            title = datasetPartLabel(part),
                            summary = when {
                                role == null -> "Not shared with you"
                                role == "owner" -> "Yours"
                                canPublishRole(role) -> "You can edit"
                                else -> "View only"
                            },
                        )
                    }
                    if (restrictedParts(profile).isNotEmpty()) {
                        Text(
                            "Sections that aren't shared with you stay hidden across the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ── People ──
                if (!isLocal && sharedConfigured) {
                    EbGroupLabel("People")
                    if (participants.isEmpty()) {
                        Text(
                            if (canAdminister) {
                                "Only you so far. Invite someone below — you choose exactly " +
                                    "what they can see."
                            } else {
                                "Loading people with access…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val pendingTransferToKeyId = remember(ownershipTransferLink) {
                        ownershipTransferLink?.let {
                            com.easybc.planner.sync.shared.parseOwnershipTransferLink(it)
                        }?.toKeyId
                    }
                    participants.forEach { participant ->
                        ParticipantCard(
                            participant = participant,
                            profileIsSplit = isSplitProfile(profile),
                            viewerCanAdminister = canAdminister,
                            viewerIsOwner = profile.role == "owner",
                            transferPending = participant.keyId == pendingTransferToKeyId,
                            busy = busy,
                            expanded = expandedParticipant == participant.keyId,
                            onToggleExpand = {
                                expandedParticipant =
                                    if (expandedParticipant == participant.keyId) null
                                    else participant.keyId
                            },
                            onDatasetRole = { part, level ->
                                runner.run { token ->
                                    vm.updateParticipantDatasetRole(
                                        token,
                                        participant.keyId,
                                        participant.emailAddress.orEmpty(),
                                        part,
                                        when (level) {
                                            EbAccessLevel.NONE -> "none"
                                            EbAccessLevel.VIEW -> "viewer"
                                            EbAccessLevel.EDIT -> "writer"
                                        },
                                    )
                                }
                            },
                            onRole = { role ->
                                runner.run { token ->
                                    vm.updateParticipantRole(
                                        token,
                                        participant.keyId,
                                        participant.emailAddress.orEmpty(),
                                        role,
                                    )
                                }
                            },
                            onRemove = {
                                confirmRevoke =
                                    participant.keyId to participant.emailAddress.orEmpty()
                            },
                            onTransfer = { confirmTransfer = participant },
                        )
                    }
                    ownershipTransferLink?.let { url ->
                        EbBanner(
                            tone = EbBannerTone.SUCCESS,
                            title = "Transfer link ready",
                            text = "Send it to the new owner — nothing changes until they " +
                                "accept it inside EasyBC. Google Drive also emails them " +
                                "about the file transfer, but that email alone doesn't " +
                                "finish the switch.",
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
                                Icon(Icons.Filled.ContentCopy, "Copy transfer link")
                            }
                            IconButton(onClick = { shareText(url, "Transfer profile ownership") }) {
                                Icon(Icons.Filled.Share, "Share transfer link")
                            }
                        }
                        TextButton(
                            onClick = { vm.discardOwnershipTransferLink() },
                            enabled = !busy,
                        ) { Text("Discard link") }
                        Text(
                            "Discarding only removes the link from this device. The " +
                                "pending Drive transfer stays until it's accepted or " +
                                "cancelled in Google Drive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            runner.run(waiting = false) { token ->
                                vm.refreshProfileParticipants(token)
                            }
                        },
                        enabled = !busy,
                    ) { Text("Refresh people") }

                    // ── Invite ──
                    if (canAdminister) {
                        if (!inviteOpen) {
                            Button(
                                onClick = { inviteOpen = true },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.PersonAdd, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Invite someone")
                            }
                        } else {
                            EbGroupLabel("Invite someone")
                            OutlinedTextField(
                                value = inviteEmail,
                                onValueChange = { inviteEmail = it },
                                label = { Text("Their Google email") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            if (isSplitProfile(profile)) {
                                Text("What can they see?", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SHARING_PRESETS.forEach { preset ->
                                        FilterChip(
                                            selected = invitePresetId == preset.id,
                                            onClick = { invitePresetId = preset.id },
                                            label = {
                                                Text(
                                                    preset.label,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                        )
                                    }
                                    FilterChip(
                                        selected = invitePresetId == "custom",
                                        onClick = { invitePresetId = "custom" },
                                        label = {
                                            Text("Custom…", style = MaterialTheme.typography.labelSmall)
                                        },
                                    )
                                }
                                if (invitePresetId == "custom") {
                                    DATASET_PARTS.forEach { part ->
                                        EbDatasetRow(
                                            dataset = datasetForPart(part),
                                            title = datasetPartLabel(part),
                                            summary = datasetPartSummary(part),
                                            trailing = {
                                                EbAccessSegmented(
                                                    value = accessLevelForRole(customGrants[part]),
                                                    enabled = !busy,
                                                    onChange = { level ->
                                                        customGrants = customGrants.toMutableMap().apply {
                                                            when (level) {
                                                                EbAccessLevel.NONE -> remove(part)
                                                                EbAccessLevel.VIEW -> put(part, "viewer")
                                                                EbAccessLevel.EDIT -> put(part, "writer")
                                                            }
                                                        }
                                                    },
                                                )
                                            },
                                        )
                                    }
                                } else {
                                    Text(
                                        SHARING_PRESETS.firstOrNull { it.id == invitePresetId }
                                            ?.description.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    EbPresetChip(
                                        label = "Viewer",
                                        selected = inviteRole == "viewer",
                                        onClick = { inviteRole = "viewer" },
                                        modifier = Modifier.weight(1f),
                                    )
                                    EbPresetChip(
                                        label = "Editor",
                                        selected = inviteRole == "writer",
                                        onClick = { inviteRole = "writer" },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Text(
                                    "This profile predates per-section sharing — invites cover " +
                                        "everything in it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { inviteOpen = false },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text("Cancel") }
                                Button(
                                    onClick = {
                                        if (inviteEmail.isBlank()) return@Button
                                        val presetGrants = when {
                                            !isSplitProfile(profile) -> null
                                            invitePresetId == "custom" -> customGrants
                                            else -> SHARING_PRESETS
                                                .firstOrNull { it.id == invitePresetId }?.grants
                                        }
                                        if (isSplitProfile(profile) && presetGrants.isNullOrEmpty()) {
                                            vm.cloudError("Pick at least one section to share.")
                                            return@Button
                                        }
                                        val effectiveRole = presetGrants
                                            ?.let { highestGrantedRole(it) }
                                            ?: inviteRole
                                        runner.run { token ->
                                            vm.inviteParticipant(
                                                token,
                                                inviteEmail,
                                                effectiveRole,
                                                presetGrants,
                                            )
                                        }
                                    },
                                    enabled = !busy && inviteEmail.isNotBlank(),
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (busy) "Inviting…" else "Create invite") }
                            }
                        }

                        joinUrl?.let { url ->
                            EbBanner(
                                tone = EbBannerTone.SUCCESS,
                                title = "Invite ready — send the join link",
                                text = "Send this link to the invitee yourself; Google's " +
                                    "share email is often filtered as spam. When they finish " +
                                    "joining, they'll send you a reply link to paste below.",
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
                                    Icon(Icons.Filled.ContentCopy, "Copy join link")
                                }
                                IconButton(onClick = { shareText(url, "Share join link") }) {
                                    Icon(Icons.Filled.Share, "Share join link")
                                }
                            }
                        }

                        // Finish a share: the recipient's reply closes the loop.
                        EbGroupLabel("Finish a share you sent")
                        OutlinedTextField(
                            value = responseLinkInput,
                            onValueChange = { responseLinkInput = it },
                            label = { Text("Reply link from the invitee") },
                            placeholder = { Text("Paste the reply link they sent back") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedButton(
                            onClick = {
                                runner.run { token ->
                                    vm.acceptResponseLink(token, responseLinkInput.trim())
                                }
                            },
                            enabled = !busy && responseLinkInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Confirm their access") }
                    }
                }

                // ── Contextual recovery ──
                if (connected && !sharedConfigured) {
                    EbBanner(
                        tone = EbBannerTone.ERROR,
                        title = "A cloud copy exists that this device can't unlock",
                        text = "Reset deletes that copy and starts fresh with this device's data.",
                        actionLabel = "Reset",
                        onAction = { if (!busy) confirmReset = true },
                    )
                }

                DangerZone(
                    profile = profile,
                    state = state,
                    isActive = true,
                    busy = busy,
                    onDisconnect = { confirmDisconnect = true },
                    onDeleteLocal = { confirmDeleteLocal = true },
                    onDeleteEverywhere = { confirmDeleteEverywhere = true },
                    onReset = { confirmReset = true },
                )
                Text(
                    "Encrypted sync locks after EasyBC has been in the background for " +
                        "15 minutes or its process ends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ── Dialogs ──
    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("Rename profile") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("Profile name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameOpen = false
                        vm.renameProfile(profileKeyArg, renameValue.trim())
                    },
                    enabled = renameValue.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Cancel") }
            },
        )
    }

    confirmRevoke?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text("Remove this person?") },
            text = {
                Text(
                    "They are removed from future encrypted revisions and their Google " +
                        "Drive access to this profile ends. This cannot remove copies " +
                        "they already downloaded.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRevoke = null
                        runner.run { token ->
                            vm.revokeParticipant(token, target.first, target.second)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevoke = null }) { Text("Cancel") }
            },
        )
    }

    confirmTransfer?.let { participant ->
        AlertDialog(
            onDismissRequest = { confirmTransfer = null },
            title = { Text("Transfer ownership?") },
            text = {
                Text(
                    "${participant.emailAddress} must accept the link. Afterward, you will remain an admin.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTransfer = null
                        runner.run { token ->
                            vm.prepareOwnershipTransfer(
                                token,
                                participant.keyId,
                                participant.emailAddress.orEmpty(),
                            )
                        }
                    },
                ) { Text("Create transfer link") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTransfer = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmSplitUpgrade) {
        AlertDialog(
            onDismissRequest = { confirmSplitUpgrade = false },
            title = { Text("Upgrade to per-section sharing?") },
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
                        confirmSplitUpgrade = false
                        runner.run { token -> vm.upgradeProfileToSplit(token) }
                    },
                ) { Text("Upgrade") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSplitUpgrade = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmMigrationBegin) {
        AlertDialog(
            onDismissRequest = { confirmMigrationBegin = false },
            title = { Text("Reorganize this profile into per-section files?") },
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
                        confirmMigrationBegin = false
                        migrationSetupOpen = false
                        runner.run { token -> vm.beginSplitMigration(token, migrationGrants) }
                    },
                ) { Text("Start upgrade") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMigrationBegin = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmMigrationClose) {
        AlertDialog(
            onDismissRequest = { confirmMigrationClose = false },
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
                        confirmMigrationClose = false
                        runner.run { token -> vm.closeSplitMigration(token) }
                    },
                ) { Text("Finish") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMigrationClose = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect this profile?") },
            text = {
                Text(
                    "EasyBC keeps the current data as a local-only profile on this device. " +
                        "The encrypted cloud copy and other participants are not changed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        vm.disconnectActiveProfileToLocal()
                    },
                ) { Text("Keep local copy") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDeleteLocal) {
        AlertDialog(
            onDismissRequest = { confirmDeleteLocal = false },
            title = { Text(if (isSharedWithYou) "Leave this shared profile?" else "Remove this profile?") },
            text = {
                Text(
                    if (isLocal) {
                        "This local-only profile is deleted from this device. This cannot be undone."
                    } else if (isSharedWithYou) {
                        "This device forgets the profile. The owner's cloud copy — and your " +
                            "access, until they remove you — is unchanged."
                    } else {
                        "This device forgets the profile. The encrypted cloud copy and your " +
                            "other devices are unchanged."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteLocal = false
                        val fallbackEncrypted = state != null && state.profiles.any {
                            profileKey(it.ownerEmail, it.datasetId) != profileKeyArg &&
                                !isLocalProfile(it)
                        } && state.profiles.none {
                            profileKey(it.ownerEmail, it.datasetId) != profileKeyArg &&
                                isLocalProfile(it)
                        }
                        if (!fallbackEncrypted) {
                            vm.deleteProfile(null, profileKeyArg, false, onBack)
                        } else {
                            runner.run { token ->
                                vm.deleteProfile(token, profileKeyArg, false, onBack)
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(if (isSharedWithYou) "Leave" else "Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteLocal = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDeleteEverywhere) {
        AlertDialog(
            onDismissRequest = { confirmDeleteEverywhere = false },
            title = { Text("Delete this profile everywhere?") },
            text = {
                Text(
                    "The encrypted files are deleted from your Google Drive, every " +
                        "participant loses access, and this device forgets the profile. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteEverywhere = false
                        runner.run { token ->
                            vm.deleteProfile(token, profileKeyArg, true, onBack)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete everywhere") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteEverywhere = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Replace the encrypted cloud copy?") },
            text = {
                Text(
                    "This deletes the encrypted Drive snapshot — including one this device " +
                        "can't unlock — and recreates it from this device's local data. Your " +
                        "sharing identity (in Drive app data) is kept, so your other devices " +
                        "stay in sync.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        runner.run { token ->
                            vm.runCloudOperation(activity, CloudSyncOperation.RESET, token)
                        }
                    },
                ) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

/** Person row: identity, trust, and — expanded — exactly what they can see. */
@Composable
private fun ParticipantCard(
    participant: SharedSyncCoordinator.ProfileParticipant,
    profileIsSplit: Boolean,
    viewerCanAdminister: Boolean,
    viewerIsOwner: Boolean,
    /** An outgoing ownership-transfer link names this person as recipient. */
    transferPending: Boolean,
    busy: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDatasetRole: (String, EbAccessLevel) -> Unit,
    onRole: (String) -> Unit,
    onRemove: () -> Unit,
    onTransfer: () -> Unit,
) {
    val email = participant.emailAddress
    val name = when {
        participant.isCurrentDevice -> "You"
        !email.isNullOrBlank() -> email.substringBefore("@")
        else -> "Unnamed key"
    }
    val roleLabel = when {
        participant.role == "owner" -> "Owner"
        participant.role == "admin" -> "Admin"
        participant.datasetRoles != null -> {
            val editable = participant.datasetRoles.orEmpty().count { canPublishRole(it.value) }
            val total = participant.datasetRoles.orEmpty().size
            when {
                editable == 0 -> "Views $total ${if (total == 1) "section" else "sections"}"
                else -> "Edits $editable of $total sections"
            }
        }
        canPublishRole(participant.role) -> "Editor"
        else -> "Viewer"
    }
    EbPersonCard(
        name = if (participant.isCurrentDevice) "$name · $roleLabel" else name,
        email = email ?: "Key ${participant.keyId.take(10)}… — email unknown",
        trust = when {
            participant.role == "owner" || participant.isCurrentDevice -> null
            participant.accountVerified -> EbTrust.VERIFIED
            else -> EbTrust.INVITE
        },
    ) {
        if (!participant.isCurrentDevice) {
            Text(
                roleLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val manageable = viewerCanAdminister &&
            participant.role != "owner" &&
            !participant.isCurrentDevice
        if (manageable && email.isNullOrBlank()) {
            Text(
                "Their email isn't in the sharing directory yet, so access can't be " +
                    "changed from this device. It fills in after the owner's next sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (manageable) {
            if (transferPending) {
                Text(
                    "Ownership transfer pending — waiting for them to accept your link.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onToggleExpand, enabled = !busy) {
                    Text(if (expanded) "Done" else "Manage access")
                }
                TextButton(
                    onClick = onRemove,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            }
            if (expanded) {
                if (participant.datasetRoles != null) {
                    DATASET_PARTS.forEach { part ->
                        val role = participant.datasetRoles?.get(part)
                        EbDatasetRow(
                            dataset = datasetForPart(part),
                            title = datasetPartLabel(part),
                            summary = datasetPartSummary(part),
                            trailing = {
                                EbAccessSegmented(
                                    value = accessLevelForRole(role),
                                    enabled = !busy && role != null,
                                    onChange = { level -> onDatasetRole(part, level) },
                                )
                            },
                        )
                    }
                    Text(
                        "To add a section this person has never received, invite them " +
                            "again with that section — sharing can't add a file they hold " +
                            "no key for.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EbPresetChip(
                            label = "Viewer",
                            selected = participant.role == "viewer",
                            onClick = { if (participant.role != "viewer") onRole("viewer") },
                            modifier = Modifier.weight(1f),
                        )
                        EbPresetChip(
                            label = "Editor",
                            selected = participant.role == "writer",
                            onClick = { if (participant.role != "writer") onRole("writer") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // Fellow admins can invite, accept replies, and change access.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Co-manager", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Can invite people, confirm replies, and change access — " +
                                "everything except deleting the profile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = participant.role == "admin",
                        onCheckedChange = { makeAdmin ->
                            onRole(if (makeAdmin) "admin" else "writer")
                        },
                        enabled = !busy,
                    )
                }
                // Rare and consequential, so it lives behind Manage access
                // rather than at card level. Requires access to every section.
                val canOwnAllDatasets = participant.datasetRoles == null ||
                    DATASET_PARTS.all { participant.datasetRoles?.containsKey(it) == true }
                if (viewerIsOwner && canOwnAllDatasets && !transferPending) {
                    TextButton(onClick = onTransfer, enabled = !busy) {
                        Text("Transfer ownership to $name…")
                    }
                }
            }
        }
    }
}

/** Owner's pre-migration access chooser: what each person sees afterwards. */
@Composable
private fun MigrationGrantsEditor(
    participants: List<SharedSyncCoordinator.ProfileParticipant>,
    grants: Map<String, Map<String, String>>,
    busy: Boolean,
    onGrantsChange: (Map<String, Map<String, String>>) -> Unit,
    onBegin: () -> Unit,
) {
    Text(
        "What each person will see after the upgrade",
        style = MaterialTheme.typography.labelLarge,
    )
    val people = participants.filter { !it.isCurrentDevice && it.role != "owner" }
    if (people.isEmpty()) {
        Text(
            "Loading people with access…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    people.forEach { participant ->
        val email = participant.emailAddress ?: "Key ${participant.keyId.take(10)}…"
        if (grants[participant.keyId] == null) {
            onGrantsChange(
                grants + (participant.keyId to DATASET_PARTS.associateWith { participant.role }),
            )
        }
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(email, style = MaterialTheme.typography.titleSmall)
            DATASET_PARTS.forEach { part ->
                val role = grants[participant.keyId]?.get(part)
                EbDatasetRow(
                    dataset = datasetForPart(part),
                    title = datasetPartLabel(part),
                    summary = datasetPartSummary(part),
                    modifier = Modifier.padding(vertical = 2.dp),
                    trailing = {
                        EbAccessSegmented(
                            value = accessLevelForRole(role),
                            enabled = !busy,
                            onChange = { level ->
                                val updated = grants[participant.keyId].orEmpty().toMutableMap()
                                when (level) {
                                    EbAccessLevel.NONE -> updated.remove(part)
                                    EbAccessLevel.VIEW -> updated[part] = "viewer"
                                    EbAccessLevel.EDIT -> updated[part] = "writer"
                                }
                                onGrantsChange(grants + (participant.keyId to updated))
                            },
                        )
                    },
                )
            }
        }
    }
    Button(
        onClick = onBegin,
        enabled = !busy && people.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (busy) "Reorganizing…" else "Start upgrade") }
    Text(
        "Creates the new files with fresh keys, shares them to everyone's existing " +
            "keys per your choices, and freezes the old file. The old file is kept " +
            "until everyone confirms, then moved to Drive's trash.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Every action names its blast radius; red stays inside the expander. */
@Composable
private fun DangerZone(
    profile: com.easybc.planner.sync.shared.ProfileRecord,
    state: com.easybc.planner.sync.shared.SharedSyncState,
    isActive: Boolean,
    busy: Boolean,
    onDisconnect: () -> Unit,
    onDeleteLocal: () -> Unit,
    onDeleteEverywhere: () -> Unit,
    onReset: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val isLocal = isLocalProfile(profile)
    val isSharedWithYou = !profile.ownerEmail.equals(state.ownerEmail, ignoreCase = true)
    Spacer(Modifier.height(4.dp))
    EbExpanderRow(
        label = "Danger zone",
        tone = EbExpanderTone.DANGER,
        expanded = open,
        onToggle = { open = !open },
    ) {
        if (isLocal) {
            if (state.profiles.size > 1) {
                EbDangerTextButton(
                    label = "Delete local profile (this device only)",
                    onClick = onDeleteLocal,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "This is the only profile on this device, so it can't be deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (isActive) {
                EbDangerTextButton(
                    label = "Keep local copy & disconnect (cloud copy untouched)",
                    onClick = onDisconnect,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.profiles.size > 1) {
                EbDangerTextButton(
                    label = if (isSharedWithYou) {
                        "Leave shared profile (this device only)"
                    } else {
                        "Remove from this device (cloud copy untouched)"
                    },
                    onClick = onDeleteLocal,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (isActive && profile.role == "owner") {
                EbDangerTextButton(
                    label = "Reset encrypted sync (replaces the Drive copy)",
                    onClick = onReset,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                EbDangerTextButton(
                    label = "Delete everywhere (removes everyone's access)",
                    onClick = onDeleteEverywhere,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
