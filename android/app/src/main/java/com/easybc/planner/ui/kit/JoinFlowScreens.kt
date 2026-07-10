package com.easybc.planner.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * Presentational screens for the guided join/accept flow (docs/join-flow.md).
 * No sync logic: a view model maps coordinator progress onto JoinFlowUiState
 * and supplies the callbacks. All interactive auth the callbacks trigger must
 * run inside InteractiveAuthGate.run { } so it never races auto-sync.
 */

/** A dataset file offered by an invitation, already resolved to UI terms. */
data class JoinFlowFile(
    val dataset: EbDataset?,
    val label: String,
    val canEdit: Boolean,
    val granted: Boolean = false,
)

sealed interface JoinFlowUiState {
    /** Invitation parsed and shown; Continue runs auth then the browser hand-off. */
    data class Preview(
        val ownerEmail: String,
        val profileName: String?,
        val files: List<JoinFlowFile>,
        val authenticating: Boolean = false,
    ) : JoinFlowUiState

    /** Browser/Picker trip in progress or returned incomplete. */
    data class AwaitingGrant(
        val ownerEmail: String,
        val files: List<JoinFlowFile>,
        val returnedIncomplete: Boolean = false,
    ) : JoinFlowUiState

    /** Grants complete; join + response generation running (no user action). */
    data class Joining(val ownerEmail: String) : JoinFlowUiState

    /** Join done: the reply link must reach the owner. */
    data class ResponseReady(
        val ownerEmail: String,
        val responseLink: String,
    ) : JoinFlowUiState

    /** Owner side: a response link arrived and is being processed. */
    data class Accepting(
        val recipientLabel: String,
        val grantSummary: String,
        val authenticating: Boolean,
    ) : JoinFlowUiState

    /** Owner side: done. */
    data class Accepted(
        val recipientLabel: String,
        val grantSummary: String,
    ) : JoinFlowUiState

    data class Failed(
        val message: String,
        val canRetry: Boolean = true,
    ) : JoinFlowUiState
}

@Composable
fun JoinFlowScreen(
    state: JoinFlowUiState,
    onContinue: () -> Unit,
    onReopenBrowser: () -> Unit,
    onAlreadyGranted: () -> Unit,
    onShareResponse: () -> Unit,
    onCopyResponse: () -> Unit,
    onRetry: () -> Unit,
    onSeePeople: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (state) {
            is JoinFlowUiState.Preview -> PreviewContent(state, onContinue)
            is JoinFlowUiState.AwaitingGrant ->
                AwaitingGrantContent(state, onReopenBrowser, onAlreadyGranted)
            is JoinFlowUiState.Joining -> JoiningContent(state)
            is JoinFlowUiState.ResponseReady ->
                ResponseReadyContent(state, onShareResponse, onCopyResponse)
            is JoinFlowUiState.Accepting -> AcceptingContent(state)
            is JoinFlowUiState.Accepted -> AcceptedContent(state, onSeePeople)
            is JoinFlowUiState.Failed -> FailedContent(state, onRetry)
        }
    }
}

@Composable
private fun StepHeader(step: Int, title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EbStepDots(count = 3, active = step)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FileList(files: List<JoinFlowFile>) {
    files.forEach { file ->
        EbDatasetRow(
            dataset = file.dataset ?: EbDataset.PLAN,
            title = file.label,
            summary = when {
                file.granted -> "Access granted"
                file.canEdit -> "You'll be able to edit"
                else -> "View only"
            },
        )
    }
}

@Composable
private fun PreviewContent(state: JoinFlowUiState.Preview, onContinue: () -> Unit) {
    StepHeader(
        step = 0,
        title = state.profileName?.let { "Join “$it”" } ?: "Join a shared profile",
        subtitle = "${state.ownerEmail} is sharing these with you:",
    )
    FileList(state.files)
    EbBanner(
        tone = EbBannerTone.INFO,
        text = "Next, Google asks you to select these files in your Drive — " +
            "that's the only manual step, and EasyBC can never see any other file.",
    )
    Spacer(Modifier.height(2.dp))
    Button(
        onClick = onContinue,
        enabled = !state.authenticating,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.authenticating) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Spacer(Modifier.padding(horizontal = 5.dp))
            Text("Confirming it's you…")
        } else {
            Text("Continue")
        }
    }
}

@Composable
private fun AwaitingGrantContent(
    state: JoinFlowUiState.AwaitingGrant,
    onReopenBrowser: () -> Unit,
    onAlreadyGranted: () -> Unit,
) {
    StepHeader(
        step = 1,
        title = "Grant access in the browser",
        subtitle = "Select every file listed below in the Google window, " +
            "then tap “Return to EasyBC”.",
    )
    FileList(state.files)
    if (state.returnedIncomplete) {
        EbBanner(
            tone = EbBannerTone.WARN,
            title = "Some files still need access",
            text = "EasyBC couldn't read every shared file yet. Open the browser " +
                "again and select the remaining files — they're in the same shared folder.",
        )
    }
    Button(onClick = onReopenBrowser, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.returnedIncomplete) "Open the browser again" else "Open the browser")
    }
    TextButton(onClick = onAlreadyGranted, modifier = Modifier.fillMaxWidth()) {
        Text("I already granted access — continue")
    }
}

@Composable
private fun JoiningContent(state: JoinFlowUiState.Joining) {
    StepHeader(
        step = 1,
        title = "Finishing the join…",
        subtitle = "Verifying the shared files and preparing your reply " +
            "to ${state.ownerEmail}. No action needed.",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ResponseReadyContent(
    state: JoinFlowUiState.ResponseReady,
    onShareResponse: () -> Unit,
    onCopyResponse: () -> Unit,
) {
    StepHeader(
        step = 2,
        title = "One last step: send your reply",
        subtitle = "Send this reply link to ${state.ownerEmail}. When they open it, " +
            "your access is confirmed and the profile starts syncing automatically.",
    )
    Text(
        state.responseLink,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onShareResponse, modifier = Modifier.fillMaxWidth()) {
        Text("Share reply link")
    }
    OutlinedButton(onClick = onCopyResponse, modifier = Modifier.fillMaxWidth()) {
        Text("Copy link")
    }
    EbStatusRow(
        tone = EbStatusTone.BUSY,
        text = "Waiting for ${state.ownerEmail} to accept — this updates automatically.",
    )
}

@Composable
private fun AcceptingContent(state: JoinFlowUiState.Accepting) {
    StepHeader(
        step = 2,
        title = "${state.recipientLabel} accepted your invite",
        subtitle = state.grantSummary,
    )
    EbStatusRow(
        tone = EbStatusTone.BUSY,
        text = if (state.authenticating) "Confirming it's you…" else "Finishing the share…",
    )
}

@Composable
private fun AcceptedContent(state: JoinFlowUiState.Accepted, onSeePeople: () -> Unit) {
    StepHeader(
        step = 2,
        title = "Share complete",
        subtitle = "${state.recipientLabel} now has access: ${state.grantSummary}",
    )
    EbBanner(
        tone = EbBannerTone.SUCCESS,
        text = "Their app syncs the shared profile automatically from now on.",
    )
    Button(onClick = onSeePeople, modifier = Modifier.fillMaxWidth()) {
        Text("See people with access")
    }
}

@Composable
private fun FailedContent(state: JoinFlowUiState.Failed, onRetry: () -> Unit) {
    EbBanner(
        tone = EbBannerTone.ERROR,
        title = "That didn't work",
        text = state.message,
    )
    if (state.canRetry) {
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Try again")
        }
    }
}
