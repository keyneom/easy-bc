package com.easybc.planner

/**
 * Calls into `planner-core` JSON API. Replace the body with uniFFI-generated bindings once you run
 * `uniffi-bindgen` per [MOBILE_FFI.md](../../crates/planner-core/MOBILE_FFI.md) and ship
 * `libplanner_core.so` under `jniLibs/`.
 */
object PlannerCoreBridge {
    fun planFertilityRiskPlannerJson(json: String): String {
        try {
            System.loadLibrary("planner_core")
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "Native library not loaded. Build with scripts/android-build-native.sh and add jniLibs + Kotlin bindings.",
                e,
            )
        }
        throw UnsupportedOperationException(
            "JNI symbol not wired: add uniFFI Kotlin output and call the generated planFertilityRiskPlannerJson.",
        )
    }
}
