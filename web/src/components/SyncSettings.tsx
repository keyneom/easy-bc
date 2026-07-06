import { useEffect, useMemo, useState } from "react";
import {
  Cloud,
  Copy,
  KeyRound,
  Link2,
  LockKeyhole,
  RefreshCw,
  Trash2,
  UserPlus,
} from "lucide-react";
import type { SharingRole } from "@keyneom/sync-kit/sharing";
import type { WasmOptions } from "../App";
import { ProfileSwitcher } from "./ProfileSwitcher";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import { idbGet, KV_SYNC_STATE } from "../idbStore";
import { currentRpId, passkeysSupported } from "../sync/passkey";
import { formatLastSync } from "../sync/sessionSync";
import {
  clearJoinLinkParams,
  folderNameForOwner,
  joinLinkSummary,
  parseJoinLinkParams,
} from "../sync/sharedJoin";
import { pickSharedAppFolder } from "../sync/sharedPicker";
import { migrateLegacyEncryptedSync } from "../sync/sharedMigration";
import {
  acceptPendingKeyResponse,
  createOwnedProfile,
  forgetSharedSync,
  inviteToDataset,
  listPendingKeyResponses,
  loadActiveProfileDataset,
  setActiveProfileKey,
  setupSharedSync,
  sharedSyncConfigFromEnv,
  syncActiveDataset,
  submitJoinResponse,
} from "../sync/sharedSync";
import { profileDisplayLabel } from "../sync/profileLabels";
import {
  buildSharedSyncPayload,
  canPublishRole,
  findProfile,
  sharedPayloadToSyncPayload,
  type SharedSyncState,
} from "../sync/sharedTypes";
import type { SyncPayloadV1 } from "../sync/types";

type Props = {
  options: WasmOptions;
  periodRecords: PeriodRecord[];
  session: PersistedSession;
  sharedSyncState: SharedSyncState | null;
  onApplyPayload: (payload: SyncPayloadV1) => Promise<void>;
  onSharedSyncStateChange: (state: SharedSyncState | null) => void;
  onSyncComplete?: (payload: SyncPayloadV1 | null) => void;
  onProfileSwitch?: (state: SharedSyncState) => Promise<void>;
};

type Notice = { kind: "info" | "success" | "error"; message: string } | null;

