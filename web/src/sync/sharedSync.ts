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
} from "@keyneom/sync-kit/sharing/controller";
import { verifySharingInvitationV1 } from "@keyneom/sync-kit/sharing/web-crypto";
import { GoogleDriveSharedBackupTransport } from "@keyneom/sync-kit/stores/google-drive/sharing";
import { idbDelete, idbGet, idbSet, KV_SHARING_SYNC_CHECKPOINT } from "../idbStore";
import { pickSharedDatasetFiles } from "./sharedPicker";
import { easyBcSharedCodec } from "./sharedCodec";
import { createSharingIdentityProvider } from "./sharedIdentity";
import { easyBcSyncFolderName, profileKey } from "./sharedFolderName";
import {
  findOwnedPrimaryProfile,
  isOwnedProfile,
  uniqueOwnedDatasetId,
} from "./profileLabels";
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
  canPublishRole,
  EASY_BC_APP_ID,
  findProfile,
  hasMeaningfulSharedData,
  isEncryptedProfile,
  isLocalProfile,
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
};

let runtime: Runtime | null = null;
let cachedState: SharedSyncState | null = null;
let operationQueue: Promise<unknown> = Promise.resolve();
let activeOperationCount = 0;
const tokenExpiresAtByProfile = new Map<string, number>();
const LOCAL_PROFILE_PAYLOAD_PREFIX = "profilePayload:";

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
  return {
    key: profileKey(ownerEmail, datasetId),
    profile: {
      datasetId,
      ownerEmail,
      folderName: "",
      displayName: displayName.trim() || "My data",
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
  return new GoogleDriveSharedBackupTransport({
    appId: EASY_BC_APP_ID,
    authorizationProvider,
    folderName: profile.folderName,
    ...(isOwnedProfile(state, profile)
      ? {}
      : { selectedAppFolderId: profile.appFolderId ?? state.selectedAppFolderId }),
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
  const registry = new ProfileScopedSharedBackupRegistry(getCachedState, scopeKey);
  return createSharedBackupController({
    appId: EASY_BC_APP_ID,
    codec: easyBcSharedCodec,
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
      await controller.deleteDataset(profile.datasetId);
      const created = await controller.createDataset(profile.datasetId, local);
      const syncedAt = new Date().toISOString();
      const nextProfile: ProfileRecord = {
        ...profile,
        fileId: created.fileId,
        lastRevisionId: created.revisionId,
        lastSyncedAt: syncedAt,
        participantPermissionIds: {},
        participantEmails: {},
        needsInitialLoad: false,
      };
      const refreshed = (await refreshCachedState()) ?? state;
      const nextState = upsertProfile(refreshed, nextProfile);
      cachedState = nextState;
      await saveSharedSyncState(nextState);
      return {
        state: nextState,
        result: {
          payload: created.value,
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
    const connectedProfile: ProfileRecord = {
      datasetId: PRIMARY_DATASET_ID,
      ownerEmail,
      folderName,
      ...(activeBeforeSetup?.displayName
        ? { displayName: activeBeforeSetup.displayName }
        : {}),
      role: "owner",
      trustedOwnerKeyId: identity.publicKey.keyId,
      syncMode: "encrypted",
    };
    const provisional: SharedSyncState = {
      schemaVersion: 1,
      rpId: config.rpId,
      ownerEmail,
      activeProfileKey: profileKey(ownerEmail, PRIMARY_DATASET_ID),
      profiles: [...preservedLocalProfiles, connectedProfile],
    };
    try {
      cachedState = provisional;
      await saveSharedSyncState(provisional);
      const controller = buildController(
        provisional,
        connectedProfile,
        config,
        identityProvider,
        authorizationProvider,
        googleIdentity,
      );
      const storage = await controller.ensureStorage();
      // Adopt an existing primary dataset (interrupted setup, reinstall,
      // reconnecting device) instead of failing with "already exists";
      // create only when the folder has none.
      const existing = (await controller.listDatasets()).find(
        (dataset) => dataset.datasetId === PRIMARY_DATASET_ID,
      );
      const created = existing
        ? await (async () => {
            try {
              await controller.adoptDataset(PRIMARY_DATASET_ID, { requireOwned: true });
            } catch (error) {
              throw new Error(
                "An encrypted sync dataset already exists in your Drive folder, " +
                  "but this device cannot unlock it. Use Reset encrypted sync to " +
                  "replace it with this device's data.",
                { cause: error },
              );
            }
            return controller.syncDataset(PRIMARY_DATASET_ID, local);
          })()
        : await controller.createDataset(PRIMARY_DATASET_ID, local);
      const encryptedState = createInitialSharedSyncState({
        rpId: config.rpId,
        ownerEmail,
        folderName,
        trustedOwnerKeyId: identity.publicKey.keyId,
        appFolderId: storage.appFolderId,
        fileId: created.fileId,
        lastRevisionId: created.revisionId,
      });
      const nextState: SharedSyncState = {
        ...encryptedState,
        profiles: [
          ...preservedLocalProfiles,
          {
            ...encryptedState.profiles[0],
            ...(connectedProfile.displayName
              ? { displayName: connectedProfile.displayName }
              : {}),
            syncMode: "encrypted",
          },
        ],
      };
      await saveSharedSyncState(nextState);
      if (activeBeforeSetup && isLocalProfile(activeBeforeSetup)) {
        await idbDelete(localProfilePayloadKey(previousState!.activeProfileKey));
      }
      runtime = {
        config,
        state: nextState,
        local: created.value,
        identityProvider,
        authorizationProvider,
        controller,
      };
      return {
        state: nextState,
        result: {
          payload: created.value,
          syncedAt: new Date().toISOString(),
          revisionId: created.revisionId,
          profileKey: nextState.activeProfileKey,
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
  return serialized(async () => {
    const active = await ensureRuntime(config, local);
    const profile = profileForActive(active.state);
    if (isLocalProfile(profile)) {
      throw new Error("This profile is local only. Connect encrypted sync before syncing it.");
    }
    const result = shouldLoadRemoteBeforePublish(profile)
      ? await active.controller.loadDataset(profile.datasetId)
      : await active.controller.syncDataset(profile.datasetId, local);
    const updatedProfile: ProfileRecord = {
      ...profile,
      fileId: result.fileId,
      lastRevisionId: result.revisionId,
      lastSyncedAt: new Date().toISOString(),
      needsInitialLoad: false,
    };
    const nextState = upsertProfile(active.state, updatedProfile);
    cachedState = nextState;
    await saveSharedSyncState(nextState);
    active.state = nextState;
    return {
      payload: result.value,
      syncedAt: updatedProfile.lastSyncedAt ?? new Date().toISOString(),
      revisionId: result.revisionId,
      profileKey: nextState.activeProfileKey,
    };
  });
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
    const loaded = await active.controller.loadDataset(profile.datasetId);
    const updatedProfile: ProfileRecord = {
      ...profile,
      fileId: loaded.fileId,
      lastRevisionId: loaded.revisionId,
      lastSyncedAt: new Date().toISOString(),
      needsInitialLoad: false,
    };
    const nextState = upsertProfile(state, updatedProfile);
    cachedState = nextState;
    await saveSharedSyncState(nextState);
    return {
      payload: loaded.value,
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
    await currentRuntime.controller.syncDataset(current.datasetId, local);
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
      const loaded = await targetRuntime.controller.loadDataset(target.datasetId);
      const nextProfile: ProfileRecord = {
        ...target,
        fileId: loaded.fileId,
        lastRevisionId: loaded.revisionId,
        lastSyncedAt: new Date().toISOString(),
        needsInitialLoad: false,
      };
      state = upsertProfile((await refreshCachedState()) ?? state, nextProfile);
      cachedState = state;
      await saveSharedSyncState(state);
      return { state, payload: loaded.value };
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
    const next = upsertProfile(state, { ...profile, displayName: trimmed });
    cachedState = next;
    await saveSharedSyncState(next);
    return next;
  });
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
        await deletionRuntime.controller.deleteDataset(profile.datasetId);
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
};

export async function listProfileParticipants(
  config: SharedSyncConfig,
  profileKeyValue: string,
): Promise<ManagedParticipant[]> {
  const state = await getCachedState();
  if (!state) return [];
  const profile = findProfile(state, profileKeyValue);
  if (!profile?.fileId || isLocalProfile(profile)) return [];
  const providers = createProviders(config, profileKeyValue);
  const identityProvider = createSharingIdentityProvider(config.rpId, () =>
    providers.authorizationProvider.authorize(),
  );
  try {
    const [stored, identity] = await Promise.all([
      buildTransport(state, profile, providers.authorizationProvider).readDataset(profile.fileId),
      identityProvider.getOrCreate(),
    ]);
    return sharedBackupParticipants(stored.envelope).map((participant) => ({
      keyId: participant.keyId,
      role: participant.role,
      ...(profile.participantEmails?.[participant.keyId]
        ? { emailAddress: profile.participantEmails[participant.keyId] }
        : {}),
      isCurrentDevice: participant.keyId === identity.publicKey.keyId,
    }));
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
      await scoped.controller.setDatasetRole({
        datasetId: profile.datasetId,
        keyId: input.keyId,
        role: input.role,
        emailAddress: input.emailAddress,
      });
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
      const revokeInput: Parameters<typeof scoped.controller.revokeDatasetKey>[0] & {
        emailAddress?: string;
      } = {
        datasetId: profile.datasetId,
        keyId,
      };
      const emailAddress = profile.participantEmails?.[keyId]?.trim();
      if (emailAddress) revokeInput.emailAddress = emailAddress;
      await scoped.controller.revokeDatasetKey(revokeInput);
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
    const primary = findOwnedPrimaryProfile(state);
    if (!primary?.appFolderId) {
      throw new Error("Your encrypted sync folder is not ready yet. Merge changes once, then try again.");
    }
    const datasetId = uniqueOwnedDatasetId(trimmed, state.ownerEmail, state.profiles);
    const emptyPayload = createEmptySharedSyncPayload();
    disposeRuntime();
    const primaryKey = profileKey(primary.ownerEmail, primary.datasetId);
    const { authorizationProvider, googleIdentity } = createProviders(config, primaryKey);
    const identityProvider = createSharingIdentityProvider(config.rpId, () =>
      authorizationProvider.authorize(),
    );
    const controller = buildController(
      state,
      primary,
      config,
      identityProvider,
      authorizationProvider,
      googleIdentity,
    );
    const created = await controller.createDataset(datasetId, emptyPayload);
    const syncedAt = new Date().toISOString();
    const newProfile: ProfileRecord = {
      datasetId,
      ownerEmail: state.ownerEmail,
      folderName: primary.folderName,
      displayName: trimmed,
      role: "owner",
      trustedOwnerKeyId: primary.trustedOwnerKeyId,
      appFolderId: primary.appFolderId,
      fileId: created.fileId,
      lastRevisionId: created.revisionId,
      lastSyncedAt: syncedAt,
    };
    const profileKeyValue = profileKey(state.ownerEmail, datasetId);
    let nextState = upsertProfile(state, newProfile);
    nextState = { ...nextState, activeProfileKey: profileKeyValue };
    cachedState = nextState;
    await saveSharedSyncState(nextState);
    await clearSharingSyncCheckpoint();
    disposeRuntime();
    return {
      state: nextState,
      result: {
        payload: created.value,
        syncedAt,
        revisionId: created.revisionId,
        profileKey: profileKeyValue,
      },
    };
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
  const primary = findOwnedPrimaryProfile(state);
  if (!primary?.appFolderId) {
    return setupSharedSync(config, local);
  }
  return serialized(async () => {
    disposeRuntime();
    const displayName = active.displayName?.trim() || "Profile";
    const datasetId = uniqueOwnedDatasetId(displayName, state.ownerEmail, state.profiles);
    const primaryKey = profileKey(primary.ownerEmail, primary.datasetId);
    const { authorizationProvider, googleIdentity } = createProviders(config, primaryKey);
    const identityProvider = createSharingIdentityProvider(config.rpId, () =>
      authorizationProvider.authorize(),
    );
    const controller = buildController(
      state,
      primary,
      config,
      identityProvider,
      authorizationProvider,
      googleIdentity,
    );
    try {
      const created = await controller.createDataset(datasetId, local);
      const syncedAt = new Date().toISOString();
      const connected: ProfileRecord = {
        datasetId,
        ownerEmail: state.ownerEmail,
        folderName: primary.folderName,
        displayName,
        role: "owner",
        trustedOwnerKeyId: primary.trustedOwnerKeyId,
        appFolderId: primary.appFolderId,
        fileId: created.fileId,
        lastRevisionId: created.revisionId,
        lastSyncedAt: syncedAt,
        syncMode: "encrypted",
      };
      const connectedKey = profileKey(connected.ownerEmail, connected.datasetId);
      const next: SharedSyncState = {
        ...state,
        activeProfileKey: connectedKey,
        profiles: state.profiles
          .filter(
            (profile) =>
              profileKey(profile.ownerEmail, profile.datasetId) !== state.activeProfileKey,
          )
          .concat(connected),
      };
      cachedState = next;
      await Promise.all([
        saveSharedSyncState(next),
        idbDelete(localProfilePayloadKey(state.activeProfileKey)),
        clearSharingSyncCheckpoint(),
      ]);
      return {
        state: next,
        result: {
          payload: created.value,
          syncedAt,
          revisionId: created.revisionId,
          profileKey: connectedKey,
        },
      };
    } finally {
      identityProvider.clear();
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
    const datasetId = input.datasetId ?? PRIMARY_DATASET_ID;
    const joinLandingUrl =
      typeof window !== "undefined"
        ? `${window.location.origin}${window.location.pathname}`
        : undefined;
    const invited = await active.controller.inviteParticipant({
      emailAddress: input.emailAddress,
      requestedGrants: [{ datasetId, role: input.role }],
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
    const invited = await active.controller.inviteParticipantForLink({
      emailAddress: input.emailAddress,
      requestedGrants: [{ datasetId, role: input.role }],
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
          await preservationRuntime.controller.syncDataset(
            currentProfile.datasetId,
            input.local,
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
    const grant = input.invitation.requestedGrants[0];
    const joinProfile: ProfileRecord = {
      datasetId: grant?.datasetId ?? PRIMARY_DATASET_ID,
      ownerEmail: input.ownerEmail,
      folderName,
      appFolderId: input.invitation.appFolderId,
      role: grant?.role ?? "viewer",
      trustedOwnerKeyId: input.invitation.trustedOwnerKeyId,
      needsInitialLoad: true,
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
    const datasetId = pending.invitation.requestedGrants[0]?.datasetId;
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
        const next = upsertProfile(refreshed, {
          ...acceptedProfile,
          participantEmails: {
            ...acceptedProfile.participantEmails,
            [input.response.keyId]: pending.recipientEmail,
          },
        });
        cachedState = next;
        await saveSharedSyncState(next);
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
    const next = upsertProfile(refreshed, {
      ...current,
      participantEmails: {
        ...current.participantEmails,
        [response.response.keyId]: input.recipientEmailAddress,
      },
    });
    cachedState = next;
    await saveSharedSyncState(next);
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
  const transport = new GoogleDriveSharedBackupTransport({
    appId: EASY_BC_APP_ID,
    authorizationProvider: createPollingAuthorizationProvider(
      authorizationProvider,
      state.activeProfileKey,
    ),
    folderName: profile.folderName,
    ...(isOwnedProfile(state, profile)
      ? {}
      : { selectedAppFolderId: profile.appFolderId ?? state.selectedAppFolderId }),
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
