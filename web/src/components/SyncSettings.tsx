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
import { formatLastSync, forgetSyncState } from "../sync/sessionSync";
import { clearJoinLinkParams } from "../sync/sharedJoin";
import { pickSharedAppFolder } from "../sync/sharedPicker";
import { migrateLegacyEncryptedSync } from "../sync/sharedMigration";
import {
  acceptPendingKeyResponse,
  acceptResponseFromLink,
  createOwnedProfile,
  forgetSharedSync,
  inviteToDatasetLink,
  isSharedSyncConfigured,
  listPendingKeyResponses,
  loadActiveProfileDataset,
  resetSharedSync,
  setActiveProfileKey,
  setupSharedSync,
  sharedSyncConfigFromEnv,
  submitJoinFromLink,
  syncActiveDataset,
} from "../sync/sharedSync";
import {
  parseSharingJoinLinkV1,
  parseSharingResponseLinkV1,
} from "@keyneom/sync-kit/sharing";
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
  const [responseLink, setResponseLink] = useState<string | null>(null);
  const [responseLinkInput, setResponseLinkInput] = useState("");
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
  const sharedConfigured = isSharedSyncConfigured(sharedSyncState);

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

  const runInvite = async () => {
    if (!config || !inviteEmail.trim()) return;
    setBusy("invite");
    try {
      const invited = await inviteToDatasetLink(config, {
        emailAddress: inviteEmail.trim(),
        role: inviteRole,
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
    const source = linkText ?? window.location.search;
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
      const { responseLink: link } = await submitJoinFromLink(config, {
        invitation: parsed.invitation,
        files: parsed.files,
        ownerEmail,
      });
      setResponseLink(link);
      clearJoinLinkParams();
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
        kind: grantOnlyRequested ? "success" : "info",
        message: grantOnlyRequested
          ? "Access granted. Return to the EasyBC app and tap Join again."
          : "Folder selected. Open the invitation link from the person who shared with you, or paste the invitation file id once available.",
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

  // The Android app hands off here for the Google Picker grant: joining must
  // happen on the device (its sharing identity lives there), so with
  // grant-folder=1 we only grant folder access and never auto-join.
  const grantOnlyRequested =
    typeof window !== "undefined" &&
    new URLSearchParams(window.location.search).get("grant-folder") === "1";

  useEffect(() => {
    if (grantOnlyRequested || !config) return;
    // Owner opened a recipient's response link → accept it.
    if (parseSharingResponseLinkV1(window.location.search)) {
      void runAcceptResponseLink(window.location.search);
      return;
    }
    // Joiner opened an invite link → grant the file(s) and produce a response.
    if (parseSharingJoinLinkV1(window.location.search)) {
      void runJoinFromLink(window.location.search);
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
      {legacyAvailable && !sharedConfigured && (
        <p className="sync-notice sync-notice-info">
          This device still uses legacy app-data encrypted sync.
          <button type="button" className="ghost" disabled={busy !== null} onClick={() => void runMigrateLegacy()}>
            Migrate to shared encrypted sync
          </button>
        </p>
      )}
      {grantOnlyRequested && (
        <p className="sync-notice sync-notice-info">
          The EasyBC app needs access to the folder that was shared with you.
          Select the folder named &ldquo;EasyBC &mdash; owner@email&rdquo; below, then return
          to the app and tap Join again.
          <button type="button" className="ghost" disabled={busy !== null} onClick={() => void runPickFolderJoin()}>
            Select the shared folder
          </button>
        </p>
      )}
      {notice && <p className={`sync-notice sync-notice-${notice.kind}`} role="status">{notice.message}</p>}

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

      <div className="sync-actions">
        {!sharedConfigured ? (
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
            <button type="button" className="ghost" disabled={unavailable || busy !== null} onClick={() => void runReset()}>
              <Trash2 aria-hidden />
              {busy === "reset" ? "Resetting…" : "Reset encrypted sync"}
            </button>
            <button type="button" className="ghost" disabled={unavailable || busy !== null} onClick={() => void runForget()}>
              <Trash2 aria-hidden />
              Remove encrypted sync from this device
            </button>
          </>
        )}
      </div>

      {sharedConfigured && findProfile(sharedSyncState!, sharedSyncState!.activeProfileKey)?.role === "owner" && (
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

