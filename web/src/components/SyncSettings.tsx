import { useEffect, useMemo, useRef, useState } from "react";
import {
  Cloud,
  Copy,
  HardDrive,
  ImagePlus,
  KeyRound,
  LockKeyhole,
  Pencil,
  RefreshCw,
  Trash2,
  Unplug,
  UserMinus,
  UserPlus,
} from "lucide-react";
import type { SharingRole } from "@keyneom/sync-kit/sharing";
import type { WasmOptions } from "../App";
import { ProfileSwitcher } from "./ProfileSwitcher";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import { idbGet, KV_SYNC_STATE } from "../idbStore";
import { currentRpId, passkeysSupported } from "../sync/passkey";
import { formatLastSync, forgetSyncState } from "../sync/sessionSync";
import { clearJoinLinkParams } from "../sync/sharedJoin";
import { pickSharedAppFolder } from "../sync/sharedPicker";
import { migrateLegacyEncryptedSync } from "../sync/sharedMigration";
import {
  acceptPendingKeyResponse,
  acceptResponseFromLink,
  connectActiveLocalProfile,
  createLocalProfile,
  deleteManagedProfile,
  disconnectProfileToLocal,
  enrollActiveControlDataset,
  grantSharedDatasetFilesFromLink,
  inviteToDatasetLink,
  isSharedSyncConfigured,
  listProfileParticipants,
  listPendingKeyResponses,
  type ManagedParticipant,
  renameManagedProfile,
  resetSharedSync,
  revokeParticipant,
  setupSharedSync,
  sharedSyncConfigFromEnv,
  submitJoinFromLink,
  switchManagedProfile,
  syncActiveDataset,
  updateParticipantDatasetRole,
  updateParticipantRole,
  updateManagedProfileAvatar,
} from "../sync/sharedSync";
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
  onSharedSyncStateChange: (state: SharedSyncState | null) => void;
  onSyncComplete?: (payload: SyncPayloadV1 | null) => void;
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

const CONTROL_DATASETS_WIRED = true;

