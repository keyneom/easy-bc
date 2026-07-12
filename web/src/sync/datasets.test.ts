import { describe, expect, it } from "vitest";
import type { DayEvent } from "../sessionUtils";
import {
  DATASET_PARTS,
  baseDatasetIdOf,
  combineDatasetParts,
  datasetIdForPart,
  grantsFromRequestedGrants,
  highestGrantedRole,
  newerSplitBaseId,
  nextSplitBaseId,
  partForDatasetId,
  projectDatasetPart,
  requestedGrantsFromDatasetGrants,
  requestedGrantsWithControl,
  sameSplitFamily,
  SHARING_PRESETS,
  splitBaseRoot,
} from "./datasets";
import { createEmptySharedSyncPayload } from "./sharedEmptyPayload";
import { sharedPayloadFingerprint, type SharedSyncPayloadV1 } from "./sharedTypes";

function samplePayload(): SharedSyncPayloadV1 {
  const empty = createEmptySharedSyncPayload();
  const ecEvent: DayEvent = {
    id: "ev-ec",
    kind: "plan_b_taken",
    ecType: "levonorgestrel",
    hoursFromAct: 12,
    occurredAt: "2026-07-02T10:00:00.000Z",
  };
  const incident: DayEvent = {
    id: "ev-cb",
    kind: "condom_broke",
    occurredAt: "2026-07-02T09:00:00.000Z",
  };
  return {
    ...empty,
    exportedAt: "2026-07-09T00:00:00.000Z",
    planner: {
      value: { ...empty.planner.value, ageYears: 31 },
      updatedAt: "2026-07-01T00:00:00.000Z",
      configured: true,
    },
    periodRecords: [{ start: "2026-07-03" } as SharedSyncPayloadV1["periodRecords"][number]],
    deletedPeriodStarts: { "2026-06-01": "2026-06-02T00:00:00.000Z" },
    calendarDayLogs: {
      // Mixed day: cycle signals + intimacy action + EC event.
      "2026-07-02": {
        actualAction: "U",
        notes: "note",
        mucus: "egg-white",
        opk: "positive",
        reconciled: true,
        events: [incident, ecEvent],
        updatedAt: "2026-07-02T12:00:00.000Z",
      },
      // Cycle-only day.
      "2026-07-04": { bbtCelsius: 36.6, updatedAt: "2026-07-04T08:00:00.000Z" },
      // Intimacy-only day.
      "2026-07-05": { actualAction: "C", updatedAt: "2026-07-05T08:00:00.000Z" },
    },
    voluntaryAbstinenceDates: { "2026-07-06": true },
    voluntaryAbstinenceUpdatedAt: { "2026-07-06": "2026-07-06T00:00:00.000Z" },
    deletedVoluntaryAbstinenceDates: {},
    ecJournal: { value: true, updatedAt: "2026-07-02T10:00:00.000Z" },
    profileMeta: {
      avatarWebp: "dGVzdA==",
      updatedAt: "2026-07-01T12:00:00.000Z",
    },
  };
}

describe("dataset ids", () => {
  it("maps parts to companion ids and back", () => {
    expect(datasetIdForPart("primary", "plan")).toBe("primary");
    expect(datasetIdForPart("primary", "cycle")).toBe("primary.cycle");
    expect(partForDatasetId("primary", "primary")).toBe("plan");
    expect(partForDatasetId("primary", "primary.sensitive")).toBe("sensitive");
    expect(partForDatasetId("primary", "other.cycle")).toBeNull();
    expect(baseDatasetIdOf("daughter.intimacy")).toBe("daughter");
    expect(baseDatasetIdOf("daughter")).toBe("daughter");
  });
});

describe("grants mapping", () => {
  it("round-trips dataset grants through requestedGrants", () => {
    const grants = { cycle: "writer", plan: "viewer" } as const;
    const requested = requestedGrantsFromDatasetGrants("primary", grants);
    expect(requested).toEqual([
      { datasetId: "primary", role: "viewer" },
      { datasetId: "primary.cycle", role: "writer" },
    ]);
    const parsed = grantsFromRequestedGrants(requested);
    expect(parsed.baseDatasetId).toBe("primary");
    expect(parsed.split).toBe(true);
    expect(parsed.grants).toEqual(grants);
  });

  it("treats a single bare-base grant as a legacy share", () => {
    const parsed = grantsFromRequestedGrants([{ datasetId: "primary", role: "writer" }]);
    expect(parsed.split).toBe(false);
    expect(parsed.baseDatasetId).toBe("primary");
  });

  it("detects a split share from a companion-only grant", () => {
    const parsed = grantsFromRequestedGrants([{ datasetId: "primary.cycle", role: "viewer" }]);
    expect(parsed.split).toBe(true);
    expect(parsed.baseDatasetId).toBe("primary");
    expect(parsed.grants).toEqual({ cycle: "viewer" });
  });

  it("computes the highest granted role", () => {
    expect(highestGrantedRole({ cycle: "viewer" })).toBe("viewer");
    expect(highestGrantedRole({ cycle: "viewer", plan: "writer" })).toBe("writer");
  });

  it("presets only ever grant listed parts", () => {
    const cycleOnly = SHARING_PRESETS.find((preset) => preset.id === "cycle-only")!;
    expect(Object.keys(cycleOnly.grants)).toEqual(["cycle"]);
    const fullPartner = SHARING_PRESETS.find((preset) => preset.id === "full-partner")!;
    expect(fullPartner.grants.sensitive).toBeUndefined();
  });

  it("adds a ready control file as a writer grant", () => {
    expect(
      requestedGrantsWithControl(
        [{ datasetId: "primary.cycle", role: "viewer" }],
        "primary.control",
        "control-file",
      ),
    ).toEqual([
      { datasetId: "primary.cycle", role: "viewer" },
      { datasetId: "primary.control", role: "writer" },
    ]);
    expect(
      requestedGrantsWithControl(
        [{ datasetId: "primary.cycle", role: "viewer" }],
        "primary.control",
      ),
    ).toHaveLength(1);
  });
});

