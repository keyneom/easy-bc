/**
 * In-flight realized-risk aggregation.
 *
 * Sums the pregnancy risk actually taken on during the *current, unresolved*
 * cycle from logged day events (broken condom, unplanned unprotected), and
 * applies any emergency-contraception (Plan B / ella / copper IUD) dose that
 * covers those acts. The result feeds the planner's `realizedCumulativeRisk` so
 * the rest of the horizon tightens to compensate.
 *
 * "Release on next period" is implicit: we only ever count the in-flight cycle
 * (days since the last period start). When a new period begins, the prior
 * cycle's events stop counting — it resolved at zero pregnancies. See
 * docs/risk-accounting-and-ec.md.
 *
 * Pricing follows the per-act model: discrete incidents are charged per-act
 * (`perActConceptionProbability`), not per-day-pattern. Multiple incidents in
 * one cycle are summed as a conservative union bound rather than composed as if
 * independent (they share one unknown ovulation day).
 */

import type { CalendarDayLog, EcType, PlannerAction } from "../sessionUtils";

export type MethodResiduals = {
  persistentMethodResidual: number;
};

/** The fields of a planner DayWeight this module needs. */
export type RealizedDayWeight = {
  recommendedAction: PlannerAction;
  rawRiskProbability: number;
  protectedRiskProbability: number;
  withdrawalRiskProbability: number;
  recommendedRiskProbability: number;
  perActConceptionProbability: number;
};

/** One in-flight cycle day with what was planned, logged, and any events. */
export type InFlightDay = {
  /** 1-based day within the cycle (for ordering EC relative to acts). */
  cycleDay: number;
  dayWeight: RealizedDayWeight;
  log?: CalendarDayLog;
};

/**
 * Estimate an EC dose's effect on an act. Injected so this module stays pure and
 * unit-testable; production passes a wasm-backed implementation (the canonical
 * model lives in the Rust core, `ec.rs`).
 */
export type EcEffectFn = (
  ecType: EcType,
  hoursFromAct: number | undefined,
  actCycleDay: number,
) => { conceptionMultiplier: number; ovulationDelayDays: number };

/** Per-act unprotected conception risk for a day (after persistent-method residual). */
function perActUnprotected(dw: RealizedDayWeight, residuals: MethodResiduals): number {
  return dw.perActConceptionProbability * residuals.persistentMethodResidual;
}

export type InFlightRealizedResult = {
  /**
   * Additional in-flight risk beyond risk already represented in the plan.
   * Conservative union-bound estimate, capped below 1.
   */
  realized: number;
  /** True if any emergency-contraception event was logged this cycle. */
  hadEc: boolean;
  /** Largest ovulation delay implied by any covering EC dose (days). */
  ovulationDelayDays: number;
};

/**
 * Aggregate in-flight realized risk across the cycle's days.
 *
 * Each explicit incident is one known act, priced with the per-act probability.
 * If a Plan B / EC dose was taken *on or after* the incident's day this cycle,
 * the incident's risk is multiplied by the EC conception multiplier (hormonal EC
 * cannot undo an act that happened after the dose). The strongest covering dose
 * is applied. Incident marginals are summed (union bound), and each incident
 * day's already-embedded plan risk is subtracted once to avoid double-counting
 * the planner's frequency-spread contribution.
 */
export function computeInFlightRealizedRisk(
  days: InFlightDay[],
  residuals: MethodResiduals,
  ecEffect?: EcEffectFn,
): InFlightRealizedResult {
  const planBEvents: { cycleDay: number; ecType: EcType; hoursFromAct?: number }[] = [];
  for (const day of days) {
    for (const event of day.log?.events ?? []) {
      if (event.kind === "plan_b_taken") {
        planBEvents.push({
          cycleDay: day.cycleDay,
          ecType: event.ecType,
          hoursFromAct: event.hoursFromAct,
        });
      }
    }
  }
  const hadEc = planBEvents.length > 0;

  let incidentUpperBound = 0;
  let embeddedPlanRisk = 0;
  let ovulationDelayDays = 0;

  for (const day of days) {
    const incidentCount = (day.log?.events ?? []).filter(
      (e) => e.kind === "condom_broke" || e.kind === "unplanned_unprotected",
    ).length;
    if (incidentCount === 0) continue;

    // Strongest EC dose covering an act on this day (dose taken on/after the act).
    let ecMultiplier = 1;
    if (ecEffect) {
      for (const pb of planBEvents) {
        if (pb.cycleDay + 1e-9 >= day.cycleDay) {
          const eff = ecEffect(pb.ecType, pb.hoursFromAct, day.cycleDay);
          ecMultiplier = Math.min(ecMultiplier, eff.conceptionMultiplier);
          ovulationDelayDays = Math.max(ovulationDelayDays, eff.ovulationDelayDays);
        }
      }
    }

    incidentUpperBound += incidentCount * perActUnprotected(day.dayWeight, residuals) * ecMultiplier;
    embeddedPlanRisk += day.dayWeight.recommendedRiskProbability;
  }

  const realized = Math.min(0.999, Math.max(0, incidentUpperBound - embeddedPlanRisk));
  return { realized, hadEc, ovulationDelayDays };
}
