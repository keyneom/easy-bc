import { referenceCycleLengthForAge } from "../periodTracker";
import { addDaysIso, compareIso, daysFromTo, eachIsoInRange, toIsoDate } from "./calendarMath";
import type { PeriodRecord } from "./types";

export type CyclePhase = "menstrual" | "follicular" | "fertile" | "luteal" | "unknown";

export type CyclePhaseEstimate = {
  phase: CyclePhase;
  cycleDay: number | null;
  predictedCycleLength: number;
  /** True when we have no logged periods and show a population-based preview only. */
  usingSampleCycle: boolean;
  /** Approximate next period start (local ISO) from calendar math only. */
  nextPeriodEstimate: string | null;
  notes: string;
};

function sortRecords(records: PeriodRecord[]): PeriodRecord[] {
  return [...records].sort((a, b) => compareIso(a.start, b.start));
}

/**
 * Effective last bleeding day for a record (inclusive). Unified with the
 * Android `CycleCalculator.effectiveBleedingEndEpochDay` rule:
 *
 * 1. Use logged `rec.end` if present.
 * 2. Else use the user's mean closed-period duration (requires ≥3 closed
 *    periods for stability), otherwise 5 days.
 * 3. Cap at the day before the next period start.
 *
 * We deliberately do **not** cap at today: for an unresolved period we want
 * to show the predicted remaining bleeding days forward so the user sees
 * when their period is expected to end. `todayIso` is kept in the signature
 * for API stability but no longer truncates.
 */
export function derivedBleedingEnd(
  rec: PeriodRecord,
  sorted: PeriodRecord[],
  _todayIso: string = toIsoDate(new Date()),
): string {
  if (rec.end) return rec.end;

  const closedOthers = sorted.filter((r) => r.end && r.start !== rec.start);
  let fallbackDays = 5;
  if (closedOthers.length >= 3) {
    const total = closedOthers.reduce(
      (acc, r) => acc + (daysFromTo(r.start, r.end as string) + 1),
      0,
    );
    const mean = total / closedOthers.length;
    fallbackDays = Math.round(mean);
    if (fallbackDays < 2) fallbackDays = 2;
    if (fallbackDays > 14) fallbackDays = 14;
  }

  const estimated = addDaysIso(rec.start, fallbackDays - 1);
  const next = sorted.find((r) => compareIso(r.start, rec.start) > 0);
  const nextCap = next ? addDaysIso(next.start, -1) : null;

  // min over (estimated, nextCap)
  let end = estimated;
  if (nextCap && compareIso(end, nextCap) > 0) end = nextCap;
  // Don't let end fall before start (possible if nextCap < start — shouldn't happen)
  if (compareIso(end, rec.start) < 0) end = rec.start;
  return end;
}

/** True if `dateIso` is within any period's bleeding window. */
export function isBleedingOnDate(
  dateIso: string,
  records: PeriodRecord[],
  todayIso: string = toIsoDate(new Date()),
): boolean {
  const sorted = sortRecords(records);
  for (const rec of sorted) {
    if (compareIso(dateIso, rec.start) < 0) continue;
    const end = derivedBleedingEnd(rec, sorted, todayIso);
    if (compareIso(dateIso, rec.start) >= 0 && compareIso(dateIso, end) <= 0) {
      return true;
    }
  }
  return false;
}

function lastPeriodStartOnOrBefore(dateIso: string, sorted: PeriodRecord[]): string | null {
  let best: string | null = null;
  for (const rec of sorted) {
    if (compareIso(rec.start, dateIso) <= 0 && (!best || compareIso(rec.start, best) > 0)) {
      best = rec.start;
    }
  }
  return best;
}

/**
 * Calendar-only phase guess (not clinical). Fertile window ~ ovulation day L-14 ± window.
 */
