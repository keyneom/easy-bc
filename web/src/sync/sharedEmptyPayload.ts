import { SYNC_EPOCH } from "../sessionUtils";
import type { PortablePlannerOptions } from "./types";
import type { SharedSyncPayloadV1 } from "./sharedTypes";

export const DEFAULT_PORTABLE_PLANNER_OPTIONS: PortablePlannerOptions = {
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
  ovulationSdDays: 3.0,
};

export function createEmptySharedSyncPayload(
  defaultOptions: PortablePlannerOptions = DEFAULT_PORTABLE_PLANNER_OPTIONS,
): SharedSyncPayloadV1 {
  return {
    schemaVersion: 1,
    exportedAt: SYNC_EPOCH,
    planner: {
      value: defaultOptions,
      updatedAt: SYNC_EPOCH,
      configured: false,
    },
    periodRecords: [],
    deletedPeriodStarts: {},
    calendarDayLogs: {},
    voluntaryAbstinenceDates: {},
    voluntaryAbstinenceUpdatedAt: {},
    deletedVoluntaryAbstinenceDates: {},
    ecJournal: { value: false, updatedAt: SYNC_EPOCH },
  };
}
