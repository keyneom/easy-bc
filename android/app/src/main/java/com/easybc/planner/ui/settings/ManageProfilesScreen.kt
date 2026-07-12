package com.easybc.planner.ui.settings

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.easybc.planner.sync.AuthorizationStep
import com.easybc.planner.sync.shared.isLocalProfile
import com.easybc.planner.sync.shared.profileDisplayLabel
import com.easybc.planner.sync.shared.profileKey
import com.easybc.planner.ui.kit.EbAvatar
import kotlinx.coroutines.launch

/**
 * Manage profiles (docs/settings-profiles-redesign.md, mockup phone 6): a
 * place, not a paragraph. Profile cards with switch, creating a new profile
 * (starts local — storage is chosen later in Storage & sharing), and the
 * doors into joining and the active profile's Storage & sharing screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageProfilesScreen(
    onBack: () -> Unit,
    /** Storage & sharing hosts joining and per-profile storage controls. */
    onOpenStorage: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val sharedState by vm.sharedSyncState.collectAsState()
    val status by vm.cloudStatus.collectAsState()
    val busy = status is SettingsViewModel.SyncStatus.Running
    var newProfileName by remember { mutableStateOf("") }
    var pendingSwitchKey by remember { mutableStateOf<String?>(null) }
    var pendingCreateName by remember { mutableStateOf<String?>(null) }

    val resolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val switchKey = pendingSwitchKey
        val createName = pendingCreateName
        pendingSwitchKey = null
        pendingCreateName = null
        if (result.resultCode != Activity.RESULT_OK) {
            vm.cloudError("Google authorization was cancelled.")
            return@rememberLauncherForActivityResult
        }
        runCatching { vm.finishCloudAuthorization(activity, result.data) }
            .onSuccess { token ->
                when {
                    switchKey != null -> vm.switchProfile(token, switchKey)
                    createName != null -> {
                        vm.createLocalProfile(token, createName)
                        newProfileName = ""
                    }
                }
            }
            .onFailure { vm.cloudError(it.message ?: "Google authorization failed.") }
    }

    fun switchTo(key: String) {
        val state = sharedState ?: return
        if (busy || key == state.activeProfileKey) return
        val active = state.profiles.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
        }
        val target = state.profiles.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == key
        } ?: return
        scope.launch {
            try {
                if (isLocalProfile(target) && active != null && isLocalProfile(active)) {
                    vm.switchProfile(null, key)
                    return@launch
                }
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> vm.switchProfile(step.accessToken, key)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingSwitchKey = key
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

    fun createProfile() {
        val name = newProfileName.trim()
        if (name.isEmpty() || busy) return
        scope.launch {
            try {
                val active = vm.activeProfile()
                if (active == null || isLocalProfile(active)) {
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
                        pendingCreateName = name
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage profiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Every profile keeps its own settings, data, storage, and sharing. " +
                    "Switching publishes your current profile first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val state = sharedState
            state?.profiles?.forEach { profile ->
                val key = profileKey(profile.ownerEmail, profile.datasetId)
                val isActive = key == state.activeProfileKey
                val label = profileDisplayLabel(state, profile)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
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
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
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
            Button(
                onClick = { createProfile() },
                enabled = !busy && newProfileName.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("New profile") }
            Text(
                "New profiles start local to this device — choose Private cloud or " +
                    "Shared later in Storage & sharing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenStorage,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join a shared profile") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenStorage,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Storage & sharing for this profile") }
            (status as? SettingsViewModel.SyncStatus.Error)?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
