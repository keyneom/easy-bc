import { useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowRightLeft,
  Cloud,
  Copy,
  HardDrive,
  ImagePlus,
  KeyRound,
  LockKeyhole,
  Pencil,
  RefreshCw,
  Trash2,
  UserMinus,
  UserPlus,
} from "lucide-react";
import type { SharingRole } from "@keyneom/sync-kit/sharing";
import type { WasmOptions } from "../App";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import { idbDelete, idbGet, idbSet, KV_SYNC_STATE } from "../idbStore";
import { currentRpId, passkeysSupported } from "../sync/passkey";
import { formatLastSync, forgetSyncState } from "../sync/sessionSync";
import { clearJoinLinkParams } from "../sync/sharedJoin";
import { pickSharedAppFolder } from "../sync/sharedPicker";
import { migrateLegacyEncryptedSync } from "../sync/sharedMigration";
import {
  acceptPendingKeyResponse,
  acceptResponseFromLink,
  connectActiveLocalProfile,
  deleteManagedProfile,
  disconnectProfileToLocal,
  enrollActiveControlDataset,
  grantSharedDatasetFilesFromLink,
  inviteToDatasetLink,
  isSharedSyncConfigured,
  listProfileParticipants,
  listPendingKeyResponses,
  type ManagedParticipant,
  prepareProfileOwnershipTransfer,
  profileKeyForOwnershipTransfer,
  acceptProfileOwnershipTransfer,
  renameManagedProfile,
  resetSharedSync,
  revokeParticipant,
  setupSharedSync,
  sharedSyncConfigFromEnv,
  submitJoinFromLink,
  acknowledgeSplitMigration,
  beginSplitMigration,
  closeSplitMigration,
  splitMigrationStatusForActive,
  syncActiveDataset,
  type LocalPayloadAccess,
  upgradeActiveProfileToSplit,
  updateParticipantDatasetRole,
  type SplitMigrationGrantChoices,
  type SplitMigrationStatus,
  updateParticipantRole,
  updateManagedProfileAvatar,
} from "../sync/sharedSync";
import {
  clearOwnershipTransferParams,
  KV_OUTGOING_OWNERSHIP_TRANSFER,
  parseOwnershipTransferLink,
} from "../sync/ownershipTransfer";
import {
  parseSharingJoinLinkV1,
  parseSharingResponseLinkV1,
} from "@keyneom/sync-kit/sharing";
import { profileDisplayLabel } from "../sync/profileLabels";
import {
  DATASET_PART_LABELS,
  DATASET_PART_SUMMARIES,
  DATASET_PARTS,
  type DatasetGrants,
  type DatasetPart,
  highestGrantedRole,
  SHARING_PRESETS,
} from "../sync/datasets";
import {
  buildSharedSyncPayload,
  canPublishRole,
  findProfile,
  isLocalProfile,
  isSplitProfile,
  partRole,
  restrictedParts,
  sharedPayloadToSyncPayload,
  type SharedSyncState,
} from "../sync/sharedTypes";
import {
  type EbAccessLevel,
  EbAccessSegmented,
  EbDatasetRow,
  EbModeCard,
  EbPersonCard,
  EbPresetChip,
} from "../ui/Kit";
import type { SyncPayloadV1 } from "../sync/types";
import { avatarDataUrl, encodeAvatarFromFile } from "../ui/avatarEncode";

type Props = {
  options: WasmOptions;
  periodRecords: PeriodRecord[];
  session: PersistedSession;
  sharedSyncState: SharedSyncState | null;
  onApplyPayload: (payload: SyncPayloadV1) => Promise<void>;
  /**
   * Live read/commit hooks for the active profile, handed straight to
   * sync-kit. `commit` re-merges against live local state, so an edit made
   * during the round trip is not overwritten. Setup, join, reset and migration
   * keep using onApplyPayload: those replace local state on purpose.
   */
  localPayloadAccess: LocalPayloadAccess;
  onSharedSyncStateChange: (state: SharedSyncState | null) => void;
  onSyncComplete?: (payload: SyncPayloadV1 | null) => void;
  /**
   * Which slice of the sharing engine to render: "detail" is a profile's
   * storage/people/invite screen; "join" is the standalone join flow that
   * lives with "add a profile" (docs/settings-profiles-redesign.md §4).
   */
  view?: "detail" | "join";
};

type Notice = { kind: "info" | "success" | "error"; message: string } | null;

/** Map a sync-kit role to the grid's None/View/Edit segmented level. */
function roleToLevel(role?: SharingRole): EbAccessLevel {
  if (role === "writer" || role === "admin" || role === "owner") return "edit";
  if (role === "viewer") return "view";
  return "none";
}

export function canManageParticipantAccess(
  activeRole: SharingRole | undefined,
  participant: Pick<ManagedParticipant, "role" | "isCurrentDevice" | "emailAddress">,
): boolean {
  return (
    (activeRole === "owner" || activeRole === "admin") &&
    participant.role !== "owner" &&
    !participant.isCurrentDevice &&
    Boolean(participant.emailAddress)
  );
}

export function safeParseSharingJoinLinkV1(input: string) {
  try {
    return parseSharingJoinLinkV1(input);
  } catch {
    return null;
  }
}

export function safeParseSharingResponseLinkV1(input: string) {
  try {
    return parseSharingResponseLinkV1(input);
  } catch {
    return null;
  }
}

export function sharingJoinErrorMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  if (message.includes("file manifest is not authenticated by its invitation")) {
    return "This invite was created by an older EasyBC release. Ask the owner to create and send a new invite link.";
  }
  return message;
}

export function shouldAutoSubmitJoinDeepLink(
  view: "detail" | "join",
  hasParsedJoin: boolean,
): boolean {
  return view !== "join" && hasParsedJoin;
}

const CONTROL_DATASETS_WIRED = true;

