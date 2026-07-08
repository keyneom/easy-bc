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
  PRIMARY_DATASET_ID,
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
  const profile = profileForActive(state);
  const { authorizationProvider, googleIdentity } = createProviders(
    config,
    state.activeProfileKey,
  );
  const identityProvider = createSharingIdentityProvider(config.rpId, () =>
    authorizationProvider.authorize(),
  );
  runtime = {
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
    const identityProvider = createSharingIdentityProvider(config.rpId, () =>
      authorizationProvider.authorize(),
    );
    await identityProvider.getOrCreate();
    const identity = await identityProvider.get();
    const provisional: SharedSyncState = {
      schemaVersion: 1,
      rpId: config.rpId,
      ownerEmail,
      activeProfileKey: profileKey(ownerEmail, PRIMARY_DATASET_ID),
      profiles: [
        {
          datasetId: PRIMARY_DATASET_ID,
          ownerEmail,
          folderName: easyBcSyncFolderName(ownerEmail),
          role: "owner",
          trustedOwnerKeyId: identity.publicKey.keyId,
        },
      ],
    };
    try {
      const controller = buildController(
        provisional,
        provisional.profiles[0],
        config,
        identityProvider,
        authorizationProvider,
        googleIdentity,
      );
      for (const dataset of await controller.listDatasets()) {
        await controller.deleteDataset(dataset.datasetId).catch(() => undefined);
      }
    } catch {
      // Best-effort Drive cleanup; setup recreates from local data.
    }
    await forgetSharedSync();
    return setupSharedSync(config, local);
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
    const provisional: SharedSyncState = {
      schemaVersion: 1,
      rpId: config.rpId,
      ownerEmail,
      activeProfileKey: profileKey(ownerEmail, PRIMARY_DATASET_ID),
      profiles: [
        {
          datasetId: PRIMARY_DATASET_ID,
          ownerEmail,
          folderName,
          role: "owner",
          trustedOwnerKeyId: identity.publicKey.keyId,
        },
      ],
    };
    try {
      cachedState = provisional;
      await saveSharedSyncState(provisional);
      const controller = buildController(
        provisional,
        provisional.profiles[0],
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
      const nextState = createInitialSharedSyncState({
        rpId: config.rpId,
        ownerEmail,
        folderName,
        trustedOwnerKeyId: identity.publicKey.keyId,
        appFolderId: storage.appFolderId,
        fileId: created.fileId,
        lastRevisionId: created.revisionId,
      });
      cachedState = nextState;
      await saveSharedSyncState(nextState);
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
    if (!canPublishRole(profile.role)) {
      const loaded = await active.controller.loadDataset(profile.datasetId);
      return {
        payload: loaded.value,
        syncedAt: new Date().toISOString(),
        revisionId: loaded.revisionId,
        profileKey: active.state.activeProfileKey,
      };
    }
    const result = await active.controller.syncDataset(profile.datasetId, local);
    const updatedProfile: ProfileRecord = {
      ...profile,
      fileId: result.fileId,
      lastRevisionId: result.revisionId,
      lastSyncedAt: new Date().toISOString(),
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
    const active = await ensureRuntime(config, {} as SharedSyncPayloadV1);
    const loaded = await active.controller.loadDataset(profile.datasetId);
    const updatedProfile: ProfileRecord = {
      ...profile,
      fileId: loaded.fileId,
      lastRevisionId: loaded.revisionId,
      lastSyncedAt: new Date().toISOString(),
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
    const datasetId = input.datasetId ?? PRIMARY_DATASET_ID;
    const invited = await active.controller.inviteParticipantForLink({
      emailAddress: input.emailAddress,
      requestedGrants: [{ datasetId, role: input.role }],
    });
    await idbSet(pendingInviteKey(invited.invitation.exchangeId), {
      invitation: invited.invitation,
      recipientEmail: input.emailAddress,
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
    const joinLink = `${baseLink}&owner=${encodeURIComponent(state.ownerEmail)}`;
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
  },
): Promise<{ responseLink: string }> {
  return serialized(async () => {
    disposeRuntime();
    const bootstrapProfileId = "__easybc-bootstrap__";
    const bootstrap = createProviders(config, bootstrapProfileId);
    const authorization = await authorizeAndRemember(
      bootstrap.authorizationProvider,
      bootstrapProfileId,
    );
    // Grant the app drive.file access to the shared dataset file(s).
    await pickSharedDatasetFiles(authorization);

    const folderName = easyBcSyncFolderName(input.ownerEmail);
    const grant = input.invitation.requestedGrants[0];
    const joinProfile: ProfileRecord = {
      datasetId: grant?.datasetId ?? PRIMARY_DATASET_ID,
      ownerEmail: input.ownerEmail,
      folderName,
      appFolderId: input.invitation.appFolderId,
      role: grant?.role ?? "viewer",
      trustedOwnerKeyId: input.invitation.trustedOwnerKeyId,
    };
    const profileKeyValue = profileKey(joinProfile.ownerEmail, joinProfile.datasetId);
    const { authorizationProvider, googleIdentity } = createProviders(config, profileKeyValue);
    await sharedAuthCache.save({
      profileId: profileKeyValue,
      accessToken: authorization.accessToken,
      expiresAt: authorization.expiresAt ?? Date.now() + 3_600_000,
    });
    rememberTokenExpiry(profileKeyValue, authorization.expiresAt);
    let state = await getCachedState();
    state = {
      schemaVersion: 1,
      rpId: config.rpId,
      ownerEmail: state?.ownerEmail ?? input.ownerEmail,
      ...(state ?? {}),
      selectedAppFolderId: input.invitation.appFolderId,
      activeProfileKey: profileKeyValue,
      profiles: upsertProfile(
        state ?? {
          schemaVersion: 1,
          rpId: config.rpId,
          ownerEmail: input.ownerEmail,
          activeProfileKey: profileKeyValue,
          profiles: [],
        },
        joinProfile,
      ).profiles,
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
    const response = await controller.submitKeyResponseFromInvitation(
      input.invitation,
      input.files,
    );
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
    return { responseLink: buildSharingResponseLinkV1({ landingUrl, response }) };
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
    const active = await ensureRuntime(config, {} as SharedSyncPayloadV1);
    const pending = await idbGet<PendingInvite>(
      pendingInviteKey(input.response.exchangeId),
    );
    if (!pending) {
      throw new Error(
        "No pending invitation matches this response. Send a fresh invite link.",
      );
    }
    await active.controller.acceptKeyResponseFromPayload({
      invitation: pending.invitation,
      response: input.response,
      recipientEmailAddress: pending.recipientEmail,
    });
    await idbDelete(pendingInviteKey(input.response.exchangeId));
  });
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
    await active.controller.acceptKeyResponse({
      invitation,
      responseFileId: input.responseFileId,
      recipientEmailAddress: input.recipientEmailAddress,
    });
    const profile = profileForActive(active.state);
    await active.controller.reconcileDrivePermissions({
      datasetId: profile.datasetId,
      participantEmails: { [response.response.keyId]: input.recipientEmailAddress },
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
