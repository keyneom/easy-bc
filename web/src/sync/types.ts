import type { WasmOptions } from "../App";
import type { CalendarDayLog } from "../sessionUtils";
import { SYNC_EPOCH } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";

export type PortablePlannerOptions = Omit<WasmOptions, "calendarCycles">;

export type SyncPayloadV1 = {
  schemaVersion: 1;
  exportedAt: string;
  planner: {
    value: PortablePlannerOptions;
    updatedAt: string;
  };
  periodRecords: PeriodRecord[];
  deletedPeriodStarts: Record<string, string>;
  calendarDayLogs: Record<string, CalendarDayLog>;
  voluntaryAbstinenceDates: Record<string, true>;
  voluntaryAbstinenceUpdatedAt: Record<string, string>;
  deletedVoluntaryAbstinenceDates: Record<string, string>;
  ecJournal: {
    value: boolean;
    updatedAt: string;
  };
  /** Android-only device preferences. Web preserves these without applying them. */
  androidPreferences?: {
    value: AndroidPreferences;
    updatedAt: string;
  };
};

export type AndroidPreferences = {
  calendarLabelPeriod: string;
  calendarLabelFertile: string;
  calendarLabelActionU: string;
  calendarLabelActionC: string;
  calendarLabelActionA: string;
  calendarLabelActionW: string;
  reminderHour: number;
  reminderMinute: number;
};

export type SyncEnvelopeV1 = {
  schemaVersion: 1;
  algorithm: "AES-256-GCM+HKDF-SHA-256";
  credentialId: string;
  rpId: string;
  prfInput: string;
  kdfSalt: string;
  nonce: string;
  ciphertext: string;
  updatedAt: string;
};

export type LocalSyncState = {
  schemaVersion: 1;
  fileId: string;
  rpId: string;
  lastSyncedAt: string;
};

export function portablePlannerOptions(options: WasmOptions): PortablePlannerOptions {
  const { calendarCycles: _calendarCycles, ...portable } = options;
  return portable;
}

function timestamp(value?: string): string {
  return value && !Number.isNaN(Date.parse(value)) ? value : SYNC_EPOCH;
}

function newer<T>(a: T, aTime: string | undefined, b: T, bTime: string | undefined): T {
  return timestamp(aTime) >= timestamp(bTime) ? a : b;
}

function mergePeriods(
  a: SyncPayloadV1,
  b: SyncPayloadV1,
): { records: PeriodRecord[]; deleted: Record<string, string> } {
  const active = new Map<string, PeriodRecord>();
  for (const record of [...a.periodRecords, ...b.periodRecords]) {
    const current = active.get(record.start);
    if (!current) active.set(record.start, record);
    else {
      const selected = newer(current, current.updatedAt, record, record.updatedAt);
      if (timestamp(current.updatedAt) === timestamp(record.updatedAt)) {
        active.set(record.start, current.end ? current : record);
      } else active.set(record.start, selected);
    }
  }
  const deleted: Record<string, string> = { ...a.deletedPeriodStarts };
  for (const [start, deletedAt] of Object.entries(b.deletedPeriodStarts)) {
    if (timestamp(deletedAt) > timestamp(deleted[start])) deleted[start] = deletedAt;
  }
  for (const [start, record] of active) {
    if (deleted[start] && timestamp(deleted[start]) >= timestamp(record.updatedAt)) active.delete(start);
    else delete deleted[start];
  }
  return {
    records: [...active.values()].sort((left, right) => left.start.localeCompare(right.start)),
    deleted,
  };
}

function mergeAbstinence(
  a: SyncPayloadV1,
  b: SyncPayloadV1,
): Pick<SyncPayloadV1, "voluntaryAbstinenceDates" | "voluntaryAbstinenceUpdatedAt" | "deletedVoluntaryAbstinenceDates"> {
  const activeTimes = { ...a.voluntaryAbstinenceUpdatedAt };
  for (const date of Object.keys(a.voluntaryAbstinenceDates)) activeTimes[date] ??= SYNC_EPOCH;
  for (const date of Object.keys(b.voluntaryAbstinenceDates)) {
    const candidate = b.voluntaryAbstinenceUpdatedAt[date] ?? SYNC_EPOCH;
    if (timestamp(candidate) > timestamp(activeTimes[date])) activeTimes[date] = candidate;
  }
  const deleted = { ...a.deletedVoluntaryAbstinenceDates };
  for (const [date, deletedAt] of Object.entries(b.deletedVoluntaryAbstinenceDates)) {
    if (timestamp(deletedAt) > timestamp(deleted[date])) deleted[date] = deletedAt;
  }
  const dates: Record<string, true> = {};
  for (const [date, activeAt] of Object.entries(activeTimes)) {
    if (deleted[date] && timestamp(deleted[date]) >= timestamp(activeAt)) delete activeTimes[date];
    else {
      dates[date] = true;
      delete deleted[date];
    }
  }
  return {
    voluntaryAbstinenceDates: dates,
    voluntaryAbstinenceUpdatedAt: activeTimes,
    deletedVoluntaryAbstinenceDates: deleted,
  };
}

export function mergeSyncPayloads(a: SyncPayloadV1, b: SyncPayloadV1): SyncPayloadV1 {
  const period = mergePeriods(a, b);
  const dayLogs: Record<string, CalendarDayLog> = { ...a.calendarDayLogs };
  for (const [date, log] of Object.entries(b.calendarDayLogs)) {
    const current = dayLogs[date];
    if (!current || timestamp(log.updatedAt) > timestamp(current.updatedAt)) dayLogs[date] = log;
  }
  const abstinence = mergeAbstinence(a, b);
  const planner = newer(a.planner, a.planner.updatedAt, b.planner, b.planner.updatedAt);
  const ecJournal = newer(
    a.ecJournal,
    a.ecJournal.updatedAt,
    b.ecJournal,
    b.ecJournal.updatedAt,
  );
  const androidPreferences = a.androidPreferences && b.androidPreferences
    ? newer(a.androidPreferences, a.androidPreferences.updatedAt, b.androidPreferences, b.androidPreferences.updatedAt)
    : (a.androidPreferences ?? b.androidPreferences);
  return {
    schemaVersion: 1,
    exportedAt: new Date().toISOString(),
    planner,
    periodRecords: period.records,
    deletedPeriodStarts: period.deleted,
    calendarDayLogs: dayLogs,
    ...abstinence,
    ecJournal,
    ...(androidPreferences ? { androidPreferences } : {}),
  };
}

export function parseSyncPayload(value: string): SyncPayloadV1 {
  const parsed = JSON.parse(value) as Partial<SyncPayloadV1>;
  if (
    parsed.schemaVersion !== 1 ||
    !parsed.planner ||
    !Array.isArray(parsed.periodRecords) ||
    !parsed.calendarDayLogs ||
    !parsed.voluntaryAbstinenceDates ||
    !parsed.ecJournal
  ) {
    throw new Error("The Drive snapshot is not a supported EasyBC sync file.");
  }
  return {
    ...parsed,
    deletedPeriodStarts: parsed.deletedPeriodStarts ?? {},
    voluntaryAbstinenceUpdatedAt: parsed.voluntaryAbstinenceUpdatedAt ?? {},
    deletedVoluntaryAbstinenceDates: parsed.deletedVoluntaryAbstinenceDates ?? {},
  } as SyncPayloadV1;
}
