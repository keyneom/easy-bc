import {
  GOOGLE_DRIVE_APPDATA_SCOPE,
  GOOGLE_DRIVE_FILE_SCOPE,
  GoogleWebAuthorizationProvider,
} from "@keyneom/sync-kit/auth/google-web";
import {
  CachingAuthorizationProvider,
  IndexedDbAuthorizationCache,
} from "@keyneom/sync-kit/auth/google-web/cache";
import { GoogleWebIdentityProvider } from "@keyneom/sync-kit/auth/google-web/identity";
import type { AuthorizationProvider } from "@keyneom/sync-kit/core";
import type { SharingRole } from "@keyneom/sync-kit/sharing";
import {
  buildSharingJoinLinkV1,
  buildSharingResponseLinkV1,
  createSharingChangeDetectorFromTransport,
  sharedBackupParticipants,
  type SharedBackupParticipantV1,
  type SharingChangeDetector,
  type SharingDatasetFileV1,
  type SharingInvitationV1,
  type SharingPublicKeyResponseV1,
  type SharingSyncCheckpoint,
} from "@keyneom/sync-kit/sharing";
import {
  createBackendlessSharingAccountBinding,
  verifySharingAccountBindingV1,
} from "@keyneom/sync-kit/sharing/account-binding";
import {
  createSharedBackupController,
  type SharedBackupController,
  type SharedBackupControllerCodec,
} from "@keyneom/sync-kit/sharing/controller";
import { verifySharingInvitationV1 } from "@keyneom/sync-kit/sharing/web-crypto";
import {
  createSharingControlCodec,
  createSharingControlDataset,
  type SharingControlDataset,
  type SharingControlStateV1,
} from "@keyneom/sync-kit/sharing/control";
import {
  GoogleDriveSharedBackupTransport,
  listAccessibleSyncKitDatasets,
  type AccessibleSyncKitDataset,
} from "@keyneom/sync-kit/stores/google-drive/sharing";
import {
  GoogleDriveFileStore,
  listAccessibleSyncKitAppFolders,
} from "@keyneom/sync-kit/stores/google-drive";
import { idbDelete, idbGet, idbSet, KV_SHARING_SYNC_CHECKPOINT } from "../idbStore";
import { appendDeveloperLog } from "../diagnostics/developerLog";
import {
  createLegacyControlRepairCodec,
  repairLegacyControlSignature,
} from "./controlSignatureRepair";
import { pickSharedAppFolder, pickSharedDatasetFiles } from "./sharedPicker";
import { easyBcSharedCodec } from "./sharedCodec";
import {
  clearSharingIdentitySession,
  createSharingIdentityProvider,
} from "./sharedIdentity";
import { easyBcSyncFolderName, profileKey } from "./sharedFolderName";
import {
  findOwnedStorageProfile,
  isOwnedProfile,
  newOwnedDatasetId,
  profileDisplayLabel,
} from "./profileLabels";
import { selectedAppFolderIdForProfile } from "./profileRouting";
import { createEmptySharedSyncPayload } from "./sharedEmptyPayload";
import {
  createInitialSharedSyncState,
  forgetSharedSyncState,
  isSharedSyncConfigured,
  loadSharedSyncState,
  ProfileScopedSharedBackupRegistry,
  saveSharedSyncState,
  upsertProfile,
} from "./sharedRegistry";
import {
  baseDatasetIdOf,
  combineDatasetParts,
  CONTROL_DATASET_SUFFIX,
  DATASET_PARTS,
  datasetIdForPart,
  discoverProfileDatasetGroups,
  grantsFromRequestedGrants,
  highestGrantedRole,
  newerSplitBaseId,
  nextSplitBaseId,
  partForDatasetId,
  projectDatasetPart,
  requestedGrantsFromDatasetGrants,
  requestedGrantsWithControl,
  splitBaseRoot,
  type DatasetGrants,
  type DatasetPart,
} from "./datasets";
import {
  canPublishRole,
  EASY_BC_APP_ID,
  findProfile,
  grantedParts,
  hasMeaningfulSharedData,
  isEncryptedProfile,
  isLocalProfile,
  isSplitProfile,
  partIsWritable,
  PRIMARY_DATASET_ID,
  shouldLoadRemoteBeforePublish,
  type ProfileRecord,
  type SharedSyncPayloadV1,
  type SharedSyncState,
} from "./sharedTypes";

export type SharedSyncConfig = {
  clientId: string;
  rpId: string;
  googleAudience: string;
  allowedOrigins: string[];
};

export type SharedSyncRunResult = {
  payload: SharedSyncPayloadV1;
  syncedAt: string;
  revisionId: string;
  profileKey: string;
};

type Runtime = {
  config: SharedSyncConfig;
  state: SharedSyncState;
  local: SharedSyncPayloadV1;
  controller: SharedBackupController<SharedSyncPayloadV1>;
  identityProvider: ReturnType<typeof createSharingIdentityProvider>;
  authorizationProvider: CachingAuthorizationProvider;
  googleIdentity: GoogleWebIdentityProvider;
};

let runtime: Runtime | null = null;
let profileDiscoverySession: {
  clientId: string;
  rpId: string;
  accountEmail?: string;
  providers: ReturnType<typeof createProviders>;
  identityProvider: ReturnType<typeof createSharingIdentityProvider>;
} | null = null;
let cachedState: SharedSyncState | null = null;
let operationQueue: Promise<unknown> = Promise.resolve();
let activeOperationCount = 0;
const tokenExpiresAtByProfile = new Map<string, number>();
const LOCAL_PROFILE_PAYLOAD_PREFIX = "profilePayload:";
const CONTROL_DATASETS_WIRED = true;
const sharingControlCodec = createSharingControlCodec();

function controlDatasetIdFor(baseDatasetId: string): string {
  return `${baseDatasetId}${CONTROL_DATASET_SUFFIX}`;
}

const GOOGLE_SCOPES = `${GOOGLE_DRIVE_FILE_SCOPE} ${GOOGLE_DRIVE_APPDATA_SCOPE}`;
const sharedAuthCache = new IndexedDbAuthorizationCache({
  databaseName: "easy-bc-sync-kit-auth",
});

export function sharedSyncOperationInProgress(): boolean {
  return activeOperationCount > 0;
}

function rememberTokenExpiry(profileId: string, expiresAt?: number): void {
  if (expiresAt === undefined) {
    tokenExpiresAtByProfile.delete(profileId);
    return;
  }
  tokenExpiresAtByProfile.set(profileId, expiresAt);
}

function serialized<T>(operation: () => Promise<T>): Promise<T> {
  activeOperationCount += 1;
  const next = operationQueue.then(operation, operation);
  operationQueue = next.then(
    () => undefined,
    () => undefined,
  );
  return next.finally(() => {
    activeOperationCount = Math.max(0, activeOperationCount - 1);
  });
}

// Drive about.get is authorized by the drive.file scope we already request;
// the OpenID userinfo endpoint would need an extra email/openid grant.
export async function fetchGoogleAccountEmail(accessToken: string): Promise<string> {
  const response = await fetch(
    "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)",
    { headers: { Authorization: `Bearer ${accessToken}` } },
  );
  if (!response.ok) {
    throw new Error("Could not read your Google account email for encrypted sync.");
  }
  const data = (await response.json()) as { user?: { emailAddress?: string } };
  const email = data.user?.emailAddress?.trim();
  if (!email) {
    throw new Error("Your Google account has no email address for encrypted sync.");
  }
  return email;
}

export type DriveVisibilityEntry = {
  id: string;
  status: number;
  ok: boolean;
  detail: string;
};

export type DriveVisibilityProbe = {
  folder: DriveVisibilityEntry;
  invitation: DriveVisibilityEntry;
};

/**
 * Diagnostic: with drive.file, another account's shares can be invisible to
 * our token (HTTP 404) even after the user Picker-grants the folder. This
 * probes files.get for both the shared folder and the invitation file inside
 * it so we can distinguish the two hypotheses with data:
 *   folder 200 + invitation 404 -> folder grant does not cover descendants
 *   folder 404 + invitation 404 -> grant never reached this OAuth client
 *   folder 200 + invitation 403 -> visible but not authorized
 * Kept in parity with SharedSyncCoordinator.probeDriveVisibility on Android.
 */
export async function probeDriveVisibility(
  accessToken: string,
  folderId: string,
  invitationFileId: string,
): Promise<DriveVisibilityProbe> {
  const check = async (id: string): Promise<DriveVisibilityEntry> => {
    if (!id) return { id, status: 0, ok: false, detail: "(no id in join link)" };
    try {
      const response = await fetch(
        `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(id)}` +
          "?fields=id,name,parents&supportsAllDrives=true",
        { headers: { Authorization: `Bearer ${accessToken}` } },
      );
      const body = (await response.text()).slice(0, 300);
      return { id, status: response.status, ok: response.ok, detail: body };
    } catch (error) {
      return {
        id,
        status: 0,
        ok: false,
        detail: error instanceof Error ? error.message : String(error),
      };
    }
  };
  const [folder, invitation] = await Promise.all([
    check(folderId),
    check(invitationFileId),
  ]);
  return { folder, invitation };
}

function summarizeVisibilityProbe(probe: DriveVisibilityProbe): string {
  const one = (entry: DriveVisibilityEntry): string =>
    entry.status ? String(entry.status) : "ERR";
  return `folder ${one(probe.folder)} / invitation ${one(probe.invitation)}`;
}

async function refreshCachedState(): Promise<SharedSyncState | null> {
  cachedState = await loadSharedSyncState();
  return cachedState;
}

async function getCachedState(): Promise<SharedSyncState | null> {
  return cachedState ?? refreshCachedState();
}

function localProfilePayloadKey(profileKeyValue: string): string {
  return `${LOCAL_PROFILE_PAYLOAD_PREFIX}${profileKeyValue}`;
}

async function saveLocalProfilePayload(
  profileKeyValue: string,
  payload: SharedSyncPayloadV1,
): Promise<void> {
  await idbSet(localProfilePayloadKey(profileKeyValue), payload);
}

async function loadLocalProfilePayload(
  profileKeyValue: string,
): Promise<SharedSyncPayloadV1> {
  return (
    (await idbGet<SharedSyncPayloadV1>(localProfilePayloadKey(profileKeyValue))) ??
    createEmptySharedSyncPayload()
  );
}

function newLocalProfile(displayName: string): { key: string; profile: ProfileRecord } {
  const id = crypto.randomUUID();
  const ownerEmail = `local-${id}`;
  const datasetId = "profile";
  const now = new Date().toISOString();
  return {
    key: profileKey(ownerEmail, datasetId),
    profile: {
      datasetId,
      ownerEmail,
      folderName: "",
      displayName: displayName.trim() || "My data",
      displayNameUpdatedAt: now,
      role: "owner",
      trustedOwnerKeyId: "",
      syncMode: "local",
    },
  };
}

export async function ensureProfileState(
  rpId: string,
  local: SharedSyncPayloadV1,
): Promise<SharedSyncState> {
  const existing = await getCachedState();
  if (existing) return existing;
  const created = newLocalProfile("My data");
  const state: SharedSyncState = {
    schemaVersion: 1,
    rpId,
    ownerEmail: created.profile.ownerEmail,
    activeProfileKey: created.key,
    profiles: [created.profile],
  };
  cachedState = state;
  await Promise.all([
    saveSharedSyncState(state),
    saveLocalProfilePayload(created.key, local),
  ]);
  return state;
}

function profileForActive(state: SharedSyncState): ProfileRecord {
  const profile = findProfile(state, state.activeProfileKey);
  if (!profile) throw new Error("The active encrypted sync profile is missing.");
  return profile;
}

function profileWithPayloadMeta(
  profile: ProfileRecord,
  payload: SharedSyncPayloadV1,
): ProfileRecord {
  const meta = payload.profileMeta;
  if (!meta) return profile;
  return {
    ...profile,
    avatarWebp: meta.avatarWebp,
    avatarUpdatedAt: meta.updatedAt,
    ...(meta.displayName === undefined ? {} : { displayName: meta.displayName }),
    ...(meta.displayNameUpdatedAt === undefined
      ? {}
      : { displayNameUpdatedAt: meta.displayNameUpdatedAt }),
  };
}

async function recoverAdditionalOwnedProfiles(
  config: SharedSyncConfig,
  initialState: SharedSyncState,
  listedDatasets: ReadonlyArray<{ datasetId: string; fileId: string }>,
  appFolderId: string,
  identityProvider: ReturnType<typeof createSharingIdentityProvider>,
  authorizationProvider: CachingAuthorizationProvider,
  googleIdentity: GoogleWebIdentityProvider,
): Promise<SharedSyncState> {
  let state = initialState;
  const groups = discoverProfileDatasetGroups(listedDatasets).filter(
    (group) => splitBaseRoot(group.baseDatasetId) !== PRIMARY_DATASET_ID,
  );
  for (const group of groups) {
    const key = profileKey(state.ownerEmail, group.baseDatasetId);
    if (findProfile(state, key)) continue;
    const datasetRecords: NonNullable<ProfileRecord["datasetRecords"]> = {};
    for (const [part, fileId] of Object.entries(group.companionFileIds)) {
      datasetRecords[datasetIdForPart(group.baseDatasetId, part as DatasetPart)] = { fileId };
    }
    if (group.controlFileId) {
      datasetRecords[group.controlDatasetId ?? controlDatasetIdFor(group.baseDatasetId)] = {
        fileId: group.controlFileId,
      };
    }
    const profile: ProfileRecord = {
      datasetId: group.baseDatasetId,
      ownerEmail: state.ownerEmail,
      folderName: easyBcSyncFolderName(state.ownerEmail),
      role: "owner",
      trustedOwnerKeyId: (await identityProvider.get()).publicKey.keyId,
      appFolderId,
      fileId: group.planFileId,
      controlDatasetId: group.controlFileId
        ? (group.controlDatasetId ?? controlDatasetIdFor(group.baseDatasetId))
        : undefined,
      controlEnrollment: group.controlFileId ? "enrolled" : "pending",
      ...(Object.keys(group.companionFileIds).length > 0
        ? { datasetGrants: OWNER_DATASET_GRANTS }
        : {}),
      ...(Object.keys(datasetRecords).length > 0 ? { datasetRecords } : {}),
      syncMode: "encrypted",
      needsInitialLoad: true,
    };
    const provisional = upsertProfile(state, profile);
    await saveSharedSyncState(provisional);
    const controller = buildController(
      provisional,
      profile,
      config,
      identityProvider,
      authorizationProvider,
      googleIdentity,
    );
    try {
      await controller.adoptDataset(group.baseDatasetId, { requireOwned: true });
      for (const part of DATASET_PARTS) {
        if (part === "plan" || !group.companionFileIds[part]) continue;
        await controller.adoptDataset(datasetIdForPart(group.baseDatasetId, part), {
          requireOwned: true,
        });
      }
      if (profile.controlDatasetId && group.controlFileId) {
        await controller.adoptDataset(profile.controlDatasetId, { requireOwned: true });
      }
      const loaded = await syncProfileDatasetGroup(
        controller,
        profile,
        createEmptySharedSyncPayload(),
        "load",
      );
      const refreshed = (await loadSharedSyncState()) ?? provisional;
      const recovered = profileWithPayloadMeta({
        ...(findProfile(refreshed, key) ?? profile),
        fileId: loaded.fileId || group.planFileId,
        lastRevisionId: loaded.revisionId,
        lastSyncedAt: new Date().toISOString(),
        needsInitialLoad: false,
      }, loaded.payload);
      state = upsertProfile(refreshed, recovered);
      await saveSharedSyncState(state);
    } catch (error) {
      await appendDeveloperLog("profile-recovery", "owned-profile-skipped", {
        datasetId: group.baseDatasetId,
        error: error instanceof Error ? error.message : String(error),
      }).catch(() => undefined);
      await saveSharedSyncState(state);
    }
  }
  return state;
}

type DiscoveredDatasetAccess = {
  datasetId: string;
  fileId: string;
  ownerEmail: string;
  participant: SharedBackupParticipantV1;
  trustedOwnerKeyId: string;
};

type DiscoveryFolder = {
  appFolderId: string;
  name: string;
};

export type ProfileDiscoveryResult = {
  state: SharedSyncState;
  discoveredProfileKeys: string[];
  scannedFolderCount: number;
  skippedFolderCount: number;
};

/**
 * Rebuild the local profile registry from every EasyBC app folder and dataset
 * file this OAuth client can currently access. A recipient profile is accepted
 * only when the encrypted envelope contains this passkey identity and names a
 * single owner key; Drive metadata supplies the owner's account email used for
 * the stable cross-device profile key.
 *
 * Discovery combines two Drive queries:
 * 1. App-root folders visible to this token (`listAccessibleSyncKitAppFolders`)
 * 2. Dataset files already granted via Picker / create (`listAccessibleSyncKitDatasets`)
 *
 * The second path is required after the Android join hand-off, which grants the
 * dataset files but often not the parent app-root folder — folder listing alone
 * would miss those shares even though the files are already readable.
 *
 * `pickSharedFolder` is the explicit recovery path for a share that has never
 * been opened by this OAuth client. Google requires selecting both the folder
 * and its dataset files; selecting a folder alone does not grant children.
 */
