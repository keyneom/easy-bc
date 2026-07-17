package com.easybc.planner.ui.settings

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalContext
import com.easybc.planner.sync.AuthorizationStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs a cloud operation that needs a Google access token, transparently
 * resolving the consent screen when authorization requires it. One instance
 * replaces the per-operation pending-state plumbing (pendingInvite,
 * pendingSwitchKey, …) that used to dominate the sharing screen.
 *
 * The action lambda usually delegates to a fire-and-forget [SettingsViewModel]
 * method, which owns progress/error reporting via `cloudStatus`.
 */
class CloudActionRunner internal constructor(
    private val vm: SettingsViewModel,
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    private val pendingAction: androidx.compose.runtime.MutableState<((String) -> Unit)?>,
    private val launch: (IntentSenderRequest) -> Unit,
) {
    /**
     * @param waiting flips `cloudStatus` to Running immediately so buttons
     *   disable while the consent sheet is up; leave false for quiet refreshes.
     */
    fun run(waiting: Boolean = true, action: (String) -> Unit) {
        if (waiting) vm.cloudWaiting()
        scope.launch {
            try {
                when (val step = vm.beginCloudAuthorization(activity)) {
                    is AuthorizationStep.Authorized -> action(step.accessToken)
                    is AuthorizationStep.NeedsResolution -> {
                        pendingAction.value = action
                        launch(
                            IntentSenderRequest.Builder(step.pendingIntent.intentSender).build(),
                        )
                    }
                }
            } catch (error: Exception) {
                vm.cloudError(error.message ?: "Google authorization failed.")
            }
        }
    }
}

@Composable
fun rememberCloudActionRunner(
    vm: SettingsViewModel,
    scope: CoroutineScope,
): CloudActionRunner {
    val activity = LocalContext.current as ComponentActivity
    val pendingAction = remember { mutableStateOf<((String) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val action = pendingAction.value
        pendingAction.value = null
        if (result.resultCode != Activity.RESULT_OK) {
            vm.cloudError("Google authorization was cancelled.")
            return@rememberLauncherForActivityResult
        }
        runCatching { vm.finishCloudAuthorization(activity, result.data) }
            .onSuccess { token -> action?.invoke(token) }
            .onFailure { vm.cloudError(it.message ?: "Google authorization failed.") }
    }
    return remember(vm) {
        CloudActionRunner(vm, activity, scope, pendingAction) { request ->
            launcher.launch(request)
        }
    }
}

/**
 * One feedback voice per screen: cloud successes and failures surface as a
 * snackbar anchored to the bottom of the screen that hosts this effect —
 * replacing the status fragments that used to appear at arbitrary scroll
 * offsets far from the control that was tapped.
 */
@Composable
fun CloudStatusSnackbar(vm: SettingsViewModel, hostState: SnackbarHostState) {
    val status by vm.cloudStatus.collectAsState()
    LaunchedEffect(status) {
        when (val s = status) {
            is SettingsViewModel.SyncStatus.Success -> {
                hostState.showSnackbar(s.message, duration = SnackbarDuration.Short)
            }
            is SettingsViewModel.SyncStatus.Error -> {
                hostState.showSnackbar(s.message, duration = SnackbarDuration.Long)
            }
            else -> {}
        }
    }
}
