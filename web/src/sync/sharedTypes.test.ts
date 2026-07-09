import { describe, expect, it } from "vitest";
import type { WasmOptions } from "../App";
import type { PersistedSession } from "../sessionUtils";
import {
  buildSharedSyncPayload,
  extractSharedPayload,
  mergeSharedSyncPayloads,
  hasMeaningfulSharedData,
  isLocalProfile,
  shouldLoadRemoteBeforePublish,
} from "./sharedTypes";
import type { PortablePlannerOptions, SyncPayloadV1 } from "./types";
import { mergeSyncPayloads } from "./types";

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

const session = (): PersistedSession => ({
  plannerConfigured: true,
  plannerOptionsUpdatedAt: "2026-01-01T00:00:00.000Z",
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

describe("shared sync payload", () => {
  it("extracts shared payload without android preferences", () => {
    const full: SyncPayloadV1 = {
      schemaVersion: 1,
      exportedAt: "2026-01-01T00:00:00.000Z",
      planner: { value: options(30), updatedAt: "2026-01-01T00:00:00.000Z" },
      periodRecords: [],
      deletedPeriodStarts: {},
      calendarDayLogs: {},
      voluntaryAbstinenceDates: {},
      voluntaryAbstinenceUpdatedAt: {},
      deletedVoluntaryAbstinenceDates: {},
      ecJournal: { value: false, updatedAt: "2026-01-01T00:00:00.000Z" },
      androidPreferences: {
        value: {
          calendarLabelPeriod: "P",
          calendarLabelFertile: "F",
          calendarLabelActionU: "U",
          calendarLabelActionC: "C",
          calendarLabelActionA: "A",
          calendarLabelActionW: "W",
          reminderHour: 9,
          reminderMinute: 0,
        },
        updatedAt: "2026-01-01T00:00:00.000Z",
      },
    };
    expect("androidPreferences" in extractSharedPayload(full)).toBe(false);
  });

  it("builds payload from local app state", () => {
    const wasm = options(32) as WasmOptions;
    const payload = buildSharedSyncPayload(wasm, [], session());
    expect(payload.planner.value.ageYears).toBe(32);
    expect(payload.schemaVersion).toBe(1);
  });

  it("merges the same way as full sync payloads", () => {
    const wasmOld = options(30) as WasmOptions;
    const wasmNew = options(35) as WasmOptions;
    const old = buildSharedSyncPayload(wasmOld, [], session());
    const recent = buildSharedSyncPayload(wasmNew, [], {
      ...session(),
      plannerOptionsUpdatedAt: "2026-02-01T00:00:00.000Z",
    });
    const sharedMerged = mergeSharedSyncPayloads(old, recent);
    const fullMerged = mergeSyncPayloads(
      { ...old, exportedAt: old.exportedAt },
      { ...recent, exportedAt: recent.exportedAt },
    );
    expect(sharedMerged.planner.value.ageYears).toBe(fullMerged.planner.value.ageYears);
  });

  it("forces a newly joined writer to load before publishing local data", () => {
    expect(
      shouldLoadRemoteBeforePublish({
        datasetId: "primary",
        ownerEmail: "leslie@example.com",
        folderName: "EasyBC — leslie@example.com",
        role: "writer",
        trustedOwnerKeyId: "owner-key",
        needsInitialLoad: true,
      }),
    ).toBe(true);
  });

  it("keeps local-only profiles outside encrypted sync", () => {
    const profile = {
      datasetId: "profile",
      ownerEmail: "local-123",
      folderName: "",
      role: "owner" as const,
      trustedOwnerKeyId: "",
      syncMode: "local" as const,
    };
    expect(isLocalProfile(profile)).toBe(true);
    expect(shouldLoadRemoteBeforePublish(profile)).toBe(false);
  });

  it("detects local data that must be preserved before joining", () => {
    const payload = buildSharedSyncPayload(
      options(32) as WasmOptions,
      [{ start: "2026-06-01" }],
      { ...session(), plannerConfigured: false },
    );
    expect(hasMeaningfulSharedData(payload)).toBe(true);
  });
});
