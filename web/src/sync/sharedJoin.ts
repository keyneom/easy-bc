import { parseGoogleDriveOpenState } from "@keyneom/sync-kit/stores/google-drive/picker";
import { easyBcSyncFolderName } from "./sharedFolderName";

export type JoinLinkParams = {
  exchangeId: string;
  appFolderId: string;
  ownerEmail: string;
  invitationFileId: string;
};

export function parseJoinLinkParams(
  search: string | URLSearchParams = window.location.search,
): JoinLinkParams | null {
  const params =
    typeof search === "string"
      ? new URLSearchParams(search.startsWith("?") ? search.slice(1) : search)
      : search;
  // Current invite links use sync-kit's parameter style
  // (sync-kit-join/sync-kit-folder/sync-kit-exchange); older links used
  // sync=join with bare exchange/folder names.
  const syncKitStyle = params.get("sync-kit-join") != null;
  if (!syncKitStyle && params.get("sync") !== "join") return null;
  const exchangeId =
    (params.get("sync-kit-exchange") ?? params.get("exchange"))?.trim() ?? "";
  const appFolderId =
    (params.get("sync-kit-folder") ?? params.get("folder"))?.trim() ?? "";
  const ownerEmail = params.get("owner")?.trim() ?? "";
  const invitationFileId = params.get("invitation")?.trim() ?? "";
  if (!exchangeId || !appFolderId || !ownerEmail) return null;
  return { exchangeId, appFolderId, ownerEmail, invitationFileId };
}

export function clearJoinLinkParams(): void {
  const url = stripJoinLinkParams(new URL(window.location.href));
  window.history.replaceState({}, "", url.toString());
}

export function stripJoinLinkParams(url: URL): URL {
  const next = new URL(url.toString());
  for (const key of [
    "sync",
    "exchange",
    "folder",
    "owner",
    "invitation",
    "sync-kit-join",
    "sync-kit-exchange",
    "sync-kit-folder",
    "sk-inv",
    "sk-files",
    "sk-resp",
    "sk-kr",
    "grant-files",
    "grant-folder",
  ]) {
    next.searchParams.delete(key);
  }
  return next;
}

export function parseDriveOpenFolderId(): string | null {
  try {
    const open = parseGoogleDriveOpenState(window.location.search);
    return open?.fileIds[0] ?? null;
  } catch {
    return null;
  }
}

export function folderNameForOwner(ownerEmail: string): string {
  return easyBcSyncFolderName(ownerEmail);
}

export function joinLinkSummary(params: JoinLinkParams): string {
  return `${folderNameForOwner(params.ownerEmail)} (${params.ownerEmail})`;
}
