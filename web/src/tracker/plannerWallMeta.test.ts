import { describe, expect, it } from "vitest";
import { resolvePlannerDayMeta, type PlannerWallPlan } from "./plannerWallMeta";

describe("plannerWallMeta", () => {
  it("resolvePlannerDayMeta returns action and risk for wall date", () => {
    const plan: PlannerWallPlan = {
      years: [
        {
          dayWeights: [
            {
              day: 1,
              recommendedAction: "C",
              rawRiskScore: 40,
              overrideCost: { condoms: 1, abstinenceDays: 0, note: "n" },
            },
          ],
        },
      ],
    };
    const m = resolvePlannerDayMeta(plan, [28], "2026-01-01", "2026-01-01");
    expect(m?.recommendedAction).toBe("C");
    expect(m?.rawRiskScore).toBe(40);
    expect(m?.row).toBe(0);
    expect(m?.dayInCycle).toBe(1);
  });

  it("resolvePlannerDayMeta defaults missing overrideCost", () => {
    const plan: PlannerWallPlan = {
      years: [{ dayWeights: [{ day: 1, recommendedAction: "U", rawRiskScore: 5 }] }],
    };
    const m = resolvePlannerDayMeta(plan, [1], "2026-02-01", "2026-02-01");
    expect(m?.overrideCost.note).toBe("");
  });
});
