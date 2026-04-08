package com.easybc.planner.data

import kotlinx.serialization.*
import kotlinx.serialization.json.Json

/** Shared Json config for communicating with the Rust planner core. */
val PlannerJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

// ── Input types (sent to Rust core) ──

@Serializable
enum class CondomMode {
    @SerialName("perfect") Perfect,
    @SerialName("typical") Typical,
    @SerialName("custom") Custom;

    val label: String get() = when (this) {
        Perfect -> "Perfect use"
        Typical -> "Typical use"
        Custom -> "Custom"
    }
}

@Serializable
enum class RecommendedAction {
    @SerialName("U") U,
    @SerialName("W") W,
    @SerialName("C") C,
    @SerialName("A") A;

    val label: String get() = when (this) {
        U -> "Unprotected"
        W -> "Withdrawal"
        C -> "Condom"
        A -> "Abstain"
    }

    val shortLabel: String get() = name
}

@Serializable
data class CycleInstance(
    val cycleLengthDays: Int,
    val cycleSdDays: Double,
    val actsPerWeek: Double,
    val ageYears: Int,
)

@Serializable
data class DayOverride(
    val yearIndex: Int,
    val day: Int,
    val action: RecommendedAction,
)

@Serializable
data class UserOptions(
    val ageYears: Int = 34,
    val horizonYears: Int = 20,
    val targetCumulativeFailure: Double = 0.05,
    val cycleLengthDays: Int = 28,
    val actsPerWeek: Double = 3.0,
    val condomMode: CondomMode = CondomMode.Typical,
    val customCondomResidual: Double = 0.08,
    val streakAversion: Double = 0.5,
    val holdLifecycleConstant: Boolean = false,
    val cyclesPerYear: Double = 13.0,
    val sdmReferenceAnnualFailure: Double = 0.05,
    val condomPerfectAnnualFailure: Double = 0.02,
    val condomTypicalAnnualFailure: Double = 0.13,
    val ovulationSdDays: Double = 3.0,
    val ovulationWindowHalfWidth: Int = 7,
    val yearCrowdingPenalty: Double = 0.8,
    val timePreferenceRate: Double = 0.03,
    val withdrawalRelativeRisk: Double = 0.35,
    val calendarCycles: List<CycleInstance>? = null,
    val realizedCumulativeRisk: Double = 0.0,
    val initialActionLocks: List<DayOverride> = emptyList(),
)

// ── Output types (received from Rust core) ──

@Serializable
data class OverrideCost(
    val overrideAction: String? = null,
    val condoms: Int = 0,
    val abstinenceDays: Int = 0,
    val note: String = "",
)

@Serializable
data class DayWeight(
    val day: Int,
    val recommendedAction: RecommendedAction,
    val rawRiskScore: Int,
    val overrideCost: OverrideCost,
)

@Serializable
data class ActionCounts(
    val unprotected: Int = 0,
    val condom: Int = 0,
    val withdrawal: Int = 0,
    val abstain: Int = 0,
)

@Serializable
data class GroupedCycleDays(
    val unprotected: List<Int> = emptyList(),
    val condom: List<Int> = emptyList(),
    val withdrawal: List<Int> = emptyList(),
    val abstain: List<Int> = emptyList(),
)

@Serializable
data class YearOutput(
    val yearIndex: Int,
    val age: Int,
    val cycleLengthDays: Int,
    val cycleSdDays: Double,
    val actsPerWeek: Double,
    val cycleRisk: Double,
    val annualRisk: Double,
    val counts: ActionCounts,
    val groupedDays: GroupedCycleDays,
    val dayWeights: List<DayWeight>,
)

@Serializable
data class HighVariabilityYear(
    val age: Int,
    @SerialName("cycleSdDays") val cycleSdDays: String,
)

/**
 * Flat representation of PlannerWarning (Rust uses internally-tagged enum with "kind").
 * Fields from all variants are present but nullable/defaulted.
 */
@Serializable
data class PlannerWarning(
    val kind: String,
    val allCondomCumulativeRisk: Double? = null,
    val message: String = "",
    val affectedYears: List<HighVariabilityYear>? = null,
    val affectedYearIndices: List<Int>? = null,
)

@Serializable
data class SdmValidation(
    val simulatedAnnualRisk: Double,
    val sdmPublishedAnchor: Double,
    val deviationRatio: Double,
    val withinTolerance: Boolean,
)

@Serializable
data class CondomResidualsUsed(
    val perfect: Double,
    val typical: Double,
    val custom: Double,
)

@Serializable
data class PlannerValidation(
    val sdmReference: SdmValidation,
    val condomResidualsUsed: CondomResidualsUsed,
    val selectedCondomResidual: Double,
)

@Serializable
data class DerivedUxWeights(
    val condomCost: Double,
    val abstainCost: Double,
    val streakPenalty: Double,
    val softMaxAbstainStreak: Int,
    val softMaxStreakPenalty: Double,
)

@Serializable
data class PlannerResult(
    val optionsUsed: UserOptions,
    val derivedUxWeights: DerivedUxWeights,
    val validation: PlannerValidation,
    val achievedCumulativeRisk: Double,
    val targetMet: Boolean,
    val warnings: List<PlannerWarning> = emptyList(),
    val years: List<YearOutput> = emptyList(),
)

// ── Replan preview types ──

@Serializable
data class ReplanPreviewRequest(
    val options: UserOptions,
    val overrides: List<DayOverride>,
)

@Serializable
data class PlanDayDiff(
    val yearIndex: Int,
    val day: Int,
    val baselineAction: RecommendedAction,
    val previewAction: RecommendedAction,
)

@Serializable
data class ReplanPreview(
    val baseline: PlannerResult,
    val preview: PlannerResult,
    val previewTargetMet: Boolean,
    val feasible: Boolean,
    val message: String? = null,
    val diffs: List<PlanDayDiff> = emptyList(),
)
