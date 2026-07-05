package com.easybc.planner.sync.shared

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.keyneom.synckit.sharing.work.SharingSyncWorker
import java.util.concurrent.TimeUnit

object SharingSyncScheduler {
    private const val UNIQUE_WORK = "easybc-sharing-sync"

    fun schedule(context: Context, tokenExpiresAt: Long?) {
        val request = PeriodicWorkRequestBuilder<EasyBcSharingSyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    SharingSyncWorker.KEY_TOKEN_EXPIRES_AT to (tokenExpiresAt ?: Long.MIN_VALUE),
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}
