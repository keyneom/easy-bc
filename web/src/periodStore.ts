import { idbGet, idbSet, KV_PERIOD_STARTS } from "./idbStore";

export async function readPeriodStarts(): Promise<string[]> {
  const v = await idbGet<string[]>(KV_PERIOD_STARTS);
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
}

export async function writePeriodStarts(dates: string[]): Promise<void> {
  await idbSet(KV_PERIOD_STARTS, [...dates].sort());
}

export async function addPeriodStart(isoDate: string): Promise<string[]> {
  const cur = await readPeriodStarts();
  if (!cur.includes(isoDate)) cur.push(isoDate);
  await writePeriodStarts(cur);
  return cur;
}

export async function removePeriodStart(isoDate: string): Promise<string[]> {
  const cur = (await readPeriodStarts()).filter((d) => d !== isoDate);
  await writePeriodStarts(cur);
  return cur;
}
