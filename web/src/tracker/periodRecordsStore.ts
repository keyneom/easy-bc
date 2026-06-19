import { idbGet, idbSet, KV_PERIOD_RECORDS, KV_PERIOD_STARTS } from "../idbStore";
import { compareIso } from "./calendarMath";
import type { PeriodRecord } from "./types";

export { type PeriodRecord } from "./types";

/** Keep legacy `periodStarts` in sync for older code paths. */
async function syncLegacyStarts(records: PeriodRecord[]): Promise<void> {
  const starts = [...new Set(records.map((r) => r.start))].sort(compareIso);
  await idbSet(KV_PERIOD_STARTS, starts);
}

export async function loadPeriodRecords(): Promise<PeriodRecord[]> {
  const raw = await idbGet<PeriodRecord[]>(KV_PERIOD_RECORDS);
  if (Array.isArray(raw) && raw.length > 0) {
    return raw
      .filter((r) => r && typeof r.start === "string")
      .sort((a, b) => compareIso(a.start, b.start));
  }
  const starts = await idbGet<string[]>(KV_PERIOD_STARTS);
  if (Array.isArray(starts) && starts.length > 0) {
    const migrated = starts
      .filter((x): x is string => typeof x === "string")
      .sort(compareIso)
      .map((start) => ({ start }));
    await idbSet(KV_PERIOD_RECORDS, migrated);
    return migrated;
  }
  return [];
}

export async function savePeriodRecords(records: PeriodRecord[]): Promise<void> {
  const sorted = [...records].sort((a, b) => compareIso(a.start, b.start));
  await idbSet(KV_PERIOD_RECORDS, sorted);
  await syncLegacyStarts(sorted);
}

export async function addPeriodStartDate(start: string): Promise<PeriodRecord[]> {
  const cur = await loadPeriodRecords();
  if (cur.some((r) => r.start === start)) return cur;
  const next = [...cur, { start }].sort((a, b) => compareIso(a.start, b.start));
  await savePeriodRecords(next);
  return next;
}

/** Set bleeding end on the most recent record that has no end, or on the record matching `start`. */
export async function setPeriodEnd(endIso: string, startKey?: string): Promise<PeriodRecord[]> {
  const cur = await loadPeriodRecords();
  const sorted = [...cur].sort((a, b) => compareIso(a.start, b.start));
  let idx = -1;
  if (startKey) {
    idx = sorted.findIndex((r) => r.start === startKey);
  } else {
    for (let i = sorted.length - 1; i >= 0; i--) {
      if (!sorted[i].end) {
        idx = i;
        break;
      }
    }
  }
  if (idx < 0) return cur;
  if (compareIso(endIso, sorted[idx].start) < 0) return cur;
  sorted[idx] = { ...sorted[idx], end: endIso };
  await savePeriodRecords(sorted);
  return sorted;
}

export async function removePeriodRecord(start: string): Promise<PeriodRecord[]> {
  const cur = await loadPeriodRecords();
  const next = cur.filter((r) => r.start !== start);
  await savePeriodRecords(next);
  return next;
}

export function periodStartsFromRecords(records: PeriodRecord[]): string[] {
  return [...new Set(records.map((r) => r.start))].sort(compareIso);
}
