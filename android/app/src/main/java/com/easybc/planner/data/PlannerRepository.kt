package com.easybc.planner.data

import com.easybc.planner.BuildConfig
import com.easybc.planner.bridge.PlannerBridge
import com.easybc.planner.data.db.*
import com.easybc.planner.util.CycleCalculator
import com.easybc.planner.util.EcModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** A logged Plan B / EC dose, located within the planner's cycle grid. */
private data class EcDose(
    val row: Int,
    val day: Int,
    val type: EcModel.EcType,
    val hours: Double?,
)

/** Mirrors the Rust `EcEffectRequest` / `EcEffectResponse` JSON boundary. */
@Serializable
private data class EcEffectRequest(
    val ecType: String,
    val hoursFromAct: Double? = null,
    val actCycleDay: Double,
    val ovulationMeanDay: Double,
    val ovulationSdDays: Double,
)

@Serializable
private data class EcEffectResponse(
    val conceptionMultiplier: Double = 1.0,
    val conceptionMultiplierLow: Double = 1.0,
    val conceptionMultiplierHigh: Double = 1.0,
    val ovulationDelayDays: Double = 0.0,
)

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
    private val dayEventDao = db.dayEventDao()
    private val syncMetadataDao = db.syncMetadataDao()

    val settingsFlow: Flow<UserSettingsEntity?> = settingsDao.getSettingsFlow()
    val periodsFlow: Flow<List<PeriodRecord>> = periodDao.getAllAscFlow()
    val dayLogsFlow: Flow<List<DayLog>> = dayLogDao.getAllFlow()
    val dayEventsFlow: Flow<List<DayEventEntity>> = dayEventDao.getAllFlow()

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
        combine(settingsFlow, periodsFlow, dayLogsFlow, dayEventsFlow) { s, p, d, events ->
            if (s == null || !s.onboardingComplete) return@combine null
            computePlan(s, p, d, events, useCalendarCycles = true)
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
            computePlan(
                s,
                p,
                dayLogs = emptyList(),
                dayEvents = emptyList(),
                useCalendarCycles = true,
            )
        }.flowOn(Dispatchers.Default)
            .shareIn(appScope, SharingStarted.WhileSubscribed(SHARE_KEEPALIVE_MS), replay = 1)

    /** Run the planner synchronously (called on Dispatchers.Default). */
    private fun computePlan(
        settings: UserSettingsEntity,
        periods: List<PeriodRecord>,
        dayLogs: List<DayLog>,
        dayEvents: List<DayEventEntity> = emptyList(),
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
        val baseCalendarCycles = if (useCalendarCycles && observedCycles.isNotEmpty()) {
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
        val calendarCycles = baseCalendarCycles?.toMutableList()?.also { cycles ->
            val active = cycleCalc.dateToCycleDay(LocalDate.now(), coverageCycles)
            if (active != null && active.first in cycles.indices) {
                val cycle = coverageCycles[active.first]
                val signals = deriveCurrentCycleBodySignals(dayLogs, cycle)
                if (signals != null) {
                    cycles[active.first] = cycles[active.first].copy(bodySignals = signals)
                }
            }
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

        val baseOptions = UserOptions(
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

        val bridgeStart = System.nanoTime()
        val probeJson = PlannerJson.encodeToString(UserOptions.serializer(), baseOptions)
        val probe = bridge.planFromJson(probeJson).getOrNull()?.let {
            runCatching { PlannerJson.decodeFromString<PlannerResult>(it) }.getOrNull()
        }
        val realized = if (
            useCalendarCycles &&
            probe != null &&
            calendarCycles != null &&
            dayEvents.isNotEmpty()
        ) {
            aggregateInFlightEventRisk(
                settings = settings,
                periods = periods,
                events = dayEvents,
                coverageCycles = coverageCycles,
                plan = probe,
            )
        } else {
            0.0
        }
        val options = baseOptions.copy(realizedCumulativeRisk = realized)
        val resultJson = if (realized > 0.0) {
            bridge.planFromJson(
                PlannerJson.encodeToString(UserOptions.serializer(), options)
            ).getOrNull()
        } else {
            probe?.let { PlannerJson.encodeToString(PlannerResult.serializer(), it) }
        }
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
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "PlannerPerf",
                "computePlan calendar=$useCalendarCycles " +
                    "cycles=${calendarCycles?.size ?: settings.horizonYears} " +
                    "locks=${loggedActionLocks.size} bridge=${bridgeMs}ms decode=${decodeMs}ms",
            )
        }
        return result
    }

    private fun deriveCurrentCycleBodySignals(
        dayLogs: List<DayLog>,
        cycle: CycleCalculator.DerivedCycle,
    ): BodySignalInputs? {
        val start = cycle.startDate.toEpochDay()
        val endExclusive = start + cycle.lengthDays
        var mucusPeakDay: Int? = null
        var lhSurgeDay: Int? = null
        for (log in dayLogs) {
            if (log.date !in start until endExclusive) continue
            val cycleDay = (log.date - start).toInt() + 1
            if (log.mucus == "eggwhite") {
                mucusPeakDay = maxOf(mucusPeakDay ?: 0, cycleDay)
            }
            if (log.opk == "positive" || log.opk == "peak") {
                lhSurgeDay = maxOf(lhSurgeDay ?: 0, cycleDay)
            }
        }
        if (mucusPeakDay == null && lhSurgeDay == null) return null
        return BodySignalInputs(
            cervicalMucusPeakDay = mucusPeakDay,
            lhSurgeDay = lhSurgeDay,
        )
    }

    /**
     * Estimate an EC dose's effect via the Rust core (native `.so`). Returns
     * [EcModel.Estimate.NONE] only when the native estimator is unavailable
     * (mock bridge / dev build without the library).
     */
    fun estimateEcEffect(
        ecType: EcModel.EcType,
        hoursFromAct: Double?,
        actCycleDay: Double,
        ovulationMeanDay: Double,
        ovulationSdDays: Double,
    ): EcModel.Estimate {
        val request = EcEffectRequest(
            ecType = ecType.wire,
            hoursFromAct = hoursFromAct,
            actCycleDay = actCycleDay,
            ovulationMeanDay = ovulationMeanDay,
            ovulationSdDays = ovulationSdDays,
        )
        val responseJson = bridge.ecEffectFromJson(
            PlannerJson.encodeToString(EcEffectRequest.serializer(), request),
        ).getOrNull() ?: return EcModel.Estimate.NONE
        val resp = runCatching {
            PlannerJson.decodeFromString(EcEffectResponse.serializer(), responseJson)
        }.getOrNull() ?: return EcModel.Estimate.NONE
        return EcModel.Estimate(
            conceptionMultiplier = resp.conceptionMultiplier,
            conceptionMultiplierLow = resp.conceptionMultiplierLow,
            conceptionMultiplierHigh = resp.conceptionMultiplierHigh,
            ovulationDelayDays = resp.ovulationDelayDays,
        )
    }

    /**
     * Conservative additional-risk estimate for explicit incidents in the
     * active (unresolved) cycle. A new period changes [currentStart], so the
     * previous cycle's events drop out automatically.
     *
     * Event probabilities share one unknown ovulation day, so summing their
     * marginal per-act probabilities is used as a conservative union bound
     * instead of assuming independence. The plan's embedded per-day risk is
     * subtracted once per incident day to avoid double-counting.
     */
    private fun aggregateInFlightEventRisk(
        settings: UserSettingsEntity,
        periods: List<PeriodRecord>,
        events: List<DayEventEntity>,
        coverageCycles: List<CycleCalculator.DerivedCycle>,
        plan: PlannerResult,
    ): Double {
        val currentStart = periods.maxOfOrNull { it.startDate } ?: return 0.0
        val today = LocalDate.now().toEpochDay()
        val persistentResidual = plan.validation.methodLibrary.persistentMethodResidual
        if (!persistentResidual.isFinite()) return 0.0

        // Plan B / EC doses in the active window, mapped to (row, dayInCycle).
        val doses = events.asSequence()
            .filter { it.date in currentStart..today && it.kind == "plan_b_taken" }
            .mapNotNull { ev ->
                val info = cycleCalc.dateToCycleDay(LocalDate.ofEpochDay(ev.date), coverageCycles)
                    ?: return@mapNotNull null
                EcModel.EcType.fromWire(ev.ecType)?.let { type ->
                    EcDose(info.first, info.second, type, ev.hoursFromAct)
                }
            }
            .toList()

        var incidentUpperBound = 0.0
        var embeddedPlanRisk = 0.0
        events
            .asSequence()
            .filter {
                it.date in currentStart..today &&
                    (it.kind == "condom_broke" || it.kind == "unplanned_unprotected")
            }
            .groupBy { it.date }
            .forEach { (epochDay, dayEvents) ->
                val info = cycleCalc.dateToCycleDay(LocalDate.ofEpochDay(epochDay), coverageCycles)
                    ?: return@forEach
                val year = plan.years.getOrNull(info.first) ?: return@forEach
                val dayWeight = year.dayWeights.getOrNull(info.second - 1) ?: return@forEach
                // Strongest EC dose covering an act on this day (dose on/after it).
                val ovuMean = year.signalSummary?.posteriorOvulationMeanDay
                    ?: (year.cycleLengthDays - 14).toDouble()
                val ovuSd = year.signalSummary?.posteriorOvulationSdDays ?: settings.ovulationSdDays
                var ecMultiplier = 1.0
                for (dose in doses) {
                    if (dose.row == info.first && dose.day >= info.second) {
                        val est = estimateEcEffect(
                            dose.type, dose.hours, info.second.toDouble(), ovuMean, ovuSd,
                        )
                        ecMultiplier = minOf(ecMultiplier, est.conceptionMultiplier)
                    }
                }
                incidentUpperBound += dayEvents.size *
                    dayWeight.perActConceptionProbability *
                    persistentResidual *
                    ecMultiplier
                embeddedPlanRisk += dayWeight.recommendedRiskProbability
            }

        return (incidentUpperBound - embeddedPlanRisk)
            .coerceIn(0.0, settings.targetCumulativeFailure)
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
        val current = settingsDao.getSettings()
        val now = System.currentTimeMillis()
        settingsDao.save(
            settings.copy(
                updatedAt = if (current == null || !current.samePlannerSettings(settings)) now else current.updatedAt,
                androidPreferencesUpdatedAt = if (
                    current == null || !current.samePortableAndroidPreferences(settings)
                ) now else current.androidPreferencesUpdatedAt,
            )
        )
    }

    suspend fun getSettings(): UserSettingsEntity? = settingsDao.getSettings()

    // ── Period mutations ──

    suspend fun logPeriodStart(date: LocalDate) {
        val epochDay = date.toEpochDay()
        val now = System.currentTimeMillis()
        periodDao.insert(PeriodRecord(startDate = epochDay, createdAt = now, updatedAt = now))
        syncMetadataDao.delete(periodDeletionKey(epochDay))
    }

    suspend fun logPeriodEnd(periodId: Long, endDate: LocalDate) {
        periodDao.getLatest()?.let { latest ->
            if (latest.id == periodId) {
                periodDao.update(latest.copy(endDate = endDate.toEpochDay(), updatedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun updatePeriod(record: PeriodRecord) {
        periodDao.update(record.copy(updatedAt = System.currentTimeMillis()))
        syncMetadataDao.delete(periodDeletionKey(record.startDate))
    }

    suspend fun deletePeriod(record: PeriodRecord) {
        periodDao.delete(record)
        syncMetadataDao.put(
            SyncMetadataEntity(periodDeletionKey(record.startDate), System.currentTimeMillis().toString())
        )
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
            updatedAt = System.currentTimeMillis(),
        ) ?: DayLog(
            date = date.toEpochDay(),
            actualAction = actualAction,
            notes = notes,
            reconciled = true,
        )
        dayLogDao.upsert(merged)
        syncMetadataDao.delete(dayDeletionKey(date.toEpochDay()))
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
            updatedAt = System.currentTimeMillis(),
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
        syncMetadataDao.delete(dayDeletionKey(date.toEpochDay()))
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
        val hasEvents = dayEventDao.countForDate(date.toEpochDay()) > 0
        if (hasSignals || hasNotes || hasEvents) {
            dayLogDao.upsert(
                existing.copy(actualAction = "", reconciled = false, updatedAt = System.currentTimeMillis())
            )
        } else {
            dayLogDao.delete(existing)
            syncMetadataDao.put(
                SyncMetadataEntity(dayDeletionKey(date.toEpochDay()), System.currentTimeMillis().toString())
            )
        }
    }

    suspend fun getDayLog(date: LocalDate): DayLog? {
        return dayLogDao.getForDate(date.toEpochDay())
    }

    suspend fun logDayEvent(
        date: LocalDate,
        kind: String,
        ecType: String? = null,
        hoursFromAct: Double? = null,
        notes: String? = null,
    ) {
        require(kind in EVENT_KINDS) { "Unsupported event kind: $kind" }
        if (kind == "plan_b_taken") {
            require(ecType in EC_TYPES) { "Emergency contraception type is required" }
        }
        val now = System.currentTimeMillis()
        val occurredAt = date.atTime(LocalTime.now())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        dayEventDao.upsert(
            DayEventEntity(
                id = UUID.randomUUID().toString(),
                date = date.toEpochDay(),
                kind = kind,
                ecType = ecType,
                hoursFromAct = hoursFromAct?.coerceIn(0.0, 120.0),
                occurredAt = occurredAt,
                notes = notes,
                updatedAt = now,
            )
        )
        touchDayLog(date, now)
        syncMetadataDao.delete(dayDeletionKey(date.toEpochDay()))
    }

    suspend fun deleteDayEvent(event: DayEventEntity) {
        dayEventDao.delete(event)
        touchDayLog(LocalDate.ofEpochDay(event.date), System.currentTimeMillis())
    }

    private suspend fun touchDayLog(date: LocalDate, updatedAt: Long) {
        val existing = dayLogDao.getForDate(date.toEpochDay())
        dayLogDao.upsert(
            existing?.copy(updatedAt = updatedAt) ?: DayLog(
                date = date.toEpochDay(),
                actualAction = "",
                reconciled = false,
                updatedAt = updatedAt,
            )
        )
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
        fun periodDeletionKey(epochDay: Long) = "period_deleted:$epochDay"
        fun dayDeletionKey(epochDay: Long) = "day_deleted:$epochDay"

        fun UserSettingsEntity.samePlannerSettings(other: UserSettingsEntity): Boolean =
            ageYears == other.ageYears &&
                horizonYears == other.horizonYears &&
                targetCumulativeFailure == other.targetCumulativeFailure &&
                cycleLengthDays == other.cycleLengthDays &&
                actsPerWeek == other.actsPerWeek &&
                persistentMethod == other.persistentMethod &&
                protectedDayMethod == other.protectedDayMethod &&
                condomMode == other.condomMode &&
                customCondomResidual == other.customCondomResidual &&
                streakAversion == other.streakAversion &&
                holdLifecycleConstant == other.holdLifecycleConstant &&
                withdrawalMode == other.withdrawalMode &&
                withdrawalTypicalAnnualFailure == other.withdrawalTypicalAnnualFailure &&
                withdrawalRelativeRisk == other.withdrawalRelativeRisk &&
                useWithdrawalBackupOnProtectedDays == other.useWithdrawalBackupOnProtectedDays &&
                combinedMethodIndependence == other.combinedMethodIndependence &&
                ovulationSdDays == other.ovulationSdDays

        fun UserSettingsEntity.samePortableAndroidPreferences(other: UserSettingsEntity): Boolean =
            calendarLabelPeriod == other.calendarLabelPeriod &&
                calendarLabelFertile == other.calendarLabelFertile &&
                calendarLabelActionU == other.calendarLabelActionU &&
                calendarLabelActionC == other.calendarLabelActionC &&
                calendarLabelActionA == other.calendarLabelActionA &&
                calendarLabelActionW == other.calendarLabelActionW &&
                reminderHour == other.reminderHour &&
                reminderMinute == other.reminderMinute

        /**
         * How long a shared planner flow stays warm after its last collector
         * unsubscribes. Long enough to bridge screen-to-screen navigation
         * (so e.g. Calendar → Reconcile reuses the computed plan), short
         * enough that an idle/backgrounded app stops recomputing.
         */
        const val SHARE_KEEPALIVE_MS = 5_000L
        val EVENT_KINDS = setOf("condom_broke", "unplanned_unprotected", "plan_b_taken")
        val EC_TYPES = setOf("levonorgestrel", "ulipristal", "copper_iud")
    }
}
