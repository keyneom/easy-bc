/** Gregorian helpers for the period tracker (local timezone, noon anchor). */

export function toIsoDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

export function parseIsoLocal(iso: string): Date {
  return new Date(iso + "T12:00:00");
}

export function compareIso(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export function addDaysIso(iso: string, delta: number): string {
  const t = parseIsoLocal(iso).getTime() + delta * 86_400_000;
  return toIsoDate(new Date(t));
}

export function daysBetweenInclusive(startIso: string, endIso: string): number {
  const a = parseIsoLocal(startIso).getTime();
  const b = parseIsoLocal(endIso).getTime();
  return Math.floor((b - a) / 86_400_000) + 1;
}

export function daysFromTo(startIso: string, endIso: string): number {
  const a = parseIsoLocal(startIso).getTime();
  const b = parseIsoLocal(endIso).getTime();
  return Math.floor((b - a) / 86_400_000);
}

/** Inclusive date range as ISO strings. */
export function eachIsoInRange(startIso: string, endIso: string): string[] {
  if (compareIso(endIso, startIso) < 0) return [];
  const out: string[] = [];
  let cur = startIso;
  while (compareIso(cur, endIso) <= 0) {
    out.push(cur);
    cur = addDaysIso(cur, 1);
  }
  return out;
}

export function monthGrid(year: number, month0: number): {
  iso: string;
  day: number;
  inMonth: boolean;
}[] {
  const first = new Date(year, month0, 1);
  const startWeekday = first.getDay();
  const cells: { iso: string; day: number; inMonth: boolean }[] = [];
  const pad = (startWeekday + 6) % 7;
  let d = new Date(year, month0, 1 - pad);
  for (let i = 0; i < 42; i++) {
    const inMonth = d.getMonth() === month0;
    cells.push({ iso: toIsoDate(d), day: d.getDate(), inMonth });
    d = new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1);
  }
  return cells;
}

export function wallDateToPlannerSlice(
  anchorIso: string,
  cycleLengths: number[],
  horizonRowCount: number,
  wallIso: string,
): { row: number; day: number } | null {
  if (cycleLengths.length === 0 || horizonRowCount < 1) return null;
  const anchor = parseIsoLocal(anchorIso).getTime();
  const wall = parseIsoLocal(wallIso).getTime();
  const daysSince = Math.floor((wall - anchor) / 86_400_000);
  if (daysSince < 0) return null;
  let remaining = daysSince;
  for (let row = 0; row < horizonRowCount && row < cycleLengths.length; row++) {
    const len = cycleLengths[row];
    if (remaining < len) {
      return { row, day: remaining + 1 };
    }
    remaining -= len;
  }
  return null;
}

/** Every wall date mapped by the planner grid: cycle rows × lengths, from anchor day 0. */
export function eachPlannerWallSlot(
  anchorIso: string,
  cycleLengths: number[],
  horizonRowCount: number,
): { iso: string; row: number; day: number }[] {
  const out: { iso: string; row: number; day: number }[] = [];
  let dayOffset = 0;
  const rows = Math.min(horizonRowCount, cycleLengths.length);
  for (let row = 0; row < rows; row++) {
    const len = cycleLengths[row];
    for (let day = 1; day <= len; day++) {
      out.push({
        iso: addDaysIso(anchorIso, dayOffset + day - 1),
        row,
        day,
      });
    }
    dayOffset += len;
  }
  return out;
}
