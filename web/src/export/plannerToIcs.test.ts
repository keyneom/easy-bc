import { describe, expect, it } from "vitest";
import { buildPlannerIcs } from "./plannerToIcs";
import type { PlannerWallPlan } from "../tracker/plannerWallMeta";

describe("plannerToIcs", () => {
  it("includes VEVENT with SUMMARY and all-day dates", () => {
    const plan: PlannerWallPlan = {
      years: [
        {
          dayWeights: [
            {
              day: 1,
              recommendedAction: "C",
              rawRiskScore: 42,
              overrideCost: { condoms: 0, abstinenceDays: 0, note: "ok" },
            },
            {
              day: 2,
              recommendedAction: "U",
              rawRiskScore: 10,
              overrideCost: { condoms: 0, abstinenceDays: 0, note: "" },
            },
          ],
        },
      ],
    };
    const ics = buildPlannerIcs(plan, "2026-01-01", [2], 1);
    expect(ics).toContain("BEGIN:VCALENDAR");
    expect(ics).toContain("END:VCALENDAR");
    expect(ics).toContain("BEGIN:VEVENT");
    expect(ics).toContain("DTSTART;VALUE=DATE:20260101");
    expect(ics).toContain("DTEND;VALUE=DATE:20260102");
    expect(ics).toContain("SUMMARY:easy-bc: C · risk 42");
    expect(ics).toContain("SUMMARY:easy-bc: U · risk 10");
    expect(ics).toMatch(/UID:easy-bc-2026-01-01-r0-d1@local/);
  });
});
