package com.easybc.planner.ui.navigation

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.easybc.planner.sync.AuthorizationStep
import com.easybc.planner.sync.shared.isLocalProfile
import com.easybc.planner.sync.shared.profileDisplayLabel
import com.easybc.planner.sync.shared.profileKey
import com.easybc.planner.ui.kit.EbAvatar
import com.easybc.planner.ui.kit.EbProfileChip
import com.easybc.planner.ui.settings.SettingsViewModel
import com.easybc.planner.ui.settings.hubProfileBadge
import com.easybc.planner.ui.settings.hubProfileMeta
import kotlinx.coroutines.launch

/**
 * Global profile chip + switcher sheet (docs/settings-profiles-redesign.md §1).
 *
 * Rendered by [AppNavigation] above the NavHost so the active profile's
 * identity is visible on every screen — logging into the wrong profile is
 * the worst failure this app can have. Switching runs the same
 * publish-before-switch routine as the Settings hub (auth gate included);
 * the sheet shows progress until the switch confirms and surfaces errors
 * inline instead of flipping state silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileChipHost(
    vm: SettingsViewModel,
    /** Navigate to the profiles / storage & sharing management screen. */
    onOpenManageProfiles: () -> Unit,
) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val scope = rememberCoroutineScope()
    val sharedState by vm.sharedSyncState.collectAsState()
    val status by vm.cloudStatus.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    var pendingSwitchKey by remember { mutableStateOf<String?>(null) }
    var switchTarget by remember { mutableStateOf<String?>(null) }
    var switchError by remember { mutableStateOf<String?>(null) }

    val state = sharedState ?: return
    if (state.profiles.isEmpty()) return
    val activeProfile = state.profiles.firstOrNull {
        profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
    } ?: return
    val busy = status is SettingsViewModel.SyncStatus.Running

    // Close the sheet once an in-flight switch lands; keep errors visible.
    if (switchTarget != null && !busy) {
        when (status) {
            is SettingsViewModel.SyncStatus.Success -> {
                switchTarget = null
                switchError = null
                showSheet = false
            }
            is SettingsViewModel.SyncStatus.Error -> {
                switchError = (status as SettingsViewModel.SyncStatus.Error).message
                switchTarget = null
            }
            else -> {}
        }
    }

    val switchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val key = pendingSwitchKey
        pendingSwitchKey = null
        if (result.resultCode != Activity.RESULT_OK) {
            switchError = "Google authorization was cancelled."
            switchTarget = null
            return@rememberLauncherForActivityResult
        }
        runCatching { vm.finishCloudAuthorization(activity, result.data) }
            .onSuccess { token -> key?.let { vm.switchProfile(token, it) } }
            .onFailure {
                switchError = it.message ?: "Google authorization failed."
                switchTarget = null
            }
    }

    fun switchTo(key: String) {
        if (busy || key == state.activeProfileKey) {
            if (key == state.activeProfileKey) showSheet = false
            return
        }
        val target = state.profiles.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == key
        } ?: return
        switchError = null
        switchTarget = key
        scope.launch {
            try {
                if (isLocalProfile(target) && isLocalProfile(activeProfile)) {
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
                switchError = error.message ?: "Google authorization failed."
                switchTarget = null
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EbProfileChip(
            name = profileDisplayLabel(state, activeProfile),
            onClick = { showSheet = true },
            colorKey = state.activeProfileKey,
            badge = hubProfileBadge(state, activeProfile),
            photoBase64 = activeProfile.avatarWebp,
        )
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { if (!busy) showSheet = false }) {
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
                    val key = profileKey(profile.ownerEmail, profile.datasetId)
                    val isActive = key == state.activeProfileKey
                    val label = profileDisplayLabel(state, profile)
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
                                    if (switchTarget == key && busy) "Switching…"
                                    else hubProfileMeta(state, profile),
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
                if (busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
                switchError?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(
                    onClick = {
                        showSheet = false
                        onOpenManageProfiles()
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Manage profiles — new, join, storage & sharing") }
            }
        }
    }
}
