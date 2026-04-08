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
)

enum class CalendarViewMode { MONTH, WEEK }

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

    val plannerResult: StateFlow<PlannerResult?> = repo.plannerResultFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val periods: StateFlow<List<PeriodRecord>> = repo.periodsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val bridgeIsNative: Boolean = app.bridge.isNative

    /** Generate cell data for the current month view. */
    val monthCells: StateFlow<List<DayCellData>> = combine(
        _currentMonth, plannerResult, periods, repo.dayLogsFlow,
    ) { month, plan, periodList, dayLogs ->
        buildMonthCells(month, plan, periodList, dayLogs)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Generate cell data for the current week view. */
    val weekCells: StateFlow<List<DayCellData>> = combine(
        _selectedDate, plannerResult, periods, repo.dayLogsFlow,
    ) { selected, plan, periodList, dayLogs ->
        buildWeekCells(selected, plan, periodList, dayLogs)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Detail data for the selected day. */
    val selectedDayDetail: StateFlow<DayCellData?> = combine(
        _selectedDate, plannerResult, periods, repo.dayLogsFlow,
    ) { date, plan, periodList, dayLogs ->
        buildCellForDate(date, YearMonth.from(date), plan, periodList, dayLogs, isCurrentMonth = true)
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

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

    fun logDayAction(date: LocalDate, action: RecommendedAction, notes: String? = null) {
        viewModelScope.launch {
            repo.logDay(date, action, notes)
        }
    }

    // ── Private helpers ──

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
        // 6 weeks of grid
        val cells = mutableListOf<DayCellData>()
        for (i in 0 until 42) {
            val date = gridStart.plusDays(i.toLong())
            cells.add(buildCellForDate(date, month, plan, periodList, dayLogs, date.month == month.month))
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
        return (0 until 7).map { i ->
            val date = weekStart.plusDays(i.toLong())
            buildCellForDate(date, YearMonth.from(date), plan, periodList, dayLogs, isCurrentMonth = true)
        }
    }

    private fun buildCellForDate(
        date: LocalDate,
        month: YearMonth,
        plan: PlannerResult?,
        periodList: List<PeriodRecord>,
        dayLogs: List<DayLog>,
        isCurrentMonth: Boolean,
    ): DayCellData {
        val today = LocalDate.now()
        val isPeriod = isDateInPeriod(date, periodList)
        val dayLog = dayLogs.find { LocalDate.ofEpochDay(it.date) == date }

        // Map date to cycle/day using period data
        val observedCycles = cycleCalc.deriveCycles(periodList)
        val stats = cycleCalc.computeStats(observedCycles)
        val avgLen = stats?.averageLength ?: settings.value?.cycleLengthDays?.toDouble() ?: 28.0
        val lastPeriod = periodList.maxByOrNull { it.startDate }
        val predictedCycles = if (lastPeriod != null) {
            cycleCalc.predictFutureCycles(LocalDate.ofEpochDay(lastPeriod.startDate), avgLen, 60)
        } else {
            emptyList()
        }

        val cycleInfo = cycleCalc.dateToyCycleDay(date, observedCycles, predictedCycles)
        val cycleDay = cycleInfo?.second
        val cycleIdx = cycleInfo?.first

        val cycleLenForDate = if (cycleIdx != null) {
            val allCycles = observedCycles + predictedCycles
            allCycles.getOrNull(cycleIdx)?.lengthDays
        } else null

        val phase = if (cycleDay != null && cycleLenForDate != null) {
            cycleCalc.cyclePhase(cycleDay, cycleLenForDate)
        } else null

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
        )
    }

    private fun isDateInPeriod(date: LocalDate, periods: List<PeriodRecord>): Boolean {
        val epochDay = date.toEpochDay()
        return periods.any { record ->
            val end = record.endDate ?: (record.startDate + 5) // default 5-day period
            epochDay in record.startDate..end
        }
    }
}