export async function discoverAvailableProfiles(
  config: SharedSyncConfig,
  options: { pickSharedFolder?: boolean } = {},
): Promise<ProfileDiscoveryResult> {
  return serialized(async () => {
    disposeRuntime();
    const previous = await getCachedState();
    if (!previous) throw new Error("No local profile registry is available.");

    const bootstrapProfileId = "__easybc-profile-discovery__";
    if (
      profileDiscoverySession &&
      (profileDiscoverySession.clientId !== config.clientId ||
        profileDiscoverySession.rpId !== config.rpId)
    ) {
      clearSharingIdentitySession();
      profileDiscoverySession.identityProvider.clear();
      profileDiscoverySession = null;
    }
    if (!profileDiscoverySession) {
      const providers = createProviders(config, bootstrapProfileId);
      profileDiscoverySession = {
        clientId: config.clientId,
        rpId: config.rpId,
        providers,
        identityProvider: createSharingIdentityProvider(config.rpId, () =>
          providers.authorizationProvider.authorize(),
        ),
      };
    }
    const bootstrap = profileDiscoverySession.providers;
    const authorization = await authorizeAndRemember(
      bootstrap.authorizationProvider,
      bootstrapProfileId,
    );
    const selfEmail = await fetchGoogleAccountEmail(authorization.accessToken);
    if (
      profileDiscoverySession.accountEmail &&
      profileDiscoverySession.accountEmail.toLowerCase() !== selfEmail.toLowerCase()
    ) {
      clearSharingIdentitySession();
      profileDiscoverySession.identityProvider.clear();
      profileDiscoverySession.identityProvider = createSharingIdentityProvider(config.rpId, () =>
        bootstrap.authorizationProvider.authorize(),
      );
    }
    profileDiscoverySession.accountEmail = selfEmail;
    const identityProvider = profileDiscoverySession.identityProvider;
    const identity = await identityProvider.getOrCreate();
    const drive = new GoogleDriveFileStore();
    const accessible = await listAccessibleSyncKitAppFolders({
      appId: EASY_BC_APP_ID,
      authorization,
      drive,
    });
    const folders = new Map<string, DiscoveryFolder>(
      accessible.map((folder) => [folder.appFolderId, folder]),
    );

    if (options.pickSharedFolder) {
      const pickedFolder = await pickSharedAppFolder(authorization);
      if (pickedFolder) {
        // This is intentionally a separate Picker. A Drive folder selection
        // never grants drive.file access to the files already inside it.
        await pickSharedDatasetFiles(authorization);
        folders.set(pickedFolder.folderId, {
          appFolderId: pickedFolder.folderId,
          name: pickedFolder.name?.trim() || "Shared EasyBC profile",
        });
      }
    }

    // File-level grants (e.g. Android join Picker hand-off) are visible here
    // even when the parent app-root folder is not.
    const accessibleDatasetFiles = await listAccessibleSyncKitDatasets({
      appId: EASY_BC_APP_ID,
      authorization,
      drive,
    });
    const datasetsByParent = new Map<string, AccessibleSyncKitDataset[]>();
    for (const dataset of accessibleDatasetFiles) {
      const appFolderId = dataset.appFolderId?.trim();
      if (!appFolderId) continue;
      if (!folders.has(appFolderId)) {
        folders.set(appFolderId, {
          appFolderId,
          name: "Shared EasyBC profile",
        });
      }
      const grouped = datasetsByParent.get(appFolderId) ?? [];
      grouped.push(dataset);
      datasetsByParent.set(appFolderId, grouped);
    }

    let state: SharedSyncState = { ...previous, ownerEmail: selfEmail };
    cachedState = state;
    await saveSharedSyncState(state);
    const discoveredProfileKeys: string[] = [];

    for (const folder of folders.values()) {
      const listed = datasetsByParent.get(folder.appFolderId) ?? [];
      if (listed.length === 0) continue;
      const transport = new GoogleDriveSharedBackupTransport({
        appId: EASY_BC_APP_ID,
        authorizationProvider: bootstrap.authorizationProvider,
        folderName: folder.name,
        selectedAppFolderId: folder.appFolderId,
      });

      const accessibleDatasets: DiscoveredDatasetAccess[] = [];
      for (const listedDataset of listed) {
        try {
          const stored = await transport.readDataset(listedDataset.fileId);
          const participants = sharedBackupParticipants(stored.envelope);
          const participant = participants.find(
            (entry) => entry.keyId === identity.publicKey.keyId,
          );
          const owners = participants.filter((entry) => entry.role === "owner");
          if (!participant || owners.length !== 1) continue;
          const metadata = await drive.get(listedDataset.fileId, authorization);
          const ownerEmail = metadata.owners?.[0]?.emailAddress?.trim();
          if (!ownerEmail) continue;
          accessibleDatasets.push({
            datasetId: stored.datasetId,
            fileId: stored.fileId,
            ownerEmail,
            participant,
            trustedOwnerKeyId: owners[0].keyId,
          });
        } catch (error) {
          await appendDeveloperLog("profile-discovery", "dataset-skipped", {
            appFolderId: folder.appFolderId,
            datasetId: listedDataset.datasetId,
            error: error instanceof Error ? error.message : String(error),
          }).catch(() => undefined);
        }
      }

      const groups = discoverProfileDatasetGroups(accessibleDatasets, {
        requirePlan: false,
      });
      for (const group of groups) {
        const groupDatasetIds = new Set<string>([
          ...(group.planFileId ? [group.baseDatasetId] : []),
          ...Object.keys(group.companionFileIds).map((part) =>
            datasetIdForPart(group.baseDatasetId, part as DatasetPart),
          ),
          ...(group.controlDatasetId ? [group.controlDatasetId] : []),
        ]);
        const groupAccess = accessibleDatasets.filter((entry) =>
          groupDatasetIds.has(entry.datasetId),
        );
        const dataAccess = groupAccess.filter(
          (entry) => !entry.datasetId.endsWith(CONTROL_DATASET_SUFFIX),
        );
        const first = dataAccess[0];
        if (!first) continue;
        if (
          groupAccess.some(
            (entry) =>
              entry.ownerEmail.toLowerCase() !== first.ownerEmail.toLowerCase() ||
              entry.trustedOwnerKeyId !== first.trustedOwnerKeyId,
          )
        ) {
          await appendDeveloperLog("profile-discovery", "profile-group-skipped", {
            appFolderId: folder.appFolderId,
            datasetId: group.baseDatasetId,
            error: "Dataset files disagreed about the owner identity.",
          }).catch(() => undefined);
          continue;
        }

        const key = profileKey(first.ownerEmail, group.baseDatasetId);
        const existing = findProfile(state, key);
        if (existing && existing.trustedOwnerKeyId !== first.trustedOwnerKeyId) {
          await appendDeveloperLog("profile-discovery", "profile-group-skipped", {
            appFolderId: folder.appFolderId,
            datasetId: group.baseDatasetId,
            error: "The discovered owner key did not match the trusted profile owner.",
          }).catch(() => undefined);
          continue;
        }

        const observedGrants: DatasetGrants = {};
        for (const access of dataAccess) {
          const part = partForDatasetId(group.baseDatasetId, access.datasetId);
          if (part) observedGrants[part] = access.participant.role;
        }
        const split = Object.keys(group.companionFileIds).length > 0;
        const datasetGrants = split || existing?.datasetGrants
          ? { ...(existing?.datasetGrants ?? {}), ...observedGrants }
          : undefined;
        const role = datasetGrants
          ? highestGrantedRole(datasetGrants)
          : first.participant.role;
        const datasetRecords: NonNullable<ProfileRecord["datasetRecords"]> = {
          ...(existing?.datasetRecords ?? {}),
        };
        for (const [part, fileId] of Object.entries(group.companionFileIds)) {
          datasetRecords[datasetIdForPart(group.baseDatasetId, part as DatasetPart)] = {
            ...(datasetRecords[datasetIdForPart(group.baseDatasetId, part as DatasetPart)] ?? {}),
            fileId,
          };
        }
        if (group.controlFileId) {
          datasetRecords[group.controlDatasetId ?? controlDatasetIdFor(group.baseDatasetId)] = {
            ...(datasetRecords[
              group.controlDatasetId ?? controlDatasetIdFor(group.baseDatasetId)
            ] ?? {}),
            fileId: group.controlFileId,
          };
        }
        const profile: ProfileRecord = {
          ...existing,
          datasetId: group.baseDatasetId,
          ownerEmail: first.ownerEmail,
          folderName: easyBcSyncFolderName(first.ownerEmail),
          appFolderId: folder.appFolderId,
          role,
          trustedOwnerKeyId: first.trustedOwnerKeyId,
          ...(group.planFileId ? { fileId: group.planFileId } : {}),
          ...(group.controlFileId
            ? {
                controlDatasetId:
                  group.controlDatasetId ?? controlDatasetIdFor(group.baseDatasetId),
                controlEnrollment: "enrolled" as const,
              }
            : {}),
          ...(datasetGrants ? { datasetGrants } : {}),
          ...(Object.keys(datasetRecords).length > 0 ? { datasetRecords } : {}),
          syncMode: "encrypted",
          needsInitialLoad: existing?.needsInitialLoad ?? true,
        };
        const beforeProfile = existing;
        state = upsertProfile(state, profile);
        cachedState = state;
        await saveSharedSyncState(state);
        await sharedAuthCache.save({
          profileId: key,
          accessToken: authorization.accessToken,
          expiresAt: authorization.expiresAt ?? Date.now() + 3_600_000,
        });
        rememberTokenExpiry(key, authorization.expiresAt);

        const controller = buildController(
          state,
          profile,
          config,
          identityProvider,
          bootstrap.authorizationProvider,
          bootstrap.googleIdentity,
        );
        try {
          for (const access of groupAccess) {
            await controller.adoptDataset(access.datasetId, {
              requireOwned: access.participant.role === "owner",
            });
          }
          const loaded = await syncProfileDatasetGroup(
            controller,
            profile,
            createEmptySharedSyncPayload(),
            "load",
          );
          const refreshed = (await loadSharedSyncState()) ?? state;
          const recovered = profileWithPayloadMeta(
            {
              ...(findProfile(refreshed, key) ?? profile),
              ...(loaded.fileId ? { fileId: loaded.fileId } : {}),
              lastRevisionId: loaded.revisionId,
              lastSyncedAt: new Date().toISOString(),
              needsInitialLoad: false,
            },
            loaded.payload,
          );
          state = upsertProfile(refreshed, recovered);
          cachedState = state;
          await saveSharedSyncState(state);
          if (!beforeProfile) discoveredProfileKeys.push(key);
        } catch (error) {
          const refreshed = (await loadSharedSyncState()) ?? state;
          state = beforeProfile
            ? upsertProfile(refreshed, beforeProfile)
            : {
                ...refreshed,
                profiles: refreshed.profiles.filter(
                  (entry) => profileKey(entry.ownerEmail, entry.datasetId) !== key,
                ),
              };
          cachedState = state;
          await saveSharedSyncState(state);
          await appendDeveloperLog("profile-discovery", "profile-load-skipped", {
            appFolderId: folder.appFolderId,
            datasetId: group.baseDatasetId,
            error: error instanceof Error ? error.message : String(error),
          }).catch(() => undefined);
        }
      }
    }

    disposeRuntime();
    return {
      state,
      discoveredProfileKeys,
      scannedFolderCount: folders.size,
      skippedFolderCount: 0,
    };
  });
}

function createProviders(config: SharedSyncConfig, profileId: string) {
  const inner = new GoogleWebAuthorizationProvider({
    clientId: config.clientId,
    scope: GOOGLE_SCOPES,
  });
  const authorizationProvider = new CachingAuthorizationProvider({
    profileId,
    inner,
    cache: sharedAuthCache,
  });
  const googleIdentity = new GoogleWebIdentityProvider({ clientId: config.clientId });
  return { authorizationProvider, googleIdentity, inner };
}

async function authorizeAndRemember(
  provider: CachingAuthorizationProvider,
  profileId: string,
) {
  const authorization = await provider.authorize();
  rememberTokenExpiry(profileId, authorization.expiresAt);
  return authorization;
}

function createPollingAuthorizationProvider(
  provider: CachingAuthorizationProvider,
  profileId: string,
): AuthorizationProvider {
  return {
    authorize: async () => {
      const cached = await provider.authorizeFromCache();
      if (cached) {
        rememberTokenExpiry(profileId, cached.expiresAt);
        return cached;
      }
      const authorization = await provider.authorize();
      rememberTokenExpiry(profileId, authorization.expiresAt);
      return authorization;
    },
    clear: () => provider.clear(),
  };
}

function buildTransport(
  state: SharedSyncState,
  profile: ProfileRecord,
  authorizationProvider: AuthorizationProvider,
): GoogleDriveSharedBackupTransport {
  const selectedAppFolderId = selectedAppFolderIdForProfile(state, profile);
  return new GoogleDriveSharedBackupTransport({
    appId: EASY_BC_APP_ID,
    authorizationProvider,
    folderName: profile.folderName,
    ...(selectedAppFolderId ? { selectedAppFolderId } : {}),
  });
}

function buildController(
  state: SharedSyncState,
  profile: ProfileRecord,
  config: SharedSyncConfig,
  identityProvider: ReturnType<typeof createSharingIdentityProvider>,
  authorizationProvider: CachingAuthorizationProvider,
  googleIdentity: GoogleWebIdentityProvider,
): SharedBackupController<SharedSyncPayloadV1> {
  const scopeKey = profileKey(profile.ownerEmail, profile.datasetId);
  // Registry writes can happen several times inside one multi-file operation;
  // always read the last persisted record so companion/control writes compose
  // instead of overwriting one another from the coordinator's cached snapshot.
  const registry = new ProfileScopedSharedBackupRegistry(loadSharedSyncState, scopeKey);
  return createSharedBackupController({
    appId: EASY_BC_APP_ID,
    codec: easyBcSharedCodec,
    codecForDataset: (datasetId) =>
      datasetId === profile.controlDatasetId ? sharingControlCodec : undefined,
    identity: () => identityProvider.getOrCreate(),
    transport: buildTransport(state, profile, authorizationProvider),
    registry,
    // Prefer account binding when present (web recipients). Android join
    // responses may omit it until Credential Manager binding is ported.
    requireAccountBinding: false,
    createAccountBinding: (context) =>
      createBackendlessSharingAccountBinding(context, {
        credential: () => identityProvider.accountBindingCredential(),
        requestGoogleIdToken: (nonce) => googleIdentity.requestIdToken(nonce),
        rpId: config.rpId,
      }),
    verifyAccountBinding: (binding, context) =>
      verifySharingAccountBindingV1(binding, context, {
        googleAudience: config.googleAudience,
        rpId: config.rpId,
        allowedOrigins: config.allowedOrigins,
      }),
    resolveFork: async () => "merge",
  });
}

function buildControlDataset(
  state: SharedSyncState,
  profile: ProfileRecord,
  identityProvider: ReturnType<typeof createSharingIdentityProvider>,
  authorizationProvider: CachingAuthorizationProvider,
): SharingControlDataset {
  const datasetId = profile.controlDatasetId;
  if (!datasetId) throw new Error("This profile has no sharing control dataset.");
  // The control profileId must survive hard-cutover rebases (the profile's
  // base dataset id changes generation, the control dataset never does), so
  // it derives from the control id's base — identical to the profile key at
  // creation time, stable forever after.
  const controlProfileId = profileKey(
    profile.ownerEmail,
    datasetId.endsWith(CONTROL_DATASET_SUFFIX)
      ? datasetId.slice(0, -CONTROL_DATASET_SUFFIX.length)
      : profile.datasetId,
  );
  return createSharingControlDataset({
    controller: buildControlController(
      state,
      profile,
      identityProvider,
      authorizationProvider,
    ),
    datasetId,
    profileId: controlProfileId,
    identity: () => identityProvider.getOrCreate(),
  });
}

function buildControlController(
  state: SharedSyncState,
  profile: ProfileRecord,
  identityProvider: ReturnType<typeof createSharingIdentityProvider>,
  authorizationProvider: CachingAuthorizationProvider,
  codec: SharedBackupControllerCodec<SharingControlStateV1> = sharingControlCodec,
): SharedBackupController<SharingControlStateV1> {
  const scopeKey = profileKey(profile.ownerEmail, profile.datasetId);
  return createSharedBackupController<SharingControlStateV1>({
    appId: EASY_BC_APP_ID,
    codec,
    identity: () => identityProvider.getOrCreate(),
    transport: buildTransport(state, profile, authorizationProvider),
    registry: new ProfileScopedSharedBackupRegistry(loadSharedSyncState, scopeKey),
    resolveFork: async () => "merge",
  });
}

async function readControlWithLegacyRepair(
  state: SharedSyncState,
  profile: ProfileRecord,
  identityProvider: ReturnType<typeof createSharingIdentityProvider>,
  authorizationProvider: CachingAuthorizationProvider,
) {
  const control = buildControlDataset(state, profile, identityProvider, authorizationProvider);
  try {
    return await control.read();
  } catch (original) {
    if (profile.role !== "owner" || !profile.controlDatasetId) throw original;
    const identity = await identityProvider.getOrCreate();
    const loaded = await buildControlController(
      state,
      profile,
      identityProvider,
      authorizationProvider,
    ).loadDataset(profile.controlDatasetId);
    const repair = await repairLegacyControlSignature(loaded.value, identity).catch(() => null);
    if (!repair) throw original;
    await buildControlController(
      state,
      profile,
      identityProvider,
      authorizationProvider,
      createLegacyControlRepairCodec(repair),
    ).syncDataset(profile.controlDatasetId, repair.state);
    void appendDeveloperLog("migration", "control-signature-repaired", {
      eventId: repair.eventId,
      repair: "rc15-target-order",
    });
    const refreshed = (await refreshCachedState()) ?? state;
    const current = findProfile(
      refreshed,
      profileKey(profile.ownerEmail, profile.datasetId),
    ) ?? profile;
    return buildControlDataset(
      refreshed,
      current,
      identityProvider,
      authorizationProvider,
    ).read();
  }
}