export function SyncSettings({
  options,
  periodRecords,
  session,
  sharedSyncState,
  onApplyPayload,
  onSharedSyncStateChange,
  onSyncComplete,
}: Props) {
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
  const [lastJoinUrl, setLastJoinUrl] = useState<string | null>(null);
  const [joinLinkInput, setJoinLinkInput] = useState("");
  const [responseLink, setResponseLink] = useState<string | null>(null);
  const [responseLinkInput, setResponseLinkInput] = useState("");
  const [pendingResponses, setPendingResponses] = useState<
    Array<{ responseFileId: string; invitationFileId: string; recipientEmail: string }>
  >([]);
  const [legacyAvailable, setLegacyAvailable] = useState(false);
  const [participants, setParticipants] = useState<ManagedParticipant[]>([]);
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
      const result = await syncActiveDataset(config, localShared());
      await applyShared(result.payload);
      onSyncComplete?.(sharedPayloadToSyncPayload(result.payload, session.androidPreferences));
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
    const parsed = parseSharingJoinLinkV1(source);
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
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const runGrantSharedFiles = async (linkText?: string) => {
    if (!config) return;
    const source = (linkText ?? joinLinkInput).trim();
    const parsed = parseSharingJoinLinkV1(source);
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
    const parsed = parseSharingResponseLinkV1(source);
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

  const switchProfile = async (key: string) => {
    if (!sharedSyncState) return;
    setBusy("switch");
    try {
      const result = await switchManagedProfile(config, key, localShared());
      await applyShared(result.payload);
      onSharedSyncStateChange(result.state);
      onSyncComplete?.(
        sharedPayloadToSyncPayload(result.payload, session.androidPreferences),
      );
      setNotice({ kind: "success", message: "Profile switched." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const addLocalProfile = async (displayName: string) => {
    if (!sharedSyncState) return;
    setBusy("create-profile");
    try {
      const result = await createLocalProfile(config, displayName, localShared());
      await applyShared(result.payload);
      onSharedSyncStateChange(result.state);
      setNotice({
        kind: "success",
        message: `Created local profile ${displayName.trim()}.`,
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
    const parsedJoin = parseSharingJoinLinkV1(source);
    const parsedResponse = parseSharingResponseLinkV1(source);
    if (parsedJoin) setJoinLinkInput(source);
    if (parsedResponse) setResponseLinkInput(source);
    if (legacyGrantOnlyRequested || fileGrantRequested) return;
    // Owner opened a recipient's response link → accept it.
    if (parsedResponse) {
      handledDeepLinkRef.current = source;
      void runAcceptResponseLink(source);
      return;
    }
    // Joiner opened an invite link → grant the file(s) and produce a response.
    if (parsedJoin) {
      handledDeepLinkRef.current = source;
      void runJoinFromLink(source);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config, fileGrantRequested, legacyGrantOnlyRequested]);

  return (
    <section className="sync-card" aria-labelledby="profile-management-title">
      <div className="sync-card-heading">
        <span className="sync-icon"><Cloud aria-hidden /></span>
        <div>
          <p className="eyebrow">Profiles, storage & access</p>
          <h3 id="profile-management-title">Profile management</h3>
          <p>
            Keep profiles local, sync them privately across your devices, or share selected
            profiles with other people. Each profile is managed independently.
          </p>
        </div>
      </div>

      <div className="sync-security-row">
        <span><HardDrive aria-hidden /> Local-first</span>
        <span><KeyRound aria-hidden /> Passkey protected</span>
        <span><LockKeyhole aria-hidden /> Encrypted before Drive</span>
      </div>

      {sharedSyncState && (
        <ProfileSwitcher
          sharedSyncState={sharedSyncState}
          onSwitchProfile={(key) => void switchProfile(key)}
          onCreateProfile={(name) => void addLocalProfile(name)}
          disabled={busy !== null}
        />
      )}

      {sharedSyncState && activeProfile && (
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

          <div className="profile-storage-summary">
            <strong>{activeIsLocal ? "Stored on this device" : "Encrypted Google Drive sync"}</strong>
            <span>
              {activeIsLocal
                ? "This profile does not leave this browser."
                : activeProfile.ownerEmail.toLowerCase() === sharedSyncState.ownerEmail.toLowerCase()
                  ? "Available to your EasyBC identity on other authorized devices."
                  : `${activeProfile.role} access from ${activeProfile.ownerEmail}.`}
            </span>
          </div>

          <div className="profile-management-actions">
            {activeIsLocal ? (
              <button
                type="button"
                disabled={unavailable || busy !== null}
                onClick={() => void runSetup()}
              >
                <Cloud aria-hidden />
                {busy === "setup" ? "Connecting…" : "Enable private encrypted sync"}
              </button>
            ) : (
              <button
                type="button"
                className="ghost"
                disabled={busy !== null}
                onClick={() => void disconnectActiveProfile()}
              >
                <Unplug aria-hidden />
                Keep local copy & disconnect
              </button>
            )}
            <button
              type="button"
              className="ghost danger"
              disabled={busy !== null || sharedSyncState.profiles.length <= 1}
              onClick={() => void removeActiveProfile(false)}
            >
              <Trash2 aria-hidden />
              {activeIsLocal
                ? "Delete local profile"
                : activeProfile.ownerEmail.toLowerCase() === sharedSyncState.ownerEmail.toLowerCase()
                  ? "Remove from this device"
                  : "Leave shared profile"}
            </button>
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

      {sharedSyncState && activeProfile?.lastSyncedAt && (
        <div className="sync-connected">
          <span className="status-dot" aria-hidden />
          <div>
            <strong>{profileDisplayLabel(sharedSyncState, activeProfile)}</strong>
            <span>Last encrypted update {formatLastSync(activeProfile.lastSyncedAt)}</span>
          </div>
        </div>
      )}

      {CONTROL_DATASETS_WIRED &&
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

      {sharedSyncState && activeProfile && !activeIsLocal && isSplitProfile(activeProfile) && (
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
      {legacyAvailable && !sharedConfigured && (
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

      <div className="sync-share-panel">
        <label className="field">
          <span>Join a shared profile</span>
          <input
            type="text"
            placeholder="Paste the join link someone sent you"
            value={joinLinkInput}
            onChange={(event) => setJoinLinkInput(event.target.value)}
          />
        </label>
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

      {responseLink && (
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

      {!activeIsLocal && (
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

      {sharedConfigured &&
        !activeIsLocal &&
        (activeProfile?.role === "owner" || activeProfile?.role === "admin") && (
        <div className="sync-share-panel">
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
              <span>{participants.length} encryption {participants.length === 1 ? "key" : "keys"}</span>
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
                    </div>
                  )}
                </EbPersonCard>
              );
            })}
          </div>
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

      <p className="field-hint sync-footnote">
        Each owner gets a Drive folder named with their email, for example EasyBC — you@example.com.
        Reloading this tab locks encrypted sync until you unlock with your passkey again.
      </p>
    </section>
  );
}