export function estimateCyclePhase(
  dateIso: string,
  records: PeriodRecord[],
  ageYears: number,
  fertileHalfWidth = 5,
): CyclePhaseEstimate {
  const sorted = sortRecords(records);
  const refL = Math.round(referenceCycleLengthForAge(ageYears));
  const todayIso = toIsoDate(new Date());

  if (sorted.length === 0) {
    const virtualStart = addDaysIso(todayIso, -Math.min(10, Math.floor(refL / 3)));
    const cycleDay = daysFromTo(virtualStart, dateIso) + 1;
    const phase = phaseFromCycleDay(cycleDay, refL, fertileHalfWidth, false);
    return {
      phase,
      cycleDay,
      predictedCycleLength: refL,
      usingSampleCycle: true,
      nextPeriodEstimate: addDaysIso(virtualStart, refL),
      notes:
        "No periods logged — showing a sample cycle from population length for this age. Log bleeding to personalize.",
    };
  }

  if (isBleedingOnDate(dateIso, sorted)) {
    const start = lastPeriodStartOnOrBefore(dateIso, sorted);
    const cd = start ? daysFromTo(start, dateIso) + 1 : null;
    return {
      phase: "menstrual",
      cycleDay: cd,
      predictedCycleLength: refL,
      usingSampleCycle: false,
      nextPeriodEstimate: start ? addDaysIso(start, refL) : null,
      notes: "Logged bleeding window (start/end in History).",
    };
  }

  const lastStart = lastPeriodStartOnOrBefore(dateIso, sorted);
  if (!lastStart) {
    return {
      phase: "unknown",
      cycleDay: null,
      predictedCycleLength: refL,
      usingSampleCycle: false,
      nextPeriodEstimate: null,
      notes: "Date is before your first logged period.",
    };
  }

  const histLengths: number[] = [];
  for (let i = 1; i < sorted.length; i++) {
    const a = sorted[i - 1].start;
    const b = sorted[i].start;
    const len = daysFromTo(a, b);
    if (len >= 21 && len <= 60) histLengths.push(len);
  }
  const personal =
    histLengths.length > 0
      ? Math.round(histLengths.reduce((x, y) => x + y, 0) / histLengths.length)
      : refL;
  const L = Math.max(21, Math.min(60, personal));

  const cycleDay = daysFromTo(lastStart, dateIso) + 1;
  const phase = phaseFromCycleDay(cycleDay, L, fertileHalfWidth, true);
  const nextPeriod = addDaysIso(lastStart, L);

  let notes =
    "Estimate from last period start and mean interval between your logged starts (calendar-only).";
  if (cycleDay > L + 7) {
    notes += " Cycle day is long vs prediction — consider logging a new period if it started.";
  }

  return {
    phase,
    cycleDay,
    predictedCycleLength: L,
    usingSampleCycle: false,
    nextPeriodEstimate: nextPeriod,
    notes,
  };
}

function phaseFromCycleDay(
  cycleDay: number,
  L: number,
  fertileHalfWidth: number,
  canBeLate: boolean,
): CyclePhase {
  if (cycleDay < 1) return "unknown";
  const ovu = Math.max(8, L - 14);
  if (Math.abs(cycleDay - ovu) <= fertileHalfWidth) return "fertile";
  if (cycleDay < ovu - fertileHalfWidth) return "follicular";
  if (canBeLate && cycleDay > L + 3) return "unknown";
  return "luteal";
}

/** All ISO dates that are bleeding for calendar shading. */
export function allBleedingDates(records: PeriodRecord[], upToIso: string): Set<string> {
  const sorted = sortRecords(records);
  const set = new Set<string>();
  for (const rec of sorted) {
    const end = derivedBleedingEnd(rec, sorted, upToIso);
    const last = compareIso(end, upToIso) > 0 ? upToIso : end;
    if (compareIso(last, rec.start) < 0) continue;
    for (const iso of eachIsoInRange(rec.start, last)) {
      set.add(iso);
    }
  }
  return set;
}
