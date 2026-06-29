import { useMemo, useState } from "react";
import { Cloud, KeyRound, LockKeyhole, RefreshCw, Trash2 } from "lucide-react";
import type { WasmOptions } from "../App";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import { currentRpId, passkeysSupported } from "../sync/passkey";
import {
  buildLocalSyncPayload,
  forgetSyncState,
  formatLastSync,
  rememberSyncState,
  runEncryptedSyncOperation,
} from "../sync/sessionSync";
import {
  type LocalSyncState,
  type SyncPayloadV1,
} from "../sync/types";

type Props = {
  options: WasmOptions;
  periodRecords: PeriodRecord[];
  session: PersistedSession;
  syncState: LocalSyncState | null;
  onApplyPayload: (payload: SyncPayloadV1) => Promise<void>;
  onSyncStateChange: (state: LocalSyncState | null) => void;
  onSyncComplete?: (payload: SyncPayloadV1 | null) => void;
};

type Operation = "setup" | "enable" | "sync" | "reset" | "delete";
type Notice = { kind: "info" | "success" | "error"; message: string } | null;

export function SyncSettings({
  options,
  periodRecords,
  session,
  syncState,
  onApplyPayload,
  onSyncStateChange,
  onSyncComplete,
}: Props) {
  const clientId = import.meta.env.VITE_GOOGLE_WEB_CLIENT_ID?.trim() ?? "";
  const [busy, setBusy] = useState<Operation | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const rpId = useMemo(currentRpId, []);
  const developmentRp = rpId === "localhost" || rpId === "127.0.0.1";

  const run = async (operation: Operation) => {
    setBusy(operation);
    setNotice({
      kind: "info",
      message: operation === "delete" ? "Waiting for Google…" : "Waiting for Google and your passkey…",
    });
    try {
      const local = buildLocalSyncPayload(options, periodRecords, session);
      const result = await runEncryptedSyncOperation({
        operation,
        clientId,
        rpId,
        local,
      });
      if (result.operation === "delete") {
        await forgetSyncState();
        onSyncStateChange(null);
        onSyncComplete?.(null);
      } else {
        if (result.operation === "enable" || result.operation === "sync") {
          await onApplyPayload(result.payload);
        }
        const nextState = await rememberSyncState(result.fileId, rpId, result.syncedAt);
        onSyncStateChange(nextState);
        onSyncComplete?.(result.payload);
      }
      setNotice({ kind: "success", message: result.message });
    } catch (error) {
      setNotice({ kind: "error", message: error instanceof Error ? error.message : String(error) });
    } finally {
      setBusy(null);
    }
  };

  const unavailable = !clientId || !passkeysSupported();
  const resetEncryption = () => {
    const confirmed = window.confirm(
      "Replace the encrypted Drive snapshot with this device's local EasyBC data and a new passkey? Other devices must receive the new passkey before they can sync again.",
    );
    if (confirmed) void run("reset");
  };
  const deleteCloudCopy = () => {
    const confirmed = window.confirm(
      "Permanently delete the encrypted EasyBC snapshot from Google Drive? Local data on this device will not be deleted.",
    );
    if (confirmed) void run("delete");
  };

  return (
    <section className="sync-card" aria-labelledby="encrypted-sync-title">
      <div className="sync-card-heading">
        <span className="sync-icon"><Cloud aria-hidden /></span>
        <div>
          <p className="eyebrow">Optional</p>
          <h3 id="encrypted-sync-title">Encrypted cross-device sync</h3>
          <p>
            Keep period records, day logs, and planner settings in Google Drive. EasyBC encrypts them
            before upload; Drive receives ciphertext and the app never stores the cloud key.
          </p>
        </div>
      </div>

      <div className="sync-security-row">
        <span><LockKeyhole aria-hidden /> Drive app-data only</span>
        <span><KeyRound aria-hidden /> Passkey protected</span>
      </div>

      {syncState ? (
        <div className="sync-connected">
          <span className="status-dot" aria-hidden />
          <div>
            <strong>Sync enabled on this device</strong>
            <span>Last synced {formatLastSync(syncState.lastSyncedAt)}</span>
          </div>
        </div>
      ) : (
        <p className="sync-explainer">
          First device? Create the encrypted snapshot and passkey. Existing device? Enable sync to
          unlock and merge the snapshot already in Drive.
        </p>
      )}

      {!clientId && (
        <p className="sync-notice sync-notice-info">
          Google Drive sync needs a web OAuth client ID before these controls can be used.
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
      {notice && <p className={`sync-notice sync-notice-${notice.kind}`} role="status">{notice.message}</p>}

      <div className="sync-actions">
        {syncState ? (
          <>
            <button type="button" onClick={() => void run("sync")} disabled={unavailable || busy !== null}>
              <RefreshCw aria-hidden className={busy === "sync" ? "spin" : undefined} />
              {busy === "sync" ? "Syncing…" : "Sync now"}
            </button>
            <button className="ghost" type="button" onClick={resetEncryption} disabled={unavailable || busy !== null}>
              <KeyRound aria-hidden />
              {busy === "reset" ? "Replacing…" : "Replace passkey and cloud copy"}
            </button>
            <button className="ghost" type="button" onClick={deleteCloudCopy} disabled={!clientId || busy !== null}>
              <Trash2 aria-hidden />
              {busy === "delete" ? "Deleting…" : "Delete cloud copy"}
            </button>
          </>
        ) : (
          <>
            <button type="button" onClick={() => void run("setup")} disabled={unavailable || busy !== null}>
              <KeyRound aria-hidden />
              {busy === "setup" ? "Setting up…" : "Set up sync"}
            </button>
            <button className="ghost" type="button" onClick={() => void run("enable")} disabled={unavailable || busy !== null}>
              <Cloud aria-hidden />
              {busy === "enable" ? "Enabling…" : "Enable on this device"}
            </button>
            <button className="ghost" type="button" onClick={resetEncryption} disabled={unavailable || busy !== null}>
              <KeyRound aria-hidden />
              {busy === "reset" ? "Replacing…" : "Replace lost passkey"}
            </button>
            <button className="ghost" type="button" onClick={deleteCloudCopy} disabled={!clientId || busy !== null}>
              <Trash2 aria-hidden />
              {busy === "delete" ? "Deleting…" : "Delete cloud copy"}
            </button>
          </>
        )}
      </div>
      <p className="field-hint sync-footnote">
        A synced passkey is required on each device. If it is lost, a device with local EasyBC data
        can create a new encrypted snapshot without losing that local copy.
      </p>
    </section>
  );
}
