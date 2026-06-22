import { describe, expect, it } from "vitest";
import {
  base64UrlToBytes,
  decryptSyncPayload,
  encryptSyncPayload,
  randomBytes,
} from "./crypto";
import type { SyncPayloadV1 } from "./types";

describe("encrypted sync envelope", () => {
  it("decrypts the shared gzip-compressed crypto vector", async () => {
    const payload = await decryptSyncPayload(
      {
        schemaVersion: 1,
        algorithm: "AES-256-GCM+HKDF-SHA-256",
        compression: "gzip",
        credentialId: "credential",
        rpId: "keyneom.github.io",
        prfInput: "AA",
        kdfSalt: "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
        nonce: "QEFCQ0RFRkdISUpL",
        ciphertext: "LMBOEaK5ATYQxI26eKGwIdJ_ELsBJ8rf76MIXyxVsT3kN1K3qY42h07AZHOYkztpJYdhERCZmbG2j_Pd1zhqKRMIMdrj_-pBd6DljWZU0uD7d5rHWCbyAqudt_psdPSHm47MuQauJTsCjVmEkPNKCZZNOZkYnceNyC9MZQM_tY15jJLsHEdOnQS43zyNhv180UmM9POJcsaWBAG6yIfDRDpd2b1IpPuxmIAV3GLMYLiNHbJ4yDUX6PRYFhzI-Hh18rggXBENhWfq9kZ3hBpPKGkms-j4MHFIGhg_o3GY_rZTHCzq3phTdD3Mo4iSWf7D6FXezQP6H_jwyhDE0ydJyTjNfIPPyCESZtc",
        updatedAt: "2026-06-22T12:00:00Z",
      },
      base64UrlToBytes("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"),
    );

    expect(payload.planner.value.ageYears).toBe(38);
    expect(payload.calendarDayLogs["2026-06-22"]?.notes).toContain("cross-platform gzip vector");
  });

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
    } as unknown as SyncPayloadV1;
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

  it("compresses repetitive payload data before encryption", async () => {
    const payload = {
      schemaVersion: 1,
      exportedAt: "2026-01-01T00:00:00.000Z",
      planner: { value: { ageYears: 34 }, updatedAt: "2026-01-01T00:00:00.000Z" },
      periodRecords: [],
      deletedPeriodStarts: {},
      calendarDayLogs: {
        "2026-01-01": {
          notes: "repeated private journal text ".repeat(200),
          updatedAt: "2026-01-01T00:00:00.000Z",
        },
      },
      voluntaryAbstinenceDates: {},
      voluntaryAbstinenceUpdatedAt: {},
      deletedVoluntaryAbstinenceDates: {},
      ecJournal: { value: false, updatedAt: "2026-01-01T00:00:00.000Z" },
    } as unknown as SyncPayloadV1;
    const secret = randomBytes(32);
    const envelope = await encryptSyncPayload(
      payload,
      secret,
      "credential",
      "keyneom.github.io",
      randomBytes(32),
      randomBytes(32),
    );

    expect(envelope.compression).toBe("gzip");
    await expect(decryptSyncPayload(envelope, secret)).resolves.toEqual(payload);
  });
});
