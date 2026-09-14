import { describe, expect, it } from "vitest";
import type { WasmOptions } from "../App";
import type { DayEvent, PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import type { PortablePlannerOptions } from "./types";
import { easyBcSharedCodec } from "./sharedCodec";
import {
  buildSharedSyncPayload,
  sharedPayloadFingerprint,
  type SharedSyncPayloadV1,
} from "./sharedTypes";
import { DATASET_PARTS, projectDatasetPart, updateCalendarDayLog } from "./datasets";
import { reconcileSyncResult } from "./reconcileSyncResult";

const options = (ageYears: number): PortablePlannerOptions => ({
  ageYears,
  horizonYears: 20,
  targetCumulativeFailure: 0.05,
  cycleLengthDays: 28,
  actsPerWeek: 3.5,
  persistentMethod: "none",
  protectedDayMethod: "none",
  condomMode: "perfect",
  streakAversion: 0.5,
  holdLifecycleConstant: false,
  realizedCumulativeRisk: 0,
  withdrawalMode: "none",
  withdrawalTypicalAnnualFailure: 0.2,
  withdrawalRelativeRisk: 0.35,
  useWithdrawalBackupOnProtectedDays: false,
  combinedMethodIndependence: 0.35,
  ovulationSdDays: 3,
});

const session = (plannerOptionsUpdatedAt: string): PersistedSession => ({
  plannerConfigured: true,
  plannerOptionsUpdatedAt,
  calendarDayLogs: {},
  dayLogs: {},
  voluntaryAbstinenceDates: {},
  voluntaryAbstinenceUpdatedAt: {},
  deletedPeriodStarts: {},
  deletedVoluntaryAbstinenceDates: {},
  ecJournalFlag: false,
  ecJournalUpdatedAt: "2026-01-01T00:00:00.000Z",
  locks: [],
  realizedCumulativeRisk: 0,
});

const payload = (
  ageYears: number,
  plannerOptionsUpdatedAt: string,
  periodRecords: PeriodRecord[],
): SharedSyncPayloadV1 =>
  buildSharedSyncPayload(
    options(ageYears) as WasmOptions,
    periodRecords,
    session(plannerOptionsUpdatedAt),
  );

const record = (start: string, updatedAt: string, end?: string): PeriodRecord => ({
  start,
  updatedAt,
  ...(end ? { end } : {}),
});

/**
 * One round trip of the real lifecycle: a snapshot goes out, the controller
 * merges it against the cloud, and the result comes back to be applied while
 * the user has kept editing.
 */
function roundTrip(snapshot: SharedSyncPayloadV1, remote: SharedSyncPayloadV1) {
  return easyBcSharedCodec.merge(snapshot, remote) as SharedSyncPayloadV1;
}

describe("reconcileSyncResult", () => {
  it("keeps an edit made while the sync round trip was in flight", () => {
    const snapshot = payload(30, "2026-01-01T00:00:00.000Z", [
      record("2026-01-02", "2026-01-02T00:00:00.000Z", "2026-01-06"),
    ]);
    // Another device logged a period while this one was syncing.
    const remote = payload(30, "2026-01-01T00:00:00.000Z", [
      record("2026-02-01", "2026-02-01T00:00:00.000Z", "2026-02-05"),
    ]);
    const synced = roundTrip(snapshot, remote);

    // The user logs a third period during the round trip.
    const live = payload(30, "2026-01-01T00:00:00.000Z", [
      ...snapshot.periodRecords,
      record("2026-03-01", "2026-03-01T00:00:00.000Z", "2026-03-05"),
    ]);

    // Applying the result as-is is what silently dropped the edit.
    expect(synced.periodRecords.map((row) => row.start)).not.toContain("2026-03-01");

    const applied = reconcileSyncResult(synced, live);
    expect(applied.periodRecords.map((row) => row.start)).toEqual([
      "2026-01-02",
      "2026-02-01",
      "2026-03-01",
    ]);
  });

  it("keeps a settings change made while the sync round trip was in flight", () => {
    const snapshot = payload(30, "2026-01-01T00:00:00.000Z", []);
    const remote = payload(31, "2026-01-05T00:00:00.000Z", []);
    const synced = roundTrip(snapshot, remote);
    expect(synced.planner.value.ageYears).toBe(31);

    const live = payload(35, "2026-01-09T00:00:00.000Z", []);
    expect(reconcileSyncResult(synced, live).planner.value.ageYears).toBe(35);
  });

  it("leaves the synced payload alone when nothing changed during the round trip", () => {
    const snapshot = payload(30, "2026-01-01T00:00:00.000Z", [
      record("2026-01-02", "2026-01-02T00:00:00.000Z", "2026-01-06"),
    ]);
    const remote = payload(31, "2026-01-05T00:00:00.000Z", [
      record("2026-02-01", "2026-02-01T00:00:00.000Z", "2026-02-05"),
    ]);
    const synced = roundTrip(snapshot, remote);

    // Live local is still the snapshot: the reconcile must be a no-op, or every
    // sync would report an unpublished change and loop.
    const applied = reconcileSyncResult(synced, snapshot);
    expect({ ...applied, exportedAt: "" }).toEqual({ ...synced, exportedAt: "" });
  });

  it("does not resurrect a record the remote deleted during the round trip", () => {
    const snapshot = payload(30, "2026-01-01T00:00:00.000Z", [
      record("2026-01-02", "2026-01-02T00:00:00.000Z", "2026-01-06"),
    ]);
    const remote: SharedSyncPayloadV1 = {
      ...payload(30, "2026-01-01T00:00:00.000Z", []),
      deletedPeriodStarts: { "2026-01-02": "2026-01-20T00:00:00.000Z" },
    };
    const synced = roundTrip(snapshot, remote);
    expect(synced.periodRecords).toEqual([]);

    // The user touched something unrelated, so live local still carries the
    // record the remote tombstoned. The tombstone is newer and must win.
    const live = payload(35, "2026-01-21T00:00:00.000Z", snapshot.periodRecords);
    const applied = reconcileSyncResult(synced, live);
    expect(applied.periodRecords).toEqual([]);
    expect(applied.planner.value.ageYears).toBe(35);
  });

  it("keeps a local edit that lands after a remote tombstone", () => {
    const snapshot = payload(30, "2026-01-01T00:00:00.000Z", []);
    const remote: SharedSyncPayloadV1 = {
      ...payload(30, "2026-01-01T00:00:00.000Z", []),
      deletedPeriodStarts: { "2026-01-02": "2026-01-20T00:00:00.000Z" },
    };
    const synced = roundTrip(snapshot, remote);

    // Re-logged locally after the delete: a newer write beats the tombstone.
    const live = payload(30, "2026-01-01T00:00:00.000Z", [
      record("2026-01-02", "2026-01-25T00:00:00.000Z", "2026-01-06"),
    ]);
    const applied = reconcileSyncResult(synced, live);
    expect(applied.periodRecords.map((row) => row.start)).toEqual(["2026-01-02"]);
  });
});

/**
 * sync-kit's apply guard, verbatim: merging `merged` into what apply returned
 * must add nothing (SharedBackupController.commitMerged in 0.4.1). Equality is
 * deliberately not the check — folding in newer local edits is expected — but
 * dropping part of the merge raises a `state` error.
 */
function subsumes(merged: SharedSyncPayloadV1, committed: SharedSyncPayloadV1): boolean {
  return (
    sharedPayloadFingerprint(
      easyBcSharedCodec.merge(merged, committed) as SharedSyncPayloadV1,
    ) === sharedPayloadFingerprint(committed)
  );
}

/**
 * Two *independent* properties keep sync-kit's guard satisfied on fields whose
 * timestamps tie, and only losing both throws — verified against the 0.4.1
 * guard across all four combinations:
 *
 *   a) `easyBcSharedCodec.merge` swaps its arguments, so the guard's
 *      `codec.merge(merged, committed)` resolves ties to `committed` itself.
 *   b) `reconcileSyncResult` resolves ties toward the synced payload, so a tie
 *      field in `committed` already holds `merged`'s value.
 *
 * Worth being precise about, because in *codec* terms our apply is
 * `codec.merge(live, merged)` — local-first, the opposite of sync-kit's
 * normative merged-first rule. We are safe despite that, not because of it.
 * If this ever needs to follow the normative form, call
 * `easyBcSharedCodec.merge(synced, live)` — but note that flips tie resolution
 * from the published value to the local one, which is a real behavior change.
 */
describe("the guard's tie-safety rests on two independent properties", () => {
  const tied = "2026-04-01T00:00:00.000Z";

  it("(b) resolves ties toward the synced payload, not live local", () => {
    const merged = payload(31, tied, []);
    const live = payload(35, tied, []);
    expect(reconcileSyncResult(merged, live).planner.value.ageYears).toBe(31);
  });

  it("(a) uses a codec whose merge resolves ties toward its remote argument", () => {
    const local = payload(31, tied, []);
    const remote = payload(35, tied, []);
    const merged = easyBcSharedCodec.merge(local, remote) as SharedSyncPayloadV1;
    expect(merged.planner.value.ageYears).toBe(35);
  });
});

describe("reconcileSyncResult satisfies sync-kit's subsumption guard", () => {
  it("subsumes the merge when a local edit survives", () => {
    const snapshot = payload(30, "2026-01-01T00:00:00.000Z", []);
    const remote = payload(31, "2026-01-05T00:00:00.000Z", [
      record("2026-02-01", "2026-02-01T00:00:00.000Z", "2026-02-05"),
    ]);
    const merged = roundTrip(snapshot, remote);
    const live = payload(35, "2026-01-09T00:00:00.000Z", []);

    expect(subsumes(merged, reconcileSyncResult(merged, live))).toBe(true);
  });

  it("subsumes the merge when nothing changed during the round trip", () => {
    const snapshot = payload(30, "2026-01-01T00:00:00.000Z", [
      record("2026-01-02", "2026-01-02T00:00:00.000Z", "2026-01-06"),
    ]);
    const merged = roundTrip(snapshot, payload(31, "2026-01-05T00:00:00.000Z", []));

    expect(subsumes(merged, reconcileSyncResult(merged, snapshot))).toBe(true);
  });

  // The maintainer's specific ask: our merge resolves ties to its first
  // argument, and the guard computes codec.merge(merged, committed) — which is
  // mergeSharedSyncPayloads(committed, merged), ties to committed. Ties must
  // land on the committed side or a correct apply raises a spurious `state`.
  it("subsumes the merge when local and remote timestamps are identical", () => {
    const sameTime = "2026-04-01T00:00:00.000Z";
    const snapshot = payload(30, sameTime, []);
    const remote = payload(31, sameTime, [record("2026-02-01", sameTime, "2026-02-05")]);
    const merged = roundTrip(snapshot, remote);
    const live = payload(44, sameTime, [record("2026-02-01", sameTime, "2026-02-05")]);

    expect(subsumes(merged, reconcileSyncResult(merged, live))).toBe(true);
  });

  // mergePeriods breaks equal-timestamp ties by preferring the record that has
  // an `end`, independent of argument order — the one place the tie rule is not
  // purely positional, so it gets its own case in both directions.
  it("subsumes the merge when an equal-timestamp period differs only by end", () => {
    const sameTime = "2026-05-01T00:00:00.000Z";
    const openLocally = payload(30, "2026-01-01T00:00:00.000Z", [
      record("2026-04-20", sameTime),
    ]);
    const closedRemotely = payload(30, "2026-01-01T00:00:00.000Z", [
      record("2026-04-20", sameTime, "2026-04-25"),
    ]);

    expect(
      subsumes(
        roundTrip(openLocally, closedRemotely),
        reconcileSyncResult(roundTrip(openLocally, closedRemotely), openLocally),
      ),
    ).toBe(true);
    expect(
      subsumes(
        roundTrip(closedRemotely, openLocally),
        reconcileSyncResult(roundTrip(closedRemotely, openLocally), closedRemotely),
      ),
    ).toBe(true);
  });

  // Split profiles sync one dataset per part: apply returns the part's
  // projection of live local, so the guard runs against a projection on both
  // sides. Parts are disjoint, so this must hold for every one of them.
  it("subsumes each part's merge on the split-profile path", () => {
    const snapshot = payload(30, "2026-01-01T00:00:00.000Z", [
      record("2026-01-02", "2026-01-02T00:00:00.000Z", "2026-01-06"),
    ]);
    const remote = payload(31, "2026-01-05T00:00:00.000Z", [
      record("2026-02-01", "2026-02-01T00:00:00.000Z", "2026-02-05"),
    ]);
    const live = payload(35, "2026-01-09T00:00:00.000Z", [
      ...snapshot.periodRecords,
      record("2026-03-01", "2026-03-01T00:00:00.000Z", "2026-03-05"),
    ]);

    for (const part of DATASET_PARTS) {
      const mergedPart = roundTrip(
        projectDatasetPart(snapshot, part),
        projectDatasetPart(remote, part),
      );
      const committedFull = reconcileSyncResult(mergedPart, live, part);
      expect(subsumes(mergedPart, projectDatasetPart(committedFull, part))).toBe(true);
    }
  });
});

describe("split day-log commits", () => {
  const date = "2026-09-13";
  const t1 = "2026-09-13T10:00:00.000Z";
  const t2 = "2026-09-13T10:01:00.000Z";
  const t3 = "2026-09-13T10:02:00.000Z";
  const incident: DayEvent = { id: "incident", kind: "condom_broke", occurredAt: t1 };
  const ec: DayEvent = {
    id: "ec", kind: "plan_b_taken", ecType: "levonorgestrel", occurredAt: t1,
  };
  const withEvents = (events: DayEvent[]): SharedSyncPayloadV1 => ({
    ...payload(30, t1, []),
    calendarDayLogs: { [date]: { actualAction: "C", mucus: "dry", events, updatedAt: t1 } },
  });

  it.each([[incident, ec], [ec, incident]])("keeps both same-day events through repeated split syncs (%j first)", (first, second) => {
    let live = withEvents([first]);
    const remote = { ...live };
    live = { ...live, calendarDayLogs: {
      [date]: updateCalendarDayLog(live.calendarDayLogs[date], { events: [first, second] }, t2),
    } };
    for (let turn = 0; turn < 2; turn++) {
      for (const part of DATASET_PARTS) {
        const merged = roundTrip(projectDatasetPart(live, part), projectDatasetPart(remote, part));
        live = reconcileSyncResult(merged, live, part);
        expect(live.calendarDayLogs[date].events).toEqual(expect.arrayContaining([incident, ec]));
        expect(live.calendarDayLogs[date].events).toHaveLength(2);
        expect(live.calendarDayLogs[date].mucus).toBe("dry");
        expect(live.calendarDayLogs[date].actualAction).toBe("C");
        expect(subsumes(merged, projectDatasetPart(live, part))).toBe(true);
      }
    }
  });

  it("keeps the incident when EC is removed and re-added during sync", () => {
    const original = withEvents([incident, ec]);
    const removed = updateCalendarDayLog(original.calendarDayLogs[date], { events: [incident] }, t2);
    const pendingDelete = projectDatasetPart({ ...original, calendarDayLogs: { [date]: removed } }, "sensitive");
    const replacement = { ...ec, id: "replacement-ec", occurredAt: t3 };
    let live: SharedSyncPayloadV1 = { ...original, calendarDayLogs: {
      [date]: updateCalendarDayLog(removed, { events: [incident, replacement] }, t3),
    } };
    live = reconcileSyncResult(pendingDelete, live, "sensitive");
    for (const part of DATASET_PARTS) {
      live = reconcileSyncResult(projectDatasetPart(live, part), live, part);
    }
    expect(live.calendarDayLogs[date].events).toEqual([incident, replacement]);
    expect(projectDatasetPart(live, "sensitive").calendarDayLogs[date].events).toEqual([replacement]);
  });

  it("keeps both events when the day has no action or body signals", () => {
    let live: SharedSyncPayloadV1 = { ...withEvents([]), calendarDayLogs: {
      [date]: { events: [incident, ec], updatedAt: t2 },
    } };
    for (const part of DATASET_PARTS) {
      live = reconcileSyncResult(projectDatasetPart(live, part), live, part);
      expect(live.calendarDayLogs[date].events).toEqual([incident, ec]);
    }
  });

  it("applies a remote EC deletion without deleting the incident or resurrecting EC", () => {
    const live = withEvents([incident, ec]);
    const remote = { ...live, calendarDayLogs: { [date]: { updatedAt: t2 } } };
    const committed = reconcileSyncResult(projectDatasetPart(remote, "sensitive"), live, "sensitive");
    expect(committed.calendarDayLogs[date].events).toEqual([incident]);
    expect(committed.calendarDayLogs[date].deletedDatasetParts?.sensitive).toBe(t2);
    const again = reconcileSyncResult(projectDatasetPart(committed, "sensitive"), committed, "sensitive");
    expect(again.calendarDayLogs[date].events).toEqual([incident]);
  });
});
