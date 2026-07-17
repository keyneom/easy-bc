package com.easybc.planner.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.sync.CloudSyncOperation
import com.easybc.planner.sync.shared.disambiguatedProfileLabel
import com.easybc.planner.sync.shared.isLocalProfile
import com.easybc.planner.sync.shared.profileKey
import com.easybc.planner.ui.kit.EbAvatar
import com.easybc.planner.ui.kit.EbBanner
import com.easybc.planner.ui.kit.EbBannerTone

/**
 * The profiles home (docs/settings-profiles-redesign.md §4): every profile on
 * this device as a card that opens its detail screen, plus the two ways a new
 * profile arrives — creating one and joining one someone shared with you.
 * Storage and sharing for a specific profile live on that profile's detail
 * screen, not here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageProfilesScreen(
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenJoin: () -> Unit,
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val runner = rememberCloudActionRunner(vm, scope)
    val snackbar = remember { SnackbarHostState() }
    CloudStatusSnackbar(vm, snackbar)

    val sharedState by vm.sharedSyncState.collectAsState()
    val status by vm.cloudStatus.collectAsState()
    val sharedConfigured by vm.sharedSyncConfigured.collectAsState()
    val legacyPresent by vm.legacySyncPresent.collectAsState()
    val connected by vm.cloudConnected.collectAsState()
    val busy = status is SettingsViewModel.SyncStatus.Running

    var newProfileOpen by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    fun switchTo(key: String) {
        val state = sharedState ?: return
        if (busy || key == state.activeProfileKey) return
        val active = state.profiles.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
        }
        val target = state.profiles.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == key
        } ?: return
        if (isLocalProfile(target) && active != null && isLocalProfile(active)) {
            vm.switchProfile(null, key)
        } else {
            runner.run { token -> vm.switchProfile(token, key) }
        }
    }

    fun createProfile() {
        val name = newProfileName.trim()
        if (name.isEmpty() || busy) return
        val active = vm.activeProfile()
        if (active == null || isLocalProfile(active)) {
            vm.createLocalProfile(null, name)
            newProfileName = ""
            newProfileOpen = false
        } else {
            runner.run { token ->
                vm.createLocalProfile(token, name)
                newProfileName = ""
                newProfileOpen = false
            }
        }
    }

    // Zero insets: the app-level scaffold already consumed the system bars.
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Profiles") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Every profile keeps its own settings, data, storage, and sharing. " +
                    "Tap a profile to manage where it lives and who can see it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val state = sharedState
            state?.profiles?.forEach { profile ->
                val key = profileKey(profile.ownerEmail, profile.datasetId)
                val isActive = key == state.activeProfileKey
                val label = disambiguatedProfileLabel(state, profile)
                Surface(
                    onClick = { onOpenProfile(key) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                                "Active",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            TextButton(
                                onClick = { switchTo(key) },
                                enabled = !busy,
                            ) { Text("Switch") }
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            if (!newProfileOpen) {
                Button(
                    onClick = { newProfileOpen = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PersonAdd, null)
                    Spacer(Modifier.width(8.dp))
                    Text("New profile")
                }
            } else {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Who is this profile for?") },
                    placeholder = { Text("Emma") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            newProfileOpen = false
                            newProfileName = ""
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel") }
                    Button(
                        onClick = { createProfile() },
                        enabled = !busy && newProfileName.trim().isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Create") }
                }
                Text(
                    "New profiles start local to this device — choose Private cloud or " +
                        "Shared any time on the profile's own screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onOpenJoin,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.GroupAdd, null)
                Spacer(Modifier.width(8.dp))
                Text("Join a profile shared with you")
            }

            // Contextual recovery — rendered only while their condition holds.
            if (legacyPresent && !sharedConfigured) {
                EbBanner(
                    tone = EbBannerTone.WARN,
                    title = "Legacy encrypted sync found",
                    text = "This device has records from the older encrypted sync. " +
                        "Migrating merges them into the current format — nothing is lost.",
                    actionLabel = "Migrate",
                    onAction = {
                        if (!busy) {
                            runner.run { token ->
                                vm.runCloudOperation(activity, CloudSyncOperation.ENABLE, token)
                            }
                        }
                    },
                )
            }
            if (connected && !sharedConfigured) {
                EbBanner(
                    tone = EbBannerTone.ERROR,
                    title = "A cloud copy exists that this device can't unlock",
                    text = "Reset deletes that copy and starts fresh with this device's data.",
                    actionLabel = "Reset",
                    onAction = { if (!busy) confirmReset = true },
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Replace the encrypted cloud copy?") },
            text = {
                Text(
                    "This deletes the encrypted Drive snapshot — including one this " +
                        "device can't unlock — and recreates it from this device's local " +
                        "data. Your sharing identity (in Drive app data) is kept.",
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
