import { describe, expect, it } from "vitest";
import {
  addDaysIso,
  compareIso,
  daysBetweenInclusive,
  eachPlannerWallSlot,
  monthGrid,
  wallDateToPlannerSlice,
} from "./calendarMath";

describe("calendarMath", () => {
  it("monthGrid is Monday-first with 42 cells", () => {
    const g = monthGrid(2026, 0);
    expect(g.length).toBe(42);
    const inMonth = g.filter((c) => c.inMonth);
    expect(inMonth.length).toBe(31);
    expect(inMonth[0].iso).toBe("2026-01-01");
  });

  it("wallDateToPlannerSlice maps anchor + lengths", () => {
    const lengths = [28, 28];
    expect(wallDateToPlannerSlice("2026-01-01", lengths, 2, "2026-01-01")).toEqual({
      row: 0,
      day: 1,
    });
    expect(wallDateToPlannerSlice("2026-01-01", lengths, 2, "2026-01-28")).toEqual({
      row: 0,
      day: 28,
    });
    expect(wallDateToPlannerSlice("2026-01-01", lengths, 2, "2026-01-29")).toEqual({
      row: 1,
      day: 1,
    });
    expect(wallDateToPlannerSlice("2026-01-01", lengths, 2, "2025-12-31")).toBeNull();
  });

  it("eachPlannerWallSlot lists wall dates in row/day order", () => {
    const slots = eachPlannerWallSlot("2026-01-01", [2, 2], 2);
    expect(slots).toEqual([
      { iso: "2026-01-01", row: 0, day: 1 },
      { iso: "2026-01-02", row: 0, day: 2 },
      { iso: "2026-01-03", row: 1, day: 1 },
      { iso: "2026-01-04", row: 1, day: 2 },
    ]);
  });

  it("addDaysIso and compareIso", () => {
    expect(compareIso("2026-01-01", "2026-01-02")).toBe(-1);
    expect(daysBetweenInclusive("2026-01-01", "2026-01-03")).toBe(3);
    expect(addDaysIso("2026-01-01", 1)).toBe("2026-01-02");
  });
});
