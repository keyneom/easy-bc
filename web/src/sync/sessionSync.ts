import {
  parseSyncEnvelopeV1,
  type SyncEnvelopeV1,
} from "@keyneom/sync-kit/crypto";
import type {
  SnapshotOperation,
  SyncReason,
  SyncResult,
} from "@keyneom/sync-kit/core";
import { GoogleWebAuthorizationProvider } from "@keyneom/sync-kit/auth/google-web";
import { createWebPasskeyProvider } from "@keyneom/sync-kit/keys/web-passkey";
import {
  createSnapshotSync,
  type SnapshotSyncController,
} from "@keyneom/sync-kit/snapshot";
import { bindWebLifecycle } from "@keyneom/sync-kit/snapshot/lifecycle";
import {
  GoogleDriveAppDataStore,
  GoogleDriveSnapshotStore,
} from "@keyneom/sync-kit/stores/google-drive";
import type { WasmOptions } from "../App";
import { idbDelete, idbSet, KV_SYNC_STATE } from "../idbStore";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import { easyBcSyncCodec, syncPayloadFingerprint } from "./codec";
import {
  easyBcCryptoBackend,
  easyBcEnvelopeCrypto,
} from "./crypto";
import { easyBcV1Profile } from "./profile";
import {
  portablePlannerOptions,
  type LocalSyncState,
  type SyncPayloadV1,
} from "./types";

export { syncPayloadFingerprint };

export type SyncOperation = "setup" | "enable" | "sync" | "reset" | "delete";

export type SyncRunResult =
  | {
      operation: Exclude<SyncOperation, "delete">;
      fileId: string;
      syncedAt: string;
      payload: SyncPayloadV1;
      message: string;
    }
  | {
      operation: "delete";
      fileId: null;
      syncedAt: null;
      payload: null;
      message: string;
    };

type SyncRuntime = {
  clientId: string;
  rpId: string;
  local: SyncPayloadV1;
  controller: SnapshotSyncController<SyncPayloadV1>;
  unbindLifecycle: () => void;
};

const SYNC_KEY_BACKGROUND_GRACE_MS = 15 * 60_000;
let runtime: SyncRuntime | null = null;

export function encryptedSyncOperationInProgress(): boolean {
  return runtime?.controller.operationInProgress() ?? false;
}

export function buildLocalSyncPayload(
  options: WasmOptions,
  periodRecords: PeriodRecord[],
  session: PersistedSession,
): SyncPayloadV1 {
  return {
    schemaVersion: 1,
    exportedAt: new Date().toISOString(),
    planner: {
      value: portablePlannerOptions(options),
      updatedAt: session.plannerOptionsUpdatedAt,
      configured: session.plannerConfigured,
    },
    periodRecords,
    deletedPeriodStarts: session.deletedPeriodStarts,
    calendarDayLogs: session.calendarDayLogs,
    voluntaryAbstinenceDates: session.voluntaryAbstinenceDates,
    voluntaryAbstinenceUpdatedAt: session.voluntaryAbstinenceUpdatedAt,
    deletedVoluntaryAbstinenceDates: session.deletedVoluntaryAbstinenceDates,
    ecJournal: { value: session.ecJournalFlag, updatedAt: session.ecJournalUpdatedAt },
    ...(session.androidPreferences ? { androidPreferences: session.androidPreferences } : {}),
  };
}

export async function rememberSyncState(
  fileId: string,
  rpId: string,
  syncedAt: string,
): Promise<LocalSyncState> {
  const next: LocalSyncState = { schemaVersion: 1, fileId, rpId, lastSyncedAt: syncedAt };
  await idbSet(KV_SYNC_STATE, next);
  return next;
}

export async function forgetSyncState(): Promise<void> {
  disposeRuntime();
  await idbDelete(KV_SYNC_STATE);
}

