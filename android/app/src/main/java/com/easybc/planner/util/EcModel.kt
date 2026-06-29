package com.easybc.planner.util

/**
 * Emergency-contraception types shared across the app. The model itself lives in
 * the Rust core (`crates/planner-core/src/ec.rs`) and runs on Android through the
 * uniFFI native library (`ecEffectEstimateJson`); see
 * `PlannerRepository.estimateEcEffect`. There is deliberately no Kotlin
 * reimplementation — the `.so` is the single source of truth.
 */
object EcModel {
    enum class EcType {
        LEVONORGESTREL,
        ULIPRISTAL,
        COPPER_IUD;

        val wire: String get() = when (this) {
            LEVONORGESTREL -> "levonorgestrel"
            ULIPRISTAL -> "ulipristal"
            COPPER_IUD -> "copper_iud"
        }

        val maximumCreditedHours: Double get() = when (this) {
            LEVONORGESTREL -> 72.0
            ULIPRISTAL, COPPER_IUD -> 120.0
        }

        companion object {
            fun fromWire(s: String?): EcType? = when (s) {
                "levonorgestrel" -> LEVONORGESTREL
                "ulipristal" -> ULIPRISTAL
                "copper_iud" -> COPPER_IUD
                else -> null
            }
        }
    }

    data class Estimate(
        val conceptionMultiplier: Double,
        val conceptionMultiplierLow: Double,
        val conceptionMultiplierHigh: Double,
        val ovulationDelayDays: Double,
    ) {
        companion object {
            /** No EC effect — used when the native estimator is unavailable (mock bridge). */
            val NONE = Estimate(1.0, 1.0, 1.0, 0.0)
        }
    }

    data class TimedIncident(
        val id: String,
        val row: Int,
        val day: Int,
        val occurredAt: Long,
    )

    data class TimedDose(
        val id: String,
        val row: Int,
        val day: Int,
        val type: EcType,
        val hoursFromAct: Double?,
    )

    /**
     * Assign a timed dose to one unambiguous incident. Calendar dates constrain
     * an N-day gap to [(N-1)*24, (N+1)*24] elapsed hours; contradictory or
     * missing timing receives no numeric credit. When several incidents remain
     * possible, the dose is conservatively assigned only to the latest one
     * rather than reused for every earlier act.
     */
    fun matchedIncidentId(dose: TimedDose, incidents: List<TimedIncident>): String? {
        val hours = dose.hoursFromAct
            ?.takeIf { it.isFinite() && it in 0.0..dose.type.maximumCreditedHours }
            ?: return null
        return incidents
            .asSequence()
            .filter { it.row == dose.row }
            .filter { incident ->
                val dayGap = dose.day - incident.day
                if (dayGap < 0) {
                    false
                } else {
                    val minHours = maxOf(0.0, (dayGap - 1) * 24.0)
                    val maxHours = (dayGap + 1) * 24.0
                    hours in minHours..maxHours
                }
            }
            .maxWithOrNull(compareBy<TimedIncident> { it.day }.thenBy { it.occurredAt })
            ?.id
    }
}
