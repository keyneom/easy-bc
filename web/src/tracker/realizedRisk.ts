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

type IncidentExposure = {
  id: string;
  cycleDay: number;
  occurredAt: string;
  dayWeight: RealizedDayWeight;
};

type EcDose = {
  cycleDay: number;
  ecType: EcType;
  hoursFromAct?: number;
};

function ecWindowHours(ecType: EcType): number {
  return ecType === "levonorgestrel" ? 72 : 120;
}

/**
 * With calendar dates but no authoritative act time, an N-day date gap can
 * represent between (N−1)*24 and (N+1)*24 elapsed hours. Reject combinations
 * that cannot be true; this prevents a dose logged days later with "12 hours"
 * from being credited to every earlier act.
 */
function timingFitsCalendarDays(actDay: number, doseDay: number, hours: number): boolean {
  const dayGap = doseDay - actDay;
  if (dayGap < 0 || !Number.isFinite(hours) || hours < 0) return false;
  const minHours = Math.max(0, (dayGap - 1) * 24);
  const maxHours = (dayGap + 1) * 24;
  return hours >= minHours && hours <= maxHours;
}

function matchedIncidentId(dose: EcDose, incidents: IncidentExposure[]): string | undefined {
  const hours = dose.hoursFromAct;
  if (hours == null || hours > ecWindowHours(dose.ecType)) return undefined;
  return incidents
    .filter((incident) => timingFitsCalendarDays(incident.cycleDay, dose.cycleDay, hours))
    .sort((left, right) => {
      if (left.cycleDay !== right.cycleDay) return left.cycleDay - right.cycleDay;
      return Date.parse(left.occurredAt) - Date.parse(right.occurredAt);
    })
    .at(-1)?.id;
}

/**
 * Aggregate in-flight realized risk across the cycle's days.
 *
 * Each explicit incident is one known act, priced with the per-act probability.
 * A timed EC dose is assigned only to the most recent incident compatible with
 * its calendar date and hours-from-act; one dose is never reused with the same
 * delay for every earlier act. Missing or contradictory timing receives no
 * numeric credit. Incident marginals are summed (union bound), then converted
 * to the conditional increment that replaces the risk already embedded in the
 * retained plan.
 */
export function computeInFlightRealizedRisk(
  days: InFlightDay[],
  residuals: MethodResiduals,
  ecEffect?: EcEffectFn,
): InFlightRealizedResult {
  const planBEvents: EcDose[] = [];
  const incidents: IncidentExposure[] = [];
  for (const day of days) {
    for (const event of day.log?.events ?? []) {
      if (event.kind === "plan_b_taken") {
        planBEvents.push({
          cycleDay: day.cycleDay,
          ecType: event.ecType,
          hoursFromAct: event.hoursFromAct,
        });
      } else if (event.kind === "condom_broke" || event.kind === "unplanned_unprotected") {
        incidents.push({
          id: event.id,
          cycleDay: day.cycleDay,
          occurredAt: event.occurredAt,
          dayWeight: day.dayWeight,
        });
      }
    }
  }
  const hadEc = planBEvents.length > 0;
  const doseMatches = planBEvents.map((dose) => ({
    dose,
    incidentId: matchedIncidentId(dose, incidents),
  }));

  let incidentUpperBound = 0;
  let embeddedPlanSurvival = 1;
  let ovulationDelayDays = 0;

  for (const day of days) {
    const dayIncidents = (day.log?.events ?? []).filter(
      (e) => e.kind === "condom_broke" || e.kind === "unplanned_unprotected",
    );
    if (dayIncidents.length === 0) continue;

    for (const incident of dayIncidents) {
      let ecMultiplier = 1;
      if (ecEffect) {
        for (const { dose, incidentId } of doseMatches) {
          if (incidentId === incident.id) {
            const eff = ecEffect(dose.ecType, dose.hoursFromAct, day.cycleDay);
            ecMultiplier = Math.min(ecMultiplier, eff.conceptionMultiplier);
            ovulationDelayDays = Math.max(ovulationDelayDays, eff.ovulationDelayDays);
          }
        }
      }
      incidentUpperBound += perActUnprotected(day.dayWeight, residuals) * ecMultiplier;
    }
    embeddedPlanSurvival *= 1 - day.dayWeight.recommendedRiskProbability;
  }

  const incidentRisk = Math.min(0.999, Math.max(0, incidentUpperBound));
  const embeddedPlanRisk = Math.min(0.999, Math.max(0, 1 - embeddedPlanSurvival));
  const realized = incidentRisk > embeddedPlanRisk
    ? Math.min(0.999, (incidentRisk - embeddedPlanRisk) / (1 - embeddedPlanRisk))
    : 0;
  return { realized, hadEc, ovulationDelayDays };
}
