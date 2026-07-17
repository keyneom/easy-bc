package com.easybc.planner

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.easybc.planner.bridge.PlannerBridge
import com.easybc.planner.bridge.createPlannerBridge
import com.easybc.planner.calendar.CalendarAutoSync
import com.easybc.planner.calendar.EasyBCCalendarSync
import com.easybc.planner.data.PlannerRepository
import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.sync.EasyBcSyncRuntime
import com.easybc.planner.sync.SyncPayloadStore
import com.easybc.planner.sync.shared.SharedSyncCoordinator
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

    /**
     * One process-wide sharing session for autosync and every navigation-scoped
     * view model. The coordinator owns the single unlocked sharing identity, so
     * moving between screens cannot trigger another passkey prompt.
     */
    val sharedSyncCoordinator: SharedSyncCoordinator by lazy {
        SharedSyncCoordinator(this, database, SyncPayloadStore(database))
    }

    private val calendarAutoSync: CalendarAutoSync by lazy {
        CalendarAutoSync(repository, cycleCalculator, calendarSync, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        // Track the current resumed Activity so the sharing passkey flow can
        // present Credential Manager UI without an Activity being threaded
        // through every encrypted-sync call.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) =
                EasyBcForegroundActivity.set(activity)

            override fun onActivityDestroyed(activity: Activity) {
                if (EasyBcForegroundActivity.current === activity) {
                    EasyBcForegroundActivity.set(null)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
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
                    EasyBcSyncRuntime.lock()
                    cloudKeyExpiryJob = null
                }
            }
        }
    }

    companion object {
        internal const val CLOUD_KEY_BACKGROUND_GRACE_MS = 15 * 60 * 1_000L
    }
}
