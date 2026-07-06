import type { SharingRole } from "@keyneom/sync-kit/sharing";
import { parseProfileKey, profileKey as buildProfileKey } from "./sharedFolderName";
import type { WasmOptions } from "../App";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import {
  mergeSyncPayloads,
  parseSyncPayload,
  portablePlannerOptions,
  plannerConfiguredFromPayload,
  type SyncPayloadV1,
} from "./types";

export const EASY_BC_APP_ID = "easy-bc";
export const PRIMARY_DATASET_ID = "primary";
export const SHARED_SYNC_STATE_VERSION = 1 as const;

/** Encrypted dataset payload — reproductive health data only (no device prefs). */
export type SharedSyncPayloadV1 = Omit<SyncPayloadV1, "androidPreferences">;

export type ProfileRecord = {
  datasetId: string;
  ownerEmail: string;
  folderName: string;
  /** User-facing label for owned profiles (e.g. "Daughter"). */
  displayName?: string;
  appFolderId?: string;
  role: SharingRole;
  trustedOwnerKeyId: string;
  fileId?: string;
  lastRevisionId?: string;
  seenRevisionIds?: string[];
  participantPermissionIds?: Record<string, string>;
  lastSyncedAt?: string;
};

export type ActiveProfile = {
  key: string;
  datasetId: string;
  ownerEmail: string;
  folderName: string;
  role: SharingRole;
  writable: boolean;
};

export type SharedSyncState = {
  schemaVersion: typeof SHARED_SYNC_STATE_VERSION;
  rpId: string;
  ownerEmail: string;
  /** Recipient: folder id selected via Picker for a joined share. */
  selectedAppFolderId?: string;
  activeProfileKey: string;
  profiles: ProfileRecord[];
  pendingJoin?: {
    exchangeId: string;
    appFolderId: string;
    invitationFileId?: string;
  };
};

export function canPublishRole(role: SharingRole): boolean {
  return role === "owner" || role === "admin" || role === "writer";
}

export function extractSharedPayload(payload: SyncPayloadV1): SharedSyncPayloadV1 {
  const { androidPreferences: _androidPreferences, ...shared } = payload;
  return shared;
}

export function sharedPayloadToSyncPayload(
  shared: SharedSyncPayloadV1,
  androidPreferences?: SyncPayloadV1["androidPreferences"],
): SyncPayloadV1 {
  return {
    ...shared,
    ...(androidPreferences ? { androidPreferences } : {}),
  };
}

export function buildSharedSyncPayload(
  options: WasmOptions,
  periodRecords: PeriodRecord[],
  session: PersistedSession,
): SharedSyncPayloadV1 {
  return extractSharedPayload({
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
  });
}

export function sharedPayloadFingerprint(payload: SharedSyncPayloadV1): string {
  const { exportedAt: _exportedAt, ...stable } = payload;
  return JSON.stringify(stable);
}

export function parseSharedSyncPayload(value: unknown): SharedSyncPayloadV1 {
  return extractSharedPayload(parseSyncPayload(value));
}

export function mergeSharedSyncPayloads(
  a: SharedSyncPayloadV1,
  b: SharedSyncPayloadV1,
): SharedSyncPayloadV1 {
  return extractSharedPayload(mergeSyncPayloads(a as SyncPayloadV1, b as SyncPayloadV1));
}

export { plannerConfiguredFromPayload };

export function activeProfileFromRecord(record: ProfileRecord, key: string): ActiveProfile {
  return {
    key,
    datasetId: record.datasetId,
    ownerEmail: record.ownerEmail,
    folderName: record.folderName,
    role: record.role,
    writable: canPublishRole(record.role),
  };
}

export function findProfile(
  state: SharedSyncState,
  profileKeyValue: string,
): ProfileRecord | undefined {
  const { ownerEmail, datasetId } = parseProfileKey(profileKeyValue);
  return state.profiles.find(
    (profile) =>
      profile.datasetId === datasetId &&
      profile.ownerEmail.toLowerCase() === ownerEmail.toLowerCase(),
  );
}

export function defaultOwnedProfileKey(ownerEmail: string): string {
  return buildProfileKey(ownerEmail, PRIMARY_DATASET_ID);
}
