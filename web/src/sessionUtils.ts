/**
 * Map calendar state → initial_action_locks and helpers for "today" in a row/day grid.
 */

export type PlannerAction = "U" | "C" | "A" | "W";
/**
 * Logged action for a day. `"CB"` was a legacy value that conflated the
 * action with a broken-condom incident; it is auto-migrated on read (in
 * `hydratePersistedSession`) to `actualAction = "C"` plus a `condom_broke`
 * event, so the type itself no longer includes `"CB"`.
 */
export type LoggedAction = PlannerAction | "NONE";

/** Emergency contraception type stored with a dated journal event. */
export type EcType = "levonorgestrel" | "ulipristal" | "copper_iud";

export type DayEventKind =
  | { kind: "condom_broke" }
  | { kind: "unplanned_unprotected" }
  | { kind: "plan_b_taken"; ecType: EcType; hoursFromAct?: number };

/**
 * Per-day out-of-band event, independent of the day's planned/actual action.
 * Multiple events per day are allowed (e.g. two broken-condom incidents
 * followed by a Plan B). Reportable on any day regardless of plan.
 */
export type DayEvent = DayEventKind & {
  id: string;
  occurredAt: string;
  notes?: string;
};

export type CalendarDayLog = {
  actualAction?: LoggedAction;
  notes?: string;
  mucus?: "dry" | "sticky" | "creamy" | "egg-white" | "eggwhite" | "spotting";
  bbtCelsius?: number;
  opk?: "negative" | "positive" | "unclear" | "peak";
  mittelschmerz?: boolean;
  breastTender?: boolean;
  reconciled?: boolean;
  events?: DayEvent[];
  /** Used only to resolve cross-device edits; it is not shown in the UI. */
  updatedAt?: string;
};
export type DayLock = { yearIndex: number; day: number; action: PlannerAction };
export type IncidentType =
  | "unprotected_on_abstinence"
  | "condom_on_abstinence"
  | "condom_failure"
  | "unprotected_instead_of_condom";
export type RescueMethod =
  | "none"
  | "levonorgestrel_ec"
  | "ulipristal_ec"
  | "copper_iud_ec";
export type PreservedAndroidPreferences = {
  value: {
    calendarLabelPeriod: string;
    calendarLabelFertile: string;
    calendarLabelActionU: string;
    calendarLabelActionC: string;
    calendarLabelActionA: string;
    calendarLabelActionW: string;
    reminderHour: number;
    reminderMinute: number;
  };
  updatedAt: string;
};
export type PlannerDayRiskLike = {
  recommendedAction: PlannerAction;
  rawRiskProbability: number;
  protectedRiskProbability: number;
  recommendedRiskProbability: number;
};

export type PersistedSession = {
  /** Whether the user has committed planner settings and should have a derived plan. */
  plannerConfigured: boolean;
  locks: DayLock[];
  realizedCumulativeRisk: number;
  ecJournalFlag: boolean;
  /** row:day (1-based day) → logged as-lived action */
  dayLogs: Record<string, PlannerAction>;
  /** ISO date → portable, user-entered observations matching the Android day log. */
  calendarDayLogs: Record<string, CalendarDayLog>;
  /**
   * Voluntary abstinence on calendar days you could have had sex — local YYYY-MM-DD.
   * v1: counted as “abstinence credits” for your own planning narrative (no automatic planner spend yet).
   */
  voluntaryAbstinenceDates: Record<string, true>;
  /** Last edit for each active abstinence credit. */
  voluntaryAbstinenceUpdatedAt: Record<string, string>;
  /** Tombstones let deletions propagate instead of reappearing after a merge. */
  deletedPeriodStarts: Record<string, string>;
  deletedVoluntaryAbstinenceDates: Record<string, string>;
  plannerOptionsUpdatedAt: string;
  ecJournalUpdatedAt: string;
  /** Android-only sync preferences; web preserves these without applying them. */
  androidPreferences?: PreservedAndroidPreferences;
};

export const SYNC_EPOCH = "1970-01-01T00:00:00.000Z";

export const defaultPersistedSession = (): PersistedSession => ({
  plannerConfigured: false,
  locks: [],
  realizedCumulativeRisk: 0,
  ecJournalFlag: false,
  dayLogs: {},
  calendarDayLogs: {},
  voluntaryAbstinenceDates: {},
  voluntaryAbstinenceUpdatedAt: {},
  deletedPeriodStarts: {},
  deletedVoluntaryAbstinenceDates: {},
  plannerOptionsUpdatedAt: SYNC_EPOCH,
  ecJournalUpdatedAt: SYNC_EPOCH,
});