function createRuntimeForProfile(
  config: SharedSyncConfig,
  state: SharedSyncState,
  profileKeyValue: string,
  local: SharedSyncPayloadV1,
): Runtime {
  const profile = findProfile(state, profileKeyValue);
  if (!profile) throw new Error("The encrypted sync profile is missing.");
  const { authorizationProvider, googleIdentity } = createProviders(
    config,
    profileKeyValue,
  );
  const identityProvider = createSharingIdentityProvider(config.rpId, () =>
    authorizationProvider.authorize(),
  );
  return {
    config,
    state,
    local,
    identityProvider,
    authorizationProvider,
    googleIdentity,
    controller: buildController(
      state,
      profile,
      config,
      identityProvider,
      authorizationProvider,
      googleIdentity,
    ),
  };
}

async function ensureRuntime(
  config: SharedSyncConfig,
  local: SharedSyncPayloadV1,
): Promise<Runtime> {
  const state = await getCachedState();
  if (!state) throw new Error("Encrypted sync is not set up on this device.");
  if (
    runtime &&
    runtime.config.clientId === config.clientId &&
    runtime.state.activeProfileKey === state.activeProfileKey
  ) {
    runtime.local = local;
    runtime.state = state;
    return runtime;
  }
  disposeRuntime();
  runtime = createRuntimeForProfile(config, state, state.activeProfileKey, local);
  return runtime;
}

export function disposeRuntime(): void {
  runtime?.identityProvider.clear();
  runtime = null;
}

/** Clears every in-memory decrypted sharing identity before browser-data erasure. */
export function disposeSensitiveSyncSession(): void {
  disposeRuntime();
  profileDiscoverySession?.identityProvider.clear();
  profileDiscoverySession = null;
  clearSharingIdentitySession();
}

/* ---------- Multi-file dataset groups (docs/sync-kit-multi-file-datasets.md) ---------- */

type EasyBcController = ReturnType<typeof buildController>;

export const OWNER_DATASET_GRANTS: DatasetGrants = {
  plan: "owner",
  cycle: "owner",
  intimacy: "owner",
  sensitive: "owner",
};

/** Every dataset file id this device knows for the profile (base first). */
export function profileDatasetIds(profile: ProfileRecord): string[] {
  if (!isSplitProfile(profile)) return [profile.datasetId];
  return grantedParts(profile).map((part) => datasetIdForPart(profile.datasetId, part));
}

function profileDatasetIdsIncludingControl(profile: ProfileRecord): string[] {
  return [
    ...profileDatasetIds(profile),
    ...(profile.controlDatasetId && profile.datasetRecords?.[profile.controlDatasetId]?.fileId
      ? [profile.controlDatasetId]
      : []),
  ];
}

/**
 * Sync or load every dataset file this device is granted, and reassemble the
 * app payload from the parts. Legacy single-file profiles pass straight
 * through to the old behavior. For split profiles, read-only parts are
 * loaded (never published), and locally edited sections without a grant are
 * simply never projected out — partial access is structural, not advisory.
 */
async function syncProfileDatasetGroup(
  controller: EasyBcController,
  profile: ProfileRecord,
  local: SharedSyncPayloadV1,
  mode: "sync" | "load",
): Promise<{ payload: SharedSyncPayloadV1; fileId: string; revisionId: string }> {
  if (!isSplitProfile(profile)) {
    const upgraded = await maybeAdoptSplitLayout(controller, profile);
    if (upgraded) {
      profile = upgraded;
    } else {
      const result =
        mode === "load"
          ? await controller.loadDataset(profile.datasetId)
          : await controller.syncDataset(profile.datasetId, local);
      return {
        payload: result.value as SharedSyncPayloadV1,
        fileId: result.fileId,
        revisionId: result.revisionId,
      };
    }
  }
  const values: Partial<Record<DatasetPart, SharedSyncPayloadV1>> = {};
  // Prefer the base (plan) file's head for the profile record; a partial
  // grant without the plan part falls back to the first granted file so the
  // result always carries a revision id.
  let baseInfo: { fileId?: string; revisionId?: string } = {};
  for (const part of grantedParts(profile)) {
    const datasetId = datasetIdForPart(profile.datasetId, part);
    const writable = mode === "sync" && partIsWritable(profile, part);
    const result = writable
      ? await controller.syncDataset(datasetId, projectDatasetPart(local, part))
      : await controller.loadDataset(datasetId);
    values[part] = result.value as SharedSyncPayloadV1;
    if (part === "plan" || baseInfo.revisionId === undefined) {
      baseInfo = { fileId: result.fileId, revisionId: result.revisionId };
    }
  }
  if (baseInfo.fileId === undefined || baseInfo.revisionId === undefined) {
    throw new Error("No dataset in this profile is accessible from this device.");
  }
  return {
    payload: combineDatasetParts(values),
    fileId: baseInfo.fileId,
    revisionId: baseInfo.revisionId,
  };
}

/**
 * Detect that another of this owner's devices upgraded a legacy profile to
 * the split layout (companion dataset files exist in the Drive folder) and
 * adopt it here, so this device never publishes a full payload into what is
 * now the plan file. Checked once per session per profile; the explicit
 * upgrade path sets datasetGrants directly and never reaches this. Only the
 * owner runs detection — participants can't list or adopt files they were
 * never granted.
 */
const splitLayoutChecked = new Set<string>();
async function maybeAdoptSplitLayout(
  controller: EasyBcController,
  profile: ProfileRecord,
): Promise<ProfileRecord | null> {
  if (
    isSplitProfile(profile) ||
    !isEncryptedProfile(profile) ||
    profile.role !== "owner"
  ) {
    return null;
  }
  const memoKey = profileKey(profile.ownerEmail, profile.datasetId);
  if (splitLayoutChecked.has(memoKey)) return null;
  splitLayoutChecked.add(memoKey);
  const files = await controller.listDatasets();
  const companionParts = DATASET_PARTS.filter(
    (part) =>
      part !== "plan" &&
      files.some((file) => file.datasetId === datasetIdForPart(profile.datasetId, part)),
  );
  if (companionParts.length === 0) return null;
  for (const part of companionParts) {
    await controller.adoptDataset(datasetIdForPart(profile.datasetId, part), {
      requireOwned: true,
    });
  }
  const refreshed = (await refreshCachedState()) ?? cachedState;
  if (!refreshed) return null;
  const upgraded: ProfileRecord = {
    ...(findProfile(refreshed, memoKey) ?? profile),
    datasetGrants: { ...OWNER_DATASET_GRANTS },
  };
  const nextState = upsertProfile(refreshed, upgraded);
  cachedState = nextState;
  await saveSharedSyncState(nextState);
  return upgraded;
}

/**
 * Create the four dataset files of a split profile. The profile record must
 * already exist in the saved state (the controller persists companion
 * registry records through it) and the controller must be scoped to this
 * profile's key.
 */
async function createProfileDatasetGroup(
  controller: EasyBcController,
  baseDatasetId: string,
  payload: SharedSyncPayloadV1,
): Promise<{ fileId: string; revisionId: string }> {
  const created = await controller.createDataset(
    baseDatasetId,
    projectDatasetPart(payload, "plan"),
  );
  for (const part of DATASET_PARTS) {
    if (part === "plan") continue;
    await controller.createDataset(
      datasetIdForPart(baseDatasetId, part),
      projectDatasetPart(payload, part),
    );
  }
  return { fileId: created.fileId, revisionId: created.revisionId };
}

export async function resetSharedSync(
  config: SharedSyncConfig,
  local: SharedSyncPayloadV1,
): Promise<{ state: SharedSyncState; result: SharedSyncRunResult }> {
  return serialized(async () => {
    disposeRuntime();
    const state = await getCachedState();
    if (!state) throw new Error("No profile registry is available on this device.");
    const profile = profileForActive(state);
    if (isLocalProfile(profile) || profile.role !== "owner") {
      throw new Error("Only an owned encrypted profile can be reset.");
    }
    const { authorizationProvider, googleIdentity } = createProviders(
      config,
      state.activeProfileKey,
    );
    const identityProvider = createSharingIdentityProvider(config.rpId, () =>
      authorizationProvider.authorize(),
    );
    const controller = buildController(
      state,
      profile,
      config,
      identityProvider,
      authorizationProvider,
      googleIdentity,
    );
    try {
      // Companions first; deleting the base dataset removes the profile
      // record itself, so it must go last.
      for (const datasetId of [...profileDatasetIdsIncludingControl(profile)].reverse()) {
        await controller.deleteDataset(datasetId);
      }
      // Restore the profile record before recreating: companion registry
      // writes during group creation need a profile to land on. Reset always
      // recreates in the multi-file layout.
      const restored: ProfileRecord = {
        ...profile,
        fileId: undefined,
        lastRevisionId: undefined,
        seenRevisionIds: undefined,
        datasetRecords: undefined,
        datasetGrants: OWNER_DATASET_GRANTS,
        participantPermissionIds: {},
        participantEmails: {},
        needsInitialLoad: false,
        controlDatasetId: profile.controlDatasetId ?? controlDatasetIdFor(profile.datasetId),
        controlEnrollment: "pending",
      };
      const afterDelete = upsertProfile((await refreshCachedState()) ?? state, restored);
      cachedState = afterDelete;
      await saveSharedSyncState(afterDelete);
      const created = await createProfileDatasetGroup(controller, profile.datasetId, local);
      const afterDataCreate = (await refreshCachedState()) ?? afterDelete;
      const controlProfile = findProfile(afterDataCreate, state.activeProfileKey) ?? restored;
      if (CONTROL_DATASETS_WIRED) {
        await buildControlDataset(
          afterDataCreate,
          controlProfile,
          identityProvider,
          authorizationProvider,
        ).create({ email: state.ownerEmail });
      }
      const syncedAt = new Date().toISOString();
      const refreshed = (await refreshCachedState()) ?? afterDelete;
      const nextProfile: ProfileRecord = {
        ...(findProfile(refreshed, state.activeProfileKey) ?? restored),
        fileId: created.fileId,
        lastRevisionId: created.revisionId,
        lastSyncedAt: syncedAt,
        controlEnrollment: CONTROL_DATASETS_WIRED ? "enrolled" : "pending",
      };
      const nextState = upsertProfile(refreshed, nextProfile);
      cachedState = nextState;
      await saveSharedSyncState(nextState);
      return {
        state: nextState,
        result: {
          payload: local,
          syncedAt,
          revisionId: created.revisionId,
          profileKey: state.activeProfileKey,
        },
      };
    } finally {
      identityProvider.clear();
      disposeRuntime();
    }
  });
}

export async function setupSharedSync(
  config: SharedSyncConfig,
  local: SharedSyncPayloadV1,
): Promise<{ state: SharedSyncState; result: SharedSyncRunResult }> {
  return serialized(async () => {
    disposeRuntime();
    const previousState = await loadSharedSyncState();
    const bootstrapProfileId = "__easybc-bootstrap__";
    const bootstrap = createProviders(config, bootstrapProfileId);
    const authorization = await authorizeAndRemember(
      bootstrap.authorizationProvider,
      bootstrapProfileId,
    );
    const ownerEmail = await fetchGoogleAccountEmail(authorization.accessToken);
    const activeProfileId = profileKey(ownerEmail, PRIMARY_DATASET_ID);
    const { authorizationProvider, googleIdentity } =
      activeProfileId === bootstrapProfileId
        ? bootstrap
        : createProviders(config, activeProfileId);
    if (activeProfileId !== bootstrapProfileId) {
      await sharedAuthCache.save({
        profileId: activeProfileId,
        accessToken: authorization.accessToken,
        expiresAt: authorization.expiresAt ?? Date.now() + 3_600_000,
      });
      rememberTokenExpiry(activeProfileId, authorization.expiresAt);
    }
    const folderName = easyBcSyncFolderName(ownerEmail);
    const identityProvider = createSharingIdentityProvider(config.rpId, () =>
      authorizationProvider.authorize(),
    );
    await identityProvider.getOrCreate();
    const identity = await identityProvider.get();
    const activeBeforeSetup = previousState
      ? findProfile(previousState, previousState.activeProfileKey)
      : undefined;
    const preservedLocalProfiles =
      previousState?.profiles.filter(
        (profile) =>
          isLocalProfile(profile) &&
          profileKey(profile.ownerEmail, profile.datasetId) !== previousState.activeProfileKey,
      ) ?? [];
    let connectedProfile: ProfileRecord = {
      datasetId: PRIMARY_DATASET_ID,
      ownerEmail,
      folderName,
      ...(activeBeforeSetup?.displayName
        ? { displayName: activeBeforeSetup.displayName }
        : {}),
      ...(activeBeforeSetup?.displayNameUpdatedAt
        ? { displayNameUpdatedAt: activeBeforeSetup.displayNameUpdatedAt }
        : {}),
      role: "owner",
      trustedOwnerKeyId: identity.publicKey.keyId,
      controlDatasetId: controlDatasetIdFor(PRIMARY_DATASET_ID),
      controlEnrollment: "pending",
      syncMode: "encrypted",
    };
    let provisional: SharedSyncState = {
      schemaVersion: 1,
      rpId: config.rpId,
      ownerEmail,
      activeProfileKey: profileKey(ownerEmail, PRIMARY_DATASET_ID),
      profiles: [...preservedLocalProfiles, connectedProfile],
    };
    try {
      cachedState = provisional;
      await saveSharedSyncState(provisional);
      let controller = buildController(
        provisional,
        connectedProfile,
        config,
        identityProvider,
        authorizationProvider,
        googleIdentity,
      );
      const storage = await controller.ensureStorage();
      // Adopt an existing profile dataset (interrupted setup, reinstall,
      // reconnecting device) instead of failing with "already exists";
      // legacy primary profiles sort first, followed by opaque-id profiles.
      // Create only when the folder has none. A fresh folder gets the
      // multi-file dataset group so per-dataset sharing works from day one;
      // adopted datasets keep their legacy single-file layout.
      const listedDatasets = await controller.listDatasets();
      const existingAnchorGroup = discoverProfileDatasetGroups(listedDatasets)[0];
      // A completed hard cutover may leave only primary.gN data files while
      // retaining primary.control; newer installations may have only opaque
      // profile ids. Re-scope setup to the first existing group so a fresh
      // device never creates an unrelated replacement profile.
      if (
        !listedDatasets.some((dataset) => dataset.datasetId === PRIMARY_DATASET_ID) &&
        existingAnchorGroup
      ) {
        connectedProfile = {
          ...connectedProfile,
          datasetId: existingAnchorGroup.baseDatasetId,
          fileId: existingAnchorGroup.planFileId,
          controlDatasetId:
            existingAnchorGroup.controlDatasetId ??
            controlDatasetIdFor(existingAnchorGroup.baseDatasetId),
        };
        provisional = {
          ...provisional,
          activeProfileKey: profileKey(ownerEmail, connectedProfile.datasetId),
          profiles: [...preservedLocalProfiles, connectedProfile],
        };
        await sharedAuthCache.save({
          profileId: provisional.activeProfileKey,
          accessToken: authorization.accessToken,
          expiresAt: authorization.expiresAt ?? Date.now() + 3_600_000,
        });
        rememberTokenExpiry(provisional.activeProfileKey, authorization.expiresAt);
        cachedState = provisional;
        await saveSharedSyncState(provisional);
        controller = buildController(
          provisional,
          connectedProfile,
          config,
          identityProvider,
          authorizationProvider,
          googleIdentity,
        );
      } else if (
        !listedDatasets.some((dataset) => dataset.datasetId === PRIMARY_DATASET_ID)
      ) {
        // New profiles use opaque identities. `primary` remains readable as a
        // legacy anchor, but a mutable/display label is never a storage key.
        const newDatasetId = newOwnedDatasetId([
          ...provisional.profiles.map((profile) => profile.datasetId),
          ...listedDatasets.map((dataset) => dataset.datasetId),
        ]);
        connectedProfile = {
          ...connectedProfile,
          datasetId: newDatasetId,
          controlDatasetId: controlDatasetIdFor(newDatasetId),
        };
        provisional = {
          ...provisional,
          activeProfileKey: profileKey(ownerEmail, connectedProfile.datasetId),
          profiles: [...preservedLocalProfiles, connectedProfile],
        };
        cachedState = provisional;
        await saveSharedSyncState(provisional);
        controller = buildController(
          provisional,
          connectedProfile,
          config,
          identityProvider,
          authorizationProvider,
          googleIdentity,
        );
      }
      const connectedProfileKey = profileKey(ownerEmail, connectedProfile.datasetId);
      const existing = listedDatasets.find(
        (dataset) => dataset.datasetId === connectedProfile.datasetId,
      );
      const companionIds = listedDatasets
        .map((dataset) => dataset.datasetId)
        .filter((datasetId) => {
          const part = partForDatasetId(connectedProfile.datasetId, datasetId);
          return part !== null && part !== "plan";
        });
      const existingControl = listedDatasets.find(
        (dataset) => dataset.datasetId === connectedProfile.controlDatasetId,
      );
      const split = !existing || companionIds.length > 0;
      let created: { fileId: string; revisionId: string };
      let createdPayload: SharedSyncPayloadV1;
      if (existing) {
        try {
          await controller.adoptDataset(connectedProfile.datasetId, { requireOwned: true });
          for (const companionId of companionIds) {
            await controller.adoptDataset(companionId, { requireOwned: true });
          }
          if (existingControl) {
            await controller.adoptDataset(existingControl.datasetId, { requireOwned: true });
          }
        } catch (error) {
          throw new Error(
            "An encrypted sync dataset already exists in your Drive folder, " +
              "but this device cannot unlock it. Use Reset encrypted sync to " +
              "replace it with this device's data.",
            { cause: error },
          );
        }
        if (companionIds.length > 0) {
          const refreshed = (await loadSharedSyncState()) ?? provisional;
          const recoveredPrimary = {
            ...(findProfile(refreshed, connectedProfileKey) ?? connectedProfile),
            datasetGrants: OWNER_DATASET_GRANTS,
          };
          const synced = await syncProfileDatasetGroup(
            controller,
            recoveredPrimary,
            local,
            "sync",
          );
          created = { fileId: synced.fileId, revisionId: synced.revisionId };
          createdPayload = synced.payload;
        } else {
          const synced = await controller.syncDataset(connectedProfile.datasetId, local);
          created = { fileId: synced.fileId, revisionId: synced.revisionId };
          createdPayload = synced.value as SharedSyncPayloadV1;
        }
      } else {
        created = await createProfileDatasetGroup(controller, connectedProfile.datasetId, local);
        const afterDataCreate = (await refreshCachedState()) ?? provisional;
        const controlProfile = findProfile(afterDataCreate, connectedProfileKey) ?? connectedProfile;
        if (CONTROL_DATASETS_WIRED) {
          await buildControlDataset(
            afterDataCreate,
            controlProfile,
            identityProvider,
            authorizationProvider,
          ).create({ email: ownerEmail });
        }
        createdPayload = local;
      }
      const encryptedState = createInitialSharedSyncState({
        rpId: config.rpId,
        ownerEmail,
        folderName,
        trustedOwnerKeyId: identity.publicKey.keyId,
        datasetId: connectedProfile.datasetId,
        appFolderId: storage.appFolderId,
        fileId: created.fileId,
        lastRevisionId: created.revisionId,
      });
      // Companion dataset registry records were persisted through the saved
      // provisional state during group creation — carry them forward.
      const persisted = await loadSharedSyncState();
      const persistedProfile = persisted
        ? findProfile(persisted, encryptedState.activeProfileKey)
        : undefined;
      const nextState: SharedSyncState = {
        ...encryptedState,
        profiles: [
          ...preservedLocalProfiles,
          profileWithPayloadMeta({
            ...encryptedState.profiles[0],
            ...(connectedProfile.displayName
              ? { displayName: connectedProfile.displayName }
              : {}),
            ...(split ? { datasetGrants: OWNER_DATASET_GRANTS } : {}),
            ...(persistedProfile?.datasetRecords
              ? { datasetRecords: persistedProfile.datasetRecords }
              : {}),
            controlDatasetId: connectedProfile.controlDatasetId,
            controlEnrollment: split && CONTROL_DATASETS_WIRED ? "enrolled" : "pending",
            syncMode: "encrypted",
          }, createdPayload),
        ],
      };
      await saveSharedSyncState(nextState);
      const recoveredState = await recoverAdditionalOwnedProfiles(
        config,
        nextState,
        listedDatasets,
        storage.appFolderId,
        identityProvider,
        authorizationProvider,
        googleIdentity,
      );
      cachedState = recoveredState;
      if (activeBeforeSetup && isLocalProfile(activeBeforeSetup)) {
        await idbDelete(localProfilePayloadKey(previousState!.activeProfileKey));
      }
      runtime = {
        config,
        state: recoveredState,
        local: createdPayload,
        identityProvider,
        authorizationProvider,
        googleIdentity,
        controller,
      };
      return {
        state: recoveredState,
        result: {
          payload: createdPayload,
          syncedAt: new Date().toISOString(),
          revisionId: created.revisionId,
          profileKey: recoveredState.activeProfileKey,
        },
      };
    } catch (error) {
      if (previousState) {
        cachedState = previousState;
        await saveSharedSyncState(previousState);
      } else {
        cachedState = null;
        await forgetSharedSyncState();
      }
      disposeRuntime();
      throw error;
    }
  });
}

