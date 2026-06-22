import { useEffect, useMemo, useState } from "react";
import { Cloud, KeyRound, LockKeyhole, RefreshCw, Trash2 } from "lucide-react";
import type { WasmOptions } from "../App";
import { idbDelete, idbGet, idbSet, KV_SYNC_STATE } from "../idbStore";
import type { PersistedSession } from "../sessionUtils";
import type { PeriodRecord } from "../tracker/types";
import { base64UrlToBytes, decryptSyncPayload, encryptSyncPayload } from "../sync/crypto";
import { deleteDriveSnapshot, findDriveSnapshot, requestDriveAccessToken, writeDriveSnapshot } from "../sync/googleDrive";
import { createSyncPasskey, currentRpId, passkeysSupported, unlockSyncPasskey } from "../sync/passkey";
import {
  mergeSyncPayloads,
  portablePlannerOptions,
  type LocalSyncState,
  type SyncPayloadV1,
} from "../sync/types";

type Props = {
  options: WasmOptions;
  periodRecords: PeriodRecord[];
  session: PersistedSession;
  onApplyPayload: (payload: SyncPayloadV1) => Promise<void>;
};

type Operation = "setup" | "enable" | "sync" | "reset" | "delete";
type Notice = { kind: "info" | "success" | "error"; message: string } | null;

function localPayload(
  options: WasmOptions,
  periodRecords: PeriodRecord[],
  session: PersistedSession,
): SyncPayloadV1 {
  return {
    schemaVersion: 1,
    exportedAt: new Date().toISOString(),
    planner: { value: portablePlannerOptions(options), updatedAt: session.plannerOptionsUpdatedAt },
    periodRecords,
    deletedPeriodStarts: session.deletedPeriodStarts,
    calendarDayLogs: session.calendarDayLogs,
    voluntaryAbstinenceDates: session.voluntaryAbstinenceDates,
    voluntaryAbstinenceUpdatedAt: session.voluntaryAbstinenceUpdatedAt,
    deletedVoluntaryAbstinenceDates: session.deletedVoluntaryAbstinenceDates,
    ecJournal: { value: session.ecJournalFlag, updatedAt: session.ecJournalUpdatedAt },
  };
}

function formatLastSync(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export function SyncSettings({ options, periodRecords, session, onApplyPayload }: Props) {
  const clientId = import.meta.env.VITE_GOOGLE_WEB_CLIENT_ID?.trim() ?? "";
  const [syncState, setSyncState] = useState<LocalSyncState | null>(null);
  const [busy, setBusy] = useState<Operation | null>(null);
  const [notice, setNotice] = useState<Notice>(null);
  const rpId = useMemo(currentRpId, []);
  const developmentRp = rpId === "localhost" || rpId === "127.0.0.1";

  useEffect(() => {
    void idbGet<LocalSyncState>(KV_SYNC_STATE).then((saved) => setSyncState(saved ?? null));
  }, []);

  const rememberSync = async (fileId: string, syncedAt: string) => {
    const next: LocalSyncState = { schemaVersion: 1, fileId, rpId, lastSyncedAt: syncedAt };
    await idbSet(KV_SYNC_STATE, next);
    setSyncState(next);
  };

  const authorize = async () => {
    if (!clientId) throw new Error("Google Drive sync is not configured in this build.");
    return requestDriveAccessToken(clientId);
  };

  const run = async (operation: Operation) => {
    setBusy(operation);
    setNotice({
      kind: "info",
      message: operation === "delete" ? "Waiting for Google…" : "Waiting for Google and your passkey…",
    });
    try {
      const token = await authorize();
      const existing = await findDriveSnapshot(token);
      const local = localPayload(options, periodRecords, session);

      if (operation === "setup") {
        if (existing) {
          throw new Error("An EasyBC snapshot already exists in this Drive. Use “Enable on this device” instead.");
        }
        const passkey = await createSyncPasskey();
        try {
          const envelope = await encryptSyncPayload(
            local,
            passkey.secret,
            passkey.credentialId,
            passkey.rpId,
            passkey.prfInput,
            passkey.kdfSalt,
          );
          const fileId = await writeDriveSnapshot(token, envelope);
          await rememberSync(fileId, envelope.updatedAt);
          setNotice({ kind: "success", message: "Encrypted sync is set up. The cloud key was discarded after upload." });
        } finally {
          passkey.secret.fill(0);
        }
        return;
      }

      if (!existing) throw new Error("No EasyBC encrypted snapshot was found in this Google Drive.");
      if (operation === "delete") {
        await deleteDriveSnapshot(token, existing.fileId);
        await idbDelete(KV_SYNC_STATE);
        setSyncState(null);
        setNotice({ kind: "success", message: "The encrypted EasyBC snapshot was deleted from Google Drive." });
        return;
      }
      if (operation === "reset") {
        const passkey = await createSyncPasskey();
        try {
          const envelope = await encryptSyncPayload(
            local,
            passkey.secret,
            passkey.credentialId,
            passkey.rpId,
            passkey.prfInput,
            passkey.kdfSalt,
          );
          await writeDriveSnapshot(token, envelope, existing.fileId);
          await rememberSync(existing.fileId, envelope.updatedAt);
          setNotice({ kind: "success", message: "The cloud snapshot now uses the new passkey and this device's local data." });
        } finally {
          passkey.secret.fill(0);
        }
        return;
      }
      if (existing.envelope.rpId !== rpId) {
        throw new Error(`This snapshot belongs to ${existing.envelope.rpId}. Open EasyBC on that domain to use its passkey.`);
      }
      const secret = await unlockSyncPasskey(
        existing.envelope.credentialId,
        existing.envelope.prfInput,
        existing.envelope.rpId,
      );
      try {
        const remote = await decryptSyncPayload(existing.envelope, secret);
        const merged = mergeSyncPayloads(remote, local);
        const envelope = await encryptSyncPayload(
          merged,
          secret,
          existing.envelope.credentialId,
          existing.envelope.rpId,
          base64UrlToBytes(existing.envelope.prfInput),
          base64UrlToBytes(existing.envelope.kdfSalt),
        );
        await writeDriveSnapshot(token, envelope, existing.fileId);
        await onApplyPayload(merged);
        await rememberSync(existing.fileId, envelope.updatedAt);
        setNotice({
          kind: "success",
          message: operation === "enable"
            ? "This device is enabled and the latest records were merged."
            : "Encrypted records and settings are up to date.",
        });
      } finally {
        secret.fill(0);
      }
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
