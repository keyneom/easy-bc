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
     */
    fun deriveCycles(periods: List<PeriodRecord>): List<DerivedCycle> {
        val sorted = periods.sortedBy { it.startDate }
        if (sorted.size < 2) return emptyList()

        val cycles = mutableListOf<DerivedCycle>()
        for (i in 0 until sorted.size - 1) {
            val start = LocalDate.ofEpochDay(sorted[i].startDate)
            val nextStart = LocalDate.ofEpochDay(sorted[i + 1].startDate)
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
     * 3. Cap at the day before the next period start (so bleeding can't
     *    overlap into the next cycle).
     *
     * We deliberately **do not** cap at today anymore: for an unresolved
     * period we show the predicted remaining bleeding days going forward so
     * the user sees when their period is expected to end. The `today`
     * parameter is kept for API stability but no longer truncates.
     *
     * Mirrors the web `derivedBleedingEnd` in spirit; both platforms agree.
     */
    @Suppress("UNUSED_PARAMETER")
    fun effectiveBleedingEndEpochDay(
        record: PeriodRecord,
        allPeriods: List<PeriodRecord>,
        today: LocalDate = LocalDate.now(),
    ): Long {
        record.endDate?.let { return it }

        val closedOthers = allPeriods.filter { it.endDate != null && it.id != record.id }
        val fallbackDays = if (closedOthers.size >= 3) {
            val mean = closedOthers
                .sumOf { (it.endDate!! - it.startDate + 1).toDouble() } / closedOthers.size
            mean.roundToInt().coerceIn(2, 14)
        } else {
            5
        }

        val estimatedEnd = record.startDate + fallbackDays - 1 // inclusive
        val nextStartMinus1 = allPeriods
            .filter { it.startDate > record.startDate }
            .minByOrNull { it.startDate }
            ?.let { it.startDate - 1 }
            ?: Long.MAX_VALUE

        return minOf(estimatedEnd, nextStartMinus1)
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
}
