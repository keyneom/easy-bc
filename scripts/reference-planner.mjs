/**
 * One-off readability reference from the README snippet. Run: node scripts/reference-planner.mjs
 * (Copy of the simplified fertilityRiskPlanner body kept in sync with README;
 * the Rust core is authoritative for reserve/relaxation post-processing.)
 *
 * Also: import { fertilityRiskPlanner } from "./scripts/reference-planner.mjs" for parity checks.
 */
import { fileURLToPath } from "url";

export function fertilityRiskPlanner(userOptions = {}) {
  const opts = {
    ageYears: 34,
    horizonYears: 20,
    targetCumulativeFailure: 0.05,
    cycleLengthDays: 28,
    actsPerWeek: 3.5,
    condomMode: "typical",
    customCondomResidual: 0.08,
    streakAversion: 0.50,
    holdLifecycleConstant: false,
    cyclesPerYear: 13,
    sdmReferenceAnnualFailure: 0.05,
    condomPerfectAnnualFailure: 0.02,
    condomTypicalAnnualFailure: 0.13,
    ovulationSdDays: 3,
    ovulationWindowHalfWidth: 7,
    yearCrowdingPenalty: 0.8,
    timePreferenceRate: 0.03,
    improvementFloor: 1e-14,
    withdrawalRelativeRisk: 0.35,
    realizedCumulativeRisk: 0,
    ...userOptions,
  };

  const U_TO_WITHDRAWAL_COST_FRAC = 0.62;
  const WITHDRAWAL_TO_CONDOM_COST_FRAC = 0.42;

  const s = opts.streakAversion;
  const UX = {
    condomCost: 1.0,
    abstainCost: 8.0 - 3.0 * s,
    streakPenalty: 2.0 + 10.0 * s,
    softMaxAbstainStreak: s < 0.33 ? 4 : s < 0.66 ? 3 : 2,
    softMaxStreakPenalty: 2.0 + 20.0 * s,
  };

  const fertileKernel = {
    [-5]: 0.10, [-4]: 0.16, [-3]: 0.14, [-2]: 0.27, [-1]: 0.31, [0]: 0.33, [1]: 0.08,
  };

  function ageMultiplier(age) {
    const anchors = [
      [18, 1.00],
      [26, 1.00],
      [29, 0.86],
      [34, 0.77],
      [37, 0.63],
      [40, 0.49],
      [44, 0.28],
      [50, 0.10],
    ];
    if (age <= anchors[0][0]) return anchors[0][1];
    for (let i = 0; i < anchors.length - 1; i++) {
      const [a0, m0] = anchors[i];
      const [a1, m1] = anchors[i + 1];
      if (age <= a1) {
        const t = Math.max(0, Math.min(1, (age - a0) / (a1 - a0)));
        return m0 + t * (m1 - m0);
      }
    }
    return anchors.at(-1)[1];
  }

  function interpolateAgeAnchors(age, anchors) {
    if (age <= anchors[0][0]) return anchors[0][1];
    for (let i = 0; i < anchors.length - 1; i++) {
      const [a0, v0] = anchors[i];
      const [a1, v1] = anchors[i + 1];
      if (age <= a1) {
        const t = Math.max(0, Math.min(1, (age - a0) / (a1 - a0)));
        return v0 + t * (v1 - v0);
      }
    }
    return anchors.at(-1)[1];
  }

  function referenceCycleLengthForAge(age) {
    return interpolateAgeAnchors(age, [
      [18, 30.0],
      [24, 29.0],
      [29, 28.5],
      [34, 28.0],
      [40, 27.8],
      [44, 28.0],
      [48, 34.0],
      [50, 40.0],
      [55, 55.0],
    ]);
  }

  function referenceCycleSdForAge(age) {
    return interpolateAgeAnchors(age, [
      [18, 5.0],
      [24, 4.0],
      [29, 3.5],
      [34, 3.2],
      [39, 3.0],
      [42, 3.5],
      [45, 4.5],
      [48, 7.0],
      [50, 10.0],
      [55, 15.0],
    ]);
  }

  function referenceFrequencyForAge(age) {
    return interpolateAgeAnchors(age, [
      [24, 4.5],
      [29, 4.0],
      [34, 3.5],
      [39, 3.0],
      [44, 2.5],
      [49, 2.0],
      [55, 1.5],
    ]);
  }

  function scaledCycleLengthForAge(age) {
    if (opts.holdLifecycleConstant) return opts.cycleLengthDays;
    const refBaseline = referenceCycleLengthForAge(opts.ageYears);
    const refAtAge = referenceCycleLengthForAge(age);
    const scaled = opts.cycleLengthDays * (refAtAge / refBaseline);
    return Math.max(21, Math.min(60, Math.round(scaled)));
  }

  function scaledCycleSdForAge(age) {
    if (opts.holdLifecycleConstant) return opts.ovulationSdDays;
    const refBaseline = referenceCycleSdForAge(opts.ageYears);
    const refAtAge = referenceCycleSdForAge(age);
    return opts.ovulationSdDays * (refAtAge / refBaseline);
  }

  function scaledFrequencyForAge(age) {
    if (opts.holdLifecycleConstant) return opts.actsPerWeek;
    const refBaseline = referenceFrequencyForAge(opts.ageYears);
    const refAtAge = referenceFrequencyForAge(age);
    return opts.actsPerWeek * (refAtAge / refBaseline);
  }

  function gaussianWeight(x, mean, sd) {
    const z = (x - mean) / sd;
    return Math.exp(-0.5 * z * z);
  }

  function ovulationWeights(cycleLengthDays, sdDays) {
    const center = cycleLengthDays - 14;
    const minDay = Math.max(1, center - opts.ovulationWindowHalfWidth);
    const maxDay = Math.min(cycleLengthDays, center + opts.ovulationWindowHalfWidth);
    const raw = [];
    for (let day = minDay; day <= maxDay; day++) {
      raw.push([day, gaussianWeight(day, center, sdDays)]);
    }
    const total = raw.reduce((acc, [, w]) => acc + w, 0);
    return raw.map(([day, w]) => [day, w / total]);
  }

  function rawPerDayCycleRiskForAge(age, cycleLengthDays, actsPerWeek, sdDays) {
    const actsPerDay = actsPerWeek / 7;
    const ovu = ovulationWeights(cycleLengthDays, sdDays);
    const ageMult = ageMultiplier(age);
    const out = [];
    for (let cycleDay = 1; cycleDay <= cycleLengthDays; cycleDay++) {
      let p = 0;
      for (const [ovulationDay, weight] of ovu) {
        const rel = cycleDay - ovulationDay;
        p += weight * (fertileKernel[rel] || 0);
      }
      out.push(p * ageMult * actsPerDay);
    }
    return out;
  }

  function referenceDayRisks() {
    return rawPerDayCycleRiskForAge(28, 28, 2.0, 2.0);
  }

  function annualRiskWithResidual(dayRisks, residual) {
    let cycleSurv = 1;
    for (const r of dayRisks) cycleSurv *= (1 - r * residual);
    return 1 - Math.pow(cycleSurv, opts.cyclesPerYear);
  }

  function solveResidualForAnnualTarget(targetAnnual, dayRisks) {
    let lo = 0, hi = 1;
    for (let i = 0; i < 50; i++) {
      const mid = (lo + hi) / 2;
      if (annualRiskWithResidual(dayRisks, mid) < targetAnnual) lo = mid;
      else hi = mid;
    }
    return (lo + hi) / 2;
  }

  const refDayRisks = referenceDayRisks();
  const condomResiduals = {
    perfect: solveResidualForAnnualTarget(opts.condomPerfectAnnualFailure, refDayRisks),
    typical: solveResidualForAnnualTarget(opts.condomTypicalAnnualFailure, refDayRisks),
    custom: opts.customCondomResidual,
  };

  const condomResidual = condomResiduals[opts.condomMode];

  function multiplierForAction(action) {
    if (action === "U") return 1;
    if (action === "W") return opts.withdrawalRelativeRisk;
    if (action === "C") return condomResidual;
    return 0;
  }

  function actionSuccessors(cur, condomR, wR) {
    if (cur === "U") {
      const s = ["C"];
      if (wR + 1e-15 < 1) s.push("W");
      return s;
    }
    if (cur === "W") {
      const s = [];
      if (condomR + 1e-15 < wR) s.push("C");
      if (wR > 1e-15) s.push("A");
      return s;
    }
    if (cur === "C") return ["A"];
    return [];
  }

  const years = [];
  for (let y = 0; y < opts.horizonYears; y++) {
    const age = opts.ageYears + y;
    const cycleLen = scaledCycleLengthForAge(age);
    const cycleSd = scaledCycleSdForAge(age);
    const freq = scaledFrequencyForAge(age);
    const raw = rawPerDayCycleRiskForAge(age, cycleLen, freq, cycleSd);
    years.push({ yearIndex: y, age, cycleLengthDays: cycleLen, cycleSdDays: cycleSd, actsPerWeek: freq, baseRiskByDay: raw });
  }

  function validateAgainstSdm() {
    const sdmRefRisk = rawPerDayCycleRiskForAge(28, 28, 1.5, 2.0);
    let cycleSurv = 1;
    for (let i = 0; i < sdmRefRisk.length; i++) {
      const day = i + 1;
      const protectedBySDM = day >= 8 && day <= 19;
      if (!protectedBySDM) cycleSurv *= (1 - sdmRefRisk[i]);
    }
    const cycleRisk = 1 - cycleSurv;
    const annualRisk = 1 - Math.pow(1 - cycleRisk, opts.cyclesPerYear);
    const anchor = opts.sdmReferenceAnnualFailure;
    const deviation = Math.abs(annualRisk - anchor) / anchor;
    return { simulatedAnnualRisk: annualRisk, sdmPublishedAnchor: anchor, deviationRatio: deviation, withinTolerance: deviation < 0.6 };
  }

  const sdmValidation = validateAgainstSdm();

  const allCondomCumulativeRisk = (() => {
    let surv = 1;
    for (let y = 0; y < years.length; y++) {
      let cycleSurv = 1;
      for (const r of years[y].baseRiskByDay) cycleSurv *= (1 - r * condomResidual);
      const cycleRisk = 1 - cycleSurv;
      surv *= Math.pow(1 - cycleRisk, opts.cyclesPerYear);
    }
    return 1 - surv;
  })();

  const infeasibilityWarning = allCondomCumulativeRisk > opts.targetCumulativeFailure
    ? { kind: "target_requires_abstinence", allCondomCumulativeRisk, message: "Target cannot be met with condoms alone; some abstinence days will be required." }
    : null;

  const highVariabilityYears = years.filter((yr) => yr.cycleSdDays > 4.5).map((yr) => ({ age: yr.age, cycleSdDays: yr.cycleSdDays.toFixed(1) }));
  const perimenopausalWarning = highVariabilityYears.length > 0
    ? { kind: "high_cycle_variability", affectedYears: highVariabilityYears, message: "Some horizon years have cycle SD > 4.5 days. Calendar-based prediction accuracy degrades substantially at this level. Consider body-signal tracking for these years." }
    : null;

  const plans = years.map((yr) => Array.from({ length: yr.cycleLengthDays }, () => "U"));
  const yearBurdenPoints = years.map(() => 0);

  function cycleRiskForYear(y) {
    const base = years[y].baseRiskByDay;
    const plan = plans[y];
    let surv = 1;
    for (let d = 0; d < base.length; d++) {
      surv *= (1 - base[d] * multiplierForAction(plan[d]));
    }
    return 1 - surv;
  }

  let cycleRisks = years.map((_, y) => cycleRiskForYear(y));
  let annualSurvival = cycleRisks.map((r) => Math.pow(1 - r, opts.cyclesPerYear));

  function totalCumulativeRisk() {
    let s = 1;
    for (let y = 0; y < annualSurvival.length; y++) s *= annualSurvival[y];
    return 1 - s;
  }

  function timeWeight(yearIndex) {
    return 1 / Math.pow(1 + opts.timePreferenceRate, yearIndex);
  }

  function crowdingWeight(yearIndex) {
    return 1 + opts.yearCrowdingPenalty * (yearBurdenPoints[yearIndex] / years[yearIndex].cycleLengthDays);
  }

  function localAbstainStreakDelta(yearIndex, dayIndex) {
    const plan = plans[yearIndex];
    let left = 0;
    for (let i = dayIndex - 1; i >= 0 && plan[i] === "A"; i--) left++;
    let right = 0;
    for (let i = dayIndex + 1; i < plan.length && plan[i] === "A"; i++) right++;
    const oldPenalty = left * left + right * right;
    const newLen = left + 1 + right;
    let newPenalty = newLen * newLen;
    if (newLen > UX.softMaxAbstainStreak) {
      newPenalty += UX.softMaxStreakPenalty * Math.pow(newLen - UX.softMaxAbstainStreak, 2);
    }
    return UX.streakPenalty * (newPenalty - oldPenalty);
  }

  function currentRiskReductionIfChange(yearIndex, dayIndex, oldAction, newAction) {
    const oldMult = multiplierForAction(oldAction);
    const newMult = multiplierForAction(newAction);
    const base = years[yearIndex].baseRiskByDay[dayIndex];
    const oldDaySurv = 1 - base * oldMult;
    const newDaySurv = 1 - base * newMult;
    if (oldDaySurv <= 0 || newDaySurv <= 0) return 0;
    return (Math.log(newDaySurv) - Math.log(oldDaySurv)) * opts.cyclesPerYear;
  }

  function applyChange(yearIndex, dayIndex, oldAction, newAction) {
    plans[yearIndex][dayIndex] = newAction;
    const burden = { U: 0, W: 1, C: 1, A: 2 };
    yearBurdenPoints[yearIndex] += burden[newAction] - burden[oldAction];
    const oldMult = multiplierForAction(oldAction);
    const newMult = multiplierForAction(newAction);
    const base = years[yearIndex].baseRiskByDay[dayIndex];
    const oldSurv = 1 - cycleRisks[yearIndex];
    const oldDaySurv = 1 - base * oldMult;
    const newDaySurv = 1 - base * newMult;
    const newSurv = oldDaySurv > 0 ? oldSurv * (newDaySurv / oldDaySurv) : 0;
    cycleRisks[yearIndex] = 1 - newSurv;
    annualSurvival[yearIndex] = Math.pow(1 - cycleRisks[yearIndex], opts.cyclesPerYear);
  }

  function marginalUpgradeCost(y, d, fromAction, toAction) {
    const tw = timeWeight(y);
    const cw = crowdingWeight(y);
    let cost;
    if (fromAction === "U" && toAction === "W") {
      cost = UX.condomCost * U_TO_WITHDRAWAL_COST_FRAC * tw * cw;
    } else if (fromAction === "U" && toAction === "C") {
      cost = UX.condomCost * tw * cw;
    } else if (fromAction === "W" && toAction === "C") {
      cost = UX.condomCost * WITHDRAWAL_TO_CONDOM_COST_FRAC * tw * cw;
    } else if (fromAction === "C" && toAction === "A") {
      cost = (UX.abstainCost - UX.condomCost) * tw * cw;
      cost += localAbstainStreakDelta(y, d) * timeWeight(y);
    } else if (fromAction === "W" && toAction === "A") {
      cost = (UX.abstainCost - UX.condomCost * U_TO_WITHDRAWAL_COST_FRAC) * tw * cw;
      cost += localAbstainStreakDelta(y, d) * timeWeight(y);
    } else {
      return null;
    }
    if (cost <= 0) cost = 1e-18;
    return cost;
  }

  function bestMarginalUpgrade() {
    let best = null;
    const wR = opts.withdrawalRelativeRisk;
    for (let y = 0; y < years.length; y++) {
      for (let d = 0; d < years[y].cycleLengthDays; d++) {
        if (years[y].baseRiskByDay[d] <= 0) continue;
        const cur = plans[y][d];
        for (const to of actionSuccessors(cur, condomResidual, wR)) {
          const benefit = currentRiskReductionIfChange(y, d, cur, to);
          if (benefit <= 0) continue;
          const cost = marginalUpgradeCost(y, d, cur, to);
          if (cost == null) continue;
          const score = benefit / cost;
          const better =
            !best ||
            score > best.score + 1e-18 ||
            (Math.abs(score - best.score) <= 1e-18 && benefit > best.benefit + 1e-18);
          if (better) best = { y, d, fromAction: cur, toAction: to, score, benefit };
        }
      }
    }
    return best;
  }

  const effectiveTarget = Math.max(0, opts.targetCumulativeFailure - opts.realizedCumulativeRisk);
  let currentRisk = totalCumulativeRisk();
  while (currentRisk > effectiveTarget) {
    const candidate = bestMarginalUpgrade();
    if (!candidate) break;
    applyChange(candidate.y, candidate.d, candidate.fromAction, candidate.toAction);
    currentRisk = totalCumulativeRisk();
  }

  const targetMet =
    effectiveTarget <= 0 ? currentRisk <= 1e-10 : currentRisk <= effectiveTarget;

  return {
    achievedCumulativeRisk: totalCumulativeRisk(),
    targetMet,
    selectedCondomResidual: condomResidual,
    year0Plan: plans[0].join(""),
    year1Plan: plans[1]?.join("") ?? "",
    sdmSimulatedAnnualRisk: sdmValidation.simulatedAnnualRisk,
    warnings: [infeasibilityWarning, perimenopausalWarning].filter(Boolean),
  };
}

const isMain = process.argv[1] === fileURLToPath(import.meta.url);
if (isMain) {
  const cases = [
    { name: "default_typical", opts: {} },
    { name: "example_readme", opts: { ageYears: 34, targetCumulativeFailure: 0.02, condomMode: "perfect", streakAversion: 0.75, horizonYears: 2 } },
    { name: "hold_constant", opts: { horizonYears: 3, holdLifecycleConstant: true } },
  ];

  const out = {};
  for (const c of cases) {
    out[c.name] = fertilityRiskPlanner(c.opts);
  }
  console.log(JSON.stringify(out, null, 2));
}
