package com.easybc.planner.bridge

import com.easybc.planner.data.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Abstraction over the Rust planner core. */
interface PlannerBridge {
    fun planFromJson(optionsJson: String): Result<String>
    fun replanPreviewFromJson(requestJson: String): Result<String>
    val isNative: Boolean
}

/**
 * Calls the Rust core via uniFFI-generated Kotlin bindings (JNA under the hood).
 * Requires libplanner_core.so in jniLibs/ and the generated uniffi/planner_core/ package.
 */
class NativePlannerBridge : PlannerBridge {
    override val isNative = true

    override fun planFromJson(optionsJson: String): Result<String> = runCatching {
        uniffi.planner_core.planFertilityRiskPlannerJson(optionsJson)
    }

    override fun replanPreviewFromJson(requestJson: String): Result<String> = runCatching {
        uniffi.planner_core.replanPreviewJson(requestJson)
    }
}

/**
 * Generates structurally correct mock plans so the UI can be developed and tested
 * without the native library. Uses a simplified biological model — NOT the real optimizer.
 */
class MockPlannerBridge : PlannerBridge {
    override val isNative = false

    override fun planFromJson(optionsJson: String): Result<String> = runCatching {
        val opts = PlannerJson.decodeFromString<UserOptions>(optionsJson)
        val result = generateMockResult(opts)
        PlannerJson.encodeToString(PlannerResult.serializer(), result)
    }

    override fun replanPreviewFromJson(requestJson: String): Result<String> =
        Result.failure(UnsupportedOperationException("Replan preview not available in mock bridge"))

    private fun generateMockResult(opts: UserOptions): PlannerResult {
        val numYears = if (opts.calendarCycles != null && opts.calendarCycles.isNotEmpty()) {
            opts.calendarCycles.size
        } else {
            opts.horizonYears
        }

        var cumulativeSurv = 1.0
        val years = (0 until numYears).map { y ->
            val age = if (opts.calendarCycles != null && opts.calendarCycles.isNotEmpty()) {
                opts.calendarCycles[y].ageYears
            } else {
                opts.ageYears + y
            }
            val cycleLen = if (opts.calendarCycles != null && opts.calendarCycles.isNotEmpty()) {
                opts.calendarCycles[y].cycleLengthDays
            } else {
                opts.cycleLengthDays
            }
            val sd = if (opts.calendarCycles != null && opts.calendarCycles.isNotEmpty()) {
                opts.calendarCycles[y].cycleSdDays
            } else {
                opts.ovulationSdDays
            }
            val freq = if (opts.calendarCycles != null && opts.calendarCycles.isNotEmpty()) {
                opts.calendarCycles[y].actsPerWeek
            } else {
                opts.actsPerWeek
            }
            val ageMul = ageMultiplier(age)
            val ovuCenter = cycleLen - 14

            val dayWeights = (1..cycleLen).map { day ->
                val riskScore = computeMockRisk(day, ovuCenter, sd, ageMul)
                val action = assignAction(riskScore, opts)
                DayWeight(
                    day = day,
                    recommendedAction = action,
                    rawRiskScore = riskScore,
                    overrideCost = OverrideCost(
                        overrideAction = when (action) {
                            RecommendedAction.A -> "Condom"
                            RecommendedAction.C -> "Unprotected"
                            else -> null
                        },
                        note = when (action) {
                            RecommendedAction.A -> "High-risk day. Using a condom here adds ~1 condom day to recover."
                            RecommendedAction.C -> "Moderate risk. Going unprotected here adds ~2 condom days to recover."
                            else -> "Low risk day."
                        },
                    ),
                )
            }

            val cycleRisk = dayWeights.sumOf {
                val base = it.rawRiskScore / 100.0 * 0.33 * ageMul * (freq / 7.0)
                when (it.recommendedAction) {
                    RecommendedAction.A -> 0.0
                    RecommendedAction.C -> base * 0.03
                    RecommendedAction.W -> base * 0.35
                    RecommendedAction.U -> base
                }
            }.coerceIn(0.0, 0.5)
            val annualRisk = 1.0 - Math.pow(1.0 - cycleRisk, 13.0)
            cumulativeSurv *= (1.0 - annualRisk)

            YearOutput(
                yearIndex = y,
                age = age,
                cycleLengthDays = cycleLen,
                cycleSdDays = sd,
                actsPerWeek = freq,
                cycleRisk = cycleRisk,
                annualRisk = annualRisk,
                counts = ActionCounts(
                    unprotected = dayWeights.count { it.recommendedAction == RecommendedAction.U },
                    condom = dayWeights.count { it.recommendedAction == RecommendedAction.C },
                    withdrawal = dayWeights.count { it.recommendedAction == RecommendedAction.W },
                    abstain = dayWeights.count { it.recommendedAction == RecommendedAction.A },
                ),
                dayWeights = dayWeights,
            )
        }

        return PlannerResult(
            optionsUsed = opts,
            derivedUxWeights = DerivedUxWeights(
                condomCost = 1.0,
                abstainCost = 8.0 - 3.0 * opts.streakAversion,
                streakPenalty = 2.0 + 10.0 * opts.streakAversion,
                softMaxAbstainStreak = if (opts.streakAversion < 0.33) 4 else if (opts.streakAversion < 0.66) 3 else 2,
                softMaxStreakPenalty = 2.0 + 20.0 * opts.streakAversion,
            ),
            validation = PlannerValidation(
                sdmReference = SdmValidation(0.11, 0.05, 1.2, true),
                condomResidualsUsed = CondomResidualsUsed(0.0045, 0.031, opts.customCondomResidual),
                selectedCondomResidual = when (opts.condomMode) {
                    CondomMode.Perfect -> 0.0045
                    CondomMode.Typical -> 0.031
                    CondomMode.Custom -> opts.customCondomResidual
                },
            ),
            achievedCumulativeRisk = (1.0 - cumulativeSurv).coerceIn(0.0, 1.0),
            targetMet = (1.0 - cumulativeSurv) <= opts.targetCumulativeFailure,
            warnings = emptyList(),
            years = years,
        )
    }

