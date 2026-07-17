import {
  parseSharedBackupOwnershipTransferV1,
  type SharedBackupOwnershipTransferV1,
} from "@keyneom/sync-kit/sharing";
import { base64UrlToBytes, bytesToBase64Url } from "./crypto";

export const OWNERSHIP_TRANSFER_PARAM = "sk-owner-transfer";

/** IndexedDB slot for the owner's outgoing transfer link (survives reloads). */
export const KV_OUTGOING_OWNERSHIP_TRANSFER = "outgoingOwnershipTransferLink";

export function buildOwnershipTransferLink(
  landingUrl: string,
  transfer: SharedBackupOwnershipTransferV1,
): string {
  const url = new URL(landingUrl);
  const encoded = new TextEncoder().encode(JSON.stringify(transfer));
  url.searchParams.set(OWNERSHIP_TRANSFER_PARAM, bytesToBase64Url(encoded));
  return url.toString();
}

export function parseOwnershipTransferLink(
  input: string,
): SharedBackupOwnershipTransferV1 | null {
  try {
    const url = new URL(input, "https://keyneom.github.io/easy-bc/");
    const encoded = url.searchParams.get(OWNERSHIP_TRANSFER_PARAM);
    if (!encoded) return null;
    const json = new TextDecoder().decode(base64UrlToBytes(encoded));
    return parseSharedBackupOwnershipTransferV1(JSON.parse(json));
  } catch {
    return null;
  }
}

export function clearOwnershipTransferParams(): void {
  if (typeof window === "undefined") return;
  const url = new URL(window.location.href);
  url.searchParams.delete(OWNERSHIP_TRANSFER_PARAM);
  window.history.replaceState({}, "", `${url.pathname}${url.search}${url.hash}`);
}
