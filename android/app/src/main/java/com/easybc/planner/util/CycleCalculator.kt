package com.easybc.planner.util

import com.easybc.planner.data.CycleInstance
import com.easybc.planner.data.db.PeriodRecord
import com.easybc.planner.data.db.UserSettingsEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Derives cycle statistics and predictions from period records.
 *
 * The unit of reasoning is a [DerivedCycle] — a contiguous window starting at
 * a period's first bleeding day and running for [DerivedCycle.lengthDays] days.
 *
 * **Active cycle:** the cycle currently in progress — the one starting at the
 * most recent period log. [deriveCycles] only returns **closed** cycles
 * (pairs of consecutive starts), so the active cycle is *not* in its output.
 * Callers that need coverage for "today" must use
 * [predictCyclesFromActive] / [buildCalendarCycles], which prepend a synthetic
 * active cycle before predicted future cycles.
 */
class CycleCalculator {

    data class DerivedCycle(
        val startDate: LocalDate,
        val lengthDays: Int,
        val isObserved: Boolean,
    )

    /** Legacy simple stats kept for screens that don't need the posterior. */
    data class CycleStats(
        val averageLength: Double,
        val sdDays: Double,
        val count: Int,
    )

    /** Bayesian posterior over cycle length, fusing age prior + observed history. */
    data class CycleLengthPosterior(
        val mean: Double,
        val predictiveSd: Double,
        val lower: Int,
        val upper: Int,
        val observedCount: Int,
    )

    // ── Observed cycles ──────────────────────────────────────────────────

