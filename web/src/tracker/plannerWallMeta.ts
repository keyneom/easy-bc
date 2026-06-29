import { wallDateToPlannerSlice } from "./calendarMath";

/** Matches WASM JSON day weight (camelCase). */
export type PlannerWallOverrideCost = {
  condoms: number;
  abstinenceDays: number;
  note: string;
};

export type PlannerWallDayWeight = {
  day: number;
  recommendedAction: string;
  rawRiskScore: number;
  rawRiskProbability?: number;
  protectedRiskProbability?: number;
  withdrawalRiskProbability?: number;
  recommendedRiskProbability?: number;
  perActConceptionProbability?: number;
  overrideCost?: PlannerWallOverrideCost;
};

export type PlannerWallYear = {
  dayWeights: PlannerWallDayWeight[];
};

export type PlannerWallPlan = {
  years: PlannerWallYear[];
};

export type PlannerDayMeta = {
  recommendedAction: string;
  rawRiskScore: number;
  rawRiskProbability: number;
  protectedRiskProbability: number;
  withdrawalRiskProbability: number;
  recommendedRiskProbability: number;
  perActConceptionProbability: number;
  overrideCost: PlannerWallOverrideCost;
  row: number;
  dayInCycle: number;
};

export function resolvePlannerDayMeta(
  plan: PlannerWallPlan | null,
  cycleLengths: number[],
  anchorIso: string | null,
  wallIso: string,
): PlannerDayMeta | null {
  if (!plan?.years?.length || cycleLengths.length === 0 || !anchorIso) return null;
  const slice = wallDateToPlannerSlice(anchorIso, cycleLengths, plan.years.length, wallIso);
  if (!slice) return null;
  const yr = plan.years[slice.row];
  const dw = yr?.dayWeights[slice.day - 1];
  if (!dw) return null;
  const oc = dw.overrideCost ?? { condoms: 0, abstinenceDays: 0, note: "" };
  return {
    recommendedAction: dw.recommendedAction,
    rawRiskScore: dw.rawRiskScore,
    rawRiskProbability: dw.rawRiskProbability ?? 0,
    protectedRiskProbability: dw.protectedRiskProbability ?? 0,
    withdrawalRiskProbability: dw.withdrawalRiskProbability ?? 0,
    recommendedRiskProbability: dw.recommendedRiskProbability ?? 0,
    perActConceptionProbability: dw.perActConceptionProbability ?? 0,
    overrideCost: oc,
    row: slice.row,
    dayInCycle: slice.day,
  };
}