export async function syncActiveDataset(
  config: SharedSyncConfig,
  local: SharedSyncPayloadV1,
): Promise<SharedSyncRunResult> {
  return serialized(() => syncActiveDatasetInternal(config, local, true));
}

async function syncActiveDatasetInternal(
  config: SharedSyncConfig,
  local: SharedSyncPayloadV1,
  allowMigrationReconcile: boolean,
): Promise<SharedSyncRunResult> {
  {
    const active = await ensureRuntime(config, local);
    let profile = profileForActive(active.state);
    if (isLocalProfile(profile)) {
      throw new Error("This profile is local only. Connect encrypted sync before syncing it.");
    }
    let freeze = false;
    if (allowMigrationReconcile) {
      const reconciled = await reconcileOpenMigration(active, profile);
      if (
        profileKey(reconciled.profile.ownerEmail, reconciled.profile.datasetId) !==
        profileKey(profile.ownerEmail, profile.datasetId)
      ) {
        // The record was rebased onto a new generation; the runtime was
        // disposed, so rebuild against the fresh state and sync once more.
        return syncActiveDatasetInternal(config, local, false);
      }
      profile = reconciled.profile;
      freeze = reconciled.freeze;
    }
    const result = await syncProfileDatasetGroup(
      active.controller,
      profile,
      local,
      freeze || shouldLoadRemoteBeforePublish(profile) ? "load" : "sync",
    );
    // Pick up companion registry records the controller persisted mid-sync.
    const refreshed = (await refreshCachedState()) ?? active.state;
    const updatedProfile: ProfileRecord = profileWithPayloadMeta({
      ...(findProfile(refreshed, active.state.activeProfileKey) ?? profile),
      ...(result.fileId ? { fileId: result.fileId } : {}),
      ...(result.revisionId ? { lastRevisionId: result.revisionId } : {}),
      lastSyncedAt: new Date().toISOString(),
      needsInitialLoad: false,
    }, result.payload);
    let nextState = upsertProfile(refreshed, updatedProfile);
    cachedState = nextState;
    await saveSharedSyncState(nextState);
    // Existing installations skip setup, so setup-only discovery never sees
    // profiles created on another platform. Scan the owner's Drive folder on
    // every normal sync and adopt any valid profile datasets that are absent
    // from this device's local registry.
    if (isOwnedProfile(nextState, updatedProfile)) {
      const storage = updatedProfile.appFolderId
        ? { appFolderId: updatedProfile.appFolderId }
        : await active.controller.ensureStorage();
      const listedDatasets = await active.controller.listDatasets();
      nextState = await recoverAdditionalOwnedProfiles(
        config,
        nextState,
        listedDatasets,
        storage.appFolderId,
        active.identityProvider,
        active.authorizationProvider,
        active.googleIdentity,
      );
      cachedState = nextState;
    }
    active.state = nextState;
    return {
      payload: result.payload,
      syncedAt: updatedProfile.lastSyncedAt ?? new Date().toISOString(),
      revisionId: result.revisionId,
      profileKey: nextState.activeProfileKey,
    };
  }
}

export async function loadActiveProfileDataset(
  config: SharedSyncConfig,
): Promise<SharedSyncRunResult> {
  return serialized(async () => {
    const state = await getCachedState();
    if (!state) throw new Error("Encrypted sync is not set up on this device.");
    const profile = profileForActive(state);
    if (isLocalProfile(profile)) {
      throw new Error("This profile is stored only on this device.");
    }
    const active = await ensureRuntime(config, {} as SharedSyncPayloadV1);
    const loaded = await syncProfileDatasetGroup(
      active.controller,
      profile,
      createEmptySharedSyncPayload(),
      "load",
    );
    const refreshed = (await refreshCachedState()) ?? state;
    const updatedProfile: ProfileRecord = profileWithPayloadMeta({
      ...(findProfile(refreshed, state.activeProfileKey) ?? profile),
      ...(loaded.fileId ? { fileId: loaded.fileId } : {}),
      ...(loaded.revisionId ? { lastRevisionId: loaded.revisionId } : {}),
      lastSyncedAt: new Date().toISOString(),
      needsInitialLoad: false,
    }, loaded.payload);
    const nextState = upsertProfile(refreshed, updatedProfile);
    cachedState = nextState;
    await saveSharedSyncState(nextState);
    return {
      payload: loaded.payload,
      syncedAt: updatedProfile.lastSyncedAt ?? new Date().toISOString(),
      revisionId: loaded.revisionId,
      profileKey: nextState.activeProfileKey,
    };
  });
}

export async function setActiveProfileKey(profileKeyValue: string): Promise<SharedSyncState> {
  const state = await getCachedState();
  if (!state) throw new Error("Encrypted sync is not set up on this device.");
  if (!findProfile(state, profileKeyValue)) {
    throw new Error("That encrypted sync profile is not available on this device.");
  }
  const next = { ...state, activeProfileKey: profileKeyValue };
  cachedState = next;
  await saveSharedSyncState(next);
  await clearSharingSyncCheckpoint();
  disposeRuntime();
  return next;
}

export type ManagedProfileResult = {
  state: SharedSyncState;
  payload: SharedSyncPayloadV1;
};

async function preserveCurrentProfile(
  config: SharedSyncConfig | null,
  state: SharedSyncState,
  local: SharedSyncPayloadV1,
): Promise<SharedSyncState> {
  const current = profileForActive(state);
  if (isLocalProfile(current)) {
    await saveLocalProfilePayload(state.activeProfileKey, local);
    return state;
  }
  if (!current.fileId || !canPublishRole(current.role) || current.needsInitialLoad) {
    return state;
  }
  if (!config) {
    throw new Error("Encrypted sync authorization is required before leaving this profile.");
  }
  const currentRuntime = createRuntimeForProfile(
    config,
    state,
    state.activeProfileKey,
    local,
  );
  try {
    await syncProfileDatasetGroup(currentRuntime.controller, current, local, "sync");
    return (await refreshCachedState()) ?? state;
  } finally {
    currentRuntime.identityProvider.clear();
  }
}

export async function switchManagedProfile(
  config: SharedSyncConfig | null,
  profileKeyValue: string,
  local: SharedSyncPayloadV1,
): Promise<ManagedProfileResult> {
  return serialized(async () => {
    disposeRuntime();
    let state = await getCachedState();
    if (!state) throw new Error("No profile registry is available on this device.");
    const requestedTarget = findProfile(state, profileKeyValue);
    if (!requestedTarget) {
      throw new Error("That profile is not available on this device.");
    }
    if (isEncryptedProfile(requestedTarget) && !config) {
      throw new Error("Encrypted sync is not configured in this build.");
    }
    const previousProfileKey = state.activeProfileKey;
    state = await preserveCurrentProfile(config, state, local);
    state = { ...state, activeProfileKey: profileKeyValue };
    cachedState = state;
    await saveSharedSyncState(state);
    await clearSharingSyncCheckpoint();

    const target = findProfile(state, profileKeyValue)!;
    if (isLocalProfile(target)) {
      disposeRuntime();
      return {
        state,
        payload: await loadLocalProfilePayload(profileKeyValue),
      };
    }
    const targetRuntime = createRuntimeForProfile(
      config!,
      state,
      profileKeyValue,
      createEmptySharedSyncPayload(),
    );
    try {
      const loaded = await syncProfileDatasetGroup(
        targetRuntime.controller,
        target,
        createEmptySharedSyncPayload(),
        "load",
      );
      const refreshedAfterLoad = (await refreshCachedState()) ?? state;
      const nextProfile: ProfileRecord = profileWithPayloadMeta({
        ...(findProfile(refreshedAfterLoad, profileKeyValue) ?? target),
        ...(loaded.fileId ? { fileId: loaded.fileId } : {}),
        ...(loaded.revisionId ? { lastRevisionId: loaded.revisionId } : {}),
        lastSyncedAt: new Date().toISOString(),
        needsInitialLoad: false,
      }, loaded.payload);
      state = upsertProfile(refreshedAfterLoad, nextProfile);
      cachedState = state;
      await saveSharedSyncState(state);
      return { state, payload: loaded.payload };
    } catch (error) {
      const rollback = { ...state, activeProfileKey: previousProfileKey };
      cachedState = rollback;
      await saveSharedSyncState(rollback);
      throw error;
    } finally {
      targetRuntime.identityProvider.clear();
      disposeRuntime();
    }
  });
}

/**
 * Upgrade the active owned legacy profile to the per-dataset split layout
 * (docs/sync-kit-multi-file-datasets.md). Hard replace, no dual-writing:
 * the freshest merged payload is split into the four dataset files, the
 * three companions are created first (so an interrupted run is resumable
 * by running the upgrade again), and the old single file is deleted only
 * after its content has a new home. Every file gets a fresh key — merely
 * republishing a plan projection into the old file would leave the full
 * legacy payload readable in its revision history once the plan file is
 * later shared.
 *
 * Restricted to profiles nobody else can access. With participants this
 * must run the control-dataset cutover ceremony (announce → Picker adopt →
 * ack → close) instead, which EasyBC has not shipped yet; the UI routes
 * owners through remove access → upgrade → re-invite in the meantime,
 * which lands on the same end state because participants must re-grant the
 * new files via the Picker either way.
 */
export function assertSplitUpgradeAllowed(profile: ProfileRecord): void {
  if (!isEncryptedProfile(profile)) {
    throw new Error(
      "Turn on encrypted sync for this profile first — the split layout lives in your Drive.",
    );
  }
  if (profile.role !== "owner") {
    throw new Error("Only the profile owner can upgrade its sharing layout.");
  }
  if (isSplitProfile(profile)) {
    throw new Error("This profile already uses per-dataset sharing.");
  }
  if (Object.keys(profile.participantEmails ?? {}).length > 0) {
    throw new Error(
      "Remove everyone's access first. Upgrading creates new files with new keys, so the people you share with must be re-invited with per-dataset access afterward.",
    );
  }
}

export async function upgradeActiveProfileToSplit(
  config: SharedSyncConfig,
  local: SharedSyncPayloadV1,
): Promise<ManagedProfileResult> {
  return serialized(async () => {
    const active = await ensureRuntime(config, local);
    const state = active.state;
    const profile = profileForActive(state);
    assertSplitUpgradeAllowed(profile);
    const controller = active.controller;
    const files = await controller.listDatasets();
    const hasBase = files.some((file) => file.datasetId === profile.datasetId);
    // Freshest snapshot: merge local + remote through the normal sync. When
    // the base file is already gone we are resuming an interrupted upgrade
    // and the local store is the source of truth.
    const payload = hasBase
      ? ((await controller.syncDataset(profile.datasetId, local)).value as SharedSyncPayloadV1)
      : local;
    for (const part of DATASET_PARTS) {
      if (part === "plan") continue;
      const datasetId = datasetIdForPart(profile.datasetId, part);
      if (files.some((file) => file.datasetId === datasetId)) continue;
      await controller.createDataset(datasetId, projectDatasetPart(payload, part));
    }
    if (hasBase) await controller.deleteDataset(profile.datasetId);
    const created = await controller.createDataset(
      profile.datasetId,
      projectDatasetPart(payload, "plan"),
    );
    const refreshed = (await refreshCachedState()) ?? state;
    const upgraded: ProfileRecord = {
      ...(findProfile(refreshed, state.activeProfileKey) ?? profile),
      datasetGrants: { ...OWNER_DATASET_GRANTS },
      fileId: created.fileId,
      lastRevisionId: created.revisionId,
      lastSyncedAt: new Date().toISOString(),
    };
    const nextState = upsertProfile(refreshed, upgraded);
    cachedState = nextState;
    await saveSharedSyncState(nextState);
    return { state: nextState, payload };
  });
}

/* ---------- Hard-cutover split migration (docs/sync-kit-multi-file-datasets.md §ceremony) ---------- */

/** Per-participant per-dataset choices the owner makes in the walkthrough. */
export type SplitMigrationGrantChoices = Record<string, DatasetGrants>;

function participantPublicKey(participant: SharedBackupParticipantV1) {
  const { role: _role, accepted: _accepted, ...publicKey } = participant;
  return publicKey;
}

function findOpenMigration(
  verified: Awaited<ReturnType<SharingControlDataset["read"]>> | null,
  sourceDatasetId: string,
) {
  if (!verified) return null;
  for (const migration of verified.migrations.values()) {
    if (verified.closedMigrations.has(migration.migrationId)) continue;
    if (migration.sourceDatasetIds.includes(sourceDatasetId)) return migration;
  }
  return null;
}

/**
 * Replace a profile record whose base dataset id changed generation. The
 * profile key is derived from the dataset id, so the active key and every
 * key-scoped cache moves with it; the control dataset id (and therefore
 * control event continuity) is untouched.
 */
async function rebaseProfileRecord(
  state: SharedSyncState,
  oldKey: string,
  next: ProfileRecord,
): Promise<SharedSyncState> {
  const newKey = profileKey(next.ownerEmail, next.datasetId);
  const nextState: SharedSyncState = {
    ...state,
    activeProfileKey: state.activeProfileKey === oldKey ? newKey : state.activeProfileKey,
    profiles: state.profiles
      .filter((entry) => profileKey(entry.ownerEmail, entry.datasetId) !== oldKey)
      .concat(next),
  };
  cachedState = nextState;
  await Promise.all([
    saveSharedSyncState(nextState),
    sharedAuthCache.delete(oldKey),
    clearSharingSyncCheckpoint(),
  ]);
  disposeRuntime();
  return nextState;
}

/**
 * Owner side, phase 1 (announce). Resumable: every step is an upsert or a
 * skip-if-present, and an already-announced migration is reused instead of
 * re-announced. Ends with this device rebased onto the target generation —
 * the owner never writes the retired source again (the structural freeze).
 */
