import type { WasmOptions } from "../App";
import { idbDelete, idbSet, KV_SYNC_STATE } from "../idbStore";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import {
  base64UrlToBytes,
  decryptSyncPayloadWithKey,
  deriveContentKey,
  encryptSyncPayloadWithKey,
} from "./crypto";
import {
  deleteDriveSnapshot,
  findDriveSnapshot,
  requestDriveAccessToken,
  writeDriveSnapshot,
} from "./googleDrive";
import { createSyncPasskey, unlockSyncPasskey } from "./passkey";
import { syncKeySession } from "./keySession";
import {
  mergeSyncPayloads,
  portablePlannerOptions,
  type LocalSyncState,
  type SyncPayloadV1,
} from "./types";

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

let syncOperationTail: Promise<void> = Promise.resolve();
let syncOperationCount = 0;

export function encryptedSyncOperationInProgress(): boolean {
  return syncOperationCount > 0;
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

export function syncPayloadFingerprint(payload: SyncPayloadV1): string {
  const { exportedAt: _exportedAt, ...stablePayload } = payload;
  return JSON.stringify(stablePayload);
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
  syncKeySession.clear();
  await idbDelete(KV_SYNC_STATE);
}

export function formatLastSync(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

async function deriveSessionKey(secret: Uint8Array, salt: Uint8Array): Promise<CryptoKey> {
  try {
    return await deriveContentKey(secret, salt);
  } finally {
    secret.fill(0);
  }
}

export async function runEncryptedSyncOperation({
  operation,
  clientId,
  rpId,
  local,
}: {
  operation: SyncOperation;
  clientId: string;
  rpId: string;
  local: SyncPayloadV1;
}): Promise<SyncRunResult> {
  syncOperationCount += 1;
  const result = syncOperationTail.then(() => runEncryptedSyncOperationNow({
    operation,
    clientId,
    rpId,
    local,
  }));
  syncOperationTail = result.then(() => undefined, () => undefined);
  try {
    return await result;
  } finally {
    syncOperationCount -= 1;
  }
}

async function runEncryptedSyncOperationNow({
  operation,
  clientId,
  rpId,
  local,
}: {
  operation: SyncOperation;
  clientId: string;
  rpId: string;
  local: SyncPayloadV1;
}): Promise<SyncRunResult> {
  if (!clientId) throw new Error("Encrypted cloud sync is not configured in this build.");
  const token = await requestDriveAccessToken(clientId);
  const existing = await findDriveSnapshot(token);

  if (operation === "setup") {
    if (existing) {
      throw new Error(
        "An encrypted EasyBC cloud snapshot already exists in this Drive. Use “Enable encrypted cloud sync on this device” instead.",
      );
    }
    const passkey = await createSyncPasskey();
    const key = await deriveSessionKey(passkey.secret, passkey.kdfSalt);
    const envelope = await encryptSyncPayloadWithKey(
      local,
      key,
      passkey.credentialId,
      passkey.rpId,
      passkey.prfInput,
      passkey.kdfSalt,
    );
    const fileId = await writeDriveSnapshot(token, envelope);
    syncKeySession.remember(envelope, key);
    return {
      operation,
      fileId,
      syncedAt: envelope.updatedAt,
      payload: local,
      message: "Encrypted cloud sync is set up and unlocked for this app session.",
    };
  }

  if (!existing) throw new Error("No EasyBC encrypted cloud snapshot was found in this Google Drive.");

  if (operation === "delete") {
    await deleteDriveSnapshot(token, existing.fileId);
    syncKeySession.clear();
    return {
      operation,
      fileId: null,
      syncedAt: null,
      payload: null,
      message: "The encrypted EasyBC cloud snapshot was deleted from Google Drive.",
    };
  }

  if (operation === "reset") {
    syncKeySession.clear();
    const passkey = await createSyncPasskey();
    const key = await deriveSessionKey(passkey.secret, passkey.kdfSalt);
    const envelope = await encryptSyncPayloadWithKey(
      local,
      key,
      passkey.credentialId,
      passkey.rpId,
      passkey.prfInput,
      passkey.kdfSalt,
    );
    await writeDriveSnapshot(token, envelope, existing.fileId);
    syncKeySession.remember(envelope, key);
    return {
      operation,
      fileId: existing.fileId,
      syncedAt: envelope.updatedAt,
      payload: local,
      message: "The encrypted cloud snapshot now uses the new passkey and this device's local data.",
    };
  }

  if (existing.envelope.rpId !== rpId) {
    throw new Error(
      `This snapshot belongs to ${existing.envelope.rpId}. Open EasyBC on that domain to use its passkey.`,
    );
  }

  const key = await syncKeySession.getOrUnlock(existing.envelope, async () => {
    const secret = await unlockSyncPasskey(
      existing.envelope.credentialId,
      existing.envelope.prfInput,
      existing.envelope.rpId,
    );
    return deriveSessionKey(secret, base64UrlToBytes(existing.envelope.kdfSalt));
  });
  let remote: SyncPayloadV1;
  try {
    remote = await decryptSyncPayloadWithKey(existing.envelope, key);
  } catch (error) {
    syncKeySession.clear();
    throw error;
  }
  const merged = mergeSyncPayloads(remote, local);
  const cloudChanged = syncPayloadFingerprint(merged) !== syncPayloadFingerprint(remote);
  let syncedAt = existing.envelope.updatedAt;
  if (cloudChanged) {
    const envelope = await encryptSyncPayloadWithKey(
      merged,
      key,
      existing.envelope.credentialId,
      existing.envelope.rpId,
      base64UrlToBytes(existing.envelope.prfInput),
      base64UrlToBytes(existing.envelope.kdfSalt),
    );
    await writeDriveSnapshot(token, envelope, existing.fileId);
    syncedAt = envelope.updatedAt;
  }
  return {
    operation,
    fileId: existing.fileId,
    syncedAt,
    payload: merged,
    message:
      operation === "enable"
        ? "Encrypted cloud sync is enabled on this device and the latest records were merged."
        : "Encrypted cloud data, records, and settings are up to date.",
  };
}
