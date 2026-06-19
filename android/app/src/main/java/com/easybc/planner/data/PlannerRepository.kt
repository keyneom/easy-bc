package com.easybc.planner.data

import com.easybc.planner.bridge.PlannerBridge
import com.easybc.planner.data.db.*
import com.easybc.planner.util.CycleCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.time.LocalDate

class PlannerRepository(
    private val db: AppDatabase,
    private val bridge: PlannerBridge,
    private val cycleCalc: CycleCalculator,
    /**
     * App-lifetime scope used to host the *shared* planner flows below. The
     * planner is expensive (a full multi-year Rust optimization), so we
     * compute each variant ONCE and broadcast it to every consumer instead
     * of letting each ViewModel's collector re-run it from scratch.
     */
    private val appScope: CoroutineScope,
) {
    private val settingsDao = db.userSettingsDao()
    private val periodDao = db.periodRecordDao()
    private val dayLogDao = db.dayLogDao()

    val settingsFlow: Flow<UserSettingsEntity?> = settingsDao.getSettingsFlow()
    val periodsFlow: Flow<List<PeriodRecord>> = periodDao.getAllAscFlow()
    val dayLogsFlow: Flow<List<DayLog>> = dayLogDao.getAllFlow()

    // The planner is an expensive multi-year Rust optimization. Each of the
    // three flows below is `shareIn`'d so every consumer (Calendar,
    // Reconcile, Settings, auto-sync) shares ONE computation instead of
    // re-running the optimizer per collector.
    //
    // `WhileSubscribed(SHARE_KEEPALIVE_MS)`: the upstream stays alive for a
    // few seconds after the last collector leaves, which comfortably covers
    // screen-to-screen navigation (Calendar → Reconcile reuses the warm
    // result) while still releasing the Room observers + recompute loop when
    // the app sits idle or backgrounded.
    //
    // `replay = 1`: re-subscribers get the last value immediately, and
    // `.first()` keeps its "wait for the first real emission" semantics
    // rather than returning a premature initial value.

    /**
     * Reactive long-range planner result. Recomputes from settings only; actual
     * logged calendar days are handled by [calendarPlannerResultFlow].
     * Emits null if settings aren't initialized yet.
     */
    val plannerResultFlow: Flow<PlannerResult?> = settingsFlow.map { s ->
        if (s == null || !s.onboardingComplete) return@map null
        computePlan(s, periods = emptyList(), dayLogs = emptyList(), useCalendarCycles = false)
    }.flowOn(Dispatchers.Default)
        .shareIn(appScope, SharingStarted.WhileSubscribed(SHARE_KEEPALIVE_MS), replay = 1)

    /**
     * Calendar-shaped plan with the user's logged actions fed in as locks.
     * Shared so navigating between the Calendar, Reconcile, and Settings
     * screens reuses one computation instead of re-running the optimizer
     * per screen.
     */
    val calendarPlannerResultFlow: Flow<PlannerResult?> =
        combine(settingsFlow, periodsFlow, dayLogsFlow) { s, p, d ->
            if (s == null || !s.onboardingComplete) return@combine null
            computePlan(s, p, d, useCalendarCycles = true)
        }.flowOn(Dispatchers.Default)
            .shareIn(appScope, SharingStarted.WhileSubscribed(SHARE_KEEPALIVE_MS), replay = 1)

    /**
     * Calendar-shaped plan without actual-day locks. Used for comparing what
     * has happened so far against the original recommendation in risk units.
     * Shared, and intentionally does NOT depend on `dayLogsFlow`, so logging
     * a day never re-runs this baseline.
     */
    val calendarBaselinePlannerResultFlow: Flow<PlannerResult?> =
        combine(settingsFlow, periodsFlow) { s, p ->
            if (s == null || !s.onboardingComplete) return@combine null
            computePlan(s, p, dayLogs = emptyList(), useCalendarCycles = true)
        }.flowOn(Dispatchers.Default)
            .shareIn(appScope, SharingStarted.WhileSubscribed(SHARE_KEEPALIVE_MS), replay = 1)

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

        // Wall-calendar mode needs one closed observed cycle so the active
        // cycle can be anchored to real dates. The posterior still shrinks
        // heavily toward the age prior while history is short.
        val observedCycles = if (useCalendarCycles) cycleCalc.deriveCycles(periods) else emptyList()
        val calendarCycles = if (useCalendarCycles && observedCycles.isNotEmpty()) {
            cycleCalc.buildCalendarCycles(periods, settings)
        } else {
            null
        }

        // Logged day actions need to see the active cycle too. In calendar
        // mode, feed them into the core as fixed actions so the optimizer
        // replans around what actually happened instead of using a separate
        // approximate budget burn.
        val coverageCycles = if (useCalendarCycles) {
            cycleCalc.buildCoverageCycles(periods, settings)
        } else {
            emptyList()
        }
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
            realizedCumulativeRisk = 0.0,
            initialActionLocks = loggedActionLocks,
        )

        val json = PlannerJson.encodeToString(UserOptions.serializer(), options)
        val bridgeStart = System.nanoTime()
        val resultJson = bridge.planFromJson(json).getOrNull()
        val bridgeMs = (System.nanoTime() - bridgeStart) / 1_000_000
        val decodeStart = System.nanoTime()
        val result = resultJson?.let {
            try {
                PlannerJson.decodeFromString<PlannerResult>(it)
            } catch (_: Exception) {
                null
            }
        }
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
        // Lightweight perf trace — surfaces whether a slow plan is the Rust
        // optimizer (bridge) or the JSON round-trip (decode), and how many
        // cycles were in play. `adb logcat -s PlannerPerf` to watch.
        android.util.Log.d(
            "PlannerPerf",
            "computePlan calendar=$useCalendarCycles " +
                "cycles=${calendarCycles?.size ?: settings.horizonYears} " +
                "locks=${loggedActionLocks.size} bridge=${bridgeMs}ms decode=${decodeMs}ms",
        )
        return result
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

    /**
     * Clear the logged action for [date], leaving any body signals and notes
     * intact. The day reverts to un-reconciled (it's no longer a user-
     * confirmed day). If the row has nothing else on it — no signals, no
     * notes — the whole row is deleted so the table doesn't accumulate
     * empty rows.
     */
    suspend fun clearDayAction(date: LocalDate) {
        val existing = dayLogDao.getForDate(date.toEpochDay()) ?: return
        val hasSignals = existing.mucus != null || existing.bbtCelsius != null ||
            existing.opk != null || existing.mittelschmerz || existing.breastTender
        val hasNotes = !existing.notes.isNullOrBlank()
        if (hasSignals || hasNotes) {
            dayLogDao.upsert(existing.copy(actualAction = "", reconciled = false))
        } else {
            dayLogDao.delete(existing)
        }
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

    private companion object {
        /**
         * How long a shared planner flow stays warm after its last collector
         * unsubscribes. Long enough to bridge screen-to-screen navigation
         * (so e.g. Calendar → Reconcile reuses the computed plan), short
         * enough that an idle/backgrounded app stops recomputing.
         */
        const val SHARE_KEEPALIVE_MS = 5_000L
    }
}