export async function beginSplitMigration(
  config: SharedSyncConfig,
  local: SharedSyncPayloadV1,
  grantsByKeyId: SplitMigrationGrantChoices,
): Promise<ManagedProfileResult> {
  return serialized(async () => {
    const active = await ensureRuntime(config, local);
    const state = active.state;
    const profile = profileForActive(state);
    if (!isEncryptedProfile(profile)) {
      throw new Error("Turn on encrypted sync for this profile first.");
    }
    if (profile.role !== "owner") {
      throw new Error("Only the profile owner can reorganize its sharing layout.");
    }
    if (isSplitProfile(profile)) {
      throw new Error("This profile already uses per-dataset sharing.");
    }
    const emails = profile.participantEmails ?? {};
    if (Object.keys(emails).length === 0) {
      throw new Error(
        "This profile has no participants — use the direct upgrade instead.",
      );
    }
    const controller = active.controller;

    // Participants come from the source envelope — the cryptographic truth.
    const { participants } = await controller.getDatasetParticipants(profile.datasetId);
    const others = participants.filter((participant) => participant.role !== "owner");
    for (const participant of others) {
      if (!emails[participant.keyId]) {
        throw new Error(
          `No email is known for key ${participant.keyId.slice(0, 10)}… — EasyBC cannot safely update their Drive access.`,
        );
      }
      const grants = grantsByKeyId[participant.keyId];
      if (!grants || !DATASET_PARTS.some((part) => grants[part])) {
        throw new Error(
          "Choose access for every person first. To cut someone off entirely, remove their access before upgrading.",
        );
      }
    }

    // Final publish of the source — the last write before the freeze.
    const synced = await controller.syncDataset(profile.datasetId, local);
    const payload = synced.value as SharedSyncPayloadV1;

    // The control dataset is the coordination channel; make sure it exists
    // and every participant can write to it (their acks are signed events).
    let currentProfile = profile;
    const controlDatasetId =
      profile.controlDatasetId ?? controlDatasetIdFor(profile.datasetId);
    if (currentProfile.controlDatasetId !== controlDatasetId) {
      currentProfile = { ...currentProfile, controlDatasetId };
      const withControl = upsertProfile(state, currentProfile);
      cachedState = withControl;
      await saveSharedSyncState(withControl);
    }
    const control = buildControlDataset(
      (await getCachedState())!,
      currentProfile,
      active.identityProvider,
      active.authorizationProvider,
    );
    const refreshedForControl = (await refreshCachedState())!;
    const controlRecord = findProfile(refreshedForControl, state.activeProfileKey);
    if (!controlRecord?.datasetRecords?.[controlDatasetId]?.fileId) {
      await control.create({ email: state.ownerEmail });
    }
    for (const participant of others) {
      await controller.addDatasetParticipant({
        datasetId: controlDatasetId,
        participant: { publicKey: participantPublicKey(participant), role: "writer" },
        emailAddress: emails[participant.keyId],
      });
    }

    // Target generation: reuse an already-announced migration's targets, or
    // an interrupted attempt's files, before minting a new generation.
    const verified = await readControlWithLegacyRepair(
      refreshedForControl,
      currentProfile,
      active.identityProvider,
      active.authorizationProvider,
    ).catch(() => null);
    const open = findOpenMigration(verified, profile.datasetId);
    let files = await controller.listDatasets();
    const datasetIds = files.map((file) => file.datasetId);
    const targetBase = open
      ? baseDatasetIdOf(open.targets[0]!.datasetId)
      : newerSplitBaseId(profile.datasetId, datasetIds) ??
        nextSplitBaseId(profile.datasetId, datasetIds);
    for (const part of DATASET_PARTS) {
      const datasetId = datasetIdForPart(targetBase, part);
      if (files.some((file) => file.datasetId === datasetId)) continue;
      await controller.createDataset(datasetId, projectDatasetPart(payload, part));
    }
    files = await controller.listDatasets();
    const targets = DATASET_PARTS.map((part) => {
      const datasetId = datasetIdForPart(targetBase, part);
      const file = files.find((entry) => entry.datasetId === datasetId);
      if (!file) throw new Error(`The ${part} dataset was not created.`);
      return { datasetId, fileId: file.fileId };
    });

    // Share each target with its intended recipients — their existing
    // public keys, fresh per-file content keys, no re-invites.
    for (const participant of others) {
      const grants = grantsByKeyId[participant.keyId];
      for (const part of DATASET_PARTS) {
        const role = grants[part];
        if (!role || role === "owner") continue;
        await controller.addDatasetParticipant({
          datasetId: datasetIdForPart(targetBase, part),
          participant: { publicKey: participantPublicKey(participant), role },
          emailAddress: emails[participant.keyId],
        });
      }
    }

    let migrationId = open?.migrationId;
    if (!migrationId) {
      migrationId = crypto.randomUUID();
      await control.announceMigration({
        migrationId,
        sourceDatasetIds: [profile.datasetId],
        targets,
        requiredAcks: others.map((participant) => ({
          keyId: participant.keyId,
          targetFileIds: DATASET_PARTS.filter((part) => {
            const role = grantsByKeyId[participant.keyId]?.[part];
            return role !== undefined && role !== "owner";
          }).map(
            (part) =>
              targets.find(
                (target) => target.datasetId === datasetIdForPart(targetBase, part),
              )!.fileId,
          ),
        })),
        mode: "hard-cutover",
      });
    }
    await synchronizeProfileControlMembers(
      active,
      { ...currentProfile, controlDatasetId },
      Object.fromEntries(
        Object.entries(emails).map(([keyId, email]) => [keyId, { email }]),
      ),
    );

    // Rebase this device onto the target generation. The base target's head
    // was recorded under datasetRecords while the old base id was scoped —
    // promote it to the top-level fields the split machinery expects.
    const refreshed = (await refreshCachedState()) ?? state;
    const persisted = findProfile(refreshed, state.activeProfileKey) ?? currentProfile;
    const baseHead = persisted.datasetRecords?.[targetBase];
    const { [targetBase]: _promoted, ...datasetRecords } =
      persisted.datasetRecords ?? {};
    const rebased: ProfileRecord = {
      ...persisted,
      datasetId: targetBase,
      datasetGrants: { ...OWNER_DATASET_GRANTS },
      datasetRecords,
      ...(baseHead?.fileId ? { fileId: baseHead.fileId } : {}),
      ...(baseHead?.lastRevisionId ? { lastRevisionId: baseHead.lastRevisionId } : {}),
      lastSyncedAt: new Date().toISOString(),
      retiredDatasetId: profile.datasetId,
      openMigrationId: migrationId,
    };
    const nextState = await rebaseProfileRecord(refreshed, state.activeProfileKey, rebased);
    return { state: nextState, payload };
  });
}

/**
 * Participant side, after the Picker re-selection: adopt and verify every
 * granted target, acknowledge through the control file, and rebase this
 * record onto the target generation with the roles read from the adopted
 * envelopes.
 */
export async function acknowledgeSplitMigration(
  config: SharedSyncConfig,
): Promise<ManagedProfileResult> {
  return serialized(async () => {
    const active = await ensureRuntime(config, createEmptySharedSyncPayload());
    const state = active.state;
    const profile = profileForActive(state);
    const pending = profile.pendingMigration;
    if (!pending) throw new Error("This profile has no migration waiting for you.");
    const controller = active.controller;
    const identity = await active.identityProvider.getOrCreate();

    // Google requires the user to re-select the new files themselves —
    // drive.file scope never grants silently. Multi-select is enabled;
    // a partial pick names what's missing and this action can simply run
    // again to pick up the rest.
    const authorization = await active.authorizationProvider.authorize();
    await pickSharedDatasetFiles(
      authorization,
      pending.targets
        .filter((target) => pending.requiredFileIds.includes(target.fileId))
        .map((target) => ({
          datasetId: target.datasetId,
          fileId: target.fileId,
          role: "viewer" as const,
        })),
    );

    const grants: DatasetGrants = {};
    for (const target of pending.targets) {
      if (!pending.requiredFileIds.includes(target.fileId)) continue;
      const part = partForDatasetId(pending.targetBaseId, target.datasetId);
      if (!part) continue;
      await controller.adoptDataset(target.datasetId);
      const { participants } = await controller.getDatasetParticipants(target.datasetId);
      const me = participants.find(
        (participant) => participant.keyId === identity.publicKey.keyId,
      );
      if (!me) {
        throw new Error(
          `You have no key for the ${DATASET_PART_LABELS_INTERNAL[part]} file — ask the owner to re-run the upgrade.`,
        );
      }
      if (me.role !== "owner") grants[part] = me.role;
    }
    if (!DATASET_PARTS.some((part) => grants[part])) {
      throw new Error("None of the reorganized files could be opened yet — pick them in Google first.");
    }

    const control = buildControlDataset(
      state,
      profile,
      active.identityProvider,
      active.authorizationProvider,
    );
    await readControlWithLegacyRepair(
      state,
      profile,
      active.identityProvider,
      active.authorizationProvider,
    );
    await control.acknowledgeMigration({
      migrationId: pending.migrationId,
      openedFileIds: pending.requiredFileIds,
    });

    const refreshed = (await refreshCachedState()) ?? state;
    const persisted = findProfile(refreshed, state.activeProfileKey) ?? profile;
    const targetBase = pending.targetBaseId;
    const baseHead = persisted.datasetRecords?.[targetBase];
    const { [targetBase]: _promoted, ...datasetRecords } =
      persisted.datasetRecords ?? {};
    const { pendingMigration: _cleared, ...rest } = persisted;
    const rebased: ProfileRecord = {
      ...rest,
      datasetId: targetBase,
      datasetGrants: grants,
      datasetRecords,
      ...(baseHead?.fileId
        ? { fileId: baseHead.fileId, ...(baseHead.lastRevisionId ? { lastRevisionId: baseHead.lastRevisionId } : {}) }
        : { fileId: undefined, lastRevisionId: undefined }),
      needsInitialLoad: true,
      lastSyncedAt: new Date().toISOString(),
    };
    const nextState = await rebaseProfileRecord(refreshed, state.activeProfileKey, rebased);

    // Load the new group immediately so the user sees their data continue.
    const loadRuntime = createRuntimeForProfile(
      config,
      nextState,
      nextState.activeProfileKey,
      createEmptySharedSyncPayload(),
    );
    try {
      const target = findProfile(nextState, nextState.activeProfileKey)!;
      const loaded = await syncProfileDatasetGroup(
        loadRuntime.controller,
        target,
        createEmptySharedSyncPayload(),
        "load",
      );
      const after = (await refreshCachedState()) ?? nextState;
      const final: ProfileRecord = {
        ...(findProfile(after, nextState.activeProfileKey) ?? target),
        ...(loaded.fileId ? { fileId: loaded.fileId } : {}),
        ...(loaded.revisionId ? { lastRevisionId: loaded.revisionId } : {}),
        needsInitialLoad: false,
        lastSyncedAt: new Date().toISOString(),
      };
      const finalState = upsertProfile(after, final);
      cachedState = finalState;
      await saveSharedSyncState(finalState);
      return { state: finalState, payload: loaded.payload };
    } finally {
      loadRuntime.identityProvider.clear();
      disposeRuntime();
    }
  });
}

const DATASET_PART_LABELS_INTERNAL: Record<DatasetPart, string> = {
  plan: "Plan & settings",
  cycle: "Cycle & periods",
  intimacy: "Intimacy log",
  sensitive: "Sensitive events",
};

export type SplitMigrationStatus = {
  migrationId: string;
  acknowledged: Array<{ keyId: string; email?: string }>;
  pending: Array<{ keyId: string; email?: string }>;
  closed: boolean;
};

/** Owner: who has completed the Picker re-selection and acknowledged. */
export async function splitMigrationStatusForActive(
  config: SharedSyncConfig,
): Promise<SplitMigrationStatus> {
  return serialized(async () => {
    const active = await ensureRuntime(config, createEmptySharedSyncPayload());
    const profile = profileForActive(active.state);
    if (!profile.openMigrationId) {
      throw new Error("This profile has no open migration.");
    }
    const control = buildControlDataset(
      active.state,
      profile,
      active.identityProvider,
      active.authorizationProvider,
    );
    await readControlWithLegacyRepair(
      active.state,
      profile,
      active.identityProvider,
      active.authorizationProvider,
    );
    const status = await control.migrationStatus(profile.openMigrationId);
    const emails = profile.participantEmails ?? {};
    return {
      migrationId: profile.openMigrationId,
      acknowledged: status.acknowledgedKeyIds.map((keyId) => ({
        keyId,
        ...(emails[keyId] ? { email: emails[keyId] } : {}),
      })),
      pending: status.pendingKeyIds.map((keyId) => ({
        keyId,
        ...(emails[keyId] ? { email: emails[keyId] } : {}),
      })),
      closed: status.closed,
    };
  });
}

/**
 * Owner: close the migration once every required acknowledgement is present
 * and trash (not delete) the retired source file.
 */
export async function closeSplitMigration(
  config: SharedSyncConfig,
): Promise<ManagedProfileResult> {
  return serialized(async () => {
    const active = await ensureRuntime(config, createEmptySharedSyncPayload());
    const state = active.state;
    const profile = profileForActive(state);
    if (!profile.openMigrationId || !profile.retiredDatasetId) {
      throw new Error("This profile has no open migration.");
    }
    if (profile.role !== "owner") {
      throw new Error("Only the owner can close a migration.");
    }
    const control = buildControlDataset(
      state,
      profile,
      active.identityProvider,
      active.authorizationProvider,
    );
    await readControlWithLegacyRepair(
      state,
      profile,
      active.identityProvider,
      active.authorizationProvider,
    );
    const status = await control.migrationStatus(profile.openMigrationId);
    if (!status.closed) {
      if (status.pendingKeyIds.length > 0) {
        throw new Error(
          "Waiting for everyone to reselect the new files — closing now would cut them off.",
        );
      }
      await control.closeMigration({ migrationId: profile.openMigrationId });
    }
    await active.controller.trashDataset(profile.retiredDatasetId);
    const refreshed = (await refreshCachedState()) ?? state;
    const persisted = findProfile(refreshed, state.activeProfileKey) ?? profile;
    const {
      retiredDatasetId: _retired,
      openMigrationId: _open,
      ...rest
    } = persisted;
    const nextState = upsertProfile(refreshed, rest as ProfileRecord);
    cachedState = nextState;
    await saveSharedSyncState(nextState);
    return { state: nextState, payload: createEmptySharedSyncPayload() };
  });
}

/**
 * Runs before a legacy profile syncs: detect an open (or completed)
 * hard-cutover this device hasn't followed yet. Owners and post-ack
 * participants adopt + rebase immediately; participants who still owe an
 * acknowledgement get a persisted pendingMigration marker and a frozen
 * (load-only) source. Memoized per session — the control file is also read
 * on other paths, this is only the safety net for stale devices.
 */