export function SyncSettings({
  options,
  periodRecords,
  session,
  sharedSyncState,
  onApplyPayload,
  localPayloadAccess,
  onSharedSyncStateChange,
  onSyncComplete,
  view = "detail",
}: Props) {
  const isJoinView = view === "join";
  const rpId = useMemo(currentRpId, []);
  const config = useMemo(() => sharedSyncConfigFromEnv(rpId), [rpId]);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState<Exclude<SharingRole, "owner">>("viewer");
  // Split profiles invite via presets; "cycle-only" is the safe default.
  // "custom" reveals the per-dataset grid to compose arbitrary grants.
  const [invitePreset, setInvitePreset] = useState<string>("cycle-only");
  const [customGrants, setCustomGrants] = useState<DatasetGrants>({ cycle: "viewer" });
  // Which participant's per-dataset access grid is expanded (keyId).
  const [expandedParticipant, setExpandedParticipant] = useState<string | null>(null);
  // Hard-cutover migration walkthrough (owner side): per-person choices.
  const [migrationSetupOpen, setMigrationSetupOpen] = useState(false);
  const [migrationGrants, setMigrationGrants] = useState<SplitMigrationGrantChoices>({});
  const [migrationStatus, setMigrationStatus] = useState<SplitMigrationStatus | null>(null);
  const [lastJoinUrl, setLastJoinUrl] = useState<string | null>(null);
  const [joinLinkInput, setJoinLinkInput] = useState("");
  const [responseLink, setResponseLink] = useState<string | null>(null);
  const sharePanelRef = useRef<HTMLDivElement | null>(null);
  const [shareAfterSetup, setShareAfterSetup] = useState(false);
  const [responseLinkInput, setResponseLinkInput] = useState("");
  const [pendingResponses, setPendingResponses] = useState<
    Array<{ responseFileId: string; invitationFileId: string; recipientEmail: string }>
  >([]);
  const [legacyAvailable, setLegacyAvailable] = useState(false);
  const [participants, setParticipants] = useState<ManagedParticipant[]>([]);
  const [ownershipTransferLink, setOwnershipTransferLink] = useState<string | null>(null);
  // The prepared link survives reloads: the owner must be able to re-copy it
  // (or discard it) later, not just in the moment it was created.
  useEffect(() => {
    void idbGet<string>(KV_OUTGOING_OWNERSHIP_TRANSFER).then((stored) => {
      if (stored) setOwnershipTransferLink(stored);
    });
  }, []);
  const pendingTransferToKeyId = useMemo(
    () =>
      ownershipTransferLink
        ? parseOwnershipTransferLink(ownershipTransferLink)?.toKeyId ?? null
        : null,
    [ownershipTransferLink],
  );
  const [incomingOwnershipTransfer, setIncomingOwnershipTransfer] = useState(() =>
    typeof window === "undefined" ? null : parseOwnershipTransferLink(window.location.href),
  );
  const [profileName, setProfileName] = useState("");
  const handledDeepLinkRef = useRef("");
  const avatarInputRef = useRef<HTMLInputElement>(null);
  const developmentRp = rpId === "localhost" || rpId === "127.0.0.1";

  useEffect(() => {
    void idbGet(KV_SYNC_STATE).then((legacy) => setLegacyAvailable(Boolean(legacy)));
  }, [sharedSyncState]);

  useEffect(() => {
    if (!sharedSyncState || !config) return;
    void listPendingKeyResponses(config)
      .then((entries) =>
        setPendingResponses(
          entries.map((entry) => ({
            responseFileId: entry.responseFileId,
            invitationFileId: entry.invitationFileId,
            recipientEmail: "",
          })),
        ),
      )
      .catch(() => setPendingResponses([]));
  }, [config, sharedSyncState]);

  const unavailable = !config || !passkeysSupported();
  const sharedConfigured = isSharedSyncConfigured(sharedSyncState);
  const activeProfile = sharedSyncState
    ? findProfile(sharedSyncState, sharedSyncState.activeProfileKey)
    : null;
  const activeIsLocal = activeProfile ? isLocalProfile(activeProfile) : true;
  const incomingOwnershipProfile = sharedSyncState && incomingOwnershipTransfer
    ? (() => {
        const key = profileKeyForOwnershipTransfer(sharedSyncState, incomingOwnershipTransfer);
        return key ? findProfile(sharedSyncState, key) : null;
      })()
    : null;

  useEffect(() => {
    if (!shareAfterSetup || !sharedConfigured || activeIsLocal) return;
    setShareAfterSetup(false);
    setNotice({
      kind: "info",
      message: "Private cloud is ready. Invite someone below to make this a shared profile.",
    });
    requestAnimationFrame(() => {
      sharePanelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    });
  }, [activeIsLocal, shareAfterSetup, sharedConfigured]);

  const localShared = () =>
    buildSharedSyncPayload(options, periodRecords, session, activeProfile);

  const applyShared = async (payload: ReturnType<typeof localShared>) => {
    await onApplyPayload(
      sharedPayloadToSyncPayload(payload, session.androidPreferences),
    );
  };

  useEffect(() => {
    if (!sharedSyncState || !activeProfile) {
      setProfileName("");
      setParticipants([]);
      return;
    }
    setProfileName(profileDisplayLabel(sharedSyncState, activeProfile));
    if (
      !config ||
      isLocalProfile(activeProfile) ||
      (activeProfile.role !== "owner" && activeProfile.role !== "admin")
    ) {
      setParticipants([]);
      return;
    }
    void listProfileParticipants(config, sharedSyncState.activeProfileKey)
      .then(setParticipants)
      .catch(() => setParticipants([]));
  }, [activeProfile, config, sharedSyncState]);

  const runSetup = async () => {
    if (!config) return;
    setBusy("setup");
    setNotice({ kind: "info", message: "Creating encrypted sync with Google Drive and your passkey…" });
    try {
      const active = sharedSyncState
        ? findProfile(sharedSyncState, sharedSyncState.activeProfileKey)
        : null;
      const { state, result } =
        active && isLocalProfile(active) && sharedConfigured
          ? await connectActiveLocalProfile(config, localShared())
          : await setupSharedSync(config, localShared());
      await applyShared(result.payload);
      await forgetSyncState();
      onSharedSyncStateChange(state);
      onSyncComplete?.(sharedPayloadToSyncPayload(result.payload, session.androidPreferences));
      setNotice({
        kind: "success",
        message: "Encrypted sync is set up. Your data lives in a private Drive folder labeled with your email.",
      });
    } catch (error) {
      setShareAfterSetup(false);
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const runSync = async () => {
    if (!config) return;
    setBusy("sync");
    setNotice({ kind: "info", message: "Merging encrypted changes…" });
    try {
      // sync-kit commits through localPayloadAccess, so local state is
      // already up to date here — no apply of the returned value.
      const result = await syncActiveDataset(config, localPayloadAccess);
      // The published payload, not the committed one — a local edit that
      // survived the reconcile must still look unpublished to the watcher.
      onSyncComplete?.(sharedPayloadToSyncPayload(result.published, session.androidPreferences));
      setNotice({ kind: "success", message: `Encrypted sync updated ${formatLastSync(result.syncedAt)}.` });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const enrollControlDataset = async () => {
    if (!config) return;
    setBusy("control-enrollment");
    try {
      const next = await enrollActiveControlDataset(config);
      onSharedSyncStateChange(next);
      setNotice({
        kind: "success",
        message:
          findProfile(next, next.activeProfileKey)?.controlEnrollment === "enrolled"
            ? "Sharing coordination is ready."
            : "Coordination file created. Re-invite existing participants so they can enroll.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const upgradeToSplit = async () => {
    if (!config) return;
    if (
      !window.confirm(
        "Upgrade this profile to per-dataset sharing?\n\n" +
          "Your data is split into four encrypted files (plan, cycle, intimacy, sensitive), " +
          "each with fresh keys, and the old single cloud file is replaced. Your data is " +
          "merged and preserved. Other devices signed into this profile pick the change up " +
          "on their next sync.",
      )
    ) {
      return;
    }
    setBusy("split-upgrade");
    setNotice({ kind: "info", message: "Upgrading this profile to per-dataset sharing…" });
    try {
      const result = await upgradeActiveProfileToSplit(config, localShared());
      await applyShared(result.payload);
      onSharedSyncStateChange(result.state);
      onSyncComplete?.(
        sharedPayloadToSyncPayload(result.payload, session.androidPreferences),
      );
      setNotice({
        kind: "success",
        message:
          "Each data section now lives in its own encrypted file — invites and person cards control access per section.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const openMigrationSetup = () => {
    const seeded: SplitMigrationGrantChoices = {};
    for (const participant of participants) {
      if (participant.isCurrentDevice || participant.role === "owner") continue;
      const role = participant.role;
      seeded[participant.keyId] = {
        plan: role,
        cycle: role,
        intimacy: role,
        sensitive: role,
      };
    }
    setMigrationGrants(seeded);
    setMigrationSetupOpen(true);
  };

  const setMigrationGrant = (keyId: string, part: DatasetPart, level: EbAccessLevel) => {
    setMigrationGrants((current) => {
      const grants = { ...(current[keyId] ?? {}) };
      if (level === "none") delete grants[part];
      else grants[part] = level === "view" ? "viewer" : "writer";
      return { ...current, [keyId]: grants };
    });
  };

  const startSplitMigration = async () => {
    if (!config) return;
    if (
      !window.confirm(
        "Reorganize this profile into per-dataset files?\n\n" +
          "Everyone keeps access according to your choices — no re-invites. " +
          "Their app will ask them to reselect the new files in Google; their " +
          "edits pause until they do. The old file stays until everyone " +
          "confirms, then moves to Drive's trash.",
      )
    ) {
      return;
    }
    setBusy("migration-begin");
    setNotice({ kind: "info", message: "Creating the new files and sharing them…" });
    try {
      const result = await beginSplitMigration(config, localShared(), migrationGrants);
      await applyShared(result.payload);
      onSharedSyncStateChange(result.state);
      onSyncComplete?.(
        sharedPayloadToSyncPayload(result.payload, session.androidPreferences),
      );
      setMigrationSetupOpen(false);
      setNotice({
        kind: "success",
        message:
          "The new files are live and shared. You'll see confirmations here as each person reselects them.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const refreshMigrationStatus = async () => {
    if (!config) return;
    setBusy("migration-status");
    try {
      setMigrationStatus(await splitMigrationStatusForActive(config));
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const finishSplitMigration = async () => {
    if (!config) return;
    if (
      !window.confirm(
        "Finish the upgrade?\n\nThe old combined file moves to your Drive trash " +
          "(recoverable for about 30 days). Everyone is already on the new files.",
      )
    ) {
      return;
    }
    setBusy("migration-close");
    try {
      const result = await closeSplitMigration(config);
      onSharedSyncStateChange(result.state);
      setMigrationStatus(null);
      setNotice({ kind: "success", message: "Upgrade complete — the old file is in Drive's trash." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const reselectMigrationFiles = async () => {
    if (!config) return;
    setBusy("migration-ack");
    setNotice({ kind: "info", message: "Opening Google to reselect the profile's files…" });
    try {
      const result = await acknowledgeSplitMigration(config);
      await applyShared(result.payload);
      onSharedSyncStateChange(result.state);
      onSyncComplete?.(
        sharedPayloadToSyncPayload(result.payload, session.androidPreferences),
      );
      setNotice({
        kind: "success",
        message: "You're on the reorganized profile now — everything synced.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const runInvite = async () => {
    if (!config || !inviteEmail.trim()) return;
    setBusy("invite");
    try {
      const splitActive = activeProfile ? isSplitProfile(activeProfile) : false;
      const grants: DatasetGrants | undefined = !splitActive
        ? undefined
        : invitePreset === "custom"
          ? customGrants
          : SHARING_PRESETS.find((entry) => entry.id === invitePreset)?.grants;
      if (splitActive && grants && Object.keys(grants).length === 0) {
        setNotice({ kind: "error", message: "Pick at least one dataset to share." });
        setBusy(null);
        return;
      }
      const invited = await inviteToDatasetLink(config, {
        emailAddress: inviteEmail.trim(),
        role: splitActive && grants
          ? (highestGrantedRole(grants) as Exclude<SharingRole, "owner">)
          : inviteRole,
        ...(splitActive && grants ? { grants } : {}),
      });
      setLastJoinUrl(invited.joinLink);
      setNotice({
        kind: "success",
        message:
          `Shared the folder with ${inviteEmail.trim()}. Send them the join link below; ` +
          "they'll send you back a response link to finish.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const runJoinFromLink = async (linkText?: string) => {
    if (!config) return;
    const source = (linkText ?? joinLinkInput).trim();
    const parsed = safeParseSharingJoinLinkV1(source);
    if (!parsed) {
      setNotice({ kind: "error", message: "That join link is missing its invitation details." });
      return;
    }
    const ownerEmail =
      new URLSearchParams(
        source.includes("://") ? new URL(source).search : source,
      ).get("owner")?.trim() ?? parsed.invitation.appId;
    setBusy("join");
    try {
      const { responseLink: link, state, initialPayload } = await submitJoinFromLink(config, {
        invitation: parsed.invitation,
        files: parsed.files,
        ownerEmail,
        local: localShared(),
      });
      await applyShared(initialPayload);
      setResponseLink(link);
      setJoinLinkInput("");
      clearJoinLinkParams();
      onSharedSyncStateChange(state);
      onSyncComplete?.(
        sharedPayloadToSyncPayload(initialPayload, session.androidPreferences),
      );
      setNotice({
        kind: "success",
        message:
          "Access granted. Send this response link back to the owner to finish joining, " +
          "then they'll accept and your data will sync.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: sharingJoinErrorMessage(error) });
    } finally {
      setBusy(null);
    }
  };

  const runGrantSharedFiles = async (linkText?: string) => {
    if (!config) return;
    const source = (linkText ?? joinLinkInput).trim();
    const parsed = safeParseSharingJoinLinkV1(source);
    if (!parsed) {
      setNotice({ kind: "error", message: "That join link is missing its invitation details." });
      return;
    }
    setBusy("grant-files");
    try {
      await grantSharedDatasetFilesFromLink(config, parsed.files);
      clearJoinLinkParams();
      setNotice({
        kind: "success",
        message: "Shared file access granted. Return to Android and tap Join shared profile.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const runAcceptResponseLink = async (linkText?: string) => {
    if (!config) return;
    const source = linkText ?? responseLinkInput;
    const parsed = safeParseSharingResponseLinkV1(source);
    if (!parsed) {
      setNotice({ kind: "error", message: "That response link is not valid." });
      return;
    }
    setBusy("accept");
    try {
      await acceptResponseFromLink(config, { response: parsed.response });
      setResponseLinkInput("");
      clearJoinLinkParams();
      setNotice({
        kind: "success",
        message: "Recipient added. They can now sync this shared profile.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const runPickFolderJoin = async () => {
    if (!config) return;
    setBusy("pick-join");
    try {
      const { GoogleWebAuthorizationProvider, GOOGLE_DRIVE_FILE_SCOPE } = await import(
        "@keyneom/sync-kit/auth/google-web"
      );
      const auth = new GoogleWebAuthorizationProvider({
        clientId: config.clientId,
        scope: GOOGLE_DRIVE_FILE_SCOPE,
      });
      const authorization = await auth.authorize();
      const picked = await pickSharedAppFolder(authorization);
      if (!picked) {
        setNotice({ kind: "info", message: "Folder selection cancelled." });
        return;
      }
      setNotice({
        kind: "success",
        message: "Legacy folder access granted. Return to the EasyBC app and tap Join again.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const runMigrateLegacy = async () => {
    if (!config) return;
    setBusy("migrate");
    try {
      await migrateLegacyEncryptedSync({
        config,
        rpId,
        clientId: config.clientId,
        options,
        periodRecords,
        session,
      });
      const { loadSharedSyncState } = await import("../sync/sharedSync");
      const state = await loadSharedSyncState();
      onSharedSyncStateChange(state);
      setLegacyAvailable(false);
      setNotice({ kind: "success", message: "Legacy encrypted sync migrated to shared encrypted sync." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const runReset = async () => {
    if (!config) return;
    if (
      !window.confirm(
        "Delete encrypted sync data in your Drive folder and replace it with this device's local data? " +
          "Shared profiles you joined are not affected.",
      )
    ) {
      return;
    }
    setBusy("reset");
    setNotice({ kind: "info", message: "Resetting encrypted sync with this device's data…" });
    try {
      const { state, result } = await resetSharedSync(config, localShared());
      await applyShared(result.payload);
      onSharedSyncStateChange(state);
      onSyncComplete?.(sharedPayloadToSyncPayload(result.payload, session.androidPreferences));
      setNotice({
        kind: "success",
        message: "Encrypted sync was reset with this device's local data.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const acceptResponse = async (entry: {
    responseFileId: string;
    invitationFileId: string;
    recipientEmail: string;
  }) => {
    if (!config || !entry.recipientEmail.trim()) return;
    setBusy("accept");
    try {
      await acceptPendingKeyResponse(config, {
        invitationFileId: entry.invitationFileId,
        responseFileId: entry.responseFileId,
        recipientEmailAddress: entry.recipientEmail.trim(),
      });
      setNotice({ kind: "success", message: "Participant accepted into encrypted sync." });
      setPendingResponses((current) =>
        current.filter((item) => item.responseFileId !== entry.responseFileId),
      );
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const renameProfile = async () => {
    if (!sharedSyncState || !profileName.trim()) return;
    setBusy("rename-profile");
    try {
      const next = await renameManagedProfile(
        sharedSyncState.activeProfileKey,
        profileName,
      );
      onSharedSyncStateChange(next);
      setNotice({ kind: "success", message: "Profile renamed." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const changeAvatar = async (file?: File) => {
    if (!sharedSyncState || !file) return;
    setBusy("avatar");
    try {
      const avatarWebp = await encodeAvatarFromFile(file);
      const next = await updateManagedProfileAvatar(
        sharedSyncState.activeProfileKey,
        avatarWebp,
      );
      onSharedSyncStateChange(next);
      setNotice({ kind: "success", message: "Profile photo updated. Sync to share it." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      if (avatarInputRef.current) avatarInputRef.current.value = "";
      setBusy(null);
    }
  };

  const removeAvatar = async () => {
    if (!sharedSyncState) return;
    setBusy("avatar");
    try {
      const next = await updateManagedProfileAvatar(sharedSyncState.activeProfileKey);
      onSharedSyncStateChange(next);
      setNotice({ kind: "success", message: "Profile photo removed. Sync to share the change." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const disconnectActiveProfile = async () => {
    if (!sharedSyncState || !activeProfile || isLocalProfile(activeProfile)) return;
    if (
      !window.confirm(
        "Keep the data currently on this device as a new local-only profile and disconnect this encrypted profile here? The cloud copy and other participants are not changed.",
      )
    ) return;
    setBusy("disconnect-profile");
    try {
      const result = await disconnectProfileToLocal(
        sharedSyncState.activeProfileKey,
        localShared(),
      );
      onSharedSyncStateChange(result.state);
      setNotice({ kind: "success", message: "This profile is now local only on this device." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const removeActiveProfile = async (deleteEverywhere: boolean) => {
    if (!sharedSyncState || !activeProfile) return;
    const label = profileDisplayLabel(sharedSyncState, activeProfile);
    const prompt = deleteEverywhere
      ? `Delete ${label} everywhere? This deletes its encrypted Google Drive file and cannot be undone.`
      : isLocalProfile(activeProfile)
        ? `Delete ${label} from this device? This local-only data cannot be recovered.`
        : `Remove ${label} from this device? The encrypted cloud profile and other participants are not changed.`;
    if (!window.confirm(prompt)) return;
    setBusy(deleteEverywhere ? "delete-everywhere" : "remove-profile");
    try {
      const result = await deleteManagedProfile(
        config,
        sharedSyncState.activeProfileKey,
        localShared(),
        deleteEverywhere,
      );
      await applyShared(result.payload);
      onSharedSyncStateChange(result.state);
      setNotice({
        kind: "success",
        message: deleteEverywhere ? "Profile deleted everywhere." : "Profile removed from this device.",
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const changeParticipantRole = async (
    participant: ManagedParticipant,
    role: Exclude<SharingRole, "owner">,
  ) => {
    if (!config || !sharedSyncState || !participant.emailAddress) return;
    setBusy(`participant-${participant.keyId}`);
    try {
      const next = await updateParticipantRole(config, {
        profileKey: sharedSyncState.activeProfileKey,
        keyId: participant.keyId,
        emailAddress: participant.emailAddress,
        role,
      });
      onSharedSyncStateChange(next);
      setParticipants((current) =>
        current.map((entry) => entry.keyId === participant.keyId ? { ...entry, role } : entry),
      );
      setNotice({ kind: "success", message: "Participant access updated." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const changeParticipantDatasetRole = async (
    participant: ManagedParticipant,
    part: DatasetPart,
    level: EbAccessLevel,
  ) => {
    if (!config || !sharedSyncState || !participant.emailAddress) return;
    const mapped = level === "none" ? "none" : level === "view" ? "viewer" : "writer";
    setBusy(`participant-${participant.keyId}`);
    try {
      const next = await updateParticipantDatasetRole(config, {
        profileKey: sharedSyncState.activeProfileKey,
        keyId: participant.keyId,
        emailAddress: participant.emailAddress,
        part,
        level: mapped,
      });
      onSharedSyncStateChange(next);
      setParticipants((current) =>
        current.map((entry) => {
          if (entry.keyId !== participant.keyId) return entry;
          const datasetRoles = { ...entry.datasetRoles };
          if (mapped === "none") delete datasetRoles[part];
          else datasetRoles[part] = mapped;
          return { ...entry, datasetRoles };
        }),
      );
      setNotice({
        kind: "success",
        message:
          mapped === "none"
            ? `Removed ${DATASET_PART_LABELS[part]} access.`
            : `${DATASET_PART_LABELS[part]} set to ${level === "view" ? "View" : "Edit"}.`,
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const removeParticipant = async (participant: ManagedParticipant) => {
    if (!config || !sharedSyncState || participant.role === "owner") return;
    if (
      !window.confirm(
        `Remove ${participant.emailAddress || "this participant"}? Their key will be removed from future encrypted revisions and EasyBC will remove the tracked direct Google Drive permission.`,
      )
    ) return;
    setBusy(`participant-${participant.keyId}`);
    try {
      const next = await revokeParticipant(
        config,
        sharedSyncState.activeProfileKey,
        participant.keyId,
      );
      onSharedSyncStateChange(next);
      setParticipants((current) => current.filter((entry) => entry.keyId !== participant.keyId));
      setNotice({ kind: "success", message: "Participant access revoked." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const offerOwnership = async (participant: ManagedParticipant) => {
    if (!config || !sharedSyncState || !participant.emailAddress) return;
    if (
      !window.confirm(
        `Transfer ownership to ${participant.emailAddress}? They must accept the link. Afterward, you will remain an admin.`,
      )
    ) return;
    setBusy(`transfer-${participant.keyId}`);
    try {
      const prepared = await prepareProfileOwnershipTransfer(config, {
        profileKey: sharedSyncState.activeProfileKey,
        toKeyId: participant.keyId,
        recipientEmailAddress: participant.emailAddress,
      });
      setOwnershipTransferLink(prepared.transferLink);
      await idbSet(KV_OUTGOING_OWNERSHIP_TRANSFER, prepared.transferLink);
      await navigator.clipboard.writeText(prepared.transferLink).catch(() => undefined);
      setNotice({
        kind: "success",
        message: `Transfer link ready for ${participant.emailAddress}. Ownership changes only after they accept.`,
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const discardOwnershipTransferLink = async () => {
    setOwnershipTransferLink(null);
    await idbDelete(KV_OUTGOING_OWNERSHIP_TRANSFER).catch(() => undefined);
  };

  const acceptOwnership = async () => {
    if (!config || !incomingOwnershipTransfer) return;
    if (
      !window.confirm(
        "Take ownership of this profile? You will control storage and sharing; the current owner will become an admin.",
      )
    ) return;
    setBusy("accept-ownership");
    try {
      const accepted = await acceptProfileOwnershipTransfer(config, incomingOwnershipTransfer);
      onSharedSyncStateChange(accepted.state);
      setIncomingOwnershipTransfer(null);
      clearOwnershipTransferParams();
      setNotice({ kind: "success", message: "Ownership transferred. You now control this profile." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  // Android hands off to the browser only for Google Picker access. The actual
  // join still happens on Android so its local profile registry is updated.
  const legacyGrantOnlyRequested =
    typeof window !== "undefined" &&
    new URLSearchParams(window.location.search).get("grant-folder") === "1";
  const fileGrantRequested =
    typeof window !== "undefined" &&
    new URLSearchParams(window.location.search).get("grant-files") === "1";

  useEffect(() => {
    if (!config) return;
    const source = window.location.href;
    if (handledDeepLinkRef.current === source) return;
    const parsedJoin = safeParseSharingJoinLinkV1(source);
    const parsedResponse = safeParseSharingResponseLinkV1(source);
    if (parsedJoin) setJoinLinkInput(source);
    if (parsedResponse) setResponseLinkInput(source);
    if (legacyGrantOnlyRequested || fileGrantRequested) return;
    // Owner opened a recipient's response link → accept it.
    if (parsedResponse) {
      handledDeepLinkRef.current = source;
      void runAcceptResponseLink(source);
      return;
    }
    // The standalone join screen must show the signed offer before any OAuth,
    // passkey, Picker, or registry mutation begins. Older combined-screen
    // routes retain their explicit handler for compatibility.
    if (shouldAutoSubmitJoinDeepLink(view, Boolean(parsedJoin))) {
      handledDeepLinkRef.current = source;
      void runJoinFromLink(source);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config, fileGrantRequested, isJoinView, legacyGrantOnlyRequested]);

  return (
    <section
      className="sync-card"
      aria-labelledby={isJoinView ? undefined : "profile-management-title"}
      aria-label={isJoinView ? "Join a shared profile" : undefined}
    >
      {!isJoinView && (
        <div className="sync-card-heading">
          <span className="sync-icon"><Cloud aria-hidden /></span>
          <div>
            <p className="eyebrow">This profile</p>
            <h3 id="profile-management-title">
              {sharedSyncState && activeProfile
                ? profileDisplayLabel(sharedSyncState, activeProfile)
                : "Storage & sharing"}
            </h3>
            <p>
              Where this profile lives, who can see it, and exactly what they can see.
            </p>
          </div>
        </div>
      )}

      {!isJoinView && (
        <div className="sync-security-row">
          <span><HardDrive aria-hidden /> Local-first</span>
          <span><KeyRound aria-hidden /> Passkey protected</span>
          <span><LockKeyhole aria-hidden /> Encrypted before Drive</span>
        </div>
      )}

      {!isJoinView && incomingOwnershipTransfer && (
        <div className="sync-notice info" role="status">
          <div>
            <strong>Ownership offered to you</strong>
            <span>
              {incomingOwnershipProfile
                ? `Accept to control ${profileDisplayLabel(sharedSyncState!, incomingOwnershipProfile)}. The current owner becomes an admin.`
                : "Open the shared profile on this device first, then accept to control storage and sharing."}
            </span>
          </div>
          <button
            type="button"
            disabled={unavailable || busy !== null || !incomingOwnershipProfile}
            onClick={() => void acceptOwnership()}
          >
            <ArrowRightLeft aria-hidden />
            {busy === "accept-ownership" ? "Transferring…" : "Accept ownership"}
          </button>
        </div>
      )}

      {!isJoinView && sharedSyncState && activeProfile && (
        <div className="profile-detail-card">
          <div className="profile-detail-heading">
            <div>
              <p className="eyebrow">Selected profile</p>
              <h4>{profileDisplayLabel(sharedSyncState, activeProfile)}</h4>
            </div>
            <span className={`profile-kind-badge ${activeIsLocal ? "local" : "encrypted"}`}>
              {activeIsLocal
                ? "Local only"
                : activeProfile.ownerEmail.toLowerCase() !== sharedSyncState.ownerEmail.toLowerCase()
                  ? "Shared with you"
                  : Object.keys(activeProfile.participantEmails ?? {}).length > 0
                    ? "Shared encrypted"
                    : "Private encrypted"}
            </span>
          </div>

          <div className="profile-name-row">
            <img
              className="profile-photo-preview"
              src={activeProfile.avatarWebp ? avatarDataUrl(activeProfile.avatarWebp) : undefined}
              alt=""
              hidden={!activeProfile.avatarWebp}
            />
            <label className="field">
              <span>Profile name</span>
              <input
                type="text"
                value={profileName}
                onChange={(event) => setProfileName(event.target.value)}
              />
            </label>
            <button
              type="button"
              className="ghost"
              disabled={busy !== null || !profileName.trim()}
              onClick={() => void renameProfile()}
            >
              <Pencil aria-hidden />
              Rename
            </button>
          </div>

          <div className="profile-management-actions">
            <input
              ref={avatarInputRef}
              type="file"
              accept="image/*"
              hidden
              onChange={(event) => void changeAvatar(event.target.files?.[0])}
            />
            <button
              type="button"
              className="ghost"
              disabled={busy !== null}
              onClick={() => avatarInputRef.current?.click()}
            >
              <ImagePlus aria-hidden />
              {activeProfile.avatarWebp ? "Change photo" : "Add photo"}
            </button>
            {activeProfile.avatarWebp && (
              <button
                type="button"
                className="ghost"
                disabled={busy !== null}
                onClick={() => void removeAvatar()}
              >
                Remove photo
              </button>
            )}
          </div>

          {(() => {
            // Storage mode selector — same three cards as Android (§6.1).
            const sharedWithYou =
              activeProfile.ownerEmail.toLowerCase() !==
              sharedSyncState.ownerEmail.toLowerCase();
            const participantCount = Object.keys(
              activeProfile.participantEmails ?? {},
            ).length;
            const currentMode = activeIsLocal
              ? "local"
              : sharedWithYou || participantCount > 0
                ? "shared"
                : "private";
            if (sharedWithYou) {
              return (
                <div className="sync-notice info">
                  <div>
                    <strong>Shared by {activeProfile.ownerEmail}</strong>
                    <span>Your access is {activeProfile.role}. The owner controls storage and sharing.</span>
                  </div>
                </div>
              );
            }
            return (
              <div className="profile-mode-cards" role="radiogroup" aria-label="Where this profile lives">
                <EbModeCard
                  mode="local"
                  title="This device"
                  description="Stays in this browser. No account needed."
                  selected={currentMode === "local"}
                  pending={busy === "setup" || busy === "disconnect-profile"}
                  disabled={busy !== null}
                  onSelect={() => {
                    if (currentMode !== "local") void disconnectActiveProfile();
                  }}
                />
                <EbModeCard
                  mode="private"
                  title="Private cloud"
                  description="Encrypted in your Google Drive; your other devices unlock it with your passkey. Only you."
                  selected={currentMode === "private"}
                  pending={busy === "setup"}
                  disabled={unavailable || busy !== null}
                  onSelect={() => {
                    if (currentMode === "local") void runSetup();
                    else if (currentMode === "shared") {
                      setNotice({
                        kind: "info",
                        message: "Remove everyone with access before making this profile private.",
                      });
                    }
                  }}
                />
                <EbModeCard
                  mode="shared"
                  title="Shared"
                  description="Private cloud, plus invited people can view or edit what you choose."
                  selected={currentMode === "shared"}
                  pending={busy === "setup"}
                  disabled={unavailable || busy !== null}
                  onSelect={() => {
                    if (currentMode === "local") {
                      setShareAfterSetup(true);
                      void runSetup();
                    } else if (currentMode === "private") {
                      setNotice({
                        kind: "info",
                        message: "Invite someone below to make this a shared profile.",
                      });
                      sharePanelRef.current?.scrollIntoView({
                        behavior: "smooth",
                        block: "start",
                      });
                    }
                  }}
                />
              </div>
            );
          })()}

          <div className="profile-management-actions">
            <button
              type="button"
              className="ghost danger"
              disabled={busy !== null || sharedSyncState.profiles.length <= 1}
              onClick={() => void removeActiveProfile(false)}
            >
              <Trash2 aria-hidden />
              {activeIsLocal
                ? "Delete local profile"
                : "Remove from this device"}
            </button>
            {!activeIsLocal && activeProfile.role !== "owner" && (
              <button
                type="button"
                className="ghost"
                disabled={busy !== null}
                onClick={() => void disconnectActiveProfile()}
              >
                <HardDrive aria-hidden />
                Keep local copy
              </button>
            )}
            {!activeIsLocal && activeProfile.role === "owner" && (
              <button
                type="button"
                className="ghost danger"
                disabled={busy !== null || sharedSyncState.profiles.length <= 1}
                onClick={() => void removeActiveProfile(true)}
              >
                <Trash2 aria-hidden />
                Delete everywhere
              </button>
            )}
          </div>
        </div>
      )}

      {!isJoinView && sharedSyncState && activeProfile?.lastSyncedAt && (
        <div className="sync-connected">
          <span className="status-dot" aria-hidden />
          <div>
            <strong>{profileDisplayLabel(sharedSyncState, activeProfile)}</strong>
            <span>Last encrypted update {formatLastSync(activeProfile.lastSyncedAt)}</span>
          </div>
        </div>
      )}

      {!isJoinView &&
        sharedSyncState &&
        activeProfile &&
        !activeIsLocal &&
        activeProfile.role === "owner" &&
        !isSplitProfile(activeProfile) &&
        (Object.keys(activeProfile.participantEmails ?? {}).length === 0 ? (
          <div className="sync-notice info">
            <div>
              <strong>Upgrade to per-dataset sharing</strong>
              <span>
                Splits this profile into four encrypted files — cycle, plan,
                intimacy, sensitive — each with its own keys, so you can share
                each section separately. The old single file is replaced.
              </span>
            </div>
            <button
              type="button"
              disabled={busy !== null}
              onClick={() => void upgradeToSplit()}
            >
              {busy === "split-upgrade" ? "Upgrading…" : "Upgrade"}
            </button>
          </div>
        ) : activeProfile.openMigrationId ? null : (
          <>
            <div className="sync-notice info">
              <div>
                <strong>Upgrade to per-dataset sharing</strong>
                <span>
                  Your data splits into four encrypted files so you control
                  exactly what each person sees. Everyone keeps their access —
                  their app will ask them to reselect the new files in Google,
                  the one step Google requires. No re-invites.
                </span>
              </div>
              <button
                type="button"
                disabled={busy !== null}
                onClick={() =>
                  migrationSetupOpen ? setMigrationSetupOpen(false) : openMigrationSetup()
                }
              >
                {migrationSetupOpen ? "Cancel" : "Choose access…"}
              </button>
            </div>
            {migrationSetupOpen && (
              <div className="dataset-access-panel">
                <p className="eyebrow">What each person will see after the upgrade</p>
                {participants
                  .filter(
                    (participant) =>
                      !participant.isCurrentDevice && participant.role !== "owner",
                  )
                  .map((participant) => (
                    <EbPersonCard
                      key={participant.keyId}
                      name={participant.emailAddress || "Unknown participant"}
                      email={participant.emailAddress}
                      colorKey={participant.emailAddress ?? participant.keyId}
                    >
                      <div className="participant-grid">
                        {DATASET_PARTS.map((part) => (
                          <EbDatasetRow
                            key={part}
                            dataset={part}
                            title={DATASET_PART_LABELS[part]}
                            summary={DATASET_PART_SUMMARIES[part]}
                            trailing={
                              <EbAccessSegmented
                                label={`${participant.emailAddress}'s access to ${DATASET_PART_LABELS[part]}`}
                                value={roleToLevel(
                                  migrationGrants[participant.keyId]?.[part],
                                )}
                                disabled={busy !== null}
                                onChange={(level) =>
                                  setMigrationGrant(participant.keyId, part, level)
                                }
                              />
                            }
                          />
                        ))}
                      </div>
                    </EbPersonCard>
                  ))}
                <button
                  type="button"
                  disabled={busy !== null}
                  onClick={() => void startSplitMigration()}
                >
                  {busy === "migration-begin" ? "Reorganizing…" : "Start upgrade"}
                </button>
                <p className="field-hint">
                  Creates the new files with fresh keys, shares them to
                  everyone's existing keys per your choices, and freezes the old
                  file. The old file is kept until everyone confirms, then moved
                  to Drive's trash — never deleted outright.
                </p>
              </div>
            )}
          </>
        ))}

      {!isJoinView &&
        sharedSyncState &&
        activeProfile &&
        !activeIsLocal &&
        activeProfile.role === "owner" &&
        activeProfile.openMigrationId && (
          <div className="sync-notice info">
            <div>
              <strong>Upgrade in progress</strong>
              <span>
                {migrationStatus
                  ? migrationStatus.pending.length === 0
                    ? "Everyone has reselected the new files — you can finish now."
                    : `Waiting on ${migrationStatus.pending
                        .map((entry) => entry.email ?? `key ${entry.keyId.slice(0, 8)}…`)
                        .join(", ")} to reselect the new files.`
                  : "Waiting for people to reselect the new files in Google. Their edits pause until they do."}
              </span>
            </div>
            <button
              type="button"
              disabled={busy !== null}
              onClick={() => void refreshMigrationStatus()}
            >
              {busy === "migration-status" ? "Checking…" : "Check status"}
            </button>
            <button
              type="button"
              disabled={
                busy !== null ||
                !migrationStatus ||
                migrationStatus.pending.length > 0
              }
              onClick={() => void finishSplitMigration()}
            >
              {busy === "migration-close" ? "Finishing…" : "Finish upgrade"}
            </button>
          </div>
        )}

      {!isJoinView && sharedSyncState && activeProfile?.pendingMigration && (
        <div className="sync-notice info">
          <div>
            <strong>{activeProfile.ownerEmail} reorganized this profile</strong>
            <span>
              Pick the new files in Google to keep your access — nothing else
              changes, and your edits pause until you do. On a computer, hold
              Ctrl (⌘ on Mac) to select several files at once; on a phone the
              picker may only take one at a time — just tap Reselect again
              until every file is picked.
            </span>
          </div>
          <button
            type="button"
            disabled={busy !== null}
            onClick={() => void reselectMigrationFiles()}
          >
            {busy === "migration-ack" ? "Opening Google…" : "Reselect files"}
          </button>
        </div>
      )}

      {!isJoinView &&
        CONTROL_DATASETS_WIRED &&
        activeProfile &&
        !activeIsLocal &&
        (activeProfile.role === "owner" || activeProfile.role === "admin") &&
        activeProfile.controlEnrollment !== "enrolled" && (
          <div className="sync-notice info">
            <div>
              <strong>Set up sharing coordination</strong>
              <span>
                This encrypted control file coordinates verified membership and future migrations.
              </span>
            </div>
            <button
              type="button"
              disabled={busy !== null}
              onClick={() => void enrollControlDataset()}
            >
              {busy === "control-enrollment" ? "Setting up…" : "Set up"}
            </button>
          </div>
        )}

      {!isJoinView && sharedSyncState && activeProfile && !activeIsLocal && isSplitProfile(activeProfile) && (
        <div className="dataset-access-panel">
          <p className="eyebrow">
            {activeProfile.role === "owner" ? "What this profile stores" : "What you can see"}
          </p>
          {DATASET_PARTS.map((part) => {
            const role = partRole(activeProfile, part);
            return (
              <EbDatasetRow
                key={part}
                dataset={part}
                title={DATASET_PART_LABELS[part]}
                summary={
                  role === undefined
                    ? "Not shared with you"
                    : role === "owner"
                      ? DATASET_PART_SUMMARIES[part]
                      : canPublishRole(role)
                        ? "You can edit"
                        : "View only"
                }
              />
            );
          })}
          {restrictedParts(activeProfile).length > 0 && (
            <p className="field-hint">
              Sections that aren't shared with you stay hidden across the app — the owner
              controls them.
            </p>
          )}
        </div>
      )}

      {!config && (
        <p className="sync-notice sync-notice-info">
          Encrypted sync needs a web OAuth client ID before these controls can be used.
        </p>
      )}
      {!passkeysSupported() && (
        <p className="sync-notice sync-notice-error">
          This browser does not expose passkeys in a secure context.
        </p>
      )}
      {developmentRp && (
        <p className="sync-notice sync-notice-info">
          Local development creates a passkey for <strong>{rpId}</strong>, not keyneom.github.io.
        </p>
      )}
      {!isJoinView && legacyAvailable && !sharedConfigured && (
        <p className="sync-notice sync-notice-info">
          This device still uses legacy app-data encrypted sync.
          <button type="button" className="ghost" disabled={busy !== null} onClick={() => void runMigrateLegacy()}>
            Migrate to shared encrypted sync
          </button>
        </p>
      )}
      {legacyGrantOnlyRequested && (
        <p className="sync-notice sync-notice-info">
          The EasyBC app needs access to the folder that was shared with you.
          Select the folder named &ldquo;EasyBC &mdash; owner@email&rdquo; below, then return
          to the app and tap Join again.
          <button type="button" className="ghost" disabled={busy !== null} onClick={() => void runPickFolderJoin()}>
            Select the shared folder
          </button>
        </p>
      )}
      {fileGrantRequested && (
        <p className="sync-notice sync-notice-info">
          Select the EasyBC <code>*.sync-kit.json</code> file shared with you.
          This grants Android and web access without joining or merging data in this browser.
          <button
            type="button"
            className="ghost"
            disabled={!config || busy !== null}
            onClick={() => void runGrantSharedFiles()}
          >
            Select shared sync file
          </button>
        </p>
      )}
      {notice && <p className={`sync-notice sync-notice-${notice.kind}`} role="status">{notice.message}</p>}

      {isJoinView && (
      <div className="sync-share-panel">
        <label className="field">
          <span>Join link</span>
          <input
            type="text"
            placeholder="Paste the join link someone sent you"
            value={joinLinkInput}
            onChange={(event) => setJoinLinkInput(event.target.value)}
          />
        </label>
        {(() => {
          // Structured preview (docs/join-flow.md, mockup J1): show exactly
          // what the link grants before anything runs — never a blind join.
          const source = joinLinkInput.trim();
          if (!source) return null;
          const parsed = safeParseSharingJoinLinkV1(source);
          if (!parsed || parsed.files.length === 0) return null;
          let previewOwner: string | null = null;
          try {
            previewOwner =
              new URLSearchParams(
                source.includes("://") ? new URL(source).search : source,
              )
                .get("owner")
                ?.trim() ?? null;
          } catch {
            previewOwner = null;
          }
          return (
            <div className="dataset-access-panel">
              <p className="eyebrow">
                {previewOwner ?? "The owner"} is sharing these with you
              </p>
              {parsed.files.map((file) => {
                const isControl = file.datasetId.endsWith(".control");
                const part = isControl
                  ? null
                  : (["cycle", "intimacy", "sensitive"] as const).find(
                      (candidate) => file.datasetId.endsWith(`.${candidate}`),
                    ) ?? ("plan" as const);
                return (
                  <EbDatasetRow
                    key={file.fileId}
                    dataset={part ?? "plan"}
                    title={
                      isControl
                        ? "Sharing coordination file"
                        : DATASET_PART_LABELS[part ?? "plan"]
                    }
                    summary={
                      isControl
                        ? "Keeps your access up to date"
                        : file.role === "writer"
                          ? "You can edit"
                          : "View only"
                    }
                  />
                );
              })}
              <p className="field-hint">
                Joining confirms it's you (Google + passkey) first, then Google
                asks you to select these files yourself — the one manual step.
              </p>
            </div>
          );
        })()}
        <button
          type="button"
          disabled={
            busy !== null ||
            unavailable ||
            !joinLinkInput.trim() ||
            fileGrantRequested ||
            legacyGrantOnlyRequested
          }
          onClick={() => void runJoinFromLink()}
        >
          {busy === "join" ? "Joining…" : "Join shared profile"}
        </button>
      </div>
      )}

      {isJoinView && responseLink && (
        <div className="sync-share-panel">
          <p className="field-hint">
            Send this response link back to the person who invited you — they tap it to finish adding you.
          </p>
          <button
            type="button"
            className="ghost"
            onClick={() => void navigator.clipboard.writeText(responseLink)}
          >
            <Copy aria-hidden />
            Copy response link
          </button>
        </div>
      )}

      {!isJoinView &&
        sharedConfigured &&
        !activeIsLocal &&
        (activeProfile?.role === "owner" || activeProfile?.role === "admin") && (
      <div className="sync-share-panel">
        <label className="field">
          <span>Finish a share you sent</span>
          <input
            type="text"
            placeholder="Paste the response link the recipient sent back"
            value={responseLinkInput}
            onChange={(event) => setResponseLinkInput(event.target.value)}
          />
        </label>
        <button
          type="button"
          disabled={busy !== null || !responseLinkInput.trim()}
          onClick={() => void runAcceptResponseLink()}
        >
          {busy === "accept" ? "Accepting…" : "Accept response link"}
        </button>
      </div>
      )}

      {!isJoinView && !activeIsLocal && (
        <div className="sync-actions">
            <button type="button" disabled={unavailable || busy !== null} onClick={() => void runSync()}>
              <RefreshCw aria-hidden className={busy === "sync" ? "spin" : undefined} />
              {busy === "sync" ? "Merging…" : "Merge encrypted changes"}
            </button>
            {activeProfile?.role === "owner" && (
              <button type="button" className="ghost" disabled={unavailable || busy !== null} onClick={() => void runReset()}>
                <Trash2 aria-hidden />
                {busy === "reset" ? "Resetting…" : "Reset encrypted sync"}
              </button>
            )}
        </div>
      )}

      {!isJoinView &&
        sharedConfigured &&
        !activeIsLocal &&
        (activeProfile?.role === "owner" || activeProfile?.role === "admin") && (
        <div ref={sharePanelRef} className="sync-share-panel">
          <p className="eyebrow">Share encrypted data</p>
          <label className="field">
            <span>Email address</span>
            <input
              type="email"
              value={inviteEmail}
              onChange={(event) => setInviteEmail(event.target.value)}
              placeholder="person@example.com"
            />
          </label>
          {activeProfile && isSplitProfile(activeProfile) ? (
            <div className="field">
              <span>What can they see?</span>
              <div className="invite-presets">
                {SHARING_PRESETS.map((preset) => (
                  <EbPresetChip
                    key={preset.id}
                    selected={invitePreset === preset.id}
                    onClick={() => setInvitePreset(preset.id)}
                  >
                    {preset.label}
                  </EbPresetChip>
                ))}
                <EbPresetChip
                  selected={invitePreset === "custom"}
                  onClick={() => setInvitePreset("custom")}
                >
                  Custom…
                </EbPresetChip>
              </div>
              {invitePreset === "custom" ? (
                <div className="invite-custom-grid">
                  {DATASET_PARTS.map((part) => (
                    <EbDatasetRow
                      key={part}
                      dataset={part}
                      title={DATASET_PART_LABELS[part]}
                      summary={DATASET_PART_SUMMARIES[part]}
                      trailing={
                        <EbAccessSegmented
                          label={`${DATASET_PART_LABELS[part]} access`}
                          value={roleToLevel(customGrants[part])}
                          onChange={(level) =>
                            setCustomGrants((current) => {
                              const next = { ...current };
                              if (level === "none") delete next[part];
                              else next[part] = level === "view" ? "viewer" : "writer";
                              return next;
                            })
                          }
                        />
                      }
                    />
                  ))}
                </div>
              ) : (
                <span className="field-hint">
                  {SHARING_PRESETS.find((preset) => preset.id === invitePreset)?.description}
                </span>
              )}
            </div>
          ) : (
            <label className="field">
              <span>Access</span>
              <select value={inviteRole} onChange={(event) => setInviteRole(event.target.value as typeof inviteRole)}>
                <option value="viewer">Viewer (read-only)</option>
                <option value="writer">Writer (can edit and sync)</option>
              </select>
              <span className="field-hint">
                This profile predates per-dataset sharing — invites cover everything in it.
              </span>
            </label>
          )}
          <button type="button" disabled={unavailable || busy !== null || !inviteEmail.trim()} onClick={() => void runInvite()}>
            <UserPlus aria-hidden />
            {busy === "invite" ? "Inviting…" : "Invite by email"}
          </button>
          {lastJoinUrl && (
            <button
              type="button"
              className="ghost"
              onClick={() => void navigator.clipboard.writeText(lastJoinUrl)}
            >
              <Copy aria-hidden />
              Copy join link
            </button>
          )}
          <div className="participant-list">
            <div className="participant-list-heading">
              <strong>People with access</strong>
              <span>{participants.length} {participants.length === 1 ? "person" : "people"}</span>
            </div>
            {participants.map((participant) => {
              const split = activeProfile ? isSplitProfile(activeProfile) : false;
              const name =
                participant.emailAddress ||
                (participant.isCurrentDevice ? "You (this identity)" : "Unknown participant");
              const canManage = canManageParticipantAccess(activeProfile?.role, participant);
              const summary = split
                ? DATASET_PARTS.filter((part) => participant.datasetRoles?.[part])
                    .map((part) => `${DATASET_PART_LABELS[part]} (${participant.datasetRoles![part]})`)
                    .join(" · ") || "No dataset access"
                : participant.role;
              const expanded = expandedParticipant === participant.keyId;
              return (
                <EbPersonCard
                  key={participant.keyId}
                  name={name}
                  email={participant.emailAddress}
                  colorKey={participant.emailAddress ?? participant.keyId}
                  trust={
                    participant.role === "owner" || participant.isCurrentDevice
                      ? undefined
                      : participant.trust
                  }
                >
                  <div className="participant-summary">
                    <span className="field-hint">
                      {summary}
                      {!participant.emailAddress && !participant.isCurrentDevice
                        ? ` · key ${participant.keyId.slice(0, 10)}…`
                        : ""}
                      {participant.keyId === pendingTransferToKeyId
                        ? " · ownership transfer pending"
                        : ""}
                    </span>
                    {canManage && (
                      <div className="participant-actions">
                        {split && (
                          <button
                            type="button"
                            className="ghost"
                            onClick={() =>
                              setExpandedParticipant(expanded ? null : participant.keyId)
                            }
                          >
                            {expanded ? "Done" : "Manage access"}
                          </button>
                        )}
                        <button
                          type="button"
                          className="ghost danger"
                          disabled={busy !== null}
                          onClick={() => void removeParticipant(participant)}
                        >
                          <UserMinus aria-hidden />
                          Remove
                        </button>
                      </div>
                    )}
                  </div>
                  {canManage && !split && (
                    <label className="field">
                      <span>Access</span>
                      <select
                        aria-label={`Access for ${participant.emailAddress}`}
                        value={participant.role}
                        disabled={busy !== null}
                        onChange={(event) =>
                          void changeParticipantRole(
                            participant,
                            event.target.value as Exclude<SharingRole, "owner">,
                          )
                        }
                      >
                        <option value="viewer">Viewer</option>
                        <option value="writer">Writer</option>
                        <option value="admin">Admin</option>
                      </select>
                      {activeProfile?.role === "owner" &&
                        participant.emailAddress &&
                        participant.keyId !== pendingTransferToKeyId && (
                          <button
                            type="button"
                            className="ghost"
                            disabled={busy !== null}
                            onClick={() => void offerOwnership(participant)}
                          >
                            <ArrowRightLeft aria-hidden />
                            Transfer ownership to {participant.emailAddress}…
                          </button>
                        )}
                    </label>
                  )}
                  {canManage && split && expanded && (
                    <div className="participant-grid">
                      {DATASET_PARTS.map((part) => {
                        const has = Boolean(participant.datasetRoles?.[part]);
                        return (
                          <EbDatasetRow
                            key={part}
                            dataset={part}
                            title={DATASET_PART_LABELS[part]}
                            summary={DATASET_PART_SUMMARIES[part]}
                            trailing={
                              <EbAccessSegmented
                                label={`${name}'s access to ${DATASET_PART_LABELS[part]}`}
                                value={roleToLevel(participant.datasetRoles?.[part])}
                                disabled={busy !== null || !has}
                                onChange={(level) =>
                                  void changeParticipantDatasetRole(participant, part, level)
                                }
                              />
                            }
                          />
                        );
                      })}
                      <span className="field-hint">
                        To add a dataset this person has never received, invite them again
                        with that dataset — sharing can’t add a file they hold no key for.
                      </span>
                      {activeProfile?.role === "owner" &&
                        participant.emailAddress &&
                        participant.keyId !== pendingTransferToKeyId &&
                        DATASET_PARTS.every((part) => participant.datasetRoles?.[part]) && (
                          <button
                            type="button"
                            className="ghost"
                            disabled={busy !== null}
                            onClick={() => void offerOwnership(participant)}
                          >
                            <ArrowRightLeft aria-hidden />
                            Transfer ownership to {participant.emailAddress}…
                          </button>
                        )}
                    </div>
                  )}
                </EbPersonCard>
              );
            })}
          </div>
          {ownershipTransferLink && (
            <div className="sync-notice info">
              <div>
                <strong>Transfer link ready</strong>
                <span>
                  Send it to the new owner — nothing changes until they accept it inside
                  EasyBC. Google Drive also emails them about the file transfer, but that
                  email alone doesn&rsquo;t finish the switch. Discarding only removes the
                  link from this device; the pending Drive transfer stays until accepted
                  or cancelled in Google Drive.
                </span>
              </div>
              <div>
                <button
                  type="button"
                  className="ghost"
                  onClick={() => void navigator.clipboard.writeText(ownershipTransferLink)}
                >
                  <Copy aria-hidden />
                  Copy link
                </button>
                <button
                  type="button"
                  className="ghost"
                  disabled={busy !== null}
                  onClick={() => void discardOwnershipTransferLink()}
                >
                  Discard link
                </button>
              </div>
            </div>
          )}
          {pendingResponses.length > 0 && (
            <div className="sync-pending-list">
              <p className="field-hint">Pending join requests</p>
              {pendingResponses.map((entry) => (
                <div key={entry.responseFileId} className="sync-pending-item">
                  <input
                    type="email"
                    placeholder="Recipient email"
                    value={entry.recipientEmail}
                    onChange={(event) =>
                      setPendingResponses((current) =>
                        current.map((item) =>
                          item.responseFileId === entry.responseFileId
                            ? { ...item, recipientEmail: event.target.value }
                            : item,
                        ),
                      )
                    }
                  />
                  <button type="button" disabled={busy !== null} onClick={() => void acceptResponse(entry)}>
                    Accept
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {!isJoinView && (
        <p className="field-hint sync-footnote">
          Each owner gets a Drive folder named with their email, for example EasyBC — you@example.com.
          Reloading this tab locks encrypted sync until you unlock with your passkey again.
        </p>
      )}
    </section>
  );
}
