package com.easybc.planner.ui.settings

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.sync.shared.CONTROL_DATASET_SUFFIX
import com.easybc.planner.sync.shared.PendingSharedJoin
import com.easybc.planner.sync.shared.canPublishRole
import com.easybc.planner.sync.shared.datasetPartLabel
import com.easybc.planner.sync.shared.grantsFromRequestedGrants
import com.easybc.planner.sync.shared.parseSharedJoinLink
import com.easybc.planner.ui.kit.EbDataset
import com.easybc.planner.ui.kit.JoinFlowFile
import com.easybc.planner.ui.kit.JoinFlowScreen
import com.easybc.planner.ui.kit.JoinFlowUiState
import com.keyneom.synckit.sharing.parseSharingJoinLinkV1

/**
 * Joining a profile someone shared with you — reached from Manage profiles,
 * because a join *adds a profile to your list*; it is not a property of any
 * profile you already have (docs/settings-profiles-redesign.md §4).
 *
 * The guided flow (docs/join-flow.md, [JoinFlowScreen]) shows what's being
 * offered before anything is granted: owner, sections, and your access per
 * section, decoded from the link itself. Deep links land here with the link
 * prefilled; `sk-granted=1` returns from the browser continue automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinProfileScreen(
    onBack: () -> Unit,
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val runner = rememberCloudActionRunner(vm, scope)
    val snackbar = remember { SnackbarHostState() }
    CloudStatusSnackbar(vm, snackbar)

    val status by vm.cloudStatus.collectAsState()
    val responseLink by vm.responseLink.collectAsState()
    val busy = status is SettingsViewModel.SyncStatus.Running

    var joinLinkInput by remember { mutableStateOf("") }
    var producedResponse by remember { mutableStateOf<String?>(null) }
    var grantOpened by remember { mutableStateOf(false) }
    var autoJoinAttempted by remember { mutableStateOf(false) }

    val pendingLinkRevision by PendingSharedJoin.revision.collectAsState()
    LaunchedEffect(pendingLinkRevision) {
        PendingSharedJoin.joinLink(activity)?.let { joinLinkInput = it }
        PendingSharedJoin.producedResponse(activity)?.let { producedResponse = it }
    }

    val trimmedLink = joinLinkInput.trim()
    val parsedV1 = remember(trimmedLink) {
        runCatching { parseSharingJoinLinkV1(trimmedLink) }.getOrNull()
    }
    val parsedLegacy = remember(trimmedLink) { parseSharedJoinLink(trimmedLink) }
    val ownerEmail = remember(trimmedLink) {
        runCatching {
            android.net.Uri.parse(trimmedLink).getQueryParameter("owner")
        }.getOrNull() ?: parsedLegacy?.ownerEmail ?: "the owner"
    }
    val previewFiles = remember(parsedV1) {
        val invitation = parsedV1?.invitation ?: return@remember emptyList()
        val appGrants = invitation.requestedGrants.filter {
            !it.datasetId.endsWith(CONTROL_DATASET_SUFFIX)
        }
        val parsed = grantsFromRequestedGrants(appGrants)
        if (parsed.split) {
            parsed.grants.map { (part, role) ->
                JoinFlowFile(
                    dataset = when (part) {
                        com.easybc.planner.sync.shared.PART_CYCLE -> EbDataset.CYCLE
                        com.easybc.planner.sync.shared.PART_INTIMACY -> EbDataset.INTIMACY
                        com.easybc.planner.sync.shared.PART_SENSITIVE -> EbDataset.SENSITIVE
                        else -> EbDataset.PLAN
                    },
                    label = datasetPartLabel(part),
                    canEdit = canPublishRole(role),
                )
            }
        } else {
            appGrants.map { grant ->
                JoinFlowFile(
                    dataset = null,
                    label = "All profile data",
                    canEdit = canPublishRole(grant.role.name.lowercase()),
                )
            }
        }
    }

    fun openGrantBrowser() {
        val grantUrl = android.net.Uri.parse(trimmedLink)
            .buildUpon()
            .appendQueryParameter("grant-files", "1")
            .build()
            .toString()
        if (com.easybc.planner.util.launchGrantInBrowser(activity, grantUrl)) {
            grantOpened = true
        } else {
            clipboard.setText(AnnotatedString(grantUrl))
            vm.cloudError(
                "No browser was available. The file-access link was copied; paste it " +
                    "into Chrome, select the shared files, then return here.",
            )
        }
    }

    fun runJoin() {
        autoJoinAttempted = true
        if (parsedV1 != null) {
            runner.run { token -> vm.joinFromLink(token, trimmedLink) }
        } else if (parsedLegacy != null) {
            runner.run { token ->
                vm.joinSharedSync(
                    token,
                    parsedLegacy.invitationFileId,
                    parsedLegacy.ownerFolderId,
                    parsedLegacy.ownerEmail,
                )
            }
        } else {
            vm.cloudError("That doesn't look like an EasyBC join link.")
        }
    }

    // Returning from the browser with sk-granted=1: continue without a tap.
    LaunchedEffect(pendingLinkRevision, trimmedLink) {
        if (
            trimmedLink.isNotBlank() &&
            PendingSharedJoin.grantCompleted(activity) &&
            !autoJoinAttempted &&
            !busy &&
            responseLink == null
        ) {
            PendingSharedJoin.setGrantCompleted(activity, false)
            runJoin()
        }
    }

    val finalResponse = responseLink ?: producedResponse
    val uiState: JoinFlowUiState? = when {
        finalResponse != null -> JoinFlowUiState.ResponseReady(
            ownerEmail = ownerEmail,
            responseLink = finalResponse,
        )
        busy && autoJoinAttempted -> JoinFlowUiState.Joining(ownerEmail)
        trimmedLink.isNotBlank() && (parsedV1 != null || parsedLegacy != null) && !grantOpened ->
            JoinFlowUiState.Preview(
                ownerEmail = ownerEmail,
                profileName = null,
                files = previewFiles.ifEmpty {
                    listOf(JoinFlowFile(dataset = null, label = "Shared profile data", canEdit = false))
                },
                authenticating = busy,
            )
        trimmedLink.isNotBlank() && (parsedV1 != null || parsedLegacy != null) ->
            JoinFlowUiState.AwaitingGrant(
                ownerEmail = ownerEmail,
                files = previewFiles,
                returnedIncomplete = false,
            )
        else -> null
    }

    fun shareResponse(link: String) {
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, link)
                },
                "Share reply link",
            ),
        )
    }

    // Leaving mid-join keeps the link (the screen prefills it again) but
    // frees the auth gate so background sync doesn't wait on this screen.
    fun leave() {
        PendingSharedJoin.parkFlow()
        onBack()
    }
    androidx.activity.compose.BackHandler { leave() }

    // Zero insets: the app-level scaffold already consumed the system bars.
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Join a shared profile") },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            if (uiState == null) {
                Text(
                    "Paste the join link someone sent you. You'll see exactly what " +
                        "they're sharing before anything happens, and the new profile " +
                        "stays separate from every other profile on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = joinLinkInput,
                    onValueChange = { joinLinkInput = it },
                    label = { Text("Join link") },
                    placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (trimmedLink.isNotBlank()) {
                    Text(
                        "That doesn't look like an EasyBC join link yet — paste the " +
                            "whole link from the invitation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                JoinFlowScreen(
                    state = uiState,
                    onContinue = {
                        // Finish Google authorization and unlock/create the
                        // sharing passkey before leaving the app. The unlocked
                        // identity stays warm for the grant return, so the flow
                        // does not surprise the user with a second prompt.
                        runner.run { token ->
                            vm.prepareJoin(token) {
                                openGrantBrowser()
                            }
                        }
                    },
                    onReopenBrowser = { openGrantBrowser() },
                    onAlreadyGranted = { runJoin() },
                    onShareResponse = { finalResponse?.let(::shareResponse) },
                    onCopyResponse = {
                        finalResponse?.let { clipboard.setText(AnnotatedString(it)) }
                    },
                    onRetry = {
                        grantOpened = false
                        autoJoinAttempted = false
                    },
                    onSeePeople = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (uiState is JoinFlowUiState.Preview || uiState is JoinFlowUiState.AwaitingGrant) {
                    OutlinedTextField(
                        value = joinLinkInput,
                        onValueChange = {
                            joinLinkInput = it
                            grantOpened = false
                            autoJoinAttempted = false
                        },
                        label = { Text("Join link") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                if (uiState is JoinFlowUiState.ResponseReady) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            PendingSharedJoin.setProducedResponse(activity, null)
                            producedResponse = null
                            vm.dismissCloudStatus()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Done — I sent the reply") }
                }
            }
        }
    }
}