const migrationReconcileChecked = new Set<string>();
async function reconcileOpenMigration(
  runtime: Runtime,
  profile: ProfileRecord,
): Promise<{ profile: ProfileRecord; freeze: boolean }> {
  const split = isSplitProfile(profile);
  const encrypted = isEncryptedProfile(profile);
  const controlFileKnown = Boolean(
    profile.controlDatasetId &&
      profile.datasetRecords?.[profile.controlDatasetId]?.fileId,
  );
  void appendDeveloperLog("migration", "reconcile-start", {
    datasetId: profile.datasetId,
    role: profile.role,
    split,
    encrypted,
    controlDatasetId: profile.controlDatasetId ?? "missing",
    controlFileKnown,
    pendingMigration: Boolean(profile.pendingMigration),
  });
  if (split || !encrypted || !profile.controlDatasetId || !controlFileKnown) {
    void appendDeveloperLog("migration", "reconcile-skipped", {
      reason: split
        ? "profile-already-split"
        : !encrypted
          ? "profile-local"
          : !profile.controlDatasetId
            ? "control-dataset-id-missing"
            : "control-file-record-missing",
    });
    return { profile, freeze: false };
  }
  const memoKey = profileKey(profile.ownerEmail, profile.datasetId);
  if (profile.pendingMigration) {
    // Already surfaced; stay frozen until acknowledged.
    void appendDeveloperLog("migration", "pending-already-surfaced", {
      migrationId: profile.pendingMigration.migrationId,
    });
    return { profile, freeze: true };
  }
  if (migrationReconcileChecked.has(memoKey)) {
    void appendDeveloperLog("migration", "reconcile-skipped", {
      reason: "already-checked-this-session",
    });
    return { profile, freeze: false };
  }
  migrationReconcileChecked.add(memoKey);
  let verified: Awaited<ReturnType<SharingControlDataset["read"]>>;
  try {
    verified = await readControlWithLegacyRepair(
      runtime.state,
      profile,
      runtime.identityProvider,
      runtime.authorizationProvider,
    );
    void appendDeveloperLog("migration", "control-read-succeeded", {
      migrations: verified.migrations.size,
      closedMigrations: verified.closedMigrations.size,
      members: verified.members.size,
    });
  } catch (error) {
    void appendDeveloperLog("migration", "control-read-failed", { error });
    return { profile, freeze: false };
  }
  const open = findOpenMigration(verified, profile.datasetId);
  if (!open) {
    void appendDeveloperLog("migration", "no-open-migration", {
      sourceDatasetId: profile.datasetId,
      knownMigrationIds: [...verified.migrations.keys()].join(",") || "none",
    });
    return { profile, freeze: false };
  }
  const identity = await runtime.identityProvider.getOrCreate();
  const myKeyId = identity.publicKey.keyId;
  const targetBase = baseDatasetIdOf(open.targets[0]!.datasetId);
  const myAck = verified!.acknowledgements
    .get(open.migrationId)
    ?.has(myKeyId) ?? false;
  const myRequirement = open.requiredAcks.find((ack) => ack.keyId === myKeyId);
  void appendDeveloperLog("migration", "open-migration-found", {
    migrationId: open.migrationId,
    identityKeyPrefix: myKeyId.slice(0, 10),
    acknowledged: myAck,
    requirementFound: Boolean(myRequirement),
    requiredFiles: myRequirement?.targetFileIds.length ?? 0,
    targets: open.targets.length,
  });

  if (profile.role === "owner" || myAck) {
    // Stale device of the owner (or of a participant who already
    // acknowledged elsewhere): adopt the targets we can decrypt and rebase.
    const grants: DatasetGrants = {};
    for (const target of open.targets) {
      const part = partForDatasetId(targetBase, target.datasetId);
      if (!part) continue;
      try {
        await runtime.controller.adoptDataset(target.datasetId, {
          requireOwned: profile.role === "owner",
        });
        const { participants } = await runtime.controller.getDatasetParticipants(
          target.datasetId,
        );
        const me = participants.find((participant) => participant.keyId === myKeyId);
        if (me) grants[part] = me.role === "owner" ? "owner" : me.role;
      } catch {
        // A dataset this identity was not granted — structural partial access.
      }
    }
    if (!DATASET_PARTS.some((part) => grants[part])) return { profile, freeze: true };
    const refreshed = (await refreshCachedState()) ?? runtime.state;
    const persisted = findProfile(refreshed, memoKey) ?? profile;
    const baseHead = persisted.datasetRecords?.[targetBase];
    const { [targetBase]: _promoted, ...datasetRecords } =
      persisted.datasetRecords ?? {};
    const rebased: ProfileRecord = {
      ...persisted,
      datasetId: targetBase,
      datasetGrants:
        profile.role === "owner" ? { ...OWNER_DATASET_GRANTS } : grants,
      datasetRecords,
      ...(baseHead?.fileId ? { fileId: baseHead.fileId } : { fileId: undefined }),
      ...(baseHead?.lastRevisionId
        ? { lastRevisionId: baseHead.lastRevisionId }
        : { lastRevisionId: undefined }),
      ...(profile.role === "owner"
        ? { retiredDatasetId: profile.datasetId, openMigrationId: open.migrationId }
        : { needsInitialLoad: true }),
    };
    await rebaseProfileRecord(refreshed, memoKey, rebased);
    void appendDeveloperLog("migration", "profile-rebased", {
      migrationId: open.migrationId,
      targetDatasetId: targetBase,
      role: profile.role,
    });
    return { profile: rebased, freeze: false };
  }

  if (myRequirement) {
    const pendingMigration: NonNullable<ProfileRecord["pendingMigration"]> = {
      migrationId: open.migrationId,
      targetBaseId: targetBase,
      requiredFileIds: myRequirement.targetFileIds,
      targets: open.targets.map(({ datasetId, fileId }) => ({ datasetId, fileId })),
    };
    const refreshed = (await refreshCachedState()) ?? runtime.state;
    const persisted = findProfile(refreshed, memoKey) ?? profile;
    const marked: ProfileRecord = { ...persisted, pendingMigration };
    const nextState = upsertProfile(refreshed, marked);
    cachedState = nextState;
    await saveSharedSyncState(nextState);
    void appendDeveloperLog("migration", "pending-migration-persisted", {
      migrationId: open.migrationId,
      targetDatasetId: targetBase,
      requiredFiles: myRequirement.targetFileIds.length,
    });
    return { profile: marked, freeze: true };
  }
  void appendDeveloperLog("migration", "identity-not-required", {
    migrationId: open.migrationId,
    identityKeyPrefix: myKeyId.slice(0, 10),
    requiredKeyPrefixes: open.requiredAcks
      .map((requirement) => requirement.keyId.slice(0, 10))
      .join(","),
  });
  return { profile, freeze: false };
}

export async function createLocalProfile(
  config: SharedSyncConfig | null,
  displayName: string,
  local: SharedSyncPayloadV1,
): Promise<ManagedProfileResult> {
  return serialized(async () => {
    const trimmed = displayName.trim();
    if (!trimmed) throw new Error("Enter a profile name.");
    let state = await getCachedState();
    if (!state) throw new Error("No profile registry is available on this device.");
    assertOwnedProfileLabelAvailable(state, trimmed);
    state = await preserveCurrentProfile(config, state, local);
    const created = newLocalProfile(trimmed);
    state = {
      ...state,
      activeProfileKey: created.key,
      profiles: [...state.profiles, created.profile],
    };
    const payload = createEmptySharedSyncPayload();
    cachedState = state;
    await Promise.all([
      saveSharedSyncState(state),
      saveLocalProfilePayload(created.key, payload),
      clearSharingSyncCheckpoint(),
    ]);
    disposeRuntime();
    return { state, payload };
  });
}

export async function renameManagedProfile(
  profileKeyValue: string,
  displayName: string,
): Promise<SharedSyncState> {
  return serialized(async () => {
    const trimmed = displayName.trim();
    if (!trimmed) throw new Error("Enter a profile name.");
    const state = await getCachedState();
    if (!state) throw new Error("No profile registry is available on this device.");
    const profile = findProfile(state, profileKeyValue);
    if (!profile) throw new Error("That profile is not available on this device.");
    const owned = isOwnedProfile(state, profile);
    const locallyManaged = owned || isLocalProfile(profile);
    if (locallyManaged) assertOwnedProfileLabelAvailable(state, trimmed, profileKeyValue);
    const next = upsertProfile(
      state,
      isEncryptedProfile(profile) && !owned
        ? { ...profile, localDisplayName: trimmed }
        : {
            ...profile,
            displayName: trimmed,
            displayNameUpdatedAt: new Date().toISOString(),
          },
    );
    cachedState = next;
    await saveSharedSyncState(next);
    return next;
  });
}

function assertOwnedProfileLabelAvailable(
  state: SharedSyncState,
  displayName: string,
  excludingProfileKey?: string,
): void {
  const normalized = displayName.trim().toLocaleLowerCase();
  const conflict = state.profiles.some((profile) => {
    const key = profileKey(profile.ownerEmail, profile.datasetId);
    return key !== excludingProfileKey &&
      (isOwnedProfile(state, profile) || isLocalProfile(profile)) &&
      profileDisplayLabel(state, profile).toLocaleLowerCase() === normalized;
  });
  if (conflict) throw new Error("Choose a unique name for each profile you own.");
}

export async function updateManagedProfileAvatar(
  profileKeyValue: string,
  avatarWebp?: string,
): Promise<SharedSyncState> {
  return serialized(async () => {
    const state = await getCachedState();
    if (!state) throw new Error("No profile registry is available on this device.");
    const profile = findProfile(state, profileKeyValue);
    if (!profile) throw new Error("That profile is not available on this device.");
    const next = upsertProfile(state, {
      ...profile,
      avatarWebp,
      avatarUpdatedAt: new Date().toISOString(),
    });
    cachedState = next;
    await saveSharedSyncState(next);
    return next;
  });
}

export async function enrollActiveControlDataset(
  config: SharedSyncConfig,
): Promise<SharedSyncState> {
  return serialized(async () => {
    if (!CONTROL_DATASETS_WIRED) {
      throw new Error("Sharing coordination requires the next sync-kit Android release.");
    }
    let state = await getCachedState();
    if (!state) throw new Error("No profile registry is available on this device.");
    let profile = profileForActive(state);
    if (isLocalProfile(profile) || (profile.role !== "owner" && profile.role !== "admin")) {
      throw new Error("Only an owner or admin can enroll sharing coordination.");
    }
    const controlDatasetId = profile.controlDatasetId ?? controlDatasetIdFor(profile.datasetId);
    profile = { ...profile, controlDatasetId, controlEnrollment: "pending" };
    state = upsertProfile(state, profile);
    cachedState = state;
    await saveSharedSyncState(state);
    const scoped = createRuntimeForProfile(config, state, state.activeProfileKey, createEmptySharedSyncPayload());
    try {
      if (!profile.datasetRecords?.[controlDatasetId]?.fileId) {
        await buildControlDataset(
          state,
          profile,
          scoped.identityProvider,
          scoped.authorizationProvider,
        ).create({ email: state.ownerEmail });
      }
      const refreshed = (await refreshCachedState()) ?? state;
      const current = findProfile(refreshed, refreshed.activeProfileKey) ?? profile;
      const required = await requiredProfileMemberKeyIds(scoped, current);
      const verified = await buildControlDataset(
        refreshed,
        current,
        scoped.identityProvider,
        scoped.authorizationProvider,
      ).read();
      const next = upsertProfile(refreshed, {
        ...current,
        controlDatasetId,
        controlEnrollment: required.every((keyId) => verified.members.has(keyId))
          ? "enrolled"
          : "pending",
      });
      cachedState = next;
      await saveSharedSyncState(next);
      return next;
    } finally {
      scoped.identityProvider.clear();
      disposeRuntime();
    }
  });
}

async function synchronizeProfileControlMembers(
  runtimeForProfile: Runtime,
  profile: ProfileRecord,
  metadata: Record<string, { email?: string }> = {},
): Promise<void> {
  if (!CONTROL_DATASETS_WIRED || !profile.controlDatasetId) return;
  const control = buildControlDataset(
    runtimeForProfile.state,
    profile,
    runtimeForProfile.identityProvider,
    runtimeForProfile.authorizationProvider,
  );
  await readControlWithLegacyRepair(
    runtimeForProfile.state,
    profile,
    runtimeForProfile.identityProvider,
    runtimeForProfile.authorizationProvider,
  );
  await control.synchronizeMembers(metadata);
  const verified = await readControlWithLegacyRepair(
    runtimeForProfile.state,
    profile,
    runtimeForProfile.identityProvider,
    runtimeForProfile.authorizationProvider,
  );
  const required = await requiredProfileMemberKeyIds(runtimeForProfile, profile);
  const enrollment = required.every((keyId) => verified.members.has(keyId))
    ? "enrolled"
    : "pending";
  const refreshed = (await refreshCachedState()) ?? runtimeForProfile.state;
  const current = findProfile(refreshed, profileKey(profile.ownerEmail, profile.datasetId)) ?? profile;
  const next = upsertProfile(refreshed, { ...current, controlEnrollment: enrollment });
  cachedState = next;
  await saveSharedSyncState(next);
}

async function requiredProfileMemberKeyIds(
  runtimeForProfile: Runtime,
  profile: ProfileRecord,
): Promise<string[]> {
  const keys = new Set<string>();
  for (const datasetId of profileDatasetIds(profile)) {
    const membership = await runtimeForProfile.controller.getDatasetParticipants(datasetId);
    membership.participants.forEach((participant) => keys.add(participant.keyId));
  }
  return [...keys];
}

export async function disconnectProfileToLocal(
  profileKeyValue: string,
  local: SharedSyncPayloadV1,
): Promise<ManagedProfileResult> {
  return serialized(async () => {
    const state = await getCachedState();
    if (!state) throw new Error("No profile registry is available on this device.");
    if (state.activeProfileKey !== profileKeyValue) {
      throw new Error("Switch to this profile before keeping a local copy.");
    }
    const profile = findProfile(state, profileKeyValue);
    if (!profile || isLocalProfile(profile)) {
      throw new Error("This profile is already local only.");
    }
    const created = newLocalProfile(profile.displayName || "Local copy");
    const next: SharedSyncState = {
      ...state,
      activeProfileKey: created.key,
      profiles: state.profiles.filter(
        (entry) => profileKey(entry.ownerEmail, entry.datasetId) !== profileKeyValue,
      ).concat(created.profile),
    };
    cachedState = next;
    await Promise.all([
      saveSharedSyncState(next),
      saveLocalProfilePayload(created.key, local),
      sharedAuthCache.delete(profileKeyValue),
      clearSharingSyncCheckpoint(),
    ]);
    disposeRuntime();
    return { state: next, payload: local };
  });
}

export async function deleteManagedProfile(
  config: SharedSyncConfig | null,
  profileKeyValue: string,
  local: SharedSyncPayloadV1,
  deleteEverywhere: boolean,
): Promise<ManagedProfileResult> {
  let state = await getCachedState();
  if (!state) throw new Error("No profile registry is available on this device.");
  if (state.profiles.length <= 1) {
    throw new Error("Create or join another profile before deleting the only profile.");
  }
  let payload = local;
  if (state.activeProfileKey === profileKeyValue) {
    const fallback = state.profiles.find(
      (profile) => profileKey(profile.ownerEmail, profile.datasetId) !== profileKeyValue,
    )!;
    const switched = await switchManagedProfile(
      config,
      profileKey(fallback.ownerEmail, fallback.datasetId),
      local,
    );
    state = switched.state;
    payload = switched.payload;
  }
  return serialized(async () => {
    const profile = findProfile(state!, profileKeyValue);
    if (!profile) throw new Error("That profile is not available on this device.");
    if (deleteEverywhere) {
      if (!config || isLocalProfile(profile) || profile.role !== "owner") {
        throw new Error("Only the owner can delete an encrypted profile everywhere.");
      }
      const deletionRuntime = createRuntimeForProfile(
        config,
        state!,
        profileKeyValue,
        createEmptySharedSyncPayload(),
      );
      try {
        // Companions first; the base dataset's registry delete also removes
        // the profile record, so it must go last.
        for (const datasetId of [...profileDatasetIdsIncludingControl(profile)].reverse()) {
          await deletionRuntime.controller.deleteDataset(datasetId);
        }
      } finally {
        deletionRuntime.identityProvider.clear();
      }
    }
    const next = {
      ...state!,
      profiles: state!.profiles.filter(
        (entry) => profileKey(entry.ownerEmail, entry.datasetId) !== profileKeyValue,
      ),
    };
    cachedState = next;
    await Promise.all([
      saveSharedSyncState(next),
      idbDelete(localProfilePayloadKey(profileKeyValue)),
      sharedAuthCache.delete(profileKeyValue),
    ]);
    disposeRuntime();
    return { state: next, payload };
  });
}

export type ManagedParticipant = {
  keyId: string;
  role: SharedBackupParticipantV1["role"];
  emailAddress?: string;
  isCurrentDevice: boolean;
  trust: "verified" | "invite";
  /** Split profiles: the participant's role per dataset part they can see. */
  datasetRoles?: Partial<Record<DatasetPart, SharedBackupParticipantV1["role"]>>;
};

/** The per-part encrypted files this device knows for a split profile. */
function splitProfileFiles(
  profile: ProfileRecord,
): Array<{ part: DatasetPart; fileId: string }> {
  const files: Array<{ part: DatasetPart; fileId: string }> = [];
  for (const part of grantedParts(profile)) {
    const fileId =
      part === "plan"
        ? profile.fileId
        : profile.datasetRecords?.[datasetIdForPart(profile.datasetId, part)]?.fileId;
    if (fileId) files.push({ part, fileId });
  }
  return files;
}

export async function listProfileParticipants(
  config: SharedSyncConfig,
  profileKeyValue: string,
): Promise<ManagedParticipant[]> {
  const state = await getCachedState();
  if (!state) return [];
  const profile = findProfile(state, profileKeyValue);
  if (!profile || isLocalProfile(profile)) return [];
  const files = isSplitProfile(profile)
    ? splitProfileFiles(profile)
    : profile.fileId
      ? [{ part: "plan" as DatasetPart, fileId: profile.fileId }]
      : [];
  if (files.length === 0) return [];
  const providers = createProviders(config, profileKeyValue);
  const identityProvider = createSharingIdentityProvider(config.rpId, () =>
    providers.authorizationProvider.authorize(),
  );
  try {
    const transport = buildTransport(state, profile, providers.authorizationProvider);
    const identity = await identityProvider.getOrCreate();
    const controlMembers = profile.controlDatasetId &&
      profile.datasetRecords?.[profile.controlDatasetId]?.fileId
      ? await buildControlDataset(
          state,
          profile,
          identityProvider,
          providers.authorizationProvider,
        ).read().then((verified) => verified.members).catch(() => new Map())
      : new Map();
    const aggregated = new Map<string, ManagedParticipant>();
    for (const file of files) {
      const stored = await transport.readDataset(file.fileId);
      for (const participant of sharedBackupParticipants(stored.envelope)) {
        const existing = aggregated.get(participant.keyId);
        const controlMember = controlMembers.get(participant.keyId);
        const emailAddress = controlMember?.email ??
          profile.participantEmails?.[participant.keyId];
        const entry: ManagedParticipant = existing ?? {
          keyId: participant.keyId,
          role: participant.role,
          ...(emailAddress ? { emailAddress } : {}),
          isCurrentDevice: participant.keyId === identity.publicKey.keyId,
          trust: controlMember?.googleSubject ? "verified" : "invite",
        };
        if (
          existing &&
          ["owner", "admin", "writer", "viewer"].indexOf(participant.role) <
            ["owner", "admin", "writer", "viewer"].indexOf(existing.role)
        ) {
          entry.role = participant.role;
        }
        if (isSplitProfile(profile)) {
          entry.datasetRoles = { ...entry.datasetRoles, [file.part]: participant.role };
        }
        aggregated.set(participant.keyId, entry);
      }
    }
    return [...aggregated.values()];
  } finally {
    identityProvider.clear();
  }
}

