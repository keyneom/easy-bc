import type { SharingRole } from "@keyneom/sync-kit/sharing";
import {
  DATASET_PARTS,
  type DatasetGrants,
  type DatasetPart,
} from "./datasets";
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
  /** Profiles exist independently of sync. Older records without this field are encrypted. */
  syncMode?: "local" | "encrypted";
  /** App-owned labels for sharing keys; encrypted envelopes intentionally contain no email. */
  participantEmails?: Record<string, string>;
  /**
   * A newly joined writable profile must load its remote dataset before it may
   * publish. This prevents the previously active profile's local working copy
   * from being merged into the joined person's dataset.
   */
  needsInitialLoad?: boolean;
  /**
   * Split profiles: role per dataset part (docs/sync-kit-multi-file-datasets.md).
   * Absent = legacy single-file profile — everything lives in `datasetId` and
   * `role` applies to all of it.
   */
  datasetGrants?: DatasetGrants;
  /**
   * Split profiles: sync-kit registry state for companion dataset files,
   * keyed by full dataset id ("<base>.cycle", …). The base dataset keeps
   * using the top-level fileId/lastRevisionId fields.
   */
  datasetRecords?: Record<string, CompanionDatasetRecord>;
};

export type CompanionDatasetRecord = {
  fileId?: string;
  lastRevisionId?: string;
  seenRevisionIds?: string[];
  participantPermissionIds?: Record<string, string>;
};

/* ---------- Split-profile helpers ---------- */

export function isSplitProfile(profile: ProfileRecord): boolean {
  return profile.datasetGrants !== undefined;
}

/** Parts this device can read. Legacy profiles grant everything at profile.role. */
export function grantedParts(profile: ProfileRecord): DatasetPart[] {
  if (!profile.datasetGrants) return [...DATASET_PARTS];
  return DATASET_PARTS.filter((part) => profile.datasetGrants![part] !== undefined);
}

/** Parts this device cannot read — the UI shows these as restricted. */
export function restrictedParts(profile: ProfileRecord): DatasetPart[] {
  if (!profile.datasetGrants) return [];
  return DATASET_PARTS.filter((part) => profile.datasetGrants![part] === undefined);
}

export function partRole(profile: ProfileRecord, part: DatasetPart): SharingRole | undefined {
  if (!profile.datasetGrants) return profile.role;
  return profile.datasetGrants[part];
}

export function partIsWritable(profile: ProfileRecord, part: DatasetPart): boolean {
  const role = partRole(profile, part);
  return role !== undefined && canPublishRole(role);
}

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

export function isLocalProfile(profile: ProfileRecord): boolean {
  return profile.syncMode === "local";
}

export function isEncryptedProfile(profile: ProfileRecord): boolean {
  return !isLocalProfile(profile);
}

export function shouldLoadRemoteBeforePublish(profile: ProfileRecord): boolean {
  return isEncryptedProfile(profile) &&
    (profile.needsInitialLoad === true || !canPublishRole(profile.role));
}

export function hasMeaningfulSharedData(payload: SharedSyncPayloadV1): boolean {
  return (
    plannerConfiguredFromPayload(payload as SyncPayloadV1) ||
    payload.periodRecords.length > 0 ||
    Object.keys(payload.calendarDayLogs).length > 0 ||
    Object.keys(payload.voluntaryAbstinenceDates).length > 0 ||
    Object.keys(payload.deletedPeriodStarts).length > 0 ||
    Object.keys(payload.deletedVoluntaryAbstinenceDates).length > 0 ||
    payload.ecJournal.value
  );
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
