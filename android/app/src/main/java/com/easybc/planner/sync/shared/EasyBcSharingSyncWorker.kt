package com.easybc.planner.sync.shared

import android.content.Context
import androidx.work.WorkerParameters
import com.easybc.planner.data.db.AppDatabase
import com.keyneom.synckit.sharing.checkpoint.SharingSyncCheckpoint
import com.keyneom.synckit.sharing.work.SharingSyncWorker

class EasyBcSharingSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : SharingSyncWorker(appContext, params) {
    private val registry = SharedSyncRegistry(AppDatabase.getInstance(appContext))
    private val driveAuth = SharedDriveAuth(appContext)

    override suspend fun transport() = run {
        val state = registry.load() ?: error("Encrypted sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        EasyBcSharedTransport.forProfile(state, profile, driveAuth)
    }

    override suspend fun loadCheckpoint(): SharingSyncCheckpoint =
        registry.loadCheckpoint()

    override suspend fun saveCheckpoint(checkpoint: SharingSyncCheckpoint) {
        registry.saveCheckpoint(checkpoint)
    }
}