export async function updateParticipantRole(
  config: SharedSyncConfig,
  input: {
    profileKey: string;
    keyId: string;
    emailAddress: string;
    role: Exclude<SharingRole, "owner">;
  },
): Promise<SharedSyncState> {
  return serialized(async () => {
    const state = await getCachedState();
    if (!state) throw new Error("No profile registry is available on this device.");
    const profile = findProfile(state, input.profileKey);
    if (!profile || isLocalProfile(profile)) throw new Error("That encrypted profile is missing.");
    const scoped = createRuntimeForProfile(config, state, input.profileKey, createEmptySharedSyncPayload());
    try {
      // Split profiles: apply the role change to every dataset file the
      // participant currently has; a per-part role editor can narrow later.
      let applied = 0;
      let lastError: unknown;
      for (const datasetId of profileDatasetIds(profile)) {
        try {
          await scoped.controller.setDatasetRole({
            datasetId,
            keyId: input.keyId,
            role: input.role,
            emailAddress: input.emailAddress,
          });
          applied += 1;
        } catch (error) {
          lastError = error;
        }
      }
      if (applied === 0) {
        throw lastError instanceof Error
          ? lastError
          : new Error("That participant was not found in any shared dataset.");
      }
      const refreshed = (await refreshCachedState()) ?? state;
      const current = findProfile(refreshed, input.profileKey) ?? profile;
      const next = upsertProfile(refreshed, {
        ...current,
        participantEmails: {
          ...current.participantEmails,
          [input.keyId]: input.emailAddress,
        },
      });
      cachedState = next;
      await saveSharedSyncState(next);
      return next;
    } finally {
      scoped.identityProvider.clear();
      disposeRuntime();
    }
  });
}

/**
 * Change one participant's access to a single dataset part of a split profile.
 * `"none"` revokes just that dataset file (re-keys it, drops its Drive
 * permission); `viewer`/`writer` adjust an existing grant. Granting a dataset
 * the participant has never held is not possible here — that needs the invite
 * key exchange — so callers only offer view/edit for parts already in
 * `datasetRoles`.
 */
export async function updateParticipantDatasetRole(
  config: SharedSyncConfig,
  input: {
    profileKey: string;
    keyId: string;
    emailAddress: string;
    part: DatasetPart;
    level: "none" | "viewer" | "writer";
  },
): Promise<SharedSyncState> {
  return serialized(async () => {
    const state = await getCachedState();
    if (!state) throw new Error("No profile registry is available on this device.");
    const profile = findProfile(state, input.profileKey);
    if (!profile || isLocalProfile(profile)) throw new Error("That encrypted profile is missing.");
    if (!isSplitProfile(profile)) {
      throw new Error("This profile shares everything as one dataset; per-dataset access needs the split.");
    }
    const datasetId = datasetIdForPart(profile.datasetId, input.part);
    const scoped = createRuntimeForProfile(config, state, input.profileKey, createEmptySharedSyncPayload());
    try {
      if (input.level === "none") {
        await scoped.controller.revokeDatasetKey({
          datasetId,
          keyId: input.keyId,
          emailAddress: input.emailAddress,
        });
      } else {
        await scoped.controller.setDatasetRole({
          datasetId,
          keyId: input.keyId,
          role: input.level,
          emailAddress: input.emailAddress,
        });
      }
      const refreshed = (await refreshCachedState()) ?? state;
      const current = findProfile(refreshed, input.profileKey) ?? profile;
      const next = upsertProfile(refreshed, {
        ...current,
        participantEmails: {
          ...current.participantEmails,
          [input.keyId]: input.emailAddress,
        },
      });
      cachedState = next;
      await saveSharedSyncState(next);
      return next;
    } finally {
      scoped.identityProvider.clear();
      disposeRuntime();
    }
  });
}

export async function revokeParticipant(
  config: SharedSyncConfig,
  profileKeyValue: string,
  keyId: string,
): Promise<SharedSyncState> {
  return serialized(async () => {
    const state = await getCachedState();
    if (!state) throw new Error("No profile registry is available on this device.");
    const profile = findProfile(state, profileKeyValue);
    if (!profile || isLocalProfile(profile)) throw new Error("That encrypted profile is missing.");
    const scoped = createRuntimeForProfile(config, state, profileKeyValue, createEmptySharedSyncPayload());
    try {
      // Revoke from every dataset file the participant was granted; each
      // revocation re-keys that file (fresh content key) and removes its
      // tracked Drive permission.
      const emailAddress = profile.participantEmails?.[keyId]?.trim();
      let revoked = 0;
      let lastError: unknown;
      for (const datasetId of profileDatasetIdsIncludingControl(profile)) {
        const revokeInput: Parameters<typeof scoped.controller.revokeDatasetKey>[0] & {
          emailAddress?: string;
        } = { datasetId, keyId };
        if (emailAddress) revokeInput.emailAddress = emailAddress;
        try {
          await scoped.controller.revokeDatasetKey(revokeInput);
          revoked += 1;
        } catch (error) {
          lastError = error;
        }
      }
      if (revoked === 0) {
        throw lastError instanceof Error
          ? lastError
          : new Error("That participant was not found in any shared dataset.");
      }
      const refreshed = (await refreshCachedState()) ?? state;
      const current = findProfile(refreshed, profileKeyValue) ?? profile;
      const participantEmails = { ...current.participantEmails };
      delete participantEmails[keyId];
      const next = upsertProfile(refreshed, { ...current, participantEmails });
      cachedState = next;
      await saveSharedSyncState(next);
      return next;
    } finally {
      scoped.identityProvider.clear();
      disposeRuntime();
    }
  });
}

export async function createOwnedProfile(
  config: SharedSyncConfig,
  displayName: string,
): Promise<{ state: SharedSyncState; result: SharedSyncRunResult }> {
  return serialized(async () => {
    const trimmed = displayName.trim();
    if (!trimmed) throw new Error("Enter a profile name.");
    const state = await getCachedState();
    if (!state) throw new Error("Encrypted sync is not set up on this device.");
    const anchor = findOwnedStorageProfile(state);
    if (!anchor?.appFolderId) {
      throw new Error("Your encrypted sync folder is not ready yet. Merge changes once, then try again.");
    }
    assertOwnedProfileLabelAvailable(state, trimmed);
    const datasetId = newOwnedDatasetId(state.profiles.map((profile) => profile.datasetId));
    const emptyPayload = createEmptySharedSyncPayload();
    disposeRuntime();
    // The profile record must exist before the dataset group is created —
    // the controller persists companion registry records through it — and
    // the controller must be scoped to the NEW profile's key.
    const profileKeyValue = profileKey(state.ownerEmail, datasetId);
    const newProfile: ProfileRecord = {
      datasetId,
      ownerEmail: state.ownerEmail,
      folderName: anchor.folderName,
      displayName: trimmed,
      displayNameUpdatedAt: new Date().toISOString(),
      role: "owner",
      trustedOwnerKeyId: anchor.trustedOwnerKeyId,
      appFolderId: anchor.appFolderId,
      controlDatasetId: controlDatasetIdFor(datasetId),
      controlEnrollment: "pending",
      datasetGrants: OWNER_DATASET_GRANTS,
      syncMode: "encrypted",
    };
    const provisional = upsertProfile(state, newProfile);
    cachedState = provisional;
    await saveSharedSyncState(provisional);
    const scoped = createRuntimeForProfile(config, provisional, profileKeyValue, emptyPayload);
    try {
      const created = await createProfileDatasetGroup(scoped.controller, datasetId, emptyPayload);
      const afterDataCreate = (await refreshCachedState()) ?? provisional;
      const controlProfile = findProfile(afterDataCreate, profileKeyValue) ?? newProfile;
      if (CONTROL_DATASETS_WIRED) {
        await buildControlDataset(
          afterDataCreate,
          controlProfile,
          scoped.identityProvider,
          scoped.authorizationProvider,
        ).create({ email: state.ownerEmail });
      }
      const syncedAt = new Date().toISOString();
      const refreshed = (await refreshCachedState()) ?? provisional;
      const finalProfile: ProfileRecord = {
        ...(findProfile(refreshed, profileKeyValue) ?? newProfile),
        fileId: created.fileId,
        lastRevisionId: created.revisionId,
        lastSyncedAt: syncedAt,
        controlEnrollment: CONTROL_DATASETS_WIRED ? "enrolled" : "pending",
      };
      let nextState = upsertProfile(refreshed, finalProfile);
      nextState = { ...nextState, activeProfileKey: profileKeyValue };
      cachedState = nextState;
      await saveSharedSyncState(nextState);
      await clearSharingSyncCheckpoint();
      return {
        state: nextState,
        result: {
          payload: emptyPayload,
          syncedAt,
          revisionId: created.revisionId,
          profileKey: profileKeyValue,
        },
      };
    } catch (error) {
      cachedState = state;
      await saveSharedSyncState(state);
      throw error;
    } finally {
      scoped.identityProvider.clear();
      disposeRuntime();
    }
  });
}

export async function connectActiveLocalProfile(
  config: SharedSyncConfig,
  local: SharedSyncPayloadV1,
): Promise<{ state: SharedSyncState; result: SharedSyncRunResult }> {
  const state = await getCachedState();
  if (!state) throw new Error("No profile registry is available on this device.");
  const active = profileForActive(state);
  if (!isLocalProfile(active)) {
    throw new Error("This profile already uses encrypted sync.");
  }
  const anchor = findOwnedStorageProfile(state);
  if (!anchor?.appFolderId) {
    return setupSharedSync(config, local);
  }
  return serialized(async () => {
    disposeRuntime();
    const displayName = active.displayName?.trim() || "Profile";
    assertOwnedProfileLabelAvailable(state, displayName, state.activeProfileKey);
    const datasetId = newOwnedDatasetId(state.profiles.map((profile) => profile.datasetId));
    const connectedKey = profileKey(state.ownerEmail, datasetId);
    const connected: ProfileRecord = {
      datasetId,
      ownerEmail: state.ownerEmail,
      folderName: anchor.folderName,
      displayName,
      displayNameUpdatedAt:
        active.displayNameUpdatedAt ?? new Date().toISOString(),
      role: "owner",
      trustedOwnerKeyId: anchor.trustedOwnerKeyId,
      appFolderId: anchor.appFolderId,
      controlDatasetId: controlDatasetIdFor(datasetId),
      controlEnrollment: "pending",
      datasetGrants: OWNER_DATASET_GRANTS,
      syncMode: "encrypted",
    };
    // Profile record first (companion registry writes land on it), scoped
    // controller second, dataset group last.
    const provisional: SharedSyncState = {
      ...state,
      activeProfileKey: connectedKey,
      profiles: state.profiles
        .filter(
          (profile) =>
            profileKey(profile.ownerEmail, profile.datasetId) !== state.activeProfileKey,
        )
        .concat(connected),
    };
    cachedState = provisional;
    await saveSharedSyncState(provisional);
    const scoped = createRuntimeForProfile(config, provisional, connectedKey, local);
    try {
      const created = await createProfileDatasetGroup(scoped.controller, datasetId, local);
      const afterDataCreate = (await refreshCachedState()) ?? provisional;
      const controlProfile = findProfile(afterDataCreate, connectedKey) ?? connected;
      if (CONTROL_DATASETS_WIRED) {
        await buildControlDataset(
          afterDataCreate,
          controlProfile,
          scoped.identityProvider,
          scoped.authorizationProvider,
        ).create({ email: state.ownerEmail });
      }
      const syncedAt = new Date().toISOString();
      const refreshed = (await refreshCachedState()) ?? provisional;
      const finalProfile: ProfileRecord = {
        ...(findProfile(refreshed, connectedKey) ?? connected),
        fileId: created.fileId,
        lastRevisionId: created.revisionId,
        lastSyncedAt: syncedAt,
        controlEnrollment: CONTROL_DATASETS_WIRED ? "enrolled" : "pending",
      };
      const next = upsertProfile(refreshed, finalProfile);
      cachedState = next;
      await Promise.all([
        saveSharedSyncState(next),
        idbDelete(localProfilePayloadKey(state.activeProfileKey)),
        clearSharingSyncCheckpoint(),
      ]);
      return {
        state: next,
        result: {
          payload: local,
          syncedAt,
          revisionId: created.revisionId,
          profileKey: connectedKey,
        },
      };
    } catch (error) {
      cachedState = state;
      await saveSharedSyncState(state);
      throw error;
    } finally {
      scoped.identityProvider.clear();
      disposeRuntime();
    }
  });
}

export async function inviteToDataset(
  config: SharedSyncConfig,
  input: {
    emailAddress: string;
    role: Exclude<SharingRole, "owner">;
    datasetId?: string;
    emailMessage?: string;
  },
): Promise<{ invitationFileId: string; exchangeId: string; joinUrl: string }> {
  return serialized(async () => {
    const state = await getCachedState();
    if (!state) throw new Error("Encrypted sync is not set up on this device.");
    const active = await ensureRuntime(config, {} as SharedSyncPayloadV1);
    const profile = profileForActive(state);
    const datasetId = input.datasetId ?? PRIMARY_DATASET_ID;
    const joinLandingUrl =
      typeof window !== "undefined"
        ? `${window.location.origin}${window.location.pathname}`
        : undefined;
    const invited = await active.controller.inviteParticipant({
      emailAddress: input.emailAddress,
      requestedGrants: requestedGrantsWithControl(
        [{ datasetId, role: input.role }],
        CONTROL_DATASETS_WIRED ? profile.controlDatasetId : undefined,
        profile.controlDatasetId
          ? profile.datasetRecords?.[profile.controlDatasetId]?.fileId
          : undefined,
      ),
      appDisplayName: "EasyBC",
      ...(joinLandingUrl ? { joinLandingUrl } : {}),
      ...(input.emailMessage ? { emailMessage: input.emailMessage } : {}),
    });
    const storage = await active.controller.ensureStorage();
    const joinUrl = buildJoinUrl({
      exchangeId: invited.invitation.exchangeId,
      appFolderId: storage.appFolderId,
      ownerEmail: state.ownerEmail,
      invitationFileId: invited.invitationFileId,
    });
    return {
      invitationFileId: invited.invitationFileId,
      exchangeId: invited.invitation.exchangeId,
      joinUrl,
    };
  });
}

const pendingInviteKey = (exchangeId: string) => `sharing-pending-invite-${exchangeId}`;

type PendingInvite = {
  invitation: SharingInvitationV1;
  recipientEmail: string;
  profileKey?: string;
};

/**
 * Owner side of the link-carried invite: per-email shares each granted dataset
 * file and returns a join link carrying the signed invitation + file list. No
 * Drive exchange file is written. The invitation is persisted (by exchange id)
 * so the owner can accept the recipient's response link later.
 */
export async function inviteToDatasetLink(
  config: SharedSyncConfig,
  input: {
    emailAddress: string;
    role: Exclude<SharingRole, "owner">;
    datasetId?: string;
    /**
     * Split profiles: exactly which dataset parts to share, at which role
     * (the invite presets). Omitted = every part at input.role. Ignored for
     * legacy single-file profiles, which are all-or-nothing.
     */
    grants?: DatasetGrants;
  },
): Promise<{ joinLink: string; exchangeId: string }> {
  return serialized(async () => {
    const state = await getCachedState();
    if (!state) throw new Error("Encrypted sync is not set up on this device.");
    const active = await ensureRuntime(config, {} as SharedSyncPayloadV1);
    const activeProfile = profileForActive(state);
    const datasetId = input.datasetId ?? activeProfile.datasetId;
    if (datasetId !== activeProfile.datasetId) {
      throw new Error("Switch to the profile you want to share before inviting someone.");
    }
    const dataGrants = isSplitProfile(activeProfile)
      ? requestedGrantsFromDatasetGrants(
          activeProfile.datasetId,
          input.grants ?? {
            plan: input.role,
            cycle: input.role,
            intimacy: input.role,
            sensitive: input.role,
          },
        )
      : [{ datasetId, role: input.role }];
    const requestedGrants = requestedGrantsWithControl(
      dataGrants,
      CONTROL_DATASETS_WIRED ? activeProfile.controlDatasetId : undefined,
      activeProfile.controlDatasetId
        ? activeProfile.datasetRecords?.[activeProfile.controlDatasetId]?.fileId
        : undefined,
    );
    if (requestedGrants.length === 0) {
      throw new Error("Choose at least one dataset to share.");
    }
    const invited = await active.controller.inviteParticipantForLink({
      emailAddress: input.emailAddress,
      requestedGrants,
    });
    await idbSet(pendingInviteKey(invited.invitation.exchangeId), {
      invitation: invited.invitation,
      recipientEmail: input.emailAddress,
      profileKey: state.activeProfileKey,
    } satisfies PendingInvite);
    const landingUrl =
      typeof window !== "undefined"
        ? `${window.location.origin}${window.location.pathname}`
        : "https://keyneom.github.io/easy-bc/";
    const baseLink = buildSharingJoinLinkV1({
      landingUrl,
      invitation: invited.invitation,
      files: invited.files,
    });
    // sync-kit sharing is email-agnostic; carry the owner email (app param) so
    // the joiner can label the profile.
    const joinLink = `${baseLink}&owner=${encodeURIComponent(activeProfile.ownerEmail)}`;
    return { joinLink, exchangeId: invited.invitation.exchangeId };
  });
}

/**
 * Recipient side of the link-carried join: opens the Picker so the user grants
 * the shared dataset file(s), then returns a response link to send back to the
 * owner. Reads no Drive exchange file.
 */