    /**
     * Compute **closed** observed cycle lengths from consecutive period starts.
     * A period whose start has no successor (the active cycle) is deliberately
     * excluded — see the class doc.
     *
     * Periods with `excludeFromStats == true` invalidate BOTH the cycle ending
     * at them and the cycle starting at them. We deliberately don't bridge
     * across an excluded record (start at A, skip B, end at C) — that would
     * invent a 2x-length phantom cycle that misrepresents the user's data.
     */
    fun deriveCycles(periods: List<PeriodRecord>): List<DerivedCycle> {
        val sorted = periods.sortedBy { it.startDate }
        if (sorted.size < 2) return emptyList()

        val cycles = mutableListOf<DerivedCycle>()
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (a.excludeFromStats || b.excludeFromStats) continue
            val start = LocalDate.ofEpochDay(a.startDate)
            val nextStart = LocalDate.ofEpochDay(b.startDate)
            val length = ChronoUnit.DAYS.between(start, nextStart).toInt()
            if (length in 21..60) {
                cycles.add(DerivedCycle(start, length, isObserved = true))
            }
        }
        return cycles
    }

    /** Plain mean + sample SD of observed cycles — back-compat only. */
    fun computeStats(cycles: List<DerivedCycle>): CycleStats? {
        val observed = cycles.filter { it.isObserved }
        if (observed.isEmpty()) return null
        val lengths = observed.map { it.lengthDays.toDouble() }
        val avg = lengths.average()
        val variance = if (lengths.size > 1) {
            lengths.sumOf { (it - avg) * (it - avg) } / (lengths.size - 1)
        } else {
            0.0
        }
        return CycleStats(avg, sqrt(variance), observed.size)
    }

    // ── Bayesian posterior on cycle length ──────────────────────────────

    /**
     * Age-based reference cycle length used as the prior mean.
     * Mirrors `referenceCycleLengthForAge` in `web/src/periodTracker.ts` and
     * the Rust core — keep them in sync.
     */
    fun referenceCycleLengthForAge(age: Int): Double = when {
        age < 20 -> 30.0
        age < 25 -> 29.0
        age < 30 -> 28.5
        age < 35 -> 28.0
        age < 40 -> 27.5
        age < 43 -> 27.0
        age < 46 -> 28.0
        age < 48 -> 32.0
        age < 50 -> 40.0
        else -> 55.0
    }

    /** Trimmed mean cycle length. With enough history, trims one value per side. */
    fun trimmedMeanLength(lengths: List<Int>, trimEachSide: Int = 0): Double {
        if (lengths.isEmpty()) return 28.0
        val s = lengths.sorted()
        val lo = minOf(trimEachSide, s.size / 4)
        val hi = s.size - lo
        val slice = if (hi > lo) s.subList(lo, hi) else emptyList()
        return if (slice.isEmpty()) s[s.size / 2].toDouble()
        else slice.sumOf { it.toDouble() } / slice.size
    }

    private fun sampleStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = values.average()
        val v = values.sumOf { (it - m) * (it - m) } / (values.size - 1)
        return sqrt(v)
    }

    /** Extra days to add to ovulation SD when lengths are noisy (capped). */
    fun sdWidenFromVariance(historyLengths: List<Int>): Double {
        val sd = sampleStdDev(historyLengths.map { it.toDouble() })
        if (sd < 1.5) return 0.0
        return minOf(4.0, (sd - 1.5) * 0.6)
    }

    /**
     * Bayesian fusion of age prior (N(refL, 4²)) with the user's empirical mean
     * and SD. Returns posterior mean and predictive SD (posterior + observation).
     * Mirrors `cycleLengthPosterior` in `web/src/periodTracker.ts`.
     */
    fun cycleLengthPosterior(
        historyLengths: List<Int>,
        ageYears: Int,
    ): CycleLengthPosterior {
        val priorMean = referenceCycleLengthForAge(ageYears)
        val priorSd = 4.0
        if (historyLengths.isEmpty()) {
            val lower = maxOf(21, (priorMean - 1.28 * priorSd).roundToInt())
            val upper = minOf(60, (priorMean + 1.28 * priorSd).roundToInt())
            return CycleLengthPosterior(priorMean, priorSd, lower, upper, 0)
        }

        val trimmed = trimmedMeanLength(historyLengths, if (historyLengths.size >= 5) 1 else 0)
        val empiricalSdRaw = sampleStdDev(historyLengths.map { it.toDouble() })
        // Floor empirical SD: single observation has no real variance; unrealistically
        // low SDs over-trust the empirical mean.
        val empiricalSd = maxOf(1.75, if (empiricalSdRaw > 0.0) empiricalSdRaw else 2.5)
        val priorVar = priorSd * priorSd
        val obsVar = empiricalSd * empiricalSd
        val n = historyLengths.size
        val posteriorPrecision = 1.0 / priorVar + n / obsVar
        val posteriorMean = ((priorMean / priorVar) + (n * trimmed) / obsVar) / posteriorPrecision
        val posteriorVar = 1.0 / posteriorPrecision
        val predictiveSd = sqrt(posteriorVar + obsVar)
        val lower = maxOf(21, (posteriorMean - 1.28 * predictiveSd).roundToInt())
        val upper = minOf(60, (posteriorMean + 1.28 * predictiveSd).roundToInt())
        return CycleLengthPosterior(posteriorMean, predictiveSd, lower, upper, n)
    }

    /**
     * Blend personal mean with age reference; shrinks toward the reference when
     * history is short (full weight on personal once n ≥ 6).
     */
    fun blendedCycleLength(personalMean: Double, ageYears: Int, nObserved: Int): Int {
        val ref = referenceCycleLengthForAge(ageYears)
        val w = minOf(1.0, nObserved.toDouble() / 6.0)
        val b = w * personalMean + (1.0 - w) * ref
        return b.roundToInt().coerceIn(21, 60)
    }

    // ── Active & predicted cycle coverage ───────────────────────────────

    /**
     * Build the list of cycles covering the **active cycle** and as many future
     * cycles as requested. The first entry starts at [lastPeriodStart] — i.e.,
     * today's active cycle — so callers get coverage for every day from the most
     * recent period forward.
     */
    fun predictCyclesFromActive(
        lastPeriodStart: LocalDate,
        averageLength: Double,
        count: Int,
    ): List<DerivedCycle> {
        if (count <= 0) return emptyList()
        val length = averageLength.roundToInt().coerceIn(21, 60)
        val predicted = mutableListOf<DerivedCycle>()
        var currentStart = lastPeriodStart
        for (i in 0 until count) {
            predicted.add(DerivedCycle(currentStart, length, isObserved = false))
            currentStart = currentStart.plusDays(length.toLong())
        }
        return predicted
    }

    /**
     * Build a `CycleInstance` list for the Rust planner's `calendar_cycles` mode.
     *
     * Contains: closed observed cycles from [deriveCycles] + the active cycle +
     * predicted future cycles, in chronological order. The plan's `years` array
     * will be indexed in that same order, so UI code mapping a calendar date to
     * a planner year must use [dateToCycleDay] against the same combined list.
     */
    fun buildCalendarCycles(
        periods: List<PeriodRecord>,
        settings: UserSettingsEntity,
        today: LocalDate = LocalDate.now(),
    ): List<CycleInstance> {
        val observedCycles = deriveCycles(periods)
        val lengths = observedCycles.map { it.lengthDays }
        val posterior = cycleLengthPosterior(lengths, settings.ageYears)
        val avgLen = blendedCycleLength(posterior.mean, settings.ageYears, lengths.size)
        val widen = sdWidenFromVariance(lengths)
        val effectiveSd = minOf(
            10.0,
            settings.ovulationSdDays + widen + posterior.predictiveSd * 0.35,
        ).coerceAtLeast(1.0)

        // Active cycle: starts at the most recent period log. Falls back to today
        // when the user has logged no periods.
        val lastStart = periods.maxByOrNull { it.startDate }
            ?.let { LocalDate.ofEpochDay(it.startDate) }
            ?: today

        val horizonEnd = today.plusYears(settings.horizonYears.toLong())
        val futureCycles = mutableListOf<DerivedCycle>()
        var start = lastStart
        while (start.isBefore(horizonEnd)) {
            futureCycles.add(DerivedCycle(start, avgLen, isObserved = false))
            start = start.plusDays(avgLen.toLong())
        }

        val allCycles = observedCycles + futureCycles

        return allCycles.map { cycle ->
            val yearsFromNow = ChronoUnit.DAYS.between(today, cycle.startDate) / 365.25
            val ageAtCycle = (settings.ageYears + yearsFromNow).roundToInt().coerceIn(15, 55)
            CycleInstance(
                cycleLengthDays = cycle.lengthDays,
                cycleSdDays = effectiveSd,
                actsPerWeek = settings.actsPerWeek,
                ageYears = ageAtCycle,
            )
        }
    }

    /**
     * Build the combined (observed + active + predicted) cycle list used for
     * wall-calendar lookups. The index into the returned list matches the
     * `yearIndex` in the planner's output.
     */
    fun buildCoverageCycles(
        periods: List<PeriodRecord>,
        settings: UserSettingsEntity,
        predictedCount: Int = 60,
    ): List<DerivedCycle> {
        val observedCycles = deriveCycles(periods)
        val posterior = cycleLengthPosterior(observedCycles.map { it.lengthDays }, settings.ageYears)
        val avgLen = blendedCycleLength(
            posterior.mean,
            settings.ageYears,
            observedCycles.size,
        ).toDouble()

        val lastStart = periods.maxByOrNull { it.startDate }
            ?.let { LocalDate.ofEpochDay(it.startDate) }

        val future = if (lastStart != null) {
            predictCyclesFromActive(lastStart, avgLen, predictedCount)
        } else {
            emptyList()
        }

        return observedCycles + future
    }

    /**
     * Find the cycle containing [date] and the 1-based day-in-cycle, or null if
     * [date] is outside all known cycles.
     */
    fun dateToCycleDay(
        date: LocalDate,
        cycles: List<DerivedCycle>,
    ): Pair<Int, Int>? {
        val sorted = cycles.sortedBy { it.startDate }
        for ((index, cycle) in sorted.withIndex()) {
            val cycleEnd = cycle.startDate.plusDays(cycle.lengthDays.toLong())
            if (!date.isBefore(cycle.startDate) && date.isBefore(cycleEnd)) {
                val dayInCycle = ChronoUnit.DAYS.between(cycle.startDate, date).toInt() + 1
                return Pair(index, dayInCycle)
            }
        }
        return null
    }

    /** @deprecated use [dateToCycleDay] with a single combined list. */
    @Deprecated(
        "Use dateToCycleDay(date, combined) — separate observed/predicted lists were error-prone",
        ReplaceWith("dateToCycleDay(date, observedCycles + predictedCycles)"),
    )
    fun dateToyCycleDay(
        date: LocalDate,
        observedCycles: List<DerivedCycle>,
        predictedCycles: List<DerivedCycle>,
    ): Pair<Int, Int>? = dateToCycleDay(date, observedCycles + predictedCycles)

    /** Estimate cycle phase from day-in-cycle and cycle length. */
    fun cyclePhase(dayInCycle: Int, cycleLengthDays: Int): CyclePhase {
        val ovulationDay = cycleLengthDays - 14
        return when {
            dayInCycle <= 5 -> CyclePhase.MENSTRUAL
            dayInCycle < ovulationDay - 3 -> CyclePhase.FOLLICULAR
            dayInCycle <= ovulationDay + 1 -> CyclePhase.OVULATORY
            else -> CyclePhase.LUTEAL
        }
    }

    enum class CyclePhase(val label: String) {
        MENSTRUAL("Menstrual"),
        FOLLICULAR("Follicular"),
        OVULATORY("Fertile"),
        LUTEAL("Luteal"),
    }

    // ── Period-end fallback (#5) ────────────────────────────────────────

    /**
     * Effective last bleeding day (epoch day, **inclusive**) for display and
     * "is this day bleeding?" checks.
     *
     * Rules:
     * 1. If [record.endDate] is set, use it.
     * 2. Else use the user's mean closed-period duration (requires ≥3 closed
     *    periods for stability), otherwise a 5-day default.
     * 3. **If today is past the estimated end, extend through today** —
     *    when the user hasn't logged an end yet and we're already past the
     *    prediction, the safe assumption is they're still bleeding (and
     *    they need the calendar to show today so they can confirm an end
     *    here). Capped at start + [BLEED_DURATION_MAX_DAYS] so a forgotten
     *    period doesn't sprawl indefinitely.
     * 4. Cap at the day before the next period start (so bleeding can't
     *    overlap into the next cycle).
     *
     * Mirrors the web `derivedBleedingEnd` in spirit; both platforms agree.
     */
    fun effectiveBleedingEndEpochDay(
        record: PeriodRecord,
        allPeriods: List<PeriodRecord>,
        today: LocalDate = LocalDate.now(),
    ): Long {
        record.endDate?.let { return it }

        val closedOthers = allPeriods.filter {
            it.endDate != null && it.id != record.id && !it.excludeFromStats
        }
        val fallbackDays = if (closedOthers.size >= 3) {
            val mean = closedOthers
                .sumOf { (it.endDate!! - it.startDate + 1).toDouble() } / closedOthers.size
            mean.roundToInt().coerceIn(2, 14)
        } else {
            5
        }

        val estimatedEnd = record.startDate + fallbackDays - 1 // inclusive
        val todayEpoch = today.toEpochDay()
        val maxOpenEnd = record.startDate + BLEED_DURATION_MAX_DAYS - 1
        val openEnd = if (todayEpoch > estimatedEnd) {
            todayEpoch.coerceAtMost(maxOpenEnd)
        } else {
            estimatedEnd
        }

        val nextStartMinus1 = allPeriods
            .filter { it.startDate > record.startDate }
            .minByOrNull { it.startDate }
            ?.let { it.startDate - 1 }
            ?: Long.MAX_VALUE

        return minOf(openEnd, nextStartMinus1)
    }

    /** True if [date] falls within any period's bleeding window. */
    fun isDateInPeriod(
        date: LocalDate,
        periods: List<PeriodRecord>,
        today: LocalDate = LocalDate.now(),
    ): Boolean {
        val epochDay = date.toEpochDay()
        return periods.any { record ->
            val end = effectiveBleedingEndEpochDay(record, periods, today)
            epochDay in record.startDate..end
        }
    }

    /**
     * Average bleed duration in days, derived from the user's closed periods.
     * Falls back to 5 (population mean) when there aren't enough samples.
     * Used both as a within-period extrapolation when the user hasn't ended
     * the current period yet AND as the bleed-window length for predicted
     * future cycles. Periods flagged `excludeFromStats` are skipped.
     */
    fun averageBleedDuration(periods: List<PeriodRecord>): Int {
        val closed = periods.filter { it.endDate != null && !it.excludeFromStats }
        if (closed.size < 3) return 5
        val mean = closed
            .sumOf { (it.endDate!! - it.startDate + 1).toDouble() } / closed.size
        return mean.roundToInt().coerceIn(2, 14)
    }

    /**
     * Epoch-day set of every day inside a *predicted* future bleed window —
     * i.e., the first N days of each cycle in [cycles] whose start is after
     * today and which doesn't already correspond to a recorded period start.
     * Lets the calendar render predicted future periods so the user can plan
     * around them; pairs with `isPeriodPredicted` to render hollow markers.
     */
    fun predictedFutureBleedDays(
        cycles: List<DerivedCycle>,
        periods: List<PeriodRecord>,
        today: LocalDate = LocalDate.now(),
    ): Set<Long> {
        val bleedDuration = averageBleedDuration(periods)
        val recordedStarts = periods.map { it.startDate }.toSet()
        val out = mutableSetOf<Long>()
        for (cycle in cycles) {
            if (!cycle.startDate.isAfter(today)) continue
            val startEpoch = cycle.startDate.toEpochDay()
            if (startEpoch in recordedStarts) continue
            for (offset in 0 until bleedDuration) {
                out.add(startEpoch + offset)
            }
        }
        return out
    }

    // ── Anovulatory / atypical-cycle flag ───────────────────────────────

    /**
     * Outcome of anovulatory-heuristic scoring for a single closed cycle.
     *
     * **Why flag at all:** a cycle that's unusually short or unusually long,
     * or has very short / very long bleeding, is more likely to be
     * anovulatory. In that case our standard "ovulation = cycle_length − 14"
     * heuristic can miss the actual fertile window, so we widen it.
     *
     * This is a coarse signal — it never shifts fertile-window location,
     * only widens it. It is zero-user-effort: computed from period start
     * and end dates only.
     */
    enum class CycleFlag {
        /** No atypical signal — predictions use normal widths. */
        NORMAL,
        /** Cycle length is outside the user's typical range or population bounds. */
        ATYPICAL_LENGTH,
        /** Bleeding duration is outside the user's typical range or population bounds. */
        ATYPICAL_BLEED,
        /** Both length and bleeding duration are atypical — strongest hint. */
        ATYPICAL_BOTH,
    }

    companion object {
        /** Population hard bounds — always flag outside these, regardless of history. */
        const val CYCLE_LENGTH_MIN_DAYS = 21
        const val CYCLE_LENGTH_MAX_DAYS = 40
        const val BLEED_DURATION_MIN_DAYS = 2
        const val BLEED_DURATION_MAX_DAYS = 8

        /** SD floors — keep very-regular users from getting spurious flags. */
        const val CYCLE_LENGTH_SD_FLOOR_DAYS = 2.0
        const val BLEED_DURATION_SD_FLOOR_DAYS = 1.5

        /** Z-score threshold for personal outlier. ~8% false-positive under normal. */
        const val ATYPICAL_Z_THRESHOLD = 1.75

        /** Minimum closed samples before we trust a personal SD estimate. */
        const val MIN_SAMPLES_FOR_PERSONAL_THRESHOLD = 4

        /**
         * Extra days added on *each* side of the fertile window for a cycle
         * flagged ATYPICAL. The window already widens with overall cycle
         * variance via [sdWidenFromVariance]; this is a per-cycle bump on
         * top of that for the specific cycles we don't trust.
         */
        const val ATYPICAL_CYCLE_FERTILE_WINDOW_EXTRA_DAYS = 2
    }

    /**
     * Flag a single cycle as atypical based on its own length and the
     * bleeding duration of [record] (the period that starts it), compared
     * against the user's history.
     *
     * Rule:
     * 1. **Hard bounds:** cycle length outside [21, 40] or bleeding outside
     *    [2, 8] always flags.
     * 2. **Personal outlier:** with ≥ 4 closed samples, flag if
     *    |value − mean| > 1.75 × max(sample_sd, floor). Floors prevent
     *    very-regular users from flagging on natural small variation.
     *
     * If `record.endDate` is null the bleeding side is not evaluated — we
     * only flag based on what we can actually measure.
     */
    fun flagForCycle(
        cycle: DerivedCycle,
        allCycleLengths: List<Int>,
        record: PeriodRecord?,
        allClosedBleedDurations: List<Int>,
    ): CycleFlag {
        val lengthAtypical = isLengthAtypical(cycle.lengthDays, allCycleLengths)
        val bleedDuration = record?.endDate?.let { end ->
            (end - record.startDate + 1).toInt()
        }
        val bleedAtypical = bleedDuration?.let {
            isBleedDurationAtypical(it, allClosedBleedDurations)
        } ?: false
        return when {
            lengthAtypical && bleedAtypical -> CycleFlag.ATYPICAL_BOTH
            lengthAtypical -> CycleFlag.ATYPICAL_LENGTH
            bleedAtypical -> CycleFlag.ATYPICAL_BLEED
            else -> CycleFlag.NORMAL
        }
    }

    /**
     * Convenience: compute a map of cycle-start → [CycleFlag] across all
     * observed closed cycles for the given periods. Unflagged cycles are
     * not in the map (callers should treat missing as [CycleFlag.NORMAL]).
     */
    fun flagObservedCycles(periods: List<PeriodRecord>): Map<LocalDate, CycleFlag> {
        val cycles = deriveCycles(periods)
        if (cycles.isEmpty()) return emptyMap()
        val lengths = cycles.map { it.lengthDays }
        val bleedDurations = periods
            .filter { it.endDate != null }
            .map { (it.endDate!! - it.startDate + 1).toInt() }
        val periodByStart = periods.associateBy { it.startDate }
        val result = mutableMapOf<LocalDate, CycleFlag>()
        for (cycle in cycles) {
            val rec = periodByStart[cycle.startDate.toEpochDay()]
            val flag = flagForCycle(cycle, lengths, rec, bleedDurations)
            if (flag != CycleFlag.NORMAL) result[cycle.startDate] = flag
        }
        return result
    }

    private fun isLengthAtypical(value: Int, history: List<Int>): Boolean {
        if (value < CYCLE_LENGTH_MIN_DAYS || value > CYCLE_LENGTH_MAX_DAYS) return true
        return isPersonalOutlier(
            value.toDouble(),
            history.map { it.toDouble() },
            CYCLE_LENGTH_SD_FLOOR_DAYS,
        )
    }

    private fun isBleedDurationAtypical(value: Int, history: List<Int>): Boolean {
        if (value < BLEED_DURATION_MIN_DAYS || value > BLEED_DURATION_MAX_DAYS) return true
        return isPersonalOutlier(
            value.toDouble(),
            history.map { it.toDouble() },
            BLEED_DURATION_SD_FLOOR_DAYS,
        )
    }

    private fun isPersonalOutlier(value: Double, history: List<Double>, sdFloor: Double): Boolean {
        if (history.size < MIN_SAMPLES_FOR_PERSONAL_THRESHOLD) return false
        val mean = history.average()
        val sampleSd = sqrt(
            history.sumOf { (it - mean) * (it - mean) } / (history.size - 1)
        )
        val effectiveSd = maxOf(sampleSd, sdFloor)
        return kotlin.math.abs(value - mean) > ATYPICAL_Z_THRESHOLD * effectiveSd
    }
}
