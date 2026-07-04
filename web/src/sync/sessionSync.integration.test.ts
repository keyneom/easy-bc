import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SyncPayloadV1 } from "./types";

const mocks = vi.hoisted(() => {
  const controller = {
    setup: vi.fn(),
    enable: vi.fn(),
    sync: vi.fn(),
    reset: vi.fn(),
    delete: vi.fn(),
    lock: vi.fn(),
    operationInProgress: vi.fn(() => false),
  };
  return {
    authorizationClear: vi.fn(),
    bindLifecycle: vi.fn(() => vi.fn()),
    controller,
    createSnapshotSync: vi.fn(() => controller),
    idbDelete: vi.fn(),
    idbSet: vi.fn(),
  };
});

vi.mock("../idbStore", () => ({
  idbDelete: mocks.idbDelete,
  idbSet: mocks.idbSet,
  KV_SYNC_STATE: "sync-state",
}));

vi.mock("./codec", () => ({
  easyBcSyncCodec: {
    serialize: (value: unknown) => value,
    parse: (value: unknown) => value,
    merge: (local: unknown) => local,
    fingerprint: () => "fingerprint",
  },
  syncPayloadFingerprint: () => "fingerprint",
}));

vi.mock("./crypto", () => ({
  easyBcCryptoBackend: {},
  easyBcEnvelopeCrypto: {},
}));

vi.mock("./profile", () => ({
  easyBcV1Profile: {
    appId: "easy-bc",
    filename: "easybc-sync-v1.json",
  },
}));

vi.mock("@keyneom/sync-kit/crypto", () => ({
  parseSyncEnvelopeV1: vi.fn(),
}));

vi.mock("@keyneom/sync-kit/keys/web-passkey", () => ({
  createWebPasskeyProvider: vi.fn(() => ({ clear: vi.fn() })),
}));

vi.mock("@keyneom/sync-kit/auth/google-web", () => ({
  GoogleWebAuthorizationProvider: class {
    clear = mocks.authorizationClear;
  },
}));

vi.mock("@keyneom/sync-kit/stores/google-drive", () => ({
  GoogleDriveAppDataStore: class {},
  GoogleDriveSnapshotStore: class {},
}));

vi.mock("@keyneom/sync-kit/snapshot", () => ({
  createSnapshotSync: mocks.createSnapshotSync,
}));

vi.mock("@keyneom/sync-kit/snapshot/lifecycle", () => ({
  bindWebLifecycle: mocks.bindLifecycle,
}));

import {
  encryptedSyncOperationInProgress,
  forgetSyncState,
  runEncryptedSyncOperation,
} from "./sessionSync";

function payload(): SyncPayloadV1 {
  return {
    schemaVersion: 1,
    exportedAt: "2026-06-30T12:00:00.000Z",
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
      updatedAt: "2026-06-30T12:00:00.000Z",
      configured: true,
    },
    periodRecords: [],
    deletedPeriodStarts: {},
    calendarDayLogs: {},
    voluntaryAbstinenceDates: {},
    voluntaryAbstinenceUpdatedAt: {},
    deletedVoluntaryAbstinenceDates: {},
    ecJournal: { value: false, updatedAt: "2026-06-30T12:00:00.000Z" },
  };
}

describe("sync-kit EasyBC facade", () => {
  beforeEach(async () => {
    await forgetSyncState();
    vi.clearAllMocks();
    mocks.controller.operationInProgress.mockReturnValue(false);
  });

  it("configures one package controller and forwards the automatic-sync reason", async () => {
    const local = payload();
    mocks.controller.sync.mockResolvedValue({
      operation: "sync",
      outcome: "unchanged",
      fileId: "file",
      syncedAt: "2026-06-30T12:00:00.000Z",
      value: local,
    });

    const result = await runEncryptedSyncOperation({
      operation: "sync",
      clientId: "client",
      rpId: "keyneom.github.io",
      local,
      reason: "change",
    });

    expect(mocks.createSnapshotSync).toHaveBeenCalledTimes(1);
    expect(mocks.bindLifecycle).toHaveBeenCalledTimes(1);
    expect(mocks.controller.sync).toHaveBeenCalledWith("change");
    expect(result.payload).toBe(local);
    expect(result.fileId).toBe("file");
  });

  it("delegates operation state so foreground signals can avoid auth feedback", async () => {
    mocks.controller.setup.mockResolvedValue({
      operation: "setup",
      outcome: "created",
      fileId: "file",
      syncedAt: "2026-06-30T12:00:00.000Z",
      value: payload(),
    });
    await runEncryptedSyncOperation({
      operation: "setup",
      clientId: "client",
      rpId: "keyneom.github.io",
      local: payload(),
    });
    mocks.controller.operationInProgress.mockReturnValue(true);

    expect(encryptedSyncOperationInProgress()).toBe(true);
  });

  it("uses the package delete path and preserves the existing facade result", async () => {
    mocks.controller.delete.mockResolvedValue(undefined);

    const result = await runEncryptedSyncOperation({
      operation: "delete",
      clientId: "client",
      rpId: "keyneom.github.io",
      local: payload(),
    });

    expect(mocks.controller.delete).toHaveBeenCalledTimes(1);
    expect(result).toMatchObject({
      operation: "delete",
      fileId: null,
      syncedAt: null,
      payload: null,
    });
  });

  it("locks and replaces the runtime when OAuth or RP configuration changes", async () => {
    mocks.controller.setup.mockResolvedValue({
      operation: "setup",
      outcome: "created",
      fileId: "file",
      syncedAt: "2026-06-30T12:00:00.000Z",
      value: payload(),
    });

    await runEncryptedSyncOperation({
      operation: "setup",
      clientId: "client-a",
      rpId: "keyneom.github.io",
      local: payload(),
    });
    await runEncryptedSyncOperation({
      operation: "setup",
      clientId: "client-b",
      rpId: "keyneom.github.io",
      local: payload(),
    });

    expect(mocks.controller.lock).toHaveBeenCalledTimes(1);
    expect(mocks.createSnapshotSync).toHaveBeenCalledTimes(2);
  });
});
