import { describe, expect, it } from "vitest";
import type { PortablePlannerOptions, SyncPayloadV1 } from "./types";
import {
  mergeSyncPayloads,
  parseSyncPayload,
  plannerConfiguredFromPayload,
  SyncPayloadParseError,
} from "./types";

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

const payload = (ageYears: number, updatedAt: string): SyncPayloadV1 => ({
  schemaVersion: 1,
  exportedAt: updatedAt,
  planner: { value: options(ageYears), updatedAt },
  periodRecords: [],
  deletedPeriodStarts: {},
  calendarDayLogs: {},
  voluntaryAbstinenceDates: {},
  voluntaryAbstinenceUpdatedAt: {},
  deletedVoluntaryAbstinenceDates: {},
  ecJournal: { value: false, updatedAt },
});

describe("mergeSyncPayloads", () => {
  it("keeps the newest planner settings and day log", () => {
    const old = payload(30, "2026-01-01T00:00:00.000Z");
    old.calendarDayLogs["2026-01-02"] = { notes: "old", updatedAt: "2026-01-02T00:00:00.000Z" };
    const recent = payload(35, "2026-02-01T00:00:00.000Z");
    recent.calendarDayLogs["2026-01-02"] = { notes: "new", updatedAt: "2026-01-03T00:00:00.000Z" };

    const merged = mergeSyncPayloads(old, recent);

    expect(merged.planner.value.ageYears).toBe(35);
    expect(merged.calendarDayLogs["2026-01-02"]?.notes).toBe("new");
  });

  it("lets a timestamp-only day tombstone replace older data", () => {
    const active = payload(30, "2026-01-01T00:00:00.000Z");
    active.calendarDayLogs["2026-08-12"] = {
      actualAction: "C",
      updatedAt: "2026-07-12T00:00:00.000Z",
    };
    const deleted = payload(30, "2026-01-01T00:00:00.000Z");
    deleted.calendarDayLogs["2026-08-12"] = {
      updatedAt: "2026-07-13T00:00:00.000Z",
    };

    expect(mergeSyncPayloads(active, deleted).calendarDayLogs["2026-08-12"]).toEqual({
      updatedAt: "2026-07-13T00:00:00.000Z",
    });
  });

  it("preserves an explicit configured marker when upgrading an equal-time legacy planner", () => {
    const legacy = payload(35, "2026-02-01T00:00:00.000Z");
    const current = payload(35, "2026-02-01T00:00:00.000Z");
    current.planner.configured = true;

    const merged = mergeSyncPayloads(legacy, current);

    expect(merged.planner.configured).toBe(true);
  });

  it("recognizes and upgrades a used legacy snapshot", () => {
    const legacy = payload(35, "2026-02-01T00:00:00.000Z");
    legacy.periodRecords = [{ start: "2026-01-01" }];

    expect(plannerConfiguredFromPayload(legacy)).toBe(true);
    expect(mergeSyncPayloads(legacy, payload(34, "1970-01-01T00:00:00.000Z")).planner.configured)
      .toBe(true);
  });

  it("propagates period and abstinence deletions", () => {
    const active = payload(30, "2026-01-01T00:00:00.000Z");
    active.periodRecords = [{ start: "2026-01-10", updatedAt: "2026-01-10T00:00:00.000Z" }];
    active.voluntaryAbstinenceDates["2026-01-12"] = true;
    active.voluntaryAbstinenceUpdatedAt["2026-01-12"] = "2026-01-12T00:00:00.000Z";
    const deleted = payload(30, "2026-01-20T00:00:00.000Z");
    deleted.deletedPeriodStarts["2026-01-10"] = "2026-01-20T00:00:00.000Z";
    deleted.deletedVoluntaryAbstinenceDates["2026-01-12"] = "2026-01-20T00:00:00.000Z";

    const merged = mergeSyncPayloads(active, deleted);

    expect(merged.periodRecords).toEqual([]);
    expect(merged.voluntaryAbstinenceDates).toEqual({});
  });

  it("keeps legacy active values when no tombstone exists", () => {
    const legacy = payload(30, "2026-01-01T00:00:00.000Z");
    legacy.periodRecords = [{ start: "2025-12-01" }];
    legacy.voluntaryAbstinenceDates["2025-12-02"] = true;

    const merged = mergeSyncPayloads(legacy, payload(30, "2026-01-01T00:00:00.000Z"));

    expect(merged.periodRecords).toHaveLength(1);
    expect(merged.voluntaryAbstinenceDates["2025-12-02"]).toBe(true);
  });

  it("preserves Android-only preferences through a web merge", () => {
    const android = payload(30, "2026-01-01T00:00:00.000Z");
    android.androidPreferences = {
      value: {
        calendarLabelPeriod: "cycle",
        calendarLabelFertile: "window",
        calendarLabelActionU: "U",
        calendarLabelActionC: "C",
        calendarLabelActionA: "A",
        calendarLabelActionW: "W",
        reminderHour: 8,
        reminderMinute: 30,
      },
      updatedAt: "2026-01-15T00:00:00.000Z",
    };

    const merged = mergeSyncPayloads(android, payload(31, "2026-02-01T00:00:00.000Z"));

    expect(merged.androidPreferences).toEqual(android.androidPreferences);
  });

  it("keeps Android reconciliation outcomes and extended observations", () => {
    const android = payload(30, "2026-01-01T00:00:00.000Z");
    android.calendarDayLogs["2026-01-12"] = {
      actualAction: "NONE",
      mucus: "spotting",
      opk: "peak",
      reconciled: true,
      updatedAt: "2026-01-12T12:00:00.000Z",
    };

    const merged = mergeSyncPayloads(payload(30, "2026-01-01T00:00:00.000Z"), android);

    expect(merged.calendarDayLogs["2026-01-12"]).toEqual(android.calendarDayLogs["2026-01-12"]);
  });

  it("merges profile name and avatar on independent timestamps", () => {
    const avatar = payload(30, "2026-01-01T00:00:00.000Z");
    avatar.profileMeta = {
      avatarWebp: "new-photo",
      updatedAt: "2026-04-01T00:00:00.000Z",
      displayName: "Old name",
      displayNameUpdatedAt: "2026-01-01T00:00:00.000Z",
    };
    const renamed = payload(30, "2026-01-01T00:00:00.000Z");
    renamed.profileMeta = {
      avatarWebp: "old-photo",
      updatedAt: "2026-02-01T00:00:00.000Z",
      displayName: "Current name",
      displayNameUpdatedAt: "2026-05-01T00:00:00.000Z",
    };

    expect(mergeSyncPayloads(avatar, renamed).profileMeta).toEqual({
      avatarWebp: "new-photo",
      updatedAt: "2026-04-01T00:00:00.000Z",
      displayName: "Current name",
      displayNameUpdatedAt: "2026-05-01T00:00:00.000Z",
    });
  });
});

describe("parseSyncPayload", () => {
  it("rejects oversized synced avatars before caching or decoding them", () => {
    const invalid = payload(30, "2026-01-01T00:00:00.000Z");
    invalid.profileMeta = {
      avatarWebp: "a".repeat(16_385),
      updatedAt: "2026-01-01T00:00:00.000Z",
    };
    expect(() => parseSyncPayload(invalid)).toThrow("invalid profile display metadata");
  });

  it("reports a redacted path and type for an incompatible payload", () => {
    try {
      parseSyncPayload({ kind: "sync-kit-sharing-control" });
      throw new Error("Expected parsing to fail");
    } catch (error) {
      expect(error).toBeInstanceOf(SyncPayloadParseError);
      expect(error).toMatchObject({
        reasonCode: "missing-field",
        path: "$.schemaVersion",
        observedType: "missing",
      });
      expect((error as Error).message).not.toContain("sync-kit-sharing-control");
    }
  });

  it("normalizes a legacy null profile metadata field to absence", () => {
    const legacy = { ...payload(30, "2026-01-01T00:00:00.000Z"), profileMeta: null };
    expect(parseSyncPayload(legacy).profileMeta).toBeUndefined();
  });
});