describe("projection & combination", () => {
  it("round-trips a full payload through all four parts", () => {
    const payload = samplePayload();
    const parts = Object.fromEntries(
      DATASET_PARTS.map((part) => [part, projectDatasetPart(payload, part)]),
    );
    const combined = combineDatasetParts(parts);
    // Deep equality, not fingerprint: JSON key order inside merged day logs is
    // not significant, and per-file fingerprints are computed on projections
    // (stable order), never on the combined payload.
    expect(combined).toEqual(payload);
  });

  it("projection is fingerprint-stable for unchanged data", () => {
    const payload = samplePayload();
    const first = sharedPayloadFingerprint(projectDatasetPart(payload, "cycle"));
    const second = sharedPayloadFingerprint(projectDatasetPart({ ...payload }, "cycle"));
    expect(first).toBe(second);
  });

  it("keeps intimacy data out of the cycle part", () => {
    const cycle = projectDatasetPart(samplePayload(), "cycle");
    const log = cycle.calendarDayLogs["2026-07-02"];
    expect(log.mucus).toBe("egg-white");
    expect(log.actualAction).toBeUndefined();
    expect(log.notes).toBeUndefined();
    expect(log.events).toBeUndefined();
    expect(cycle.calendarDayLogs["2026-07-05"]).toBeUndefined();
    expect(cycle.ecJournal.value).toBe(false);
    expect(cycle.voluntaryAbstinenceDates).toEqual({});
  });

  it("routes EC events to sensitive and incidents to intimacy", () => {
    const payload = samplePayload();
    const intimacy = projectDatasetPart(payload, "intimacy");
    const sensitive = projectDatasetPart(payload, "sensitive");
    expect(intimacy.calendarDayLogs["2026-07-02"].events?.map((event) => event.kind)).toEqual([
      "condom_broke",
    ]);
    expect(sensitive.calendarDayLogs["2026-07-02"].events?.map((event) => event.kind)).toEqual([
      "plan_b_taken",
    ]);
    expect(sensitive.ecJournal.value).toBe(true);
    expect(intimacy.ecJournal.value).toBe(false);
  });

  it("combining a partial grant leaves missing sections empty", () => {
    const payload = samplePayload();
    const combined = combineDatasetParts({ cycle: projectDatasetPart(payload, "cycle") });
    expect(combined.periodRecords).toHaveLength(1);
    expect(combined.planner.configured).toBe(false);
    expect(combined.ecJournal.value).toBe(false);
    expect(combined.calendarDayLogs["2026-07-02"].actualAction).toBeUndefined();
  });

  it("plan part carries only planner options", () => {
    const plan = projectDatasetPart(samplePayload(), "plan");
    expect(plan.planner.configured).toBe(true);
    expect(plan.periodRecords).toHaveLength(0);
    expect(Object.keys(plan.calendarDayLogs)).toHaveLength(0);
  });
});

describe("split base generations", () => {
  it("derives roots across generations and companions", () => {
    expect(splitBaseRoot("primary")).toBe("primary");
    expect(splitBaseRoot("primary.g2")).toBe("primary");
    expect(splitBaseRoot("primary.g2.cycle")).toBe("primary");
    // A hyphen-suffixed id is a distinct profile, never a generation.
    expect(splitBaseRoot("emma-2")).toBe("emma-2");
  });

  it("mints the next unused generation", () => {
    expect(nextSplitBaseId("primary", ["primary"])).toBe("primary.g2");
    expect(
      nextSplitBaseId("primary", ["primary", "primary.g2", "primary.g2.cycle"]),
    ).toBe("primary.g3");
    // Sibling profiles with hyphen ids do not bump the generation.
    expect(nextSplitBaseId("emma", ["emma", "emma-2"])).toBe("emma.g2");
  });

  it("detects a newer generation this device has not adopted", () => {
    expect(newerSplitBaseId("primary", ["primary"])).toBeNull();
    expect(
      newerSplitBaseId("primary", ["primary", "primary.g2.cycle"]),
    ).toBe("primary.g2");
    expect(newerSplitBaseId("primary.g2", ["primary", "primary.g2"])).toBeNull();
  });

  it("keeps generations in one family and separate profiles out of it", () => {
    expect(sameSplitFamily("primary", "primary.g2")).toBe(true);
    expect(sameSplitFamily("primary", "primary.g2.sensitive")).toBe(true);
    expect(sameSplitFamily("emma", "emma-2")).toBe(false);
    expect(sameSplitFamily("primary", "primary.control")).toBe(false);
  });
});
