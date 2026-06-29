import { describe, expect, it } from "vitest";
import type { WasmOptions } from "../App";
import { defaultPersistedSession, SYNC_EPOCH, type PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import { buildLocalSyncPayload, syncPayloadFingerprint } from "./sessionSync";

const baseOptions = (): WasmOptions => ({
  ageYears: 34,
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

describe("session sync helpers", () => {
  it("builds a portable payload with planner configured state", () => {
    const session: PersistedSession = {
      ...defaultPersistedSession(),
      plannerConfigured: true,
      plannerOptionsUpdatedAt: "2026-06-01T00:00:00.000Z",
      calendarDayLogs: {
        "2026-06-03": { actualAction: "C", updatedAt: "2026-06-03T12:00:00.000Z" },
      },
      androidPreferences: {
        value: {
          calendarLabelPeriod: "P",
          calendarLabelFertile: "F",
          calendarLabelActionU: "U",
          calendarLabelActionC: "C",
          calendarLabelActionA: "A",
          calendarLabelActionW: "W",
          reminderHour: 9,
          reminderMinute: 30,
        },
        updatedAt: "2026-06-02T00:00:00.000Z",
      },
    };
    const records: PeriodRecord[] = [
      { start: "2026-05-01", end: "2026-05-05", updatedAt: "2026-05-05T00:00:00.000Z" },
    ];

    const payload = buildLocalSyncPayload(baseOptions(), records, session);

    expect(payload.planner.configured).toBe(true);
    expect(payload.planner.updatedAt).toBe("2026-06-01T00:00:00.000Z");
    expect(payload.periodRecords).toEqual(records);
    expect(payload.calendarDayLogs["2026-06-03"].actualAction).toBe("C");
    expect(payload.androidPreferences?.value.reminderMinute).toBe(30);
  });

  it("ignores export time but changes when synced data changes", () => {
    const session = defaultPersistedSession();
    const payload = buildLocalSyncPayload(baseOptions(), [], session);
    const sameDataLater = {
      ...payload,
      exportedAt: "2026-06-23T00:00:00.000Z",
    };
    const changedData = {
      ...payload,
      planner: {
        ...payload.planner,
        updatedAt: "2026-06-24T00:00:00.000Z",
      },
    };

    expect(payload.planner.updatedAt).toBe(SYNC_EPOCH);
    expect(syncPayloadFingerprint(payload)).toBe(syncPayloadFingerprint(sameDataLater));
    expect(syncPayloadFingerprint(payload)).not.toBe(syncPayloadFingerprint(changedData));
  });
});