    private fun computeMockRisk(day: Int, ovuCenter: Int, sd: Double, ageMul: Double): Int {
        val dist = abs(day - ovuCenter).toDouble()
        val gauss = exp(-0.5 * (dist / sd) * (dist / sd))
        return (gauss * 100.0 * ageMul).roundToInt().coerceIn(0, 100)
    }

    private fun assignAction(riskScore: Int, opts: UserOptions): RecommendedAction {
        val target = opts.targetCumulativeFailure
        val strictness = ((1.0 - target * 10.0) * 100).coerceIn(20.0, 90.0)
        return when {
            riskScore >= strictness -> RecommendedAction.A
            riskScore >= strictness * 0.4 && opts.protectedDayMethod != ProtectedDayMethod.None -> {
                RecommendedAction.C
            }
            riskScore >= strictness * 0.25 && opts.withdrawalMode != WithdrawalMode.None -> {
                RecommendedAction.W
            }
            else -> RecommendedAction.U
        }
    }

    private fun ageMultiplier(age: Int): Double {
        val anchors = listOf(
            18.0 to 1.00,
            26.0 to 1.00,
            29.0 to 0.86,
            34.0 to 0.77,
            37.0 to 0.63,
            40.0 to 0.49,
            44.0 to 0.28,
            50.0 to 0.10,
        )
        val a = age.toDouble()
        if (a <= anchors.first().first) return anchors.first().second
        for (i in 0 until anchors.lastIndex) {
            val (a0, m0) = anchors[i]
            val (a1, m1) = anchors[i + 1]
            if (a <= a1) {
                val t = ((a - a0) / (a1 - a0)).coerceIn(0.0, 1.0)
                return m0 + t * (m1 - m0)
            }
        }
        return anchors.last().second
    }
}

/** Try native, fall back to mock. */
fun createPlannerBridge(): PlannerBridge {
    return try {
        NativePlannerBridge()
    } catch (_: UnsatisfiedLinkError) {
        MockPlannerBridge()
    }
}
