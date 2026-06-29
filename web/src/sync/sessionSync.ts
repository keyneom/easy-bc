import type { WasmOptions } from "../App";
import { idbDelete, idbSet, KV_SYNC_STATE } from "../idbStore";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import { base64UrlToBytes, decryptSyncPayload, encryptSyncPayload } from "./crypto";
import {
  deleteDriveSnapshot,
  findDriveSnapshot,
  requestDriveAccessToken,
  writeDriveSnapshot,
} from "./googleDrive";
import { createSyncPasskey, unlockSyncPasskey } from "./passkey";
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
}: {
  operation: SyncOperation;
  clientId: string;
  rpId: string;
  local: SyncPayloadV1;
}): Promise<SyncRunResult> {
  if (!clientId) throw new Error("Google Drive sync is not configured in this build.");
  const token = await requestDriveAccessToken(clientId);
  const existing = await findDriveSnapshot(token);

  if (operation === "setup") {
    if (existing) {
      throw new Error(
        "An EasyBC snapshot already exists in this Drive. Use “Enable on this device” instead.",
      );
    }
    const passkey = await createSyncPasskey();
    try {
      const envelope = await encryptSyncPayload(
        local,
        passkey.secret,
        passkey.credentialId,
        passkey.rpId,
        passkey.prfInput,
        passkey.kdfSalt,
      );
      const fileId = await writeDriveSnapshot(token, envelope);
      return {
        operation,
        fileId,
        syncedAt: envelope.updatedAt,
        payload: local,
        message: "Encrypted sync is set up. The cloud key was discarded after upload.",
      };
    } finally {
      passkey.secret.fill(0);
    }
  }

  if (!existing) throw new Error("No EasyBC encrypted snapshot was found in this Google Drive.");

  if (operation === "delete") {
    await deleteDriveSnapshot(token, existing.fileId);
    return {
      operation,
      fileId: null,
      syncedAt: null,
      payload: null,
      message: "The encrypted EasyBC snapshot was deleted from Google Drive.",
    };
  }

  if (operation === "reset") {
    const passkey = await createSyncPasskey();
    try {
      const envelope = await encryptSyncPayload(
        local,
        passkey.secret,
        passkey.credentialId,
        passkey.rpId,
        passkey.prfInput,
        passkey.kdfSalt,
      );
      await writeDriveSnapshot(token, envelope, existing.fileId);
      return {
        operation,
        fileId: existing.fileId,
        syncedAt: envelope.updatedAt,
        payload: local,
        message: "The cloud snapshot now uses the new passkey and this device's local data.",
      };
    } finally {
      passkey.secret.fill(0);
    }
  }

  if (existing.envelope.rpId !== rpId) {
    throw new Error(
      `This snapshot belongs to ${existing.envelope.rpId}. Open EasyBC on that domain to use its passkey.`,
    );
  }

  const secret = await unlockSyncPasskey(
    existing.envelope.credentialId,
    existing.envelope.prfInput,
    existing.envelope.rpId,
  );
  try {
    const remote = await decryptSyncPayload(existing.envelope, secret);
    const merged = mergeSyncPayloads(remote, local);
    const envelope = await encryptSyncPayload(
      merged,
      secret,
      existing.envelope.credentialId,
      existing.envelope.rpId,
      base64UrlToBytes(existing.envelope.prfInput),
      base64UrlToBytes(existing.envelope.kdfSalt),
    );
    await writeDriveSnapshot(token, envelope, existing.fileId);
    return {
      operation,
      fileId: existing.fileId,
      syncedAt: envelope.updatedAt,
      payload: merged,
      message:
        operation === "enable"
          ? "This device is enabled and the latest records were merged."
          : "Encrypted records and settings are up to date.",
    };
  } finally {
    secret.fill(0);
  }
}
