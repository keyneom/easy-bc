package com.easybc.planner

import android.app.Application
import com.easybc.planner.bridge.PlannerBridge
import com.easybc.planner.bridge.createPlannerBridge
import com.easybc.planner.calendar.CalendarAutoSync
import com.easybc.planner.calendar.EasyBCCalendarSync
import com.easybc.planner.data.PlannerRepository
import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.util.CycleCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class EasyBCApp : Application() {

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
        PlannerRepository(database, bridge, cycleCalculator)
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
}
