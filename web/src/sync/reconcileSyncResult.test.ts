import { describe, expect, it } from "vitest";
import type { WasmOptions } from "../App";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import type { PortablePlannerOptions } from "./types";
import { easyBcSharedCodec } from "./sharedCodec";
import {
  buildSharedSyncPayload,
  sharedPayloadFingerprint,
  type SharedSyncPayloadV1,
} from "./sharedTypes";
import { DATASET_PARTS, projectDatasetPart } from "./datasets";
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
      const committedFull = reconcileSyncResult(mergedPart, live);
      expect(subsumes(mergedPart, projectDatasetPart(committedFull, part))).toBe(true);
    }
  });
});
