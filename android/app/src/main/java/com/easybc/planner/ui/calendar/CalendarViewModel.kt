package com.easybc.planner.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easybc.planner.EasyBCApp
import com.easybc.planner.data.*
import com.easybc.planner.data.db.DayLog
import com.easybc.planner.data.db.PeriodRecord
import com.easybc.planner.data.db.UserSettingsEntity
import com.easybc.planner.util.CycleCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class DayCellData(
    val date: LocalDate,
    val dayOfMonth: Int,
    val isCurrentMonth: Boolean,
    val isToday: Boolean,
    val isPeriod: Boolean,
    val cycleDay: Int?,
    val cycleLengthDays: Int?,
    val phase: CycleCalculator.CyclePhase?,
    val plannerAction: RecommendedAction?,
    val riskScore: Int?,
    val overrideCost: OverrideCost?,
    val dayLog: DayLog?,
    /**
     * Non-null when the cycle containing this date was flagged atypical by
     * the anovulatory heuristic. UI renders it as a small "atypical" chip;
     * the planner's fertile window for that cycle is widened elsewhere.
     */
    val cycleFlag: CycleCalculator.CycleFlag? = null,
    /**
     * True when this day falls inside a period record whose `endDate` has
     * not been set yet — i.e. the bleed window's extent is being predicted
     * (extrapolated from history mean), not user-confirmed. Always `false`
     * when [isPeriod] is `false`.
     */
    val isPeriodPredicted: Boolean = false,
    /** True when this date is the recorded `endDate` of an open period. */
    val isPeriodEndDay: Boolean = false,
    /** True when this date is the recorded `startDate` of any period. */
    val isPeriodStartDay: Boolean = false,
    /**
     * True when this date is inside an actual `PeriodRecord` (recorded or
     * still open). False for predicted future bleeds projected by the
     * cycle model — those have nothing to end or undo, so the day-detail
     * sheet should offer "Mark period start", not "Confirm period end".
     */
    val hasPeriodRecord: Boolean = false,
)

enum class CalendarViewMode { MONTH, WEEK }

/**
 * Compact per-cycle risk view. Anchored to the ORIGINAL plan: surfaces the
 * unlocked baseline cycle risk (stable as days are logged), realized risk so
 * far, and risk saved/spent against that baseline for the logged days.
 */
