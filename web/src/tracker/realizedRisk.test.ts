import { describe, expect, it } from "vitest";
import {
  computeInFlightRealizedRisk,
  type EcEffectFn,
  type InFlightDay,
  type MethodResiduals,
  type RealizedDayWeight,
} from "./realizedRisk";

const NO_METHOD: MethodResiduals = {
  persistentMethodResidual: 1,
};

// Peak fertile day: per-act conception 0.23, per-day-pattern raw 0.1 (3 acts/wk).
function peakDay(overrides: Partial<RealizedDayWeight> = {}): RealizedDayWeight {
  return {
    recommendedAction: "A",
    rawRiskProbability: 0.1,
    protectedRiskProbability: 0.008,
    withdrawalRiskProbability: 0.035,
    recommendedRiskProbability: 0, // planner recommended abstinence
    perActConceptionProbability: 0.23,
    ...overrides,
  };
}

describe("computeInFlightRealizedRisk", () => {
  it("is zero with no logs (released / fresh cycle)", () => {
    const days: InFlightDay[] = [{ cycleDay: 12, dayWeight: peakDay() }];
    expect(computeInFlightRealizedRisk(days, NO_METHOD).realized).toBe(0);
  });

  it("prices an unplanned-unprotected event per-act on a planned-abstain day", () => {
    const days: InFlightDay[] = [
      {
        cycleDay: 12,
        dayWeight: peakDay(),
        log: {
          events: [
            { id: "e1", kind: "unplanned_unprotected", occurredAt: "2026-06-12T10:00:00Z" },
          ],
        },
      },
    ];
    const { realized } = computeInFlightRealizedRisk(days, NO_METHOD);
    // The planned day is abstinence (embedded risk 0), so the full per-act risk is additional.
    expect(realized).toBeCloseTo(0.23, 6);
  });

  it("prices a broken condom as one failed protected act", () => {
    const days: InFlightDay[] = [
      {
        cycleDay: 12,
        dayWeight: peakDay({ recommendedAction: "C", recommendedRiskProbability: 0.008 }),
        log: {
          actualAction: "C",
          events: [{ id: "e1", kind: "condom_broke", occurredAt: "2026-06-12T10:00:00Z" }],
        },
      },
    ];
    const { realized } = computeInFlightRealizedRisk(days, NO_METHOD);
    // Replace the protected per-day contribution (0.008) with one full failed act (0.23).
    expect(realized).toBeCloseTo((0.23 - 0.008) / (1 - 0.008), 6);
  });

  it("does not apply an EC credit when no EC estimator is supplied", () => {
    const days: InFlightDay[] = [
      {
        cycleDay: 12,
        dayWeight: peakDay(),
        log: {
          events: [
            { id: "e1", kind: "unplanned_unprotected", occurredAt: "2026-06-12T10:00:00Z" },
            {
              id: "e2",
              kind: "plan_b_taken",
              ecType: "levonorgestrel",
              hoursFromAct: 12,
              occurredAt: "2026-06-12T22:00:00Z",
            },
          ],
        },
      },
    ];
    const { realized, hadEc } = computeInFlightRealizedRisk(days, NO_METHOD);
    expect(hadEc).toBe(true);
    expect(realized).toBeCloseTo(0.23, 6); // journaled but no numeric credit without the estimator
  });

  it("reduces a covered incident by the EC conception multiplier", () => {
    // Stub estimator: 70% efficacy (multiplier 0.3), 2-day ovulation delay.
    const ecEffect: EcEffectFn = () => ({ conceptionMultiplier: 0.3, ovulationDelayDays: 2 });
    const days: InFlightDay[] = [
      {
        cycleDay: 12,
        dayWeight: peakDay(),
        log: {
          events: [
            { id: "e1", kind: "unplanned_unprotected", occurredAt: "2026-06-12T10:00:00Z" },
            {
              id: "e2",
              kind: "plan_b_taken",
              ecType: "levonorgestrel",
              hoursFromAct: 12,
              occurredAt: "2026-06-12T22:00:00Z",
            },
          ],
        },
      },
    ];
    const { realized, ovulationDelayDays } = computeInFlightRealizedRisk(days, NO_METHOD, ecEffect);
    expect(realized).toBeCloseTo(0.23 * 0.3, 6);
    expect(ovulationDelayDays).toBe(2);
  });

  it("does not credit EC to an act that happened AFTER the dose", () => {
    const ecEffect: EcEffectFn = () => ({ conceptionMultiplier: 0.1, ovulationDelayDays: 2 });
    const days: InFlightDay[] = [
      {
        cycleDay: 10,
        dayWeight: peakDay(),
        log: {
          events: [
            {
              id: "e1",
              kind: "plan_b_taken",
              ecType: "levonorgestrel",
              hoursFromAct: 12,
              occurredAt: "2026-06-10T22:00:00Z",
            },
          ],
        },
      },
      {
        cycleDay: 14, // act AFTER the dose — not covered
        dayWeight: peakDay(),
        log: {
          events: [
            { id: "e2", kind: "unplanned_unprotected", occurredAt: "2026-06-14T10:00:00Z" },
          ],
        },
      },
    ];
    const { realized } = computeInFlightRealizedRisk(days, NO_METHOD, ecEffect);
    expect(realized).toBeCloseTo(0.23, 6); // day-14 act uncovered → full per-act
  });

  it("does not invent EC timing when hours-from-act is missing", () => {
    const ecEffect: EcEffectFn = () => ({ conceptionMultiplier: 0.1, ovulationDelayDays: 2 });
    const days: InFlightDay[] = [
      {
        cycleDay: 12,
        dayWeight: peakDay(),
        log: {
          events: [
            { id: "act", kind: "unplanned_unprotected", occurredAt: "2026-06-12T10:00:00Z" },
            {
              id: "dose",
              kind: "plan_b_taken",
              ecType: "levonorgestrel",
              occurredAt: "2026-06-12T22:00:00Z",
            },
          ],
        },
      },
    ];

    expect(computeInFlightRealizedRisk(days, NO_METHOD, ecEffect).realized).toBeCloseTo(0.23, 6);
  });

  it("does not reuse one dose's delay for an incompatible earlier act", () => {
    const ecEffect: EcEffectFn = () => ({ conceptionMultiplier: 0.1, ovulationDelayDays: 2 });
    const days: InFlightDay[] = [
      {
        cycleDay: 10,
        dayWeight: peakDay(),
        log: {
          events: [
            { id: "old-act", kind: "unplanned_unprotected", occurredAt: "2026-06-10T10:00:00Z" },
          ],
        },
      },
      {
        cycleDay: 14,
        dayWeight: peakDay(),
        log: {
          events: [
            { id: "recent-act", kind: "unplanned_unprotected", occurredAt: "2026-06-14T09:00:00Z" },
            {
              id: "dose",
              kind: "plan_b_taken",
              ecType: "levonorgestrel",
              hoursFromAct: 12,
              occurredAt: "2026-06-14T21:00:00Z",
            },
          ],
        },
      },
    ];

    const { realized } = computeInFlightRealizedRisk(days, NO_METHOD, ecEffect);
    expect(realized).toBeCloseTo(0.23 + 0.23 * 0.1, 6);
  });

  it("sums multiple incidents conservatively instead of assuming independence", () => {
    const days: InFlightDay[] = [
      {
        cycleDay: 10,
        dayWeight: peakDay(),
        log: {
          events: [
            { id: "e1", kind: "unplanned_unprotected", occurredAt: "2026-06-10T10:00:00Z" },
          ],
        },
      },
      {
        cycleDay: 14,
        dayWeight: peakDay(),
        log: {
          events: [
            { id: "e2", kind: "unplanned_unprotected", occurredAt: "2026-06-14T10:00:00Z" },
          ],
        },
      },
    ];
    const { realized } = computeInFlightRealizedRisk(days, NO_METHOD);
    expect(realized).toBeCloseTo(0.46, 6);
  });
});
