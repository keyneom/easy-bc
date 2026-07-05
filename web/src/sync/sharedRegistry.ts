import type {
  SharedBackupRegistry,
  SharedDatasetRegistryRecord,
} from "@keyneom/sync-kit/sharing/controller";
import { idbDelete, idbGet, idbSet, KV_SHARED_SYNC_STATE } from "../idbStore";
import { parseProfileKey, profileKey } from "./sharedFolderName";
import {
  defaultOwnedProfileKey,
  PRIMARY_DATASET_ID,
  SHARED_SYNC_STATE_VERSION,
  findProfile,
  type ProfileRecord,
  type SharedSyncState,
} from "./sharedTypes";

export { KV_SHARED_SYNC_STATE };

export async function loadSharedSyncState(): Promise<SharedSyncState | null> {
  const saved = await idbGet<SharedSyncState>(KV_SHARED_SYNC_STATE);
  if (!saved || saved.schemaVersion !== SHARED_SYNC_STATE_VERSION) return null;
  return saved;
}

export async function saveSharedSyncState(state: SharedSyncState): Promise<void> {
  await idbSet(KV_SHARED_SYNC_STATE, state);
}

export async function forgetSharedSyncState(): Promise<void> {
  await idbDelete(KV_SHARED_SYNC_STATE);
}

export function registryRecordFromProfile(profile: ProfileRecord): SharedDatasetRegistryRecord {
  return {
    datasetId: profile.datasetId,
    ...(profile.fileId ? { fileId: profile.fileId } : {}),
    trustedOwnerKeyId: profile.trustedOwnerKeyId,
    ...(profile.lastRevisionId ? { lastRevisionId: profile.lastRevisionId } : {}),
    ...(profile.seenRevisionIds ? { seenRevisionIds: profile.seenRevisionIds } : {}),
    ...(profile.participantPermissionIds
      ? { participantPermissionIds: profile.participantPermissionIds }
      : {}),
  };
}

export function applyRegistryRecord(
  profile: ProfileRecord,
  record: SharedDatasetRegistryRecord,
): ProfileRecord {
  return {
    ...profile,
    ...(record.fileId ? { fileId: record.fileId } : {}),
    trustedOwnerKeyId: record.trustedOwnerKeyId,
    ...(record.lastRevisionId ? { lastRevisionId: record.lastRevisionId } : {}),
    ...(record.seenRevisionIds ? { seenRevisionIds: record.seenRevisionIds } : {}),
    ...(record.participantPermissionIds
      ? { participantPermissionIds: record.participantPermissionIds }
      : {}),
  };
}

/** Registry scoped to one profile so duplicate dataset ids across owners stay isolated. */
export class ProfileScopedSharedBackupRegistry implements SharedBackupRegistry {
  constructor(
    private readonly getState: () => Promise<SharedSyncState | null>,
    private readonly scopeProfileKey: string,
  ) {}

  private async scopedProfile(): Promise<ProfileRecord | null> {
    const state = await this.getState();
    if (!state) return null;
    return findProfile(state, this.scopeProfileKey) ?? null;
  }

  async get(datasetId: string): Promise<SharedDatasetRegistryRecord | null> {
    const profile = await this.scopedProfile();
    if (!profile || profile.datasetId !== datasetId) return null;
    return registryRecordFromProfile(profile);
  }

  async set(record: SharedDatasetRegistryRecord): Promise<void> {
    const state = await this.getState();
    if (!state) throw new Error("Shared sync is not configured on this device.");
    const key = this.scopeProfileKey;
    const existing = findProfile(state, key);
    const base: ProfileRecord = existing ?? {
      ...parseProfileKey(key),
      folderName: "",
      role: "viewer",
      trustedOwnerKeyId: record.trustedOwnerKeyId,
      datasetId: record.datasetId,
    };
    const next = upsertProfile(state, applyRegistryRecord(base, record));
    await saveSharedSyncState(next);
  }

  async delete(datasetId: string): Promise<void> {
    const state = await this.getState();
    if (!state) return;
    const { ownerEmail } = parseProfileKey(this.scopeProfileKey);
    await saveSharedSyncState({
      ...state,
      profiles: state.profiles.filter(
        (entry) =>
          !(
            entry.datasetId === datasetId &&
            entry.ownerEmail.toLowerCase() === ownerEmail.toLowerCase()
          ),
      ),
    });
  }
}

export function createInitialSharedSyncState(input: {
  rpId: string;
  ownerEmail: string;
  folderName: string;
  trustedOwnerKeyId: string;
  appFolderId?: string;
  fileId?: string;
  lastRevisionId?: string;
}): SharedSyncState {
  const key = defaultOwnedProfileKey(input.ownerEmail);
  const profile: ProfileRecord = {
    datasetId: PRIMARY_DATASET_ID,
    ownerEmail: input.ownerEmail,
    folderName: input.folderName,
    role: "owner",
    trustedOwnerKeyId: input.trustedOwnerKeyId,
    ...(input.appFolderId ? { appFolderId: input.appFolderId } : {}),
    ...(input.fileId ? { fileId: input.fileId } : {}),
    ...(input.lastRevisionId ? { lastRevisionId: input.lastRevisionId } : {}),
    lastSyncedAt: new Date().toISOString(),
  };
  return {
    schemaVersion: SHARED_SYNC_STATE_VERSION,
    rpId: input.rpId,
    ownerEmail: input.ownerEmail,
    activeProfileKey: key,
    profiles: [profile],
  };
}

export function upsertProfile(state: SharedSyncState, profile: ProfileRecord): SharedSyncState {
  const key = profileKey(profile.ownerEmail, profile.datasetId);
  const index = state.profiles.findIndex(
    (entry) => profileKey(entry.ownerEmail, entry.datasetId) === key,
  );
  const profiles =
    index >= 0
      ? state.profiles.map((entry, i) => (i === index ? { ...entry, ...profile } : entry))
      : [...state.profiles, profile];
  return { ...state, profiles };
}

export async function updateProfileByKey(
  state: SharedSyncState,
  key: string,
  patch: Partial<ProfileRecord>,
): Promise<SharedSyncState> {
  const { ownerEmail, datasetId } = parseProfileKey(key);
  const profiles = state.profiles.map((entry) =>
    entry.ownerEmail.toLowerCase() === ownerEmail.toLowerCase() &&
    entry.datasetId === datasetId
      ? { ...entry, ...patch }
      : entry,
  );
  const next = { ...state, profiles };
  await saveSharedSyncState(next);
  return next;
}