data class CycleLedger(
    /** 1-based position of today within the current cycle. */
    val currentDayInCycle: Int,
    /** Total days in the current cycle (by its derived length). */
    val cycleLengthDays: Int,
    /**
     * The ORIGINAL planned cycle-level risk (fraction, 0..1), taken from the
     * unlocked baseline plan. This is deliberately *not* the replanned risk:
     * it stays put as the user logs days, so "plan" always means "what we
     * originally recommended for this cycle." The effect of logged days is
     * tracked separately via [realizedSoFar] and the saved/spent diff.
     */
    val plannedCycleRisk: Double,
    /** Realized risk contribution from the days already logged this cycle. */
    val realizedSoFar: Double,
    /** Positive when logged days saved risk vs the unlocked baseline plan. */
    val savedRiskVsBaseline: Double,
    /** Positive when logged days spent extra risk vs the unlocked baseline plan. */
    val extraRiskVsBaseline: Double,
    /** Full-horizon risk after applying logged day locks. */
    val horizonRisk: Double,
    /** User's full-horizon target. */
    val horizonTarget: Double,
    /** Whether the current replanned horizon still fits the target. */
    val targetMet: Boolean,
) {
    val headroom: Double get() = (plannedCycleRisk - realizedSoFar).coerceAtLeast(0.0)
    val cycleOverPlan: Boolean get() = realizedSoFar > plannedCycleRisk + 1e-12
    val overBudget: Boolean get() = !targetMet
}

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EasyBCApp
    private val repo = app.repository
    private val cycleCalc = app.cycleCalculator

    private val _currentMonth = MutableStateFlow(YearMonth.now())
    val currentMonth: StateFlow<YearMonth> = _currentMonth

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    private val _viewMode = MutableStateFlow(CalendarViewMode.MONTH)
    val viewMode: StateFlow<CalendarViewMode> = _viewMode

    private val _showDayDetail = MutableStateFlow(false)
    val showDayDetail: StateFlow<Boolean> = _showDayDetail

    val settings: StateFlow<UserSettingsEntity?> = repo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val plannerResult: StateFlow<PlannerResult?> = repo.calendarPlannerResultFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val baselinePlannerResult: StateFlow<PlannerResult?> = repo.calendarBaselinePlannerResultFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val periods: StateFlow<List<PeriodRecord>> = repo.periodsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val bridgeIsNative: Boolean = app.bridge.isNative

    /**
     * Count of past planner days (U/W/C, inside the last 30 days) that the
     * user hasn't reconciled yet. Drives the small banner on the Calendar
     * screen that taps through to [com.easybc.planner.ui.reconcile.ReconcileScreen].
     *
     * Kept intentionally simple — one scalar count — so the chip only ever
     * nags when there's concrete work to do, and disappears the moment the
     * queue empties.
     */
    /**
     * The most recent period record that's still open (no `endDate`) AND
     * whose predicted end is already past today — i.e., the calendar is
     * showing extended bleed days because we don't know whether the user
     * actually stopped bleeding. Surfaces a banner so the user can confirm
     * the end date in one tap rather than hunting through the calendar.
     * Null when no such period exists.
     */
    val openPeriodPastPrediction: StateFlow<PeriodRecord?> = periods.map { periodList ->
        val today = LocalDate.now()
        val open = periodList.filter { it.endDate == null }
            .maxByOrNull { it.startDate } ?: return@map null
        val predictedEnd = cycleCalc.effectiveBleedingEndEpochDay(open, periodList, today)
        // The calendar clamps the visible bleed window at start + max bleed
        // days, so the banner only fires once today is past the original
        // prediction (which is when "still bleeding?" actually becomes a
        // useful question).
        val originalEstimate = open.startDate + cycleCalc.averageBleedDuration(periodList) - 1
        if (today.toEpochDay() > originalEstimate && predictedEnd >= today.toEpochDay()) {
            open
        } else null
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val unreconciledCount: StateFlow<Int> = combine(
        settings, plannerResult, periods, repo.dayLogsFlow,
    ) { s, plan, periodList, dayLogs ->
        if (s == null || plan == null || plan.years.isEmpty()) return@combine 0
        val today = LocalDate.now()
        val cycles = cycleCalc.buildCoverageCycles(periodList, s)
        val logsByDate = dayLogs.associateBy { it.date }
        var count = 0
        var d = today.minusDays(30)
        while (d.isBefore(today)) {
            val info = cycleCalc.dateToCycleDay(d, cycles)
            if (info != null) {
                val dw = plan.years.getOrNull(info.first)?.dayWeights?.getOrNull(info.second - 1)
                if (dw != null && dw.recommendedAction != RecommendedAction.A) {
                    val existing = logsByDate[d.toEpochDay()]
                    if (existing == null || !existing.reconciled) count++
                }
            }
            d = d.plusDays(1)
        }
        count
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    /**
     * Per-cycle risk ledger for the cycle containing *today*. Null when we
     * don't have enough to compute (no plan, no cycles, or today falls
     * outside the coverage). The contract matches `plan.years[i]` ↔
     * `coverageCycles[i]`, same as the calendar cell renderer.
     */
    val currentCycleLedger: StateFlow<CycleLedger?> = combine(
        settings, plannerResult, baselinePlannerResult, periods, repo.dayLogsFlow,
    ) { s, plan, baselinePlan, periodList, dayLogs ->
        if (s == null || plan == null || baselinePlan == null || plan.years.isEmpty()) return@combine null
        val today = LocalDate.now()
        val cycles = cycleCalc.buildCoverageCycles(periodList, s)
        val info = cycleCalc.dateToCycleDay(today, cycles) ?: return@combine null
        val (cycleIdx, dayInCycle) = info
        val year = plan.years.getOrNull(cycleIdx) ?: return@combine null
        val baselineYear = baselinePlan.years.getOrNull(cycleIdx) ?: return@combine null
        val cycle = cycles.getOrNull(cycleIdx) ?: return@combine null

        val cycleStartEpoch = cycle.startDate.toEpochDay()
        val cycleEndEpochExclusive = cycleStartEpoch + cycle.lengthDays
        val todayEpoch = today.toEpochDay()
        val logsThisCycle = dayLogs.filter {
            it.date in cycleStartEpoch until cycleEndEpochExclusive && it.date <= todayEpoch
        }

        // Realized-so-far: chain (1 - r_i) survivals for logged days through today.
        var survival = 1.0
        var baselineSurvival = 1.0
        for (log in logsThisCycle) {
            val dayIdx = (log.date - cycleStartEpoch).toInt() + 1
            if (dayIdx < 1 || dayIdx > cycle.lengthDays) continue
            val dw = year.dayWeights.getOrNull(dayIdx - 1) ?: continue
            val baselineDw = baselineYear.dayWeights.getOrNull(dayIdx - 1) ?: continue
            val r = when (log.actualAction) {
                "U", "CB" -> dw.rawRiskProbability
                "W" -> dw.withdrawalRiskProbability
                "C" -> dw.protectedRiskProbability
                "A" -> 0.0
                "NONE" -> 0.0
                else -> 0.0
            }
            survival *= (1.0 - r).coerceIn(0.0, 1.0)
            baselineSurvival *= (1.0 - baselineDw.recommendedRiskProbability).coerceIn(0.0, 1.0)
        }
        val realized = (1.0 - survival).coerceIn(0.0, 1.0)
        val baselinePlannedForLoggedDays = (1.0 - baselineSurvival).coerceIn(0.0, 1.0)
        val deltaVsBaseline = realized - baselinePlannedForLoggedDays

        CycleLedger(
            currentDayInCycle = dayInCycle,
            cycleLengthDays = cycle.lengthDays,
            // Original plan — from the unlocked baseline, so logging a day
            // never moves this number. The replanned `year.cycleRisk` is
            // intentionally not used here; it's reflected in the horizon
            // fields and the saved/spent-vs-baseline diff instead.
            plannedCycleRisk = baselineYear.cycleRisk,
            realizedSoFar = realized,
            savedRiskVsBaseline = (-deltaVsBaseline).coerceAtLeast(0.0),
            extraRiskVsBaseline = deltaVsBaseline.coerceAtLeast(0.0),
            horizonRisk = plan.achievedCumulativeRisk,
            horizonTarget = s.targetCumulativeFailure,
            targetMet = plan.targetMet,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Generate cell data for the current month view. */
    val monthCells: StateFlow<List<DayCellData>> = combine(
        _currentMonth, plannerResult, periods, repo.dayLogsFlow,
    ) { month, plan, periodList, dayLogs ->
        buildMonthCells(month, plan, periodList, dayLogs)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Generate cell data for the current week view. */
    val weekCells: StateFlow<List<DayCellData>> = combine(
        _selectedDate, plannerResult, periods, repo.dayLogsFlow,
    ) { selected, plan, periodList, dayLogs ->
        buildWeekCells(selected, plan, periodList, dayLogs)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Detail data for the selected day. */
    val selectedDayDetail: StateFlow<DayCellData?> = combine(
        _selectedDate, plannerResult, periods, repo.dayLogsFlow,
    ) { date, plan, periodList, dayLogs ->
        val ctx = buildCalendarContext(periodList, dayLogs)
        buildCellForDate(date, plan, periodList, ctx, isCurrentMonth = true)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
        _currentMonth.value = YearMonth.from(date)
        _showDayDetail.value = true
    }

    fun dismissDayDetail() {
        _showDayDetail.value = false
    }

    fun setViewMode(mode: CalendarViewMode) {
        _viewMode.value = mode
    }

    fun navigateMonth(delta: Int) {
        _currentMonth.value = _currentMonth.value.plusMonths(delta.toLong())
    }

    fun navigateWeek(delta: Int) {
        _selectedDate.value = _selectedDate.value.plusWeeks(delta.toLong())
        _currentMonth.value = YearMonth.from(_selectedDate.value)
    }

    fun goToToday() {
        _selectedDate.value = LocalDate.now()
        _currentMonth.value = YearMonth.now()
    }

    fun logPeriodStart(date: LocalDate) {
        viewModelScope.launch {
            repo.logPeriodStart(date)
        }
    }

    fun endPeriod(periodId: Long, endDate: LocalDate) {
        viewModelScope.launch {
            repo.logPeriodEnd(periodId, endDate)
        }
    }

    /**
     * Find the period record that contains [date] and set its endDate to [date].
     * Called from the day detail sheet when the user taps "Mark period ended on this day".
     */
    fun endCurrentPeriod(date: LocalDate) {
        viewModelScope.launch {
            val epochDay = date.toEpochDay()
            // Use a generous 30-day window here (vs. the display rule's cap at
            // today) so the user can retroactively end an old forgotten period.
            val period = periods.value.find { record ->
                val end = record.endDate ?: (record.startDate + 30)
                epochDay in record.startDate..end
            }
            if (period != null) {
                repo.updatePeriod(period.copy(endDate = epochDay))
            }
        }
    }

    /**
     * Undo a previously-confirmed period end. Reverts the matching period
     * record's `endDate` to `null` so the bleed window is once again
     * predicted from history. Called when the user taps undo on the
     * "period end confirmed" row.
     */
    fun clearPeriodEnd(date: LocalDate) {
        viewModelScope.launch {
            val epochDay = date.toEpochDay()
            val period = periods.value.find { it.endDate == epochDay }
            if (period != null) {
                repo.updatePeriod(period.copy(endDate = null))
            }
        }
    }

    /**
     * Undo a mistakenly-logged period start by deleting the period record
     * whose `startDate` matches [date]. Only callable for the start day
     * itself; deeper period edits live on the History screen.
     */
    fun clearPeriodStart(date: LocalDate) {
        viewModelScope.launch {
            val epochDay = date.toEpochDay()
            val period = periods.value.find { it.startDate == epochDay }
            if (period != null) {
                repo.deletePeriod(period)
            }
        }
    }

    fun logDayAction(date: LocalDate, action: RecommendedAction, notes: String? = null) {
        viewModelScope.launch {
            repo.logDay(date, action, notes)
        }
    }

    /**
     * Clear the logged action for [date]. Body signals on the day are kept;
     * the row is removed entirely if nothing else remains on it.
     */
    fun clearDayAction(date: LocalDate) {
        viewModelScope.launch {
            repo.clearDayAction(date)
        }
    }

    /**
     * Merge-save optional body signals for [date]. Preserves any existing
     * actualAction/notes; does *not* mark the row as reconciled (signals on
     * their own aren't a reconciliation).
     */
    fun logDayObservations(
        date: LocalDate,
        mucus: String? = null,
        bbtCelsius: Double? = null,
        opk: String? = null,
        mittelschmerz: Boolean = false,
        breastTender: Boolean = false,
    ) {
        viewModelScope.launch {
            repo.logObservations(date, mucus, bbtCelsius, opk, mittelschmerz, breastTender)
        }
    }

    /**
     * True iff the user has ever filled in any body-signal field on any
     * day. Drives the "expand observations by default on the Day Detail
     * sheet" heuristic — once a user has opted in even once we stop hiding
     * it behind a collapse.
     */
    val hasEverLoggedObservations: StateFlow<Boolean> = repo.dayLogsFlow
        .map { logs ->
            logs.any { it.mucus != null || it.bbtCelsius != null || it.opk != null ||
                it.mittelschmerz || it.breastTender }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // ── Private helpers ──

    /**
     * Per-render snapshot of the things that are identical for every cell in
     * a month/week grid. Building these once and threading them through
     * [buildCellForDate] turns 42 redundant ~260-cycle rebuilds (and two
     * full cycle scans) per month render into one — the difference between
     * a janky calendar and a smooth one.
     */
    private data class CalendarContext(
        val today: LocalDate,
        val allCycles: List<CycleCalculator.DerivedCycle>,
        val predictedBleedDays: Set<Long>,
        val cycleFlags: Map<LocalDate, CycleCalculator.CycleFlag>,
        val dayLogsByEpoch: Map<Long, DayLog>,
    )

    private fun buildCalendarContext(
        periodList: List<PeriodRecord>,
        dayLogs: List<DayLog>,
    ): CalendarContext {
        val today = LocalDate.now()
        val settingsNow = settings.value
        val allCycles = if (settingsNow != null) {
            cycleCalc.buildCoverageCycles(periodList, settingsNow)
        } else {
            cycleCalc.deriveCycles(periodList)
        }
        return CalendarContext(
            today = today,
            allCycles = allCycles,
            predictedBleedDays = cycleCalc.predictedFutureBleedDays(allCycles, periodList, today),
            cycleFlags = cycleCalc.flagObservedCycles(periodList),
            dayLogsByEpoch = dayLogs.associateBy { it.date },
        )
    }

    private fun buildMonthCells(
        month: YearMonth,
        plan: PlannerResult?,
        periodList: List<PeriodRecord>,
        dayLogs: List<DayLog>,
    ): List<DayCellData> {
        val firstOfMonth = month.atDay(1)
        // Start grid on Monday
        val gridStart = firstOfMonth.with(DayOfWeek.MONDAY).let {
            if (it.isAfter(firstOfMonth)) it.minusWeeks(1) else it
        }
        val ctx = buildCalendarContext(periodList, dayLogs)
        // 6 weeks of grid
        val cells = mutableListOf<DayCellData>()
        for (i in 0 until 42) {
            val date = gridStart.plusDays(i.toLong())
            cells.add(buildCellForDate(date, plan, periodList, ctx, date.month == month.month))
        }
        return cells
    }

    private fun buildWeekCells(
        selectedDate: LocalDate,
        plan: PlannerResult?,
        periodList: List<PeriodRecord>,
        dayLogs: List<DayLog>,
    ): List<DayCellData> {
        val weekStart = selectedDate.with(DayOfWeek.MONDAY)
        val ctx = buildCalendarContext(periodList, dayLogs)
        return (0 until 7).map { i ->
            val date = weekStart.plusDays(i.toLong())
            buildCellForDate(date, plan, periodList, ctx, isCurrentMonth = true)
        }
    }

    private fun buildCellForDate(
        date: LocalDate,
        plan: PlannerResult?,
        periodList: List<PeriodRecord>,
        ctx: CalendarContext,
        isCurrentMonth: Boolean,
    ): DayCellData {
        val today = ctx.today
        val recordedIsPeriod = cycleCalc.isDateInPeriod(date, periodList, today)
        val dayLog = ctx.dayLogsByEpoch[date.toEpochDay()]

        // Find the period record whose bleed window contains this date, if
        // any. Used to surface "this is predicted" vs "you confirmed this"
        // and to flag the recorded period-end day specifically.
        val containingPeriod = if (recordedIsPeriod) {
            val epochDay = date.toEpochDay()
            periodList.find { record ->
                val end = cycleCalc.effectiveBleedingEndEpochDay(record, periodList, today)
                epochDay in record.startDate..end
            }
        } else null
        val isPeriodEndDay = containingPeriod?.endDate == date.toEpochDay()
        val isPeriodStartDay = containingPeriod?.startDate == date.toEpochDay()

        // Single combined cycle list: observed + active + predicted. Must stay
        // aligned with the cycle list the planner saw (see buildCalendarCycles),
        // because plan.years[i] corresponds to allCycles[i]. Built once per
        // render and threaded in via [ctx].
        val allCycles = ctx.allCycles

        // Predicted future bleed window: if this is a *future* cycle's
        // first N days and there's no recorded period for it yet, surface
        // it as a predicted period day so users can plan around it.
        val isPredictedFutureBleed = !recordedIsPeriod &&
            date.toEpochDay() in ctx.predictedBleedDays

        val isPeriod = recordedIsPeriod || isPredictedFutureBleed
        val isPeriodPredicted =
            isPredictedFutureBleed || (recordedIsPeriod && containingPeriod?.endDate == null)

        val cycleInfo = cycleCalc.dateToCycleDay(date, allCycles)
        val cycleDay = cycleInfo?.second
        val cycleIdx = cycleInfo?.first

        val cycleLenForDate = if (cycleIdx != null) {
            allCycles.getOrNull(cycleIdx)?.lengthDays
        } else null

        val phase = if (cycleDay != null && cycleLenForDate != null) {
            cycleCalc.cyclePhase(cycleDay, cycleLenForDate)
        } else null

        // Atypical-cycle flag for the cycle containing this date (closed
        // observed cycles only — predicted/active cycles can't be flagged).
        val cycleStart = if (cycleIdx != null) allCycles.getOrNull(cycleIdx)?.startDate else null
        val cycleFlag = cycleStart?.let { ctx.cycleFlags[it] }

        // Map to planner output
        var plannerAction: RecommendedAction? = null
        var riskScore: Int? = null
        var overrideCost: OverrideCost? = null

        if (plan != null && cycleIdx != null && cycleDay != null) {
            val yearOutput = plan.years.getOrNull(cycleIdx)
            if (yearOutput != null) {
                val dw = yearOutput.dayWeights.getOrNull(cycleDay - 1)
                if (dw != null) {
                    plannerAction = dw.recommendedAction
                    riskScore = dw.rawRiskScore
                    overrideCost = dw.overrideCost
                }
            }
        }

        return DayCellData(
            date = date,
            dayOfMonth = date.dayOfMonth,
            isCurrentMonth = isCurrentMonth,
            isToday = date == today,
            isPeriod = isPeriod,
            cycleDay = cycleDay,
            cycleLengthDays = cycleLenForDate,
            phase = phase,
            plannerAction = plannerAction,
            riskScore = riskScore,
            overrideCost = overrideCost,
            dayLog = dayLog,
            cycleFlag = cycleFlag,
            isPeriodPredicted = isPeriodPredicted,
            isPeriodEndDay = isPeriodEndDay,
            isPeriodStartDay = isPeriodStartDay,
            hasPeriodRecord = recordedIsPeriod,
        )
    }

}
