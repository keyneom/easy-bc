package com.easybc.planner.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easybc.planner.sync.shared.PendingSharedJoin
import com.easybc.planner.ui.kit.EbBanner
import com.easybc.planner.ui.kit.EbBannerTone

/** Processes a recipient reply independently of whichever profile is active. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcceptResponseScreen(
    onDone: () -> Unit,
    profileChip: @Composable () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val activity = LocalContext.current as ComponentActivity
    val runner = rememberCloudActionRunner(vm, rememberCoroutineScope())
    val status by vm.cloudStatus.collectAsState()
    val revision by PendingSharedJoin.revision.collectAsState()
    var responseLink by remember { mutableStateOf<String?>(null) }
    var attempted by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        responseLink = PendingSharedJoin.responseToAccept(activity)
    }
    LaunchedEffect(responseLink, attempted) {
        val link = responseLink ?: return@LaunchedEffect
        if (!attempted) {
            attempted = true
            runner.run { token -> vm.acceptResponseLink(token, link) }
        }
    }

    // Leaving mid-flow keeps the reply link (still reachable from the
    // profile's "Finish a share you sent") but frees the auth gate so
    // background sync doesn't wait on an abandoned screen.
    fun leave() {
        PendingSharedJoin.parkFlow()
        onDone()
    }
    androidx.activity.compose.BackHandler { leave() }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("Finish sharing") },
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = status) {
                SettingsViewModel.SyncStatus.Running -> {
                    CircularProgressIndicator()
                    Text("Confirming the recipient's encrypted access…")
                }
                is SettingsViewModel.SyncStatus.Success -> {
                    EbBanner(
                        tone = EbBannerTone.SUCCESS,
                        title = "Sharing finished",
                        text = current.message,
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("See profiles")
                    }
                }
                is SettingsViewModel.SyncStatus.Error -> {
                    EbBanner(
                        tone = EbBannerTone.ERROR,
                        title = "Couldn't finish sharing",
                        text = current.message,
                    )
                    OutlinedButton(
                        onClick = { attempted = false },
                        enabled = responseLink != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Try again")
                    }
                }
                SettingsViewModel.SyncStatus.Idle -> {
                    if (responseLink == null) {
                        Text(
                            "This response link is no longer pending. Ask the recipient to send it again.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
