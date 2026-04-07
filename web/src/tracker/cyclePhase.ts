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

/** Effective last bleeding day for a record (inclusive), capped before next period. */
export function derivedBleedingEnd(rec: PeriodRecord, sorted: PeriodRecord[]): string | undefined {
  if (rec.end) return rec.end;
  const idx = sorted.findIndex((x) => x.start === rec.start);
  if (idx < 0) return undefined;
  const next = sorted[idx + 1];
  if (next) return addDaysIso(next.start, -1);
  return undefined;
}

/** Ongoing period (no end) is treated as bleeding only through `todayIso`, not forever forward. */
export function isBleedingOnDate(
  dateIso: string,
  records: PeriodRecord[],
  todayIso: string = toIsoDate(new Date()),
): boolean {
  const sorted = sortRecords(records);
  for (const rec of sorted) {
    if (compareIso(dateIso, rec.start) < 0) continue;
    const end = derivedBleedingEnd(rec, sorted);
    if (!end) {
      if (
        compareIso(dateIso, rec.start) >= 0 &&
        compareIso(dateIso, todayIso) <= 0
      ) {
        return true;
      }
    } else if (compareIso(dateIso, rec.start) >= 0 && compareIso(dateIso, end) <= 0) {
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
    const end = derivedBleedingEnd(rec, sorted) ?? upToIso;
    const last = compareIso(end, upToIso) > 0 ? upToIso : end;
    if (compareIso(last, rec.start) < 0) continue;
    for (const iso of eachIsoInRange(rec.start, last)) {
      set.add(iso);
    }
  }
  return set;
}
