/**
 * Map calendar state → initial_action_locks and helpers for "today" in a row/day grid.
 */

export type PlannerAction = "U" | "C" | "A" | "W";
export type DayLock = { yearIndex: number; day: number; action: PlannerAction };

export type PersistedSession = {
  locks: DayLock[];
  realizedCumulativeRisk: number;
  ecJournalFlag: boolean;
  /** row:day (1-based day) → logged as-lived action */
  dayLogs: Record<string, PlannerAction>;
  /**
   * Voluntary abstinence on calendar days you could have had sex — local YYYY-MM-DD.
   * v1: counted as “abstinence credits” for your own planning narrative (no automatic planner spend yet).
   */
  voluntaryAbstinenceDates: Record<string, true>;
};

export const defaultPersistedSession = (): PersistedSession => ({
  locks: [],
  realizedCumulativeRisk: 0,
  ecJournalFlag: false,
  dayLogs: {},
  voluntaryAbstinenceDates: {},
});

export function dayLogKey(rowIndex: number, day: number): string {
  return `${rowIndex}:${day}`;
}

/** Days since anchor (inclusive day 1 = first day of row 0). */
export function resolveHorizonRowAndDay(
  cycleLengths: number[],
  daysSinceFirstCycleStart: number,
): { row: number; dayInCycle: number } | null {
  if (cycleLengths.length === 0 || daysSinceFirstCycleStart < 0) return null;
  let remaining = daysSinceFirstCycleStart;
  for (let row = 0; row < cycleLengths.length; row++) {
    const len = cycleLengths[row];
    if (remaining < len) {
      return { row, dayInCycle: remaining + 1 };
    }
    remaining -= len;
  }
  const last = cycleLengths.length - 1;
  return { row: last, dayInCycle: cycleLengths[last] };
}

/**
 * Locks for all (row, day) strictly before `pos` in the grid, using dayLogs or defaultAsLived.
 */
export function initialLocksForPastDays(
  cycleLengths: number[],
  pos: { row: number; dayInCycle: number },
  dayLogs: Record<string, PlannerAction>,
  defaultAsLived: PlannerAction = "U",
): DayLock[] {
  const out: DayLock[] = [];
  for (let row = 0; row < cycleLengths.length; row++) {
    const len = cycleLengths[row];
    const endDay = row < pos.row ? len : row > pos.row ? 0 : pos.dayInCycle - 1;
    for (let day = 1; day <= endDay; day++) {
      const k = dayLogKey(row, day);
      const action = dayLogs[k] ?? defaultAsLived;
      out.push({ yearIndex: row, day, action });
    }
  }
  return out;
}

/** Days since first modeled cycle start (day 0 = first cycle day). */
export function daysSinceFirstCycleStart(firstPeriodStartIso: string, today = new Date()): number {
  const start = new Date(firstPeriodStartIso + "T12:00:00");
  const t = new Date(today);
  t.setHours(12, 0, 0, 0);
  return Math.floor((t.getTime() - start.getTime()) / 86_400_000);
}

/** v1 placeholder increments per docs/incidents.md (non-clinical). */
export const INCIDENT_REALIZED_DELTA: Record<string, number> = {
  unprotected_on_abstinence: 0.005,
  condom_on_abstinence: 0.0005,
  condom_failure: 0.008,
  unprotected_instead_of_condom: 0.006,
};
