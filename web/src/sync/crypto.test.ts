import { describe, expect, it } from "vitest";
import { decryptSyncPayload, encryptSyncPayload, randomBytes } from "./crypto";
import type { SyncPayloadV1 } from "./types";

describe("encrypted sync envelope", () => {
  it("round-trips authenticated ciphertext", async () => {
    const payload = {
      schemaVersion: 1,
      exportedAt: "2026-01-01T00:00:00.000Z",
      planner: { value: { ageYears: 34 }, updatedAt: "2026-01-01T00:00:00.000Z" },
      periodRecords: [{ start: "2025-12-20" }],
      deletedPeriodStarts: {},
      calendarDayLogs: {},
      voluntaryAbstinenceDates: {},
      voluntaryAbstinenceUpdatedAt: {},
      deletedVoluntaryAbstinenceDates: {},
      ecJournal: { value: false, updatedAt: "2026-01-01T00:00:00.000Z" },
    } as SyncPayloadV1;
    const secret = randomBytes(32);
    const envelope = await encryptSyncPayload(
      payload,
      secret,
      "credential",
      "keyneom.github.io",
      randomBytes(32),
      randomBytes(32),
    );

    await expect(decryptSyncPayload(envelope, secret)).resolves.toEqual(payload);
    await expect(decryptSyncPayload(envelope, randomBytes(32))).rejects.toThrow(/could not decrypt/u);
  });
});
