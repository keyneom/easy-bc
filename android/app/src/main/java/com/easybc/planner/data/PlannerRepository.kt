package com.easybc.planner.data

import com.easybc.planner.bridge.PlannerBridge
import com.easybc.planner.data.db.*
import com.easybc.planner.util.CycleCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.time.LocalDate

class PlannerRepository(
    private val db: AppDatabase,
    private val bridge: PlannerBridge,
    private val cycleCalc: CycleCalculator,
) {
    private val settingsDao = db.userSettingsDao()
    private val periodDao = db.periodRecordDao()
    private val dayLogDao = db.dayLogDao()

    val settingsFlow: Flow<UserSettingsEntity?> = settingsDao.getSettingsFlow()
    val periodsFlow: Flow<List<PeriodRecord>> = periodDao.getAllAscFlow()
    val dayLogsFlow: Flow<List<DayLog>> = dayLogDao.getAllFlow()

    /**
     * Reactive planner result. Recomputes whenever settings or periods change.
     * Emits null if settings aren't initialized yet.
     */
    val plannerResultFlow: Flow<PlannerResult?> = combine(settingsFlow, periodsFlow, dayLogsFlow) { s, p, d ->
        if (s == null || !s.onboardingComplete) return@combine null
        computePlan(s, p, d, useCalendarCycles = false)
    }.flowOn(Dispatchers.Default)

    val calendarPlannerResultFlow: Flow<PlannerResult?> = combine(settingsFlow, periodsFlow, dayLogsFlow) { s, p, d ->
        if (s == null || !s.onboardingComplete) return@combine null
        computePlan(s, p, d, useCalendarCycles = true)
    }.flowOn(Dispatchers.Default)

    /** Run the planner synchronously (called on Dispatchers.Default). */
    private fun computePlan(
        settings: UserSettingsEntity,
        periods: List<PeriodRecord>,
        dayLogs: List<DayLog>,
        useCalendarCycles: Boolean,
    ): PlannerResult? {
        val condomMode = try {
            CondomMode.entries.first { it.name.lowercase() == settings.condomMode }
        } catch (_: Exception) {
            CondomMode.Typical
        }
        val persistentMethod = try {
            PersistentMethod.entries.first { it.name.equals(settings.persistentMethod, ignoreCase = true) }
        } catch (_: Exception) {
            PersistentMethod.None
        }
        val protectedDayMethod = try {
            ProtectedDayMethod.entries.first { it.name.equals(settings.protectedDayMethod, ignoreCase = true) }
        } catch (_: Exception) {
            ProtectedDayMethod.ExternalCondom
        }
        val withdrawalMode = try {
            WithdrawalMode.entries.first { it.name.equals(settings.withdrawalMode, ignoreCase = true) }
        } catch (_: Exception) {
            WithdrawalMode.None
        }

        val observedCycles = cycleCalc.deriveCycles(periods)
        // Wall-calendar mode needs one closed observed cycle so the active
        // cycle can be anchored to real dates. The posterior still shrinks
        // heavily toward the age prior while history is short.
        val calendarCycles = if (useCalendarCycles && observedCycles.isNotEmpty()) {
            cycleCalc.buildCalendarCycles(periods, settings)
        } else {
            null
        }

        // Logged day actions need to see the active cycle too. In calendar
        // mode, feed them into the core as fixed actions so the optimizer
        // replans around what actually happened instead of using a separate
        // approximate budget burn.
        val coverageCycles = cycleCalc.buildCoverageCycles(periods, settings)
        val loggedActionLocks = if (calendarCycles != null) {
            buildLoggedActionLocks(
                dayLogs = dayLogs,
                coverageCycles = coverageCycles,
                planRowCount = calendarCycles.size,
                protectedDayMethod = protectedDayMethod,
                withdrawalMode = withdrawalMode,
            )
        } else {
            emptyList()
        }
        val realizedRisk = if (calendarCycles == null) {
            computeRealizedRisk(dayLogs, coverageCycles, settings)
        } else {
            0.0
        }

        val options = UserOptions(
            ageYears = settings.ageYears,
            horizonYears = settings.horizonYears,
            targetCumulativeFailure = settings.targetCumulativeFailure,
            cycleLengthDays = settings.cycleLengthDays,
            actsPerWeek = settings.actsPerWeek,
            persistentMethod = persistentMethod,
            protectedDayMethod = protectedDayMethod,
            condomMode = condomMode,
            customCondomResidual = settings.customCondomResidual,
            streakAversion = settings.streakAversion,
            holdLifecycleConstant = settings.holdLifecycleConstant,
            withdrawalMode = withdrawalMode,
            withdrawalTypicalAnnualFailure = settings.withdrawalTypicalAnnualFailure,
            withdrawalRelativeRisk = settings.withdrawalRelativeRisk,
            useWithdrawalBackupOnProtectedDays = settings.useWithdrawalBackupOnProtectedDays,
            combinedMethodIndependence = settings.combinedMethodIndependence,
            ovulationSdDays = settings.ovulationSdDays,
            calendarCycles = calendarCycles?.takeIf { it.isNotEmpty() },
            realizedCumulativeRisk = realizedRisk.coerceAtMost(settings.targetCumulativeFailure),
            initialActionLocks = loggedActionLocks,
        )

        val json = PlannerJson.encodeToString(UserOptions.serializer(), options)
        return bridge.planFromJson(json).getOrNull()?.let { resultJson ->
            try {
                PlannerJson.decodeFromString<PlannerResult>(resultJson)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Rough estimate of realized cumulative risk from logged day actions.
     * For each logged day that was unprotected or withdrawal, adds risk proportional
     * to that day's estimated conception probability. This is approximate — the real
     * implementation should use the planner's own risk model, but this works for MVP.
     */
    private fun computeRealizedRisk(
        dayLogs: List<DayLog>,
        coverageCycles: List<CycleCalculator.DerivedCycle>,
        settings: UserSettingsEntity,
    ): Double {
        if (dayLogs.isEmpty() || coverageCycles.isEmpty()) return 0.0

        val today = LocalDate.now()
        var survivalProduct = 1.0

        for (log in dayLogs) {
            val date = LocalDate.ofEpochDay(log.date)
            if (date.isAfter(today)) continue

            val cycleInfo = cycleCalc.dateToCycleDay(date, coverageCycles) ?: continue
            val (_, dayInCycle) = cycleInfo

            // Simple fertile-window risk estimate
            val cycle = coverageCycles.getOrNull(cycleInfo.first) ?: continue
            val ovuDay = cycle.lengthDays - 14
            val dist = kotlin.math.abs(dayInCycle - ovuDay)
            val baseRisk = when {
                dist == 0 -> 0.33
                dist == 1 -> 0.31
                dist == 2 -> 0.27
                dist == 3 -> 0.14
                dist == 4 -> 0.16
                dist == 5 -> 0.10
                else -> 0.0
            }

            val multiplier = when (log.actualAction) {
                "U" -> 1.0
                "W" -> settings.withdrawalRelativeRisk
                "C" -> 0.03  // approximate condom residual
                "A" -> 0.0
                // Reconciliation outcomes:
                "NONE" -> 0.0  // "abstained" — no intercourse, regardless of what was planned
                "CB" -> 1.0    // condom broke — treat as realized U
                // "" (observations-only row) or unknown → contribute no risk
                else -> 0.0
            }

            val dayRisk = baseRisk * multiplier * ageMultiplier(settings.ageYears)
            survivalProduct *= (1.0 - dayRisk)
        }

        return (1.0 - survivalProduct).coerceIn(0.0, 1.0)
    }

    private fun buildLoggedActionLocks(
        dayLogs: List<DayLog>,
        coverageCycles: List<CycleCalculator.DerivedCycle>,
        planRowCount: Int,
        protectedDayMethod: ProtectedDayMethod,
        withdrawalMode: WithdrawalMode,
    ): List<DayOverride> {
        if (dayLogs.isEmpty() || coverageCycles.isEmpty() || planRowCount <= 0) return emptyList()

        val allowProtected = protectedDayMethod != ProtectedDayMethod.None
        val allowWithdrawal = withdrawalMode != WithdrawalMode.None

        return dayLogs.mapNotNull { log ->
            val lockedAction = when (log.actualAction) {
                "U", "CB" -> RecommendedAction.U
                "W" -> if (allowWithdrawal) RecommendedAction.W else RecommendedAction.U
                "C" -> if (allowProtected) RecommendedAction.C else RecommendedAction.U
                "A", "NONE" -> RecommendedAction.A
                else -> null
            } ?: return@mapNotNull null

            val date = LocalDate.ofEpochDay(log.date)
            val (cycleIdx, dayInCycle) = cycleCalc.dateToCycleDay(date, coverageCycles)
                ?: return@mapNotNull null
            if (cycleIdx !in 0 until planRowCount) return@mapNotNull null

            DayOverride(
                yearIndex = cycleIdx,
                day = dayInCycle,
                action = lockedAction,
            )
        }.sortedWith(compareBy<DayOverride> { it.yearIndex }.thenBy { it.day })
    }

    private fun ageMultiplier(age: Int): Double = when {
        age in 19..26 -> 1.00
        age in 27..29 -> 0.86
        age in 30..34 -> 0.77
        age in 35..37 -> 0.63
        age in 38..40 -> 0.49
        age in 41..44 -> 0.28
        age >= 45 -> 0.10
        else -> 1.00
    }

    // ── Settings mutations ──

    suspend fun saveSettings(settings: UserSettingsEntity) {
        settingsDao.save(settings)
    }

    suspend fun getSettings(): UserSettingsEntity? = settingsDao.getSettings()

    // ── Period mutations ──

    suspend fun logPeriodStart(date: LocalDate) {
        periodDao.insert(PeriodRecord(startDate = date.toEpochDay()))
    }

    suspend fun logPeriodEnd(periodId: Long, endDate: LocalDate) {
        periodDao.getLatest()?.let { latest ->
            if (latest.id == periodId) {
                periodDao.update(latest.copy(endDate = endDate.toEpochDay()))
            }
        }
    }

    suspend fun updatePeriod(record: PeriodRecord) {
        periodDao.update(record)
    }

    suspend fun deletePeriod(record: PeriodRecord) {
        periodDao.delete(record)
    }

    suspend fun getLatestPeriod(): PeriodRecord? = periodDao.getLatest()

    // ── Day log mutations ──

    /**
     * Log what actually happened on a day. Preserves any already-logged
     * body-signal fields (mucus/BBT/OPK/Mittelschmerz/breast tenderness)
     * and notes unless explicitly overwritten. A logged day is considered
     * reconciled automatically, so it won't show up in the reconciliation
     * chip.
     */
    suspend fun logDay(date: LocalDate, action: RecommendedAction, notes: String? = null) {
        reconcileDay(date, action.shortLabel, notes)
    }

    /**
     * Reconcile a specific day to an action string. Accepts the planner
     * actions (U/W/C/A) plus the reconciliation-only outcomes:
     *   - "NONE" — no intercourse happened
     *   - "CB"   — planned C but breakage
     *
     * The call is idempotent and merge-safe: we preserve any already-logged
     * body signals and notes (unless [notes] is non-null, in which case we
     * overwrite).
     */
    suspend fun reconcileDay(
        date: LocalDate,
        actualAction: String,
        notes: String? = null,
    ) {
        val existing = dayLogDao.getForDate(date.toEpochDay())
        val merged = existing?.copy(
            actualAction = actualAction,
            notes = notes ?: existing.notes,
            reconciled = true,
        ) ?: DayLog(
            date = date.toEpochDay(),
            actualAction = actualAction,
            notes = notes,
            reconciled = true,
        )
        dayLogDao.upsert(merged)
    }

    /**
     * Reconcile a contiguous range of days to the same action. Used by the
     * batch reconciliation UI for "we were traveling for a week, mark all
     * these days as abstained" style bulk entry. Body signals on each day
     * are preserved.
     */
    suspend fun reconcileRange(
        startInclusive: LocalDate,
        endInclusive: LocalDate,
        actualAction: String,
    ) {
        var d = startInclusive
        while (!d.isAfter(endInclusive)) {
            reconcileDay(d, actualAction)
            d = d.plusDays(1)
        }
    }

    /**
     * Save optional body signals for a day without overwriting action/notes
     * already logged. A row created purely from observations has
     * `actualAction = ""` and `reconciled = false` — it still shows up in
     * the reconciliation chip so the user can confirm what happened.
     */
    suspend fun logObservations(
        date: LocalDate,
        mucus: String? = null,
        bbtCelsius: Double? = null,
        opk: String? = null,
        mittelschmerz: Boolean = false,
        breastTender: Boolean = false,
    ) {
        val existing = dayLogDao.getForDate(date.toEpochDay())
        val merged = existing?.copy(
            mucus = mucus,
            bbtCelsius = bbtCelsius,
            opk = opk,
            mittelschmerz = mittelschmerz,
            breastTender = breastTender,
        ) ?: DayLog(
            date = date.toEpochDay(),
            actualAction = "",
            mucus = mucus,
            bbtCelsius = bbtCelsius,
            opk = opk,
            mittelschmerz = mittelschmerz,
            breastTender = breastTender,
        )
        dayLogDao.upsert(merged)
    }

    suspend fun getDayLog(date: LocalDate): DayLog? {
        return dayLogDao.getForDate(date.toEpochDay())
    }

    fun dayLogsForRange(start: LocalDate, end: LocalDate): Flow<List<DayLog>> {
        return dayLogDao.getForRangeFlow(start.toEpochDay(), end.toEpochDay())
    }

    /**
     * Flow of past days (on or after [fromEpochDay], before [beforeEpochDay])
     * that haven't been reconciled. Used by the home-screen reconciliation
     * chip to surface only actionable items.
     */
    fun unreconciledLogsFlow(fromEpochDay: Long, beforeEpochDay: Long) =
        dayLogDao.getUnreconciledInRangeFlow(fromEpochDay, beforeEpochDay)
}
