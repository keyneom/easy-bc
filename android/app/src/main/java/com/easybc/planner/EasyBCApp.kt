package com.easybc.planner

import android.app.Application
import com.easybc.planner.bridge.PlannerBridge
import com.easybc.planner.bridge.createPlannerBridge
import com.easybc.planner.calendar.CalendarAutoSync
import com.easybc.planner.calendar.EasyBCCalendarSync
import com.easybc.planner.data.PlannerRepository
import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.sync.CloudSyncKeySession
import com.easybc.planner.util.CycleCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EasyBCApp : Application() {
    private var foregroundActivityCount = 0
    private var cloudKeyExpiryJob: Job? = null

    /**
     * Application-scoped coroutine scope for long-running collectors (e.g.
     * [CalendarAutoSync]). SupervisorJob so one failing collector doesn't
     * tear down its siblings.
     */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob()) }

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val bridge: PlannerBridge by lazy { createPlannerBridge() }

    val cycleCalculator: CycleCalculator by lazy { CycleCalculator() }

    val repository: PlannerRepository by lazy {
        PlannerRepository(database, bridge, cycleCalculator, appScope)
    }

    val calendarSync: EasyBCCalendarSync by lazy { EasyBCCalendarSync(this) }

    private val calendarAutoSync: CalendarAutoSync by lazy {
        CalendarAutoSync(repository, cycleCalculator, calendarSync, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        // Kick off the auto-sync collector. It's a no-op until the user
        // flips `calendarSyncEnabled` on in Settings, at which point it
        // begins debounced resyncs on every data change.
        calendarAutoSync.start()
    }

    @Synchronized
    fun cloudSyncActivityStarted() {
        foregroundActivityCount += 1
        cloudKeyExpiryJob?.cancel()
        cloudKeyExpiryJob = null
    }

    @Synchronized
    fun cloudSyncActivityStopped() {
        foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
        if (foregroundActivityCount != 0) return
        cloudKeyExpiryJob?.cancel()
        cloudKeyExpiryJob = appScope.launch {
            delay(CLOUD_KEY_BACKGROUND_GRACE_MS)
            synchronized(this@EasyBCApp) {
                if (foregroundActivityCount == 0) {
                    CloudSyncKeySession.clear()
                    cloudKeyExpiryJob = null
                }
            }
        }
    }

    companion object {
        internal const val CLOUD_KEY_BACKGROUND_GRACE_MS = 15 * 60 * 1_000L
    }
}
