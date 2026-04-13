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
        computePlan(s, p, d)
    }.flowOn(Dispatchers.Default)

    /** Run the planner synchronously (called on Dispatchers.Default). */
    private fun computePlan(
        settings: UserSettingsEntity,
        periods: List<PeriodRecord>,
        dayLogs: List<DayLog>,
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
        val calendarCycles = if (observedCycles.size >= 2) {
            cycleCalc.buildCalendarCycles(observedCycles, settings)
        } else {
            null
        }

        // Compute realized risk from past day logs
        val realizedRisk = computeRealizedRisk(dayLogs, observedCycles, settings)

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
        observedCycles: List<CycleCalculator.DerivedCycle>,
        settings: UserSettingsEntity,
    ): Double {
        if (dayLogs.isEmpty() || observedCycles.isEmpty()) return 0.0

        val today = LocalDate.now()
        var survivalProduct = 1.0

        for (log in dayLogs) {
            val date = LocalDate.ofEpochDay(log.date)
            if (date.isAfter(today)) continue

            val cycleInfo = cycleCalc.dateToyCycleDay(date, observedCycles, emptyList()) ?: continue
            val (_, dayInCycle) = cycleInfo

            // Simple fertile-window risk estimate
            val cycle = observedCycles.getOrNull(cycleInfo.first) ?: continue
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
                else -> 0.0
            }

            val dayRisk = baseRisk * multiplier * ageMultiplier(settings.ageYears)
            survivalProduct *= (1.0 - dayRisk)
        }

        return (1.0 - survivalProduct).coerceIn(0.0, 1.0)
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

    suspend fun logDay(date: LocalDate, action: RecommendedAction, notes: String? = null) {
        dayLogDao.upsert(
            DayLog(
                date = date.toEpochDay(),
                actualAction = action.shortLabel,
                notes = notes,
            )
        )
    }

    suspend fun getDayLog(date: LocalDate): DayLog? {
        return dayLogDao.getForDate(date.toEpochDay())
    }

    fun dayLogsForRange(start: LocalDate, end: LocalDate): Flow<List<DayLog>> {
        return dayLogDao.getForRangeFlow(start.toEpochDay(), end.toEpochDay())
    }
}
