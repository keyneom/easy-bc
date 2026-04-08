package com.easybc.planner.util

import com.easybc.planner.data.CycleInstance
import com.easybc.planner.data.db.PeriodRecord
import com.easybc.planner.data.db.UserSettingsEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Derives cycle statistics and predictions from period records. */
class CycleCalculator {

    data class DerivedCycle(
        val startDate: LocalDate,
        val lengthDays: Int,
        val isObserved: Boolean,
    )

    data class CycleStats(
        val averageLength: Double,
        val sdDays: Double,
        val count: Int,
    )

    /** Compute observed cycle lengths from consecutive period starts. */
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

    /** Statistics from observed cycles. */
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

    /** Predict future cycle starts from the last known period. */
    fun predictFutureCycles(
        lastPeriodStart: LocalDate,
        averageLength: Double,
        count: Int,
    ): List<DerivedCycle> {
        val predicted = mutableListOf<DerivedCycle>()
        var currentStart = lastPeriodStart.plusDays(averageLength.roundToLong())
        for (i in 0 until count) {
            val length = averageLength.roundToInt()
            predicted.add(DerivedCycle(currentStart, length, isObserved = false))
            currentStart = currentStart.plusDays(length.toLong())
        }
        return predicted
    }

    /**
     * Build CycleInstance list for the Rust planner's calendar_cycles mode.
     *
     * Combines observed past cycles with predicted future cycles up to the horizon.
     * Age is computed relative to the user's current age and the date distance.
     */
    fun buildCalendarCycles(
        observedCycles: List<DerivedCycle>,
        settings: UserSettingsEntity,
        today: LocalDate = LocalDate.now(),
    ): List<CycleInstance> {
        val stats = computeStats(observedCycles)
        val avgLen = stats?.averageLength ?: settings.cycleLengthDays.toDouble()
        val sd = stats?.sdDays?.coerceAtLeast(1.0) ?: settings.ovulationSdDays

        // Determine last period start
        val lastObserved = observedCycles.maxByOrNull { it.startDate }
        val lastStart = lastObserved?.startDate
            ?: today // If no periods logged, start predictions from today

        // Predict enough future cycles to cover the horizon
        val horizonEnd = today.plusYears(settings.horizonYears.toLong())
        val predictedCycles = mutableListOf<DerivedCycle>()
        var nextStart = lastStart.plusDays(avgLen.roundToLong())
        while (nextStart.isBefore(horizonEnd)) {
            val length = avgLen.roundToInt()
            predictedCycles.add(DerivedCycle(nextStart, length, isObserved = false))
            nextStart = nextStart.plusDays(length.toLong())
        }

        // Combine past observed + future predicted
        val allCycles = observedCycles + predictedCycles

        return allCycles.map { cycle ->
            val yearsFromNow = ChronoUnit.DAYS.between(today, cycle.startDate) / 365.25
            val ageAtCycle = (settings.ageYears + yearsFromNow).roundToInt().coerceIn(15, 55)
            CycleInstance(
                cycleLengthDays = cycle.lengthDays,
                cycleSdDays = sd,
                actsPerWeek = settings.actsPerWeek,
                ageYears = ageAtCycle,
            )
        }
    }

    /**
     * Determine which cycle a given date falls in.
     * Returns (cycleIndex, dayInCycle) or null if date is outside known cycles.
     */
    fun dateToyCycleDay(
        date: LocalDate,
        observedCycles: List<DerivedCycle>,
        predictedCycles: List<DerivedCycle>,
    ): Pair<Int, Int>? {
        val allCycles = (observedCycles + predictedCycles).sortedBy { it.startDate }
        for ((index, cycle) in allCycles.withIndex()) {
            val cycleEnd = cycle.startDate.plusDays(cycle.lengthDays.toLong())
            if (!date.isBefore(cycle.startDate) && date.isBefore(cycleEnd)) {
                val dayInCycle = ChronoUnit.DAYS.between(cycle.startDate, date).toInt() + 1
                return Pair(index, dayInCycle)
            }
        }
        return null
    }

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
}
