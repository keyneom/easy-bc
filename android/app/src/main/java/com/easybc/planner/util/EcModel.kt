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
}
