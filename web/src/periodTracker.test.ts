import { describe, expect, it } from "vitest";
import {
  blendedCycleLength,
  buildCalendarCycles,
  predictCycleLengthsV15,
  sampleStdDev,
  sdWidenFromVariance,
  trimmedMeanLength,
} from "./periodTracker";
import {
  daysSinceFirstCycleStart,
  initialLocksForPastDays,
  resolveHorizonRowAndDay,
} from "./sessionUtils";

describe("periodTracker v1.5", () => {
  it("trimmed mean drops extreme lengths when enough samples", () => {
    const hist = [21, 28, 28, 28, 29, 60];
    // `slice(1, 5)` drops one low and one high → [28,28,28,29]
    expect(trimmedMeanLength(hist, 1)).toBeCloseTo((28 + 28 + 28 + 29) / 4, 5);
  });

  it("sample std dev and SD widen respond to noisy history", () => {
    expect(sampleStdDev([28, 28, 28])).toBe(0);
    expect(sdWidenFromVariance([28, 28, 28, 40])).toBeGreaterThan(0);
  });

  it("blended length moves toward personal mean with more observed cycles", () => {
    const a = blendedCycleLength(26, 34, 2);
    const b = blendedCycleLength(26, 34, 8);
    expect(Math.abs(b - 26)).toBeLessThan(Math.abs(a - 26));
  });

  it("buildCalendarCycles applies v15 lengths and widened SD", () => {
    const rows = buildCalendarCycles(3, 32, 3.5, 3.0, [27, 28, 29]);
    expect(rows.length).toBe(3);
    for (const r of rows) {
      expect(r.cycleLengthDays).toBeGreaterThanOrEqual(21);
      expect(r.cycleLengthDays).toBeLessThanOrEqual(60);
      expect(r.cycleSdDays).toBeGreaterThanOrEqual(3);
    }
  });

  it("predictCycleLengthsV15 is non-empty for positive count", () => {
    const lens = predictCycleLengthsV15(2, [28, 29], 34);
    expect(lens).toEqual([expect.any(Number), expect.any(Number)]);
  });
});

describe("sessionUtils horizon grid", () => {
  it("resolveHorizonRowAndDay walks cycle lengths", () => {
    expect(resolveHorizonRowAndDay([28, 30], 0)).toEqual({ row: 0, dayInCycle: 1 });
    expect(resolveHorizonRowAndDay([28, 30], 27)).toEqual({ row: 0, dayInCycle: 28 });
    expect(resolveHorizonRowAndDay([28, 30], 28)).toEqual({ row: 1, dayInCycle: 1 });
  });

  it("initialLocksForPastDays fills prior rows and partial current row", () => {
    const locks = initialLocksForPastDays([3, 3], { row: 1, dayInCycle: 2 }, {}, "U");
    expect(locks).toEqual([
      { yearIndex: 0, day: 1, action: "U" },
      { yearIndex: 0, day: 2, action: "U" },
      { yearIndex: 0, day: 3, action: "U" },
      { yearIndex: 1, day: 1, action: "U" },
    ]);
  });

  it("daysSinceFirstCycleStart is whole days from anchor", () => {
    const d = daysSinceFirstCycleStart("2026-04-01", new Date("2026-04-05T12:00:00"));
    expect(d).toBe(4);
  });
});
