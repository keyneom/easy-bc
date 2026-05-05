package com.easybc.planner.ui.reconcile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easybc.planner.EasyBCApp
import com.easybc.planner.data.PlannerResult
import com.easybc.planner.data.RecommendedAction
import com.easybc.planner.data.db.DayLog
import com.easybc.planner.data.db.PeriodRecord
import com.easybc.planner.data.db.UserSettingsEntity
import com.easybc.planner.util.CycleCalculator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Backs the reconciliation chip + batch-reconcile screen.
 *
 * We derive the list of "days the user should still confirm" by joining:
 *   - the planner's per-day recommendation (what we *asked* them to do), and
 *   - their logged day rows (what they've confirmed happened),
 * then surfacing past U/W/C days that don't yet have a reconciled log.
 *
 * Abstain (A) days aren't surfaced by default: there's nothing active to
 * confirm, and if the user *did* break abstinence they'll log it through the
 * Day Detail sheet.
 */
class ReconcileViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EasyBCApp
    private val repo = app.repository
    private val cycleCalc = app.cycleCalculator

    /** How far back from today we prompt for reconciliation. */
    private val lookbackDays = 30L

    data class Row(
        val date: LocalDate,
        val plannerAction: RecommendedAction,
        val existingActualAction: String?,
        val reconciled: Boolean,
    ) {
        /** Whether the "as planned" quick-action can reasonably be applied. */
        val canAcceptAsPlanned: Boolean
            get() = plannerAction != RecommendedAction.A
    }

    private val settingsFlow: Flow<UserSettingsEntity?> = repo.settingsFlow
    private val periodsFlow: Flow<List<PeriodRecord>> = repo.periodsFlow
    private val dayLogsFlow: Flow<List<DayLog>> = repo.dayLogsFlow
    private val plannerFlow: Flow<PlannerResult?> = repo.calendarPlannerResultFlow

    /** Tracks which rows the user has multi-selected on the screen. */
    private val _selectedDates = MutableStateFlow<Set<Long>>(emptySet())
    val selectedDates: StateFlow<Set<Long>> = _selectedDates

    /** Derived list of unreconciled days; reactive to data changes. */
    val unreconciled: StateFlow<List<Row>> =
        combine(settingsFlow, periodsFlow, dayLogsFlow, plannerFlow) { s, p, logs, plan ->
            computeUnreconciled(s, p, logs, plan)
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Selection mutations ──

    fun toggleSelection(date: LocalDate) {
        val epoch = date.toEpochDay()
        _selectedDates.update { cur -> if (epoch in cur) cur - epoch else cur + epoch }
    }

    fun selectAll() {
        _selectedDates.value = unreconciled.value.map { it.date.toEpochDay() }.toSet()
    }

    fun clearSelection() {
        _selectedDates.value = emptySet()
    }

    fun selectRange(start: LocalDate, endInclusive: LocalDate) {
        val lo = minOf(start.toEpochDay(), endInclusive.toEpochDay())
        val hi = maxOf(start.toEpochDay(), endInclusive.toEpochDay())
        _selectedDates.value = (lo..hi).toSet()
    }

    // ── Reconcile actions ──

    /** Reconcile a single date to the given action string. */
    fun reconcileOne(date: LocalDate, actualAction: String) {
        viewModelScope.launch {
            repo.reconcileDay(date, actualAction)
            _selectedDates.update { it - date.toEpochDay() }
        }
    }

    /** Accept the planner's recommended action for a single date. */
    fun acceptAsPlanned(date: LocalDate) {
        val row = unreconciled.value.firstOrNull { it.date == date } ?: return
        reconcileOne(date, row.plannerAction.shortLabel)
    }

    /** Apply the same action to every currently selected date. */
    fun reconcileSelected(actualAction: String) {
        val selected = _selectedDates.value
        if (selected.isEmpty()) return
        viewModelScope.launch {
            for (epoch in selected) {
                repo.reconcileDay(LocalDate.ofEpochDay(epoch), actualAction)
            }
            _selectedDates.value = emptySet()
        }
    }

    /**
     * For the selected dates, reconcile each to its *planner-recommended*
     * action — same as individually tapping "As planned" on each one.
     */
    fun acceptSelectedAsPlanned() {
        val selected = _selectedDates.value
        if (selected.isEmpty()) return
        val byDate = unreconciled.value.associateBy { it.date.toEpochDay() }
        viewModelScope.launch {
            for (epoch in selected) {
                val row = byDate[epoch] ?: continue
                repo.reconcileDay(
                    LocalDate.ofEpochDay(epoch),
                    row.plannerAction.shortLabel,
                )
            }
            _selectedDates.value = emptySet()
        }
    }

    // ── Internals ──

    private fun computeUnreconciled(
        settings: UserSettingsEntity?,
        periods: List<PeriodRecord>,
        dayLogs: List<DayLog>,
        plan: PlannerResult?,
    ): List<Row> {
        if (settings == null || plan == null || plan.years.isEmpty()) return emptyList()

        val today = LocalDate.now()
        val oldest = today.minusDays(lookbackDays)
        val cycles = cycleCalc.buildCoverageCycles(periods, settings)
        val logsByDate = dayLogs.associateBy { it.date }

        val rows = mutableListOf<Row>()
        var d = oldest
        while (d.isBefore(today)) {
            val cycleInfo = cycleCalc.dateToCycleDay(d, cycles)
            if (cycleInfo != null) {
                val (cycleIdx, dayInCycle) = cycleInfo
                val year = plan.years.getOrNull(cycleIdx)
                val dw = year?.dayWeights?.getOrNull(dayInCycle - 1)
                if (dw != null && dw.recommendedAction != RecommendedAction.A) {
                    val existing = logsByDate[d.toEpochDay()]
                    if (existing == null || !existing.reconciled) {
                        rows += Row(
                            date = d,
                            plannerAction = dw.recommendedAction,
                            existingActualAction = existing?.actualAction,
                            reconciled = existing?.reconciled ?: false,
                        )
                    }
                }
            }
            d = d.plusDays(1)
        }
        // Most recent first — the user usually wants to reconcile yesterday
        // before reconciling three weeks ago.
        return rows.sortedByDescending { it.date }
    }
}
