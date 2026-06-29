import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SyncEnvelopeV1, SyncPayloadV1 } from "./types";

const mocks = vi.hoisted(() => ({
  clearKey: vi.fn(),
  clearToken: vi.fn(),
  deleteSnapshot: vi.fn(),
  decrypt: vi.fn(),
  deriveKey: vi.fn(),
  encrypt: vi.fn(),
  findSnapshot: vi.fn(),
  forgetState: vi.fn(),
  getOrUnlock: vi.fn(),
  rememberKey: vi.fn(),
  requestToken: vi.fn(),
  unlockPasskey: vi.fn(),
  writeSnapshot: vi.fn(),
}));

vi.mock("../idbStore", () => ({
  idbDelete: mocks.forgetState,
  idbSet: vi.fn(),
  KV_SYNC_STATE: "sync-state",
}));

vi.mock("./googleDrive", () => ({
  clearDriveAccessToken: mocks.clearToken,
  deleteDriveSnapshot: mocks.deleteSnapshot,
  findDriveSnapshot: mocks.findSnapshot,
  requestDriveAccessToken: mocks.requestToken,
  writeDriveSnapshot: mocks.writeSnapshot,
}));

vi.mock("./crypto", () => ({
  base64UrlToBytes: () => new Uint8Array(32),
  decryptSyncPayloadWithKey: mocks.decrypt,
  deriveContentKey: mocks.deriveKey,
  encryptSyncPayloadWithKey: mocks.encrypt,
}));

vi.mock("./passkey", () => ({
  createSyncPasskey: vi.fn(),
  unlockSyncPasskey: mocks.unlockPasskey,
}));

vi.mock("./keySession", () => ({
  syncKeySession: {
    clear: mocks.clearKey,
    getOrUnlock: mocks.getOrUnlock,
    remember: mocks.rememberKey,
  },
}));

import {
  encryptedSyncOperationInProgress,
  runEncryptedSyncOperation,
} from "./sessionSync";

const envelope: SyncEnvelopeV1 = {
  schemaVersion: 1,
  algorithm: "AES-256-GCM+HKDF-SHA-256",
  credentialId: "credential",
  rpId: "keyneom.github.io",
  prfInput: "input",
  kdfSalt: "salt",
  nonce: "nonce",
  ciphertext: "ciphertext",
  updatedAt: "2026-06-29T12:00:00.000Z",
};

function payload(overrides: Partial<SyncPayloadV1> = {}): SyncPayloadV1 {
  return {
    schemaVersion: 1,
    exportedAt: "2026-06-29T12:00:00.000Z",
    planner: {
      value: {
        ageYears: 34,
        horizonYears: 20,
        targetCumulativeFailure: 0.05,
        cycleLengthDays: 28,
        actsPerWeek: 3.5,
        persistentMethod: "none",
        protectedDayMethod: "none",
        condomMode: "perfect",
        streakAversion: 0.5,
        holdLifecycleConstant: false,
        realizedCumulativeRisk: 0,
        withdrawalMode: "none",
        withdrawalTypicalAnnualFailure: 0.2,
        withdrawalRelativeRisk: 0.35,
        useWithdrawalBackupOnProtectedDays: false,
        combinedMethodIndependence: 0.35,
        ovulationSdDays: 3,
      },
      updatedAt: "2026-06-29T12:00:00.000Z",
      configured: true,
    },
    periodRecords: [],
    deletedPeriodStarts: {},
    calendarDayLogs: {},
    voluntaryAbstinenceDates: {},
    voluntaryAbstinenceUpdatedAt: {},
    deletedVoluntaryAbstinenceDates: {},
    ecJournal: { value: false, updatedAt: "2026-06-29T12:00:00.000Z" },
    ...overrides,
  };
}

describe("encrypted sync operation wiring", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.requestToken.mockResolvedValue("token");
    mocks.findSnapshot.mockResolvedValue({ fileId: "file", envelope });
    mocks.getOrUnlock.mockResolvedValue({} as CryptoKey);
    mocks.decrypt.mockResolvedValue(payload());
    mocks.encrypt.mockResolvedValue({
      ...envelope,
      updatedAt: "2026-06-29T13:00:00.000Z",
    });
    mocks.writeSnapshot.mockResolvedValue("file");
  });

  it("uses the cached key and skips a no-op cloud upload", async () => {
    const local = payload({ exportedAt: "2026-06-29T12:30:00.000Z" });

    const result = await runEncryptedSyncOperation({
      operation: "sync",
      clientId: "client",
      rpId: "keyneom.github.io",
      local,
    });

    expect(mocks.getOrUnlock).toHaveBeenCalledTimes(1);
    expect(mocks.unlockPasskey).not.toHaveBeenCalled();
    expect(mocks.encrypt).not.toHaveBeenCalled();
    expect(mocks.writeSnapshot).not.toHaveBeenCalled();
    expect(result.syncedAt).toBe(envelope.updatedAt);
  });

  it("uploads when the merged user data changed", async () => {
    const local = payload({
      planner: {
        ...payload().planner,
        value: { ...payload().planner.value, ageYears: 35 },
        updatedAt: "2026-06-29T13:00:00.000Z",
      },
    });

    await runEncryptedSyncOperation({
      operation: "sync",
      clientId: "client",
      rpId: "keyneom.github.io",
      local,
    });

    expect(mocks.encrypt).toHaveBeenCalledTimes(1);
    expect(mocks.writeSnapshot).toHaveBeenCalledTimes(1);
  });

  it("clears the cached key when decryption fails", async () => {
    mocks.decrypt.mockRejectedValue(new Error("could not decrypt"));

    await expect(runEncryptedSyncOperation({
      operation: "sync",
      clientId: "client",
      rpId: "keyneom.github.io",
      local: payload(),
    })).rejects.toThrow("could not decrypt");

    expect(mocks.clearKey).toHaveBeenCalledTimes(1);
    expect(mocks.writeSnapshot).not.toHaveBeenCalled();
  });

  it("reports authorization work as in progress so foreground events can ignore it", async () => {
    let resolveToken!: (token: string) => void;
    mocks.requestToken.mockReturnValue(new Promise<string>((resolve) => {
      resolveToken = resolve;
    }));

    const operation = runEncryptedSyncOperation({
      operation: "sync",
      clientId: "client",
      rpId: "keyneom.github.io",
      local: payload(),
    });
    expect(encryptedSyncOperationInProgress()).toBe(true);

    resolveToken("token");
    await operation;
    expect(encryptedSyncOperationInProgress()).toBe(false);
  });
});
