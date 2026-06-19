package com.easybc.planner.calendar

import android.util.Log
import com.easybc.planner.data.PlannerRepository
import com.easybc.planner.data.PlannerResult
import com.easybc.planner.data.db.DayLog
import com.easybc.planner.data.db.PeriodRecord
import com.easybc.planner.data.db.UserSettingsEntity
import com.easybc.planner.util.CycleCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Observes the app's reactive data flows and keeps the device's "EasyBC"
 * local calendar in sync whenever the user has enabled calendar sync
 * ([UserSettingsEntity.calendarSyncEnabled]).
 *
 * ### Design
 *
 * - Listens to `settings × periods × dayLogs × plannerResult` and debounces
 *   bursts (user editing a period, logging a day, tweaking settings) so we
 *   only resync once per quiet period instead of per keystroke.
 * - Emits only when `calendarSyncEnabled` is true — toggling it off simply
 *   stops producing snapshots; nothing cancels the collector itself.
 * - Errors (missing permission, provider down, etc.) are logged and swallowed
 *   so a transient failure never takes down the app.
 *
 * The in-app calendar view (`CalendarScreen`) already auto-updates via its
 * own `Flow.stateIn(...)` on the same sources — so turning this on covers
 * the "device calendar" half of the user-visible story.
 */
@OptIn(FlowPreview::class)
class CalendarAutoSync(
    private val repo: PlannerRepository,
    private val cycleCalc: CycleCalculator,
    private val calendarSync: EasyBCCalendarSync,
    private val appScope: CoroutineScope,
    private val debounceMs: Long = 1_500L,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true

        appScope.launch {
            combine(
                repo.settingsFlow,
                repo.periodsFlow,
                repo.dayLogsFlow,
                repo.calendarPlannerResultFlow,
            ) { settings, periods, dayLogs, plan ->
                if (settings?.calendarSyncEnabled == true && settings.onboardingComplete) {
                    Snapshot(settings, periods, dayLogs, plan)
                } else {
                    null
                }
            }
                .filterNotNull()
                .debounce(debounceMs)
                .distinctUntilChanged()
                .collect { snap ->
                    if (!calendarSync.hasPermission()) {
                        // Permission was revoked from system settings — skip
                        // quietly. The user's next visit to Settings will
                        // surface the state when they try to manual-sync.
                        return@collect
                    }
                    try {
                        calendarSync.syncEvents(
                            periods = snap.periods,
                            plan = snap.plan,
                            settings = snap.settings,
                            cycleCalc = cycleCalc,
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "Auto-sync failed", t)
                    }
                }
        }
    }

    private data class Snapshot(
        val settings: UserSettingsEntity,
        val periods: List<PeriodRecord>,
        val dayLogs: List<DayLog>,
        val plan: PlannerResult?,
    )

    companion object {
        private const val TAG = "CalendarAutoSync"
    }
}
