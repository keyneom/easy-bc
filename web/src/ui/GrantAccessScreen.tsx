/**
 * Dedicated browser page for the one manual step of joining a shared
 * profile: granting the app's `drive.file` token access to the shared
 * files via the Google Picker.
 *
 * The Android app hands off here (Custom Tab) with the original join-link
 * params plus `grant-files=1`. This page must do exactly one job with zero
 * hunting: show which files are needed, open the multi-select Picker, verify
 * every expected file was selected, and send the user straight back to the
 * app — which is already authenticated and finishes the join automatically.
 *
 * Rendered from main.tsx *instead of* the app shell whenever the grant
 * params are present (see docs/join-flow.md).
 */
import { useMemo, useState } from "react";
import { ArrowLeft, ExternalLink, FolderOpen } from "lucide-react";
import {
  parseSharingJoinLinkV1,
  type SharingDatasetFileV1,
} from "@keyneom/sync-kit/sharing";
import { pickSharedDatasetFiles, pickSharedAppFolder } from "../sync/sharedPicker";
import { sharedSyncConfigFromEnv } from "../sync/sharedSync";
import { currentRpId } from "../sync/passkey";
import { EbBanner, EbButton, EbGroupLabel, EbStatusRow, EbStepDots } from "./Kit";

const ANDROID_PACKAGE = "com.easybc.planner";

/** User-facing labels for dataset ids (see docs/sync-kit-multi-file-datasets.md). */
const DATASET_LABELS: Record<string, string> = {
  primary: "Shared profile data",
  control: "Sharing coordination file",
  cycle: "Cycle & periods",
  plan: "Plan & settings",
  intimacy: "Intimacy log",
  sensitive: "Sensitive events",
};

export function datasetLabel(datasetId: string): string {
  if (DATASET_LABELS[datasetId]) return DATASET_LABELS[datasetId];
  // Suffixed ids: "primary.cycle" → cycle; generation bases "primary.g2"
  // (hard-cutover migrations) → the plan/base label.
  const tail = datasetId.split(".").pop() ?? datasetId;
  if (DATASET_LABELS[tail]) return DATASET_LABELS[tail];
  if (/^g\d+$/.test(tail)) return DATASET_LABELS.primary;
  return datasetId;
}

/**
 * Migration re-grant hand-off: the Android app opens this page with
 * `grant-files=1&sk-mfiles=<base64url JSON [{fileId,datasetId,role?}]>`
 * when the owner reorganized a shared profile (hard cutover) and Google
 * requires the user to reselect the new files. The list is built by the
 * app from its verified control state — this page just runs the Picker.
 */
export function parseMigrationFilesParam(raw: string | null): SharingDatasetFileV1[] {
  if (!raw) return [];
  try {
    const decoded = JSON.parse(
      atob(raw.replace(/-/g, "+").replace(/_/g, "/")),
    ) as unknown;
    if (!Array.isArray(decoded)) return [];
    return decoded.flatMap((entry) => {
      if (
        typeof entry !== "object" ||
        entry === null ||
        typeof (entry as { fileId?: unknown }).fileId !== "string" ||
        typeof (entry as { datasetId?: unknown }).datasetId !== "string"
      ) {
        return [];
      }
      const candidate = entry as { fileId: string; datasetId: string; role?: string };
      return [
        {
          datasetId: candidate.datasetId,
          fileId: candidate.fileId,
          role: candidate.role === "writer" ? ("writer" as const) : ("viewer" as const),
        },
      ];
    });
  } catch {
    return [];
  }
}

/**
 * Deep link that returns the user to the Android app with the original join
 * params plus `sk-granted=1`. Uses an intent:// URI so Chrome reliably
 * re-opens the app from a user gesture, with the https App Link as fallback.
 */
export function buildReturnToAppLinks(href: string): { intentUri: string; httpsUrl: string } {
  const url = new URL(href);
  url.searchParams.delete("grant-files");
  url.searchParams.delete("grant-folder");
  url.searchParams.set("sk-granted", "1");
  const httpsUrl = url.toString();
  const intentUri =
    `intent://${url.host}${url.pathname}${url.search}` +
    `#Intent;scheme=https;package=${ANDROID_PACKAGE};` +
    `S.browser_fallback_url=${encodeURIComponent(httpsUrl)};end`;
  return { intentUri, httpsUrl };
}