export function SyncSettings({
  options,
  periodRecords,
  session,
  sharedSyncState,
  onApplyPayload,
  onSharedSyncStateChange,
  onSyncComplete,
  onProfileSwitch,
}: Props) {
  const rpId = useMemo(currentRpId, []);
  const config = useMemo(() => sharedSyncConfigFromEnv(rpId), [rpId]);
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState<Exclude<SharingRole, "owner">>("viewer");
  const [lastJoinUrl, setLastJoinUrl] = useState<string | null>(null);
  const [pendingResponses, setPendingResponses] = useState<
    Array<{ responseFileId: string; invitationFileId: string; recipientEmail: string }>
  >([]);
  const [legacyAvailable, setLegacyAvailable] = useState(false);
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

  const localShared = () => buildSharedSyncPayload(options, periodRecords, session);

  const applyShared = async (payload: ReturnType<typeof localShared>) => {
    await onApplyPayload(
      sharedPayloadToSyncPayload(payload, session.androidPreferences),
    );
  };

  const runSetup = async () => {
    if (!config) return;
    setBusy("setup");
    setNotice({ kind: "info", message: "Creating encrypted sync with Google Drive and your passkey…" });
    try {
      const { state, result } = await setupSharedSync(config, localShared());
      await applyShared(result.payload);
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

  const runInvite = async () => {
    if (!config || !inviteEmail.trim()) return;
    setBusy("invite");
    try {
      const invited = await inviteToDataset(config, {
        emailAddress: inviteEmail.trim(),
        role: inviteRole,
        emailMessage: `Open this link in EasyBC to join encrypted sync: `,
      });
      setLastJoinUrl(invited.joinUrl);
      setNotice({
        kind: "success",
        message: `Invitation sent to ${inviteEmail.trim()}. Copy the join link below for them.`,
      });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const runJoinFromLink = async () => {
    const params = parseJoinLinkParams();
    if (!params || !config) return;
    setBusy("join");
    try {
      await submitJoinResponse(config, {
        invitationFileId: params.invitationFileId,
        ownerFolderId: params.appFolderId,
        ownerEmail: params.ownerEmail,
        folderName: folderNameForOwner(params.ownerEmail),
      });
      clearJoinLinkParams();
      const loaded = await loadSharedSyncStateAfterJoin(config);
      if (loaded) {
        onSharedSyncStateChange(loaded.state);
        await applyShared(loaded.result.payload);
      }
      setNotice({
        kind: "success",
        message: `Join request submitted for ${joinLinkSummary(params)}. The owner must accept before you can sync.`,
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
        kind: "info",
        message:
          "Folder selected. Open the invitation link from the person who shared with you, or paste the invitation file id once available.",
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

  const runForget = async () => {
    if (!window.confirm("Stop encrypted sync on this device? Local EasyBC data will not be deleted.")) return;
    setBusy("forget");
    try {
      await forgetSharedSync();
      onSharedSyncStateChange(null);
      onSyncComplete?.(null);
      setNotice({ kind: "success", message: "Encrypted sync was removed from this device." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const switchProfile = async (key: string) => {
    if (!config || !sharedSyncState) return;
    setBusy("switch");
    try {
      const next = await setActiveProfileKey(key);
      onSharedSyncStateChange(next);
      if (onProfileSwitch) await onProfileSwitch(next);
      else {
        const result = await loadActiveProfileDataset(config);
        await applyShared(result.payload);
      }
      setNotice({ kind: "success", message: "Switched encrypted sync profile." });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const addOwnedProfile = async (displayName: string) => {
    if (!config || !sharedSyncState) return;
    setBusy("create-profile");
    try {
      const active = findProfile(sharedSyncState, sharedSyncState.activeProfileKey);
      if (active && canPublishRole(active.role)) {
        const local = buildSharedSyncPayload(options, periodRecords, session);
        await syncActiveDataset(config, local);
      }
      const { state, result } = await createOwnedProfile(config, displayName);
      onSharedSyncStateChange(state);
      if (onProfileSwitch) await onProfileSwitch(state);
      else await applyShared(result.payload);
      setNotice({
        kind: "success",
        message: `Created profile ${displayName.trim()}. Enter cycle data for this person below.`,
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

  useEffect(() => {
    if (parseJoinLinkParams() && config && !sharedSyncState) {
      void runJoinFromLink();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config, sharedSyncState]);

  const activeProfile = sharedSyncState
    ? findProfile(sharedSyncState, sharedSyncState.activeProfileKey)
    : null;

  return (
    <section className="sync-card" aria-labelledby="encrypted-sync-title">
      <div className="sync-card-heading">
        <span className="sync-icon"><Cloud aria-hidden /></span>
        <div>
          <p className="eyebrow">Optional</p>
          <h3 id="encrypted-sync-title">Encrypted sync</h3>
          <p>
            Encrypt planner settings, period records, and day logs in your own Google Drive folder.
            Share read or write access with others by email. Android uses the same shared folders
            and join links.
          </p>
        </div>
      </div>

      <div className="sync-security-row">
        <span><LockKeyhole aria-hidden /> Drive folder per owner</span>
        <span><KeyRound aria-hidden /> Passkey protected</span>
      </div>

      {sharedSyncState && (
        <ProfileSwitcher
          sharedSyncState={sharedSyncState}
          onSwitchProfile={(key) => void switchProfile(key)}
          onCreateProfile={(name) => void addOwnedProfile(name)}
          disabled={busy !== null}
        />
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
      {legacyAvailable && !sharedSyncState && (
        <p className="sync-notice sync-notice-info">
          This device still uses legacy app-data encrypted sync.
          <button type="button" className="ghost" disabled={busy !== null} onClick={() => void runMigrateLegacy()}>
            Migrate to shared encrypted sync
          </button>
        </p>
      )}
      {notice && <p className={`sync-notice sync-notice-${notice.kind}`} role="status">{notice.message}</p>}

      <div className="sync-actions">
        {!sharedSyncState ? (
          <>
            <button type="button" disabled={unavailable || busy !== null} onClick={() => void runSetup()}>
              <KeyRound aria-hidden />
              {busy === "setup" ? "Setting up…" : "Set up encrypted sync"}
            </button>
            <button type="button" className="ghost" disabled={unavailable || busy !== null} onClick={() => void runPickFolderJoin()}>
              <Link2 aria-hidden />
              Join someone else's encrypted sync
            </button>
          </>
        ) : (
          <>
            <button type="button" disabled={unavailable || busy !== null} onClick={() => void runSync()}>
              <RefreshCw aria-hidden className={busy === "sync" ? "spin" : undefined} />
              {busy === "sync" ? "Merging…" : "Merge encrypted changes"}
            </button>
            <button type="button" className="ghost" disabled={unavailable || busy !== null} onClick={() => void runForget()}>
              <Trash2 aria-hidden />
              Remove encrypted sync from this device
            </button>
          </>
        )}
      </div>

      {sharedSyncState && findProfile(sharedSyncState, sharedSyncState.activeProfileKey)?.role === "owner" && (
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
          <label className="field">
            <span>Access</span>
            <select value={inviteRole} onChange={(event) => setInviteRole(event.target.value as typeof inviteRole)}>
              <option value="viewer">Viewer (read-only)</option>
              <option value="writer">Writer (can edit and sync)</option>
            </select>
          </label>
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

async function loadSharedSyncStateAfterJoin(
  config: NonNullable<ReturnType<typeof sharedSyncConfigFromEnv>>,
) {
  const { loadSharedSyncState } = await import("../sync/sharedSync");
  const state = await loadSharedSyncState();
  if (!state) return null;
  const result = await loadActiveProfileDataset(config);
  return { state, result };
}