export async function submitJoinFromLink(
  config: SharedSyncConfig,
  input: {
    invitation: SharingInvitationV1;
    files: SharingDatasetFileV1[];
    ownerEmail: string;
    local: SharedSyncPayloadV1;
  },
): Promise<{
  responseLink: string;
  state: SharedSyncState;
  initialPayload: SharedSyncPayloadV1;
}> {
  let createdOwnedProfile = false;
  if (!(await getCachedState()) && hasMeaningfulSharedData(input.local)) {
    await setupSharedSync(config, input.local);
    createdOwnedProfile = true;
  }
  return serialized(async () => {
    disposeRuntime();
    let previousState = await getCachedState();
    if (previousState && !createdOwnedProfile) {
      const currentProfile = profileForActive(previousState);
      if (isLocalProfile(currentProfile)) {
        await saveLocalProfilePayload(previousState.activeProfileKey, input.local);
      } else if (
        currentProfile.fileId &&
        canPublishRole(currentProfile.role) &&
        !currentProfile.needsInitialLoad
      ) {
        const preservationRuntime = createRuntimeForProfile(
          config,
          previousState,
          previousState.activeProfileKey,
          input.local,
        );
        try {
          await syncProfileDatasetGroup(
            preservationRuntime.controller,
            currentProfile,
            input.local,
            "sync",
          );
          previousState = (await refreshCachedState()) ?? previousState;
        } finally {
          preservationRuntime.identityProvider.clear();
        }
      }
    }
    const bootstrapProfileId = "__easybc-bootstrap__";
    const bootstrap = createProviders(config, bootstrapProfileId);
    const authorization = await authorizeAndRemember(
      bootstrap.authorizationProvider,
      bootstrapProfileId,
    );
    // Grant the app drive.file access to the shared dataset file(s).
    await pickSharedDatasetFiles(authorization, input.files);
    const selfEmail = await fetchGoogleAccountEmail(authorization.accessToken);

    const folderName = easyBcSyncFolderName(input.ownerEmail);
    // A split share grants a subset of the profile's dataset files; the
    // profile is keyed by the base dataset id and remembers per-part roles
    // so sync and the UI know exactly what this device can see.
    const controlGrant = input.invitation.requestedGrants.find((grant) =>
      grant.datasetId.endsWith(CONTROL_DATASET_SUFFIX),
    );
    const appGrants = input.invitation.requestedGrants.filter(
      (grant) => grant.datasetId !== controlGrant?.datasetId,
    );
    const parsedGrants = grantsFromRequestedGrants(appGrants);
    const joinProfile: ProfileRecord = {
      datasetId: parsedGrants.baseDatasetId || PRIMARY_DATASET_ID,
      ownerEmail: input.ownerEmail,
      folderName,
      appFolderId: input.invitation.appFolderId,
      role: parsedGrants.split
        ? highestGrantedRole(parsedGrants.grants)
        : input.invitation.requestedGrants[0]?.role ?? "viewer",
      trustedOwnerKeyId: input.invitation.trustedOwnerKeyId,
      needsInitialLoad: true,
      ...(controlGrant
        ? { controlDatasetId: controlGrant.datasetId, controlEnrollment: "pending" as const }
        : {}),
      ...(parsedGrants.split ? { datasetGrants: parsedGrants.grants } : {}),
    };
    const profileKeyValue = profileKey(joinProfile.ownerEmail, joinProfile.datasetId);
    const { authorizationProvider, googleIdentity } = createProviders(config, profileKeyValue);
    await sharedAuthCache.save({
      profileId: profileKeyValue,
      accessToken: authorization.accessToken,
      expiresAt: authorization.expiresAt ?? Date.now() + 3_600_000,
    });
    rememberTokenExpiry(profileKeyValue, authorization.expiresAt);
    let state: SharedSyncState = {
      schemaVersion: 1,
      rpId: config.rpId,
      ownerEmail: previousState?.ownerEmail ?? selfEmail,
      ...(previousState ?? {}),
      selectedAppFolderId: input.invitation.appFolderId,
      activeProfileKey: profileKeyValue,
      profiles: upsertProfile(
        previousState ?? {
          schemaVersion: 1,
          rpId: config.rpId,
          ownerEmail: selfEmail,
          activeProfileKey: profileKeyValue,
          profiles: [],
        },
        joinProfile,
      ).profiles,
    };
    cachedState = state;
    await saveSharedSyncState(state);
    try {
      const identityProvider = createSharingIdentityProvider(config.rpId, () =>
        authorizationProvider.authorize(),
      );
      await identityProvider.getOrCreate();
      const controller = buildController(
        state,
        joinProfile,
        config,
        identityProvider,
        authorizationProvider,
        googleIdentity,
      );
      const response = await controller.submitKeyResponseFromInvitation(
        input.invitation,
        input.files,
      );
      state = (await refreshCachedState()) ?? state;
      runtime = {
        config,
        state,
        local: {} as SharedSyncPayloadV1,
        identityProvider,
        authorizationProvider,
        googleIdentity,
        controller,
      };
      const landingUrl =
        typeof window !== "undefined"
          ? `${window.location.origin}${window.location.pathname}`
          : "https://keyneom.github.io/easy-bc/";
      return {
        responseLink: buildSharingResponseLinkV1({ landingUrl, response }),
        state,
        initialPayload: createEmptySharedSyncPayload(),
      };
    } catch (error) {
      if (previousState) {
        cachedState = previousState;
        await saveSharedSyncState(previousState);
      } else {
        cachedState = null;
        await forgetSharedSyncState();
      }
      disposeRuntime();
      throw error;
    }
  });
}

export async function grantSharedDatasetFilesFromLink(
  config: SharedSyncConfig,
  files: SharingDatasetFileV1[],
): Promise<void> {
  return serialized(async () => {
    disposeRuntime();
    const bootstrapProfileId = "__easybc-bootstrap__";
    const bootstrap = createProviders(config, bootstrapProfileId);
    const authorization = await authorizeAndRemember(
      bootstrap.authorizationProvider,
      bootstrapProfileId,
    );
    await pickSharedDatasetFiles(authorization, files);
  });
}

/**
 * Owner side: accepts a key-response link, adding the recipient to the dataset's
 * key grants and per-email sharing the dataset file(s). Uses the invitation
 * persisted at invite time (by exchange id).
 */
export async function acceptResponseFromLink(
  config: SharedSyncConfig,
  input: { response: SharingPublicKeyResponseV1 },
): Promise<void> {
  return serialized(async () => {
    const pending = await idbGet<PendingInvite>(
      pendingInviteKey(input.response.exchangeId),
    );
    if (!pending) {
      throw new Error(
        "No pending invitation matches this response. Send a fresh invite link.",
      );
    }
    const state = await getCachedState();
    if (!state) throw new Error("Encrypted sync is not set up on this device.");
    const datasetId = pending.invitation.requestedGrants.find(
      (grant) => !grant.datasetId.endsWith(CONTROL_DATASET_SUFFIX),
    )?.datasetId;
    const fallbackProfile = state.profiles.find(
      (profile) =>
        profile.datasetId === datasetId &&
        profile.trustedOwnerKeyId === pending.invitation.trustedOwnerKeyId &&
        (profile.role === "owner" || profile.role === "admin"),
    );
    const profileKeyValue =
      pending.profileKey ??
      (fallbackProfile
        ? profileKey(fallbackProfile.ownerEmail, fallbackProfile.datasetId)
        : state.activeProfileKey);
    const scopedRuntime = createRuntimeForProfile(
      config,
      state,
      profileKeyValue,
      {} as SharedSyncPayloadV1,
    );
    try {
      const results = await scopedRuntime.controller.acceptKeyResponseFromPayload({
        invitation: pending.invitation,
        response: input.response,
        recipientEmailAddress: pending.recipientEmail,
      });
      assertAcceptedDatasetResults(results);
      const refreshed = (await refreshCachedState()) ?? state;
      const acceptedProfile = findProfile(refreshed, profileKeyValue);
      if (acceptedProfile) {
        const updatedProfile = {
          ...acceptedProfile,
          participantEmails: {
            ...acceptedProfile.participantEmails,
            [input.response.keyId]: pending.recipientEmail,
          },
        };
        const next = upsertProfile(refreshed, updatedProfile);
        cachedState = next;
        await saveSharedSyncState(next);
        await synchronizeProfileControlMembers(scopedRuntime, updatedProfile, {
          [input.response.keyId]: { email: pending.recipientEmail },
        });
      }
      disposeRuntime();
      await idbDelete(pendingInviteKey(input.response.exchangeId));
    } finally {
      scopedRuntime.identityProvider.clear();
    }
  });
}

export function assertAcceptedDatasetResults(
  results: Array<{ datasetId: string; status: string; error?: unknown }>,
): void {
  const failures = results.filter((result) => result.status !== "accepted");
  if (failures.length === 0) return;
  const detail = failures
    .map((failure) => {
      const message =
        failure.error instanceof Error ? failure.error.message : String(failure.error ?? "failed");
      return `${failure.datasetId}: ${message}`;
    })
    .join("; ");
  throw new Error(`The recipient was not added to every dataset (${detail}). Try accepting again.`);
}

export async function listPendingKeyResponses(
  config: SharedSyncConfig,
): Promise<Array<{ responseFileId: string; exchangeId: string; invitationFileId: string }>> {
  return serialized(async () => {
    const active = await ensureRuntime(config, {} as SharedSyncPayloadV1);
    const responses = await active.controller.listExchanges({ kind: "key-response" });
    const invitations = await active.controller.listExchanges({ kind: "invitation" });
    return responses
      .map((response) => ({
        responseFileId: response.fileId,
        exchangeId: response.exchangeId,
        invitationFileId:
          invitations.find((invitation) => invitation.exchangeId === response.exchangeId)?.fileId ??
          "",
      }))
      .filter((entry) => entry.invitationFileId);
  });
}

export async function acceptPendingKeyResponse(
  config: SharedSyncConfig,
  input: { invitationFileId: string; responseFileId: string; recipientEmailAddress: string },
): Promise<void> {
  return serialized(async () => {
    const active = await ensureRuntime(config, {} as SharedSyncPayloadV1);
    const transport = buildTransport(
      active.state,
      profileForActive(active.state),
      active.authorizationProvider,
    );
    const invitation = await verifySharingInvitationV1(
      await transport.readInvitation(input.invitationFileId),
      { crypto: globalThis.crypto },
    );
    const response = await transport.readKeyResponse(
      input.responseFileId,
      invitation.recipientDrivePermissionId,
    );
    const results = await active.controller.acceptKeyResponse({
      invitation,
      responseFileId: input.responseFileId,
      recipientEmailAddress: input.recipientEmailAddress,
    });
    assertAcceptedDatasetResults(results);
    const profile = profileForActive(active.state);
    await active.controller.reconcileDrivePermissions({
      datasetId: profile.datasetId,
      participantEmails: { [response.response.keyId]: input.recipientEmailAddress },
    });
    const refreshed = (await refreshCachedState()) ?? active.state;
    const current = findProfile(refreshed, refreshed.activeProfileKey) ?? profile;
    const updatedProfile = {
      ...current,
      participantEmails: {
        ...current.participantEmails,
        [response.response.keyId]: input.recipientEmailAddress,
      },
    };
    const next = upsertProfile(refreshed, updatedProfile);
    cachedState = next;
    await saveSharedSyncState(next);
    await synchronizeProfileControlMembers(active, updatedProfile, {
      [response.response.keyId]: { email: input.recipientEmailAddress },
    });
  });
}

export async function submitJoinResponse(
  config: SharedSyncConfig,
  input: {
    invitationFileId: string;
    ownerFolderId: string;
    ownerEmail: string;
    folderName: string;
    role?: SharingRole;
  },
): Promise<void> {
  return serialized(async () => {
    disposeRuntime();
    const bootstrapProfileId = "__easybc-bootstrap__";
    const bootstrap = createProviders(config, bootstrapProfileId);
    const authorization = await authorizeAndRemember(
      bootstrap.authorizationProvider,
      bootstrapProfileId,
    );
    const selfEmail = await fetchGoogleAccountEmail(authorization.accessToken);
    let state = await getCachedState();
    if (!state) {
      state = {
        schemaVersion: 1,
        rpId: config.rpId,
        ownerEmail: selfEmail,
        activeProfileKey: profileKey(input.ownerEmail, PRIMARY_DATASET_ID),
        profiles: [],
      };
    }
    const transport = new GoogleDriveSharedBackupTransport({
      appId: EASY_BC_APP_ID,
      authorizationProvider: bootstrap.authorizationProvider,
      folderName: input.folderName,
      selectedAppFolderId: input.ownerFolderId,
    });
    let invitationRaw;
    try {
      invitationRaw = await transport.readInvitation(input.invitationFileId);
    } catch (error) {
      const probe = await probeDriveVisibility(
        authorization.accessToken,
        input.ownerFolderId,
        input.invitationFileId,
      );
      console.warn("[EasyBcSync] join visibility probe", probe);
      const detail = summarizeVisibilityProbe(probe);
      throw new Error(
        "EasyBC can't see the shared folder yet " +
          `(${detail}). Open Settings → the shared folder screen and use ` +
          "“Grant folder access” to select the shared EasyBC folder with " +
          "this same Google account, then try joining again.",
        { cause: error },
      );
    }
    const invitation = await verifySharingInvitationV1(invitationRaw, {
      crypto: globalThis.crypto,
    });
    const grant = invitation.requestedGrants[0];
    const joinProfile: ProfileRecord = {
      datasetId: grant?.datasetId ?? PRIMARY_DATASET_ID,
      ownerEmail: input.ownerEmail,
      folderName: input.folderName,
      appFolderId: input.ownerFolderId,
      role: grant?.role ?? input.role ?? "viewer",
      trustedOwnerKeyId: invitation.trustedOwnerKeyId,
      needsInitialLoad: true,
    };
    const profileKeyValue = profileKey(joinProfile.ownerEmail, joinProfile.datasetId);
    const { authorizationProvider, googleIdentity } = createProviders(config, profileKeyValue);
    if (profileKeyValue !== bootstrapProfileId) {
      await sharedAuthCache.save({
        profileId: profileKeyValue,
        accessToken: authorization.accessToken,
        expiresAt: authorization.expiresAt ?? Date.now() + 3_600_000,
      });
      rememberTokenExpiry(profileKeyValue, authorization.expiresAt);
    }
    state = {
      ...state,
      selectedAppFolderId: input.ownerFolderId,
      activeProfileKey: profileKeyValue,
      profiles: upsertProfile(state, joinProfile).profiles,
    };
    cachedState = state;
    await saveSharedSyncState(state);
    const identityProvider = createSharingIdentityProvider(config.rpId, () =>
      authorizationProvider.authorize(),
    );
    await identityProvider.getOrCreate();
    const controller = buildController(
      state,
      joinProfile,
      config,
      identityProvider,
      authorizationProvider,
      googleIdentity,
    );
    await controller.submitKeyResponse(input.invitationFileId);
    runtime = {
      config,
      state,
      local: {} as SharedSyncPayloadV1,
      identityProvider,
      authorizationProvider,
      googleIdentity,
      controller,
    };
  });
}

export function buildJoinUrl(input: {
  exchangeId: string;
  appFolderId: string;
  ownerEmail: string;
  invitationFileId: string;
}): string {
  const url = new URL(window.location.href);
  url.search = "";
  url.searchParams.set("sync", "join");
  url.searchParams.set("exchange", input.exchangeId);
  url.searchParams.set("folder", input.appFolderId);
  url.searchParams.set("owner", input.ownerEmail);
  url.searchParams.set("invitation", input.invitationFileId);
  return url.toString();
}

export async function forgetSharedSync(): Promise<void> {
  disposeRuntime();
  clearSharingIdentitySession();
  cachedState = null;
  tokenExpiresAtByProfile.clear();
  await forgetSharedSyncState();
  await idbSet(KV_SHARING_SYNC_CHECKPOINT, {});
}

export async function clearSharingSyncCheckpoint(): Promise<void> {
  await idbSet(KV_SHARING_SYNC_CHECKPOINT, {});
}

export async function loadSharingSyncCheckpoint(): Promise<SharingSyncCheckpoint> {
  return (await idbGet<SharingSyncCheckpoint>(KV_SHARING_SYNC_CHECKPOINT)) ?? {};
}

export async function saveSharingSyncCheckpoint(
  checkpoint: SharingSyncCheckpoint,
): Promise<void> {
  await idbSet(KV_SHARING_SYNC_CHECKPOINT, checkpoint);
}

export async function createSharingChangeDetectorForActiveProfile(
  config: SharedSyncConfig,
): Promise<SharingChangeDetector | null> {
  const state = await getCachedState();
  if (!state) return null;
  const profile = profileForActive(state);
  const { authorizationProvider } = createProviders(config, state.activeProfileKey);
  const selectedAppFolderId = selectedAppFolderIdForProfile(state, profile);
  const transport = new GoogleDriveSharedBackupTransport({
    appId: EASY_BC_APP_ID,
    authorizationProvider: createPollingAuthorizationProvider(
      authorizationProvider,
      state.activeProfileKey,
    ),
    folderName: profile.folderName,
    ...(selectedAppFolderId ? { selectedAppFolderId } : {}),
  });
  return createSharingChangeDetectorFromTransport(transport, {
    tokenExpiresAt: () => tokenExpiresAtByProfile.get(state.activeProfileKey),
  });
}

export { isSharedSyncConfigured, loadSharedSyncState, saveSharedSyncState };

export function sharedSyncConfigFromEnv(rpId: string): SharedSyncConfig | null {
  const clientId = import.meta.env.VITE_GOOGLE_WEB_CLIENT_ID?.trim() ?? "";
  if (!clientId) return null;
  const origin = typeof window !== "undefined" ? window.location.origin : "";
  return {
    clientId,
    rpId,
    googleAudience: clientId,
    allowedOrigins: origin ? [origin] : [],
  };
}