type FileStatus = "pending" | "granted";
type Phase = "idle" | "authorizing" | "picking" | "partial" | "complete" | "error";

export function GrantAccessScreen() {
  const href = window.location.href;
  const params = new URLSearchParams(window.location.search);
  const legacyFolderMode = params.get("grant-folder") === "1";
  const ownerEmail = params.get("owner")?.trim() || null;

  const parsed = useMemo(() => {
    try {
      return parseSharingJoinLinkV1(href);
    } catch {
      return null;
    }
  }, [href]);
  const config = useMemo(() => sharedSyncConfigFromEnv(currentRpId()), []);

  const [phase, setPhase] = useState<Phase>("idle");
  const [grantedIds, setGrantedIds] = useState<Set<string>>(new Set());
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const migrationFiles = useMemo(
    () => parseMigrationFilesParam(params.get("sk-mfiles")),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [href],
  );
  const migrationMode = !parsed && migrationFiles.length > 0;
  const files: SharingDatasetFileV1[] = parsed?.files ?? migrationFiles;
  const missing = files.filter((file) => !grantedIds.has(file.fileId));
  const returnLinks = useMemo(() => buildReturnToAppLinks(href), [href]);

  const openPicker = async () => {
    if (!config) {
      setErrorMessage("Sync is not configured for this deployment.");
      setPhase("error");
      return;
    }
    setErrorMessage(null);
    setPhase("authorizing");
    try {
      const { GoogleWebAuthorizationProvider, GOOGLE_DRIVE_FILE_SCOPE } = await import(
        "@keyneom/sync-kit/auth/google-web"
      );
      const auth = new GoogleWebAuthorizationProvider({
        clientId: config.clientId,
        scope: GOOGLE_DRIVE_FILE_SCOPE,
      });
      const authorization = await auth.authorize();
      setPhase("picking");
      if (legacyFolderMode && files.length === 0) {
        const picked = await pickSharedAppFolder(authorization);
        if (!picked) {
          setPhase("idle");
          return;
        }
        setPhase("complete");
        return;
      }
      // Do not throw on a partial selection: keep what was granted and let
      // the user finish the rest in another Picker pass.
      const selected = await pickSharedDatasetFiles(authorization, []);
      const nextGranted = new Set(grantedIds);
      const expectedIds = new Set(files.map((file) => file.fileId));
      for (const file of selected) {
        // rc.11 Picker contract: ignore selected files outside the expected set.
        if (expectedIds.has(file.fileId)) nextGranted.add(file.fileId);
      }
      setGrantedIds(nextGranted);
      const stillMissing = files.filter((file) => !nextGranted.has(file.fileId));
      setPhase(stillMissing.length === 0 ? "complete" : "partial");
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : String(error));
      setPhase("error");
    }
  };

  const invalidLink =
    !legacyFolderMode && ((!parsed && !migrationMode) || files.length === 0);
  const busy = phase === "authorizing" || phase === "picking";
  const complete = phase === "complete";

  return (
    <div className="grant-screen">
      <header className="grant-header">
        <h1>
          {migrationMode
            ? "Reselect this profile's files"
            : "Give EasyBC access to the shared files"}
        </h1>
        <p>
          {migrationMode ? (
            <>
              {ownerEmail ? <strong>{ownerEmail}</strong> : "The owner"} reorganized the
              shared profile into separate files. Pick the new files to keep your access —
              nothing else changes.
            </>
          ) : ownerEmail ? (
            <>
              <strong>{ownerEmail}</strong> shared an encrypted profile with you.
            </>
          ) : (
            "Someone shared an encrypted profile with you."
          )}{" "}
          Google requires you to select the shared files yourself — that's the only manual
          step. EasyBC can never see any other file in your Drive.
        </p>
        <div className="grant-steps" aria-hidden>
          <EbStepDots count={migrationMode ? 2 : 3} active={complete ? 1 : 0} />
          <span className="grant-steps-caption">
            {migrationMode
              ? complete
                ? "Step 2 of 2 — return to the app"
                : "Step 1 of 2 — choose the files · then return to the app"
              : complete
                ? "Step 2 of 3 — return to the app"
                : "Step 1 of 3 — choose the files · then return to the app · then send your reply"}
          </span>
        </div>
      </header>

      {invalidLink ? (
        <EbBanner tone="error" title="This link is incomplete">
          It doesn't list the shared files. Ask the owner to send a fresh invite link from
          their EasyBC app, and open it on this device.
        </EbBanner>
      ) : (
        <>
          {files.length > 0 && (
            <>
              <EbGroupLabel>
                {files.length === 1 ? "The file to select" : `The ${files.length} files to select`}
              </EbGroupLabel>
              <ul className="grant-file-list">
                {files.map((file) => {
                  const granted = grantedIds.has(file.fileId);
                  const status: FileStatus = granted ? "granted" : "pending";
                  return (
                    <li key={file.fileId} className={`grant-file grant-file-${status}`}>
                      <span className="grant-file-check" aria-hidden>
                        {granted ? "✓" : ""}
                      </span>
                      <span className="grant-file-name">{datasetLabel(file.datasetId)}</span>
                      <span className="grant-file-role">
                        {granted ? "Access granted" : file.role === "writer" ? "You can edit" : "View only"}
                      </span>
                    </li>
                  );
                })}
              </ul>
              {files.length > 1 && (
                <p className="grant-multiselect-hint">
                  In the picker, hold <strong>Ctrl</strong> (<strong>⌘</strong> on Mac) to
                  select several files at once. On a phone the picker may only take one at
                  a time — pick what you can and it reopens for the rest.
                </p>
              )}
            </>
          )}

          {phase === "partial" && (
            <EbBanner tone="warn" title={`${missing.length} file${missing.length === 1 ? "" : "s"} still needed`}>
              Still missing: {missing.map((file) => datasetLabel(file.datasetId)).join(", ")}. Open
              the picker again and select {missing.length === 1 ? "it" : "them"} — they're in the
              same shared folder.
            </EbBanner>
          )}
          {phase === "error" && errorMessage && (
            <EbBanner tone="error" title="That didn't work">
              {errorMessage} If the picker showed no files, check drive.google.com → “Shared with
              me” (and Spam) for the owner's share first.
            </EbBanner>
          )}
          {busy && (
            <EbStatusRow tone="busy">
              {phase === "authorizing"
                ? "Waiting for Google sign-in…"
                : "Google Picker is open — select every file listed above, then press Select."}
            </EbStatusRow>
          )}

          {!complete ? (
            <div className="grant-actions">
              <EbButton onClick={() => void openPicker()} disabled={busy}>
                <FolderOpen size={17} aria-hidden />
                {phase === "partial"
                  ? "Open the picker again"
                  : legacyFolderMode && files.length === 0
                    ? "Select the shared EasyBC folder"
                    : files.length === 1
                      ? "Choose the file in Google Drive"
                      : `Choose the ${files.length} files in Google Drive`}
              </EbButton>
              <p className="grant-hint">
                A Google window will open. Select {files.length <= 1 ? "the file" : "all the files"}{" "}
                (tap each one), then press <strong>Select</strong>.
              </p>
            </div>
          ) : (
            <div className="grant-actions">
              <EbBanner tone="success" title="Access granted">
                EasyBC can now read the shared files. Return to the app — it finishes joining
                automatically and gives you a reply link to send back
                {ownerEmail ? ` to ${ownerEmail}` : ""}.
              </EbBanner>
              <EbButton
                onClick={() => {
                  window.location.href = returnLinks.intentUri;
                }}
              >
                <ArrowLeft size={17} aria-hidden />
                Return to EasyBC
              </EbButton>
              <a className="grant-fallback" href={returnLinks.httpsUrl}>
                <ExternalLink size={13} aria-hidden /> App didn't open? Tap here.
              </a>
            </div>
          )}
        </>
      )}
    </div>
  );
}
