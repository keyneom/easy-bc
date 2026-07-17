package com.easybc.planner.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.sync.shared.disambiguatedProfileLabel
import com.easybc.planner.sync.shared.parseOwnershipTransferLink
import com.easybc.planner.sync.shared.profileForOwnershipTransfer
import com.easybc.planner.sync.shared.profileKey
import com.easybc.planner.ui.kit.EbBanner
import com.easybc.planner.ui.kit.EbBannerTone
import com.easybc.planner.ui.kit.EbProfileHeaderCard

/**
 * Recipient side of an ownership transfer (docs/settings-profiles-redesign.md
 * §11). The offer names the profile and its current owner before anything is
 * signed; leaving the screen parks the offer (it stays reachable from
 * Profiles) — only an explicit Decline discards it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcceptOwnershipScreen(
    onDone: () -> Unit,
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val scope = rememberCoroutineScope()
    val runner = rememberCloudActionRunner(vm, scope)
    val snackbar = remember { SnackbarHostState() }
    CloudStatusSnackbar(vm, snackbar)

    val status by vm.cloudStatus.collectAsState()
    val sharedState by vm.sharedSyncState.collectAsState()
    val link by vm.incomingOwnershipTransferLink.collectAsState()
    val busy = status is SettingsViewModel.SyncStatus.Running
    var confirmAccept by remember { mutableStateOf(false) }
    var confirmDecline by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }

    val transfer = remember(link) { link?.let(::parseOwnershipTransferLink) }
    val state = sharedState
    val profile = remember(state, transfer) {
        if (state != null && transfer != null) {
            profileForOwnershipTransfer(state, transfer)
        } else {
            null
        }
    }

    // Undecided exits keep the offer; the auth gate is freed either way so
    // background sync never waits on an abandoned screen.
    fun leaveUndecided() {
        vm.parkOwnershipTransferOffer()
        onDone()
    }
    BackHandler { leaveUndecided() }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Ownership offer") },
                navigationIcon = {
                    IconButton(onClick = { leaveUndecided() }) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            when {
                accepted -> {
                    EbBanner(
                        tone = EbBannerTone.SUCCESS,
                        title = "You own this profile now",
                        text = "You control its storage and sharing. The previous owner " +
                            "stays on as an admin.",
                    )
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("See profiles") }
                }
                link == null -> {
                    EbBanner(
                        tone = EbBannerTone.INFO,
                        title = "No ownership offer waiting",
                        text = "Open the transfer link the current owner sent you.",
                    )
                }
                transfer == null -> {
                    EbBanner(
                        tone = EbBannerTone.ERROR,
                        title = "This transfer link can't be read",
                        text = "It may have been truncated by the app it was sent " +
                            "through. Ask the owner to copy and send the link again.",
                    )
                    TextButton(
                        onClick = { confirmDecline = true },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Discard this link") }
                }
                profile == null -> {
                    EbBanner(
                        tone = EbBannerTone.WARN,
                        title = "This offer is for a profile that isn't on this device",
                        text = "Join the shared profile first — ask the owner for its " +
                            "invite link — then open this transfer link again. The offer " +
                            "stays saved here in the meantime.",
                    )
                    TextButton(
                        onClick = { confirmDecline = true },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Decline offer") }
                }
                else -> {
                    EbProfileHeaderCard(
                        name = state?.let { disambiguatedProfileLabel(it, profile) }
                            ?: profile.displayName ?: "Shared profile",
                        meta = "Currently owned by ${profile.ownerEmail}",
                        colorKey = profileKey(profile.ownerEmail, profile.datasetId),
                        badge = state?.let { hubProfileBadge(it, profile) },
                        photoBase64 = profile.avatarWebp,
                    )
                    EbBanner(
                        tone = EbBannerTone.INFO,
                        title = "${profile.ownerEmail} wants to make you the owner",
                        text = "Accepting moves the encrypted files' ownership to your " +
                            "Google Drive: you control storage and sharing, and " +
                            "${profile.ownerEmail} stays on as an admin. Nothing changes " +
                            "for other people who have access.",
                    )
                    Button(
                        onClick = { confirmAccept = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Transferring…" else "Accept ownership") }
                    TextButton(
                        onClick = { confirmDecline = true },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Decline offer") }
                    Text(
                        "Not ready to decide? Going back keeps the offer waiting on " +
                            "the Profiles screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmAccept && link != null) {
        AlertDialog(
            onDismissRequest = { confirmAccept = false },
            title = { Text("Take ownership?") },
            text = {
                Text(
                    "The encrypted files move to your Google Drive and count against " +
                        "your storage. You control sharing from now on; the current " +
                        "owner remains an admin.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAccept = false
                        runner.run { token ->
                            vm.acceptOwnershipTransfer(token, link!!) { accepted = true }
                        }
                    },
                ) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAccept = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDecline) {
        AlertDialog(
            onDismissRequest = { confirmDecline = false },
            title = { Text("Decline this offer?") },
            text = {
                Text(
                    "The saved offer is removed from this device. The owner can send " +
                        "a new transfer link any time.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDecline = false
                        vm.dismissOwnershipTransferOffer()
                        onDone()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Decline") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDecline = false }) { Text("Cancel") }
            },
        )
    }
}