export function formatLastSync(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export async function runEncryptedSyncOperation({
  operation,
  clientId,
  rpId,
  local,
  reason = "manual",
}: {
  operation: SyncOperation;
  clientId: string;
  rpId: string;
  local: SyncPayloadV1;
  reason?: SyncReason;
}): Promise<SyncRunResult> {
  if (!clientId) {
    throw new Error("Encrypted cloud sync is not configured in this build.");
  }
  const active = getRuntime(clientId, rpId, local);
  active.local = local;

  if (operation === "delete") {
    await active.controller.delete();
    return {
      operation,
      fileId: null,
      syncedAt: null,
      payload: null,
      message: "The encrypted EasyBC cloud snapshot was deleted from Google Drive.",
    };
  }

  const result = await runControllerOperation(active.controller, operation, reason);
  if (
    result.outcome === "coalesced" ||
    !result.fileId ||
    !result.syncedAt ||
    !result.value
  ) {
    throw new Error("Encrypted cloud sync is already in progress.");
  }
  return {
    operation,
    fileId: result.fileId,
    syncedAt: result.syncedAt,
    payload: result.value,
    message: operationMessage(operation),
  };
}

function getRuntime(
  clientId: string,
  rpId: string,
  local: SyncPayloadV1,
): SyncRuntime {
  if (runtime?.clientId === clientId && runtime.rpId === rpId) return runtime;
  disposeRuntime();

  const authorizationProvider = new GoogleWebAuthorizationProvider({ clientId });
  const keyProvider = createWebPasskeyProvider(easyBcV1Profile, {
    rpId,
    backend: easyBcCryptoBackend,
  });
  const drive = new GoogleDriveAppDataStore({
    onUnauthorized: () => authorizationProvider.clear(),
  });
  const cloudStore = new GoogleDriveSnapshotStore<SyncEnvelopeV1>({
    appId: easyBcV1Profile.appId,
    filename: easyBcV1Profile.filename,
    parse: (value) => parseSyncEnvelopeV1(value, easyBcV1Profile),
    drive,
  });

  const next: SyncRuntime = {
    clientId,
    rpId,
    local,
    controller: null as unknown as SnapshotSyncController<SyncPayloadV1>,
    unbindLifecycle: () => {},
  };
  next.controller = createSnapshotSync({
    appId: easyBcV1Profile.appId,
    codec: easyBcSyncCodec,
    envelopeCrypto: easyBcEnvelopeCrypto,
    keyProvider,
    authorizationProvider,
    cloudStore,
    readLocal: () => next.local,
    // EasyBC applies the returned value after updating its React state boundary.
    applyMerged: () => undefined,
    envelopeUpdatedAt: (envelope) => envelope.updatedAt,
  });
  next.unbindLifecycle = bindWebLifecycle(next.controller, {
    backgroundGraceMs: SYNC_KEY_BACKGROUND_GRACE_MS,
  });
  runtime = next;
  return next;
}

function disposeRuntime(): void {
  if (!runtime) return;
  runtime.unbindLifecycle();
  runtime.controller.lock();
  runtime = null;
}

function runControllerOperation(
  controller: SnapshotSyncController<SyncPayloadV1>,
  operation: Exclude<SyncOperation, "delete">,
  reason: SyncReason,
): Promise<SyncResult<SyncPayloadV1>> {
  switch (operation) {
    case "setup":
      return controller.setup();
    case "enable":
      return controller.enable();
    case "sync":
      return controller.sync(reason);
    case "reset":
      return controller.reset();
  }
}

function operationMessage(operation: SnapshotOperation): string {
  switch (operation) {
    case "setup":
      return "Encrypted cloud sync is set up and unlocked for this app session.";
    case "enable":
      return "Encrypted cloud sync is enabled on this device and the latest records were merged.";
    case "sync":
      return "Encrypted cloud data, records, and settings are up to date.";
    case "reset":
      return "The encrypted cloud snapshot now uses the new passkey and this device's local data.";
  }
}