export function hydratePersistedSession(
  raw: Partial<PersistedSession> | undefined,
  periodRecordCount: number,
): PersistedSession {
  const hydrated: PersistedSession = {
    ...defaultPersistedSession(),
    ...raw,
    locks: raw?.locks ?? [],
    dayLogs: raw?.dayLogs ?? {},
    calendarDayLogs: migrateCalendarDayLogs(raw?.calendarDayLogs ?? {}),
    voluntaryAbstinenceDates: raw?.voluntaryAbstinenceDates ?? {},
    voluntaryAbstinenceUpdatedAt: raw?.voluntaryAbstinenceUpdatedAt ?? {},
    deletedPeriodStarts: raw?.deletedPeriodStarts ?? {},
    deletedVoluntaryAbstinenceDates: raw?.deletedVoluntaryAbstinenceDates ?? {},
  };
  return {
    ...hydrated,
    plannerConfigured: raw?.plannerConfigured ?? (
      hydrated.plannerOptionsUpdatedAt !== SYNC_EPOCH && periodRecordCount > 0
    ),
  };
}

/**
 * Auto-migrate legacy `"CB"` (condom-broke) records into the new shape:
 * `actualAction = "C"` plus a synthesized `condom_broke` event with the
 * day's existing `updatedAt` timestamp. Idempotent — leaves already-migrated
 * logs untouched.
 */
function migrateCalendarDayLogs(
  logs: Record<string, CalendarDayLog>,
): Record<string, CalendarDayLog> {
  let mutated = false;
  const out: Record<string, CalendarDayLog> = {};
  for (const [iso, log] of Object.entries(logs)) {
    const legacyAction = log.actualAction as string | undefined;
    if (legacyAction === "CB") {
      mutated = true;
      const existingEvents = log.events ?? [];
      const alreadyMigrated = existingEvents.some((e) => e.kind === "condom_broke");
      out[iso] = {
        ...log,
        actualAction: "C",
        events: alreadyMigrated
          ? existingEvents
          : [
              ...existingEvents,
              {
                id: synthEventId(iso, "condom_broke"),
                kind: "condom_broke",
                occurredAt: log.updatedAt ?? `${iso}T12:00:00.000Z`,
              },
            ],
      };
    } else {
      out[iso] = log;
    }
  }
  return mutated ? out : logs;
}

function synthEventId(iso: string, kind: string): string {
  // Deterministic id so the same legacy record migrates to the same event id
  // across devices — avoids sync dedup creating duplicates after both sides
  // run the migration.
  return `migrated:${iso}:${kind}`;
}

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
  defaultAsLived?: PlannerAction | null,
): DayLock[] {
  const out: DayLock[] = [];
  for (let row = 0; row < cycleLengths.length; row++) {
    const len = cycleLengths[row];
    const endDay = row < pos.row ? len : row > pos.row ? 0 : pos.dayInCycle - 1;
    for (let day = 1; day <= endDay; day++) {
      const k = dayLogKey(row, day);
      const action = dayLogs[k] ?? defaultAsLived;
      if (!action) continue;
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

export function incidentActionForType(type: IncidentType): PlannerAction {
  switch (type) {
    case "condom_on_abstinence":
      return "C";
    case "condom_failure":
    case "unprotected_instead_of_condom":
    case "unprotected_on_abstinence":
      return "U";
  }
}

/**
 * Added pregnancy risk from a discrete incident on a modeled day, versus the
 * planner's recommendation for that day.
 *
 * The `*RiskProbability` fields are *per-day-pattern* — they spread acts_per_week
 * across all 7 days, so the bare difference understates a single real act by the
 * frequency factor (~0.43× at 3 acts/week). When `actsPerWeek` is supplied we
 * undo that scaling so the *excess over plan* is priced per-act, which is what a
 * one-off incident (broken condom, unprotected act on a planned-abstain day)
 * actually is. Omitting `actsPerWeek` falls back to the legacy per-day-pattern
 * difference. See docs/risk-accounting-and-ec.md.
 */
export function estimateIncidentAdditionalRisk(
  dayWeight: PlannerDayRiskLike | null | undefined,
  type: IncidentType,
  actsPerWeek?: number,
): number {
  if (!dayWeight) return 0;
  const actualPdp =
    type === "condom_on_abstinence"
      ? dayWeight.protectedRiskProbability
      : dayWeight.rawRiskProbability;
  const excessPdp = Math.max(0, actualPdp - dayWeight.recommendedRiskProbability);
  if (excessPdp <= 0) return 0;
  const actsPerDay = actsPerWeek && actsPerWeek > 0 ? actsPerWeek / 7 : null;
  if (!actsPerDay) return excessPdp;
  return Math.min(1, excessPdp / actsPerDay);
}
