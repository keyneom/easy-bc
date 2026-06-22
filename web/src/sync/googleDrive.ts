import type { SyncEnvelopeV1 } from "./types";
import { parseSyncEnvelope } from "./crypto";

const DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata";
const DRIVE_FILE_NAME = "easybc-sync-v1.json";

type TokenResponse = { access_token?: string; error?: string; error_description?: string };
type TokenClient = {
  requestAccessToken: (options?: { prompt?: string }) => void;
};
type GoogleIdentity = {
  accounts: {
    oauth2: {
      initTokenClient: (config: {
        client_id: string;
        scope: string;
        callback: (response: TokenResponse) => void;
        error_callback?: (error: { type?: string }) => void;
      }) => TokenClient;
    };
  };
};

declare global {
  interface Window {
    google?: GoogleIdentity;
  }
}

export type DriveSnapshot = {
  fileId: string;
  envelope: SyncEnvelopeV1;
};

let scriptPromise: Promise<void> | null = null;

function loadGoogleIdentity(): Promise<void> {
  if (window.google?.accounts.oauth2) return Promise.resolve();
  if (scriptPromise) return scriptPromise;
  scriptPromise = new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error("Google sign-in could not be loaded."));
    document.head.append(script);
  });
  return scriptPromise;
}

export async function requestDriveAccessToken(clientId: string): Promise<string> {
  await loadGoogleIdentity();
  const google = window.google;
  if (!google) throw new Error("Google sign-in is unavailable.");
  return new Promise((resolve, reject) => {
    const client = google.accounts.oauth2.initTokenClient({
      client_id: clientId,
      scope: DRIVE_SCOPE,
      callback: (response) => {
        if (response.access_token) resolve(response.access_token);
        else reject(new Error(response.error_description ?? response.error ?? "Google authorization failed."));
      },
      error_callback: (error) => reject(new Error(error.type ?? "Google authorization was cancelled.")),
    });
    client.requestAccessToken({ prompt: "" });
  });
}

async function driveFetch(url: string, token: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(url, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(init?.headers ?? {}),
    },
  });
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 400);
    throw new Error(`Google Drive request failed (${response.status}). ${detail}`);
  }
  return response;
}

export async function findDriveSnapshot(token: string): Promise<DriveSnapshot | null> {
  const params = new URLSearchParams({
    spaces: "appDataFolder",
    q: `name = '${DRIVE_FILE_NAME}' and trashed = false`,
    fields: "files(id,name,modifiedTime)",
    pageSize: "1",
  });
  const response = await driveFetch(`https://www.googleapis.com/drive/v3/files?${params}`, token);
  const listed = await response.json() as { files?: Array<{ id: string }> };
  const fileId = listed.files?.[0]?.id;
  if (!fileId) return null;
  const media = await driveFetch(
    `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}?alt=media`,
    token,
  );
  return { fileId, envelope: parseSyncEnvelope(await media.text()) };
}

export async function writeDriveSnapshot(
  token: string,
  envelope: SyncEnvelopeV1,
  fileId?: string,
): Promise<string> {
  const content = JSON.stringify(envelope);
  if (fileId) {
    await driveFetch(
      `https://www.googleapis.com/upload/drive/v3/files/${encodeURIComponent(fileId)}?uploadType=media&fields=id`,
      token,
      { method: "PATCH", headers: { "Content-Type": "application/json" }, body: content },
    );
    return fileId;
  }
  const boundary = `easybc-${crypto.randomUUID()}`;
  const body = new Blob([
    `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n`,
    JSON.stringify({ name: DRIVE_FILE_NAME, parents: ["appDataFolder"] }),
    `\r\n--${boundary}\r\nContent-Type: application/json\r\n\r\n`,
    content,
    `\r\n--${boundary}--`,
  ]);
  const response = await driveFetch(
    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
    token,
    {
      method: "POST",
      headers: { "Content-Type": `multipart/related; boundary=${boundary}` },
      body,
    },
  );
  const created = await response.json() as { id?: string };
  if (!created.id) throw new Error("Google Drive did not return a file id.");
  return created.id;
}

export async function deleteDriveSnapshot(token: string, fileId: string): Promise<void> {
  await driveFetch(
    `https://www.googleapis.com/drive/v3/files/${encodeURIComponent(fileId)}`,
    token,
    { method: "DELETE" },
  );
}
