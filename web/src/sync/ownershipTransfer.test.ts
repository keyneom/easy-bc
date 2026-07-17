import { describe, expect, it } from "vitest";
import type { SharedBackupOwnershipTransferV1 } from "@keyneom/sync-kit/sharing";
import {
  buildOwnershipTransferLink,
  OWNERSHIP_TRANSFER_PARAM,
  parseOwnershipTransferLink,
} from "./ownershipTransfer";

const transfer: SharedBackupOwnershipTransferV1 = {
  schemaVersion: 1,
  kind: "sync-kit-ownership-transfer",
  transferId: "transfer-1",
  appId: "easy-bc",
  fromKeyId: "lwwsJjdVXCf_aIvHs9zewC53e9HU7fLK78a6m2s2n1s",
  toKeyId: "Ynbbj4Dx5xVfa3U1k9wjzbOVVk73QSpZhJDOetVRlYg",
  previousOwnerRole: "admin",
  datasets: [{
    datasetId: "primary",
    revisionId: "revision-1",
    accessControlHash: "ofuYUaurImAjbjwXLSFSceipRmoKfW6goY__kfaI5CA",
    providerPermissionId: "permission-1",
  }],
  providerObjects: [
    { kind: "app-folder", fileId: "app-folder", providerPermissionId: "app-permission" },
    { kind: "exchanges-folder", fileId: "exchanges-folder", providerPermissionId: "exchange-permission" },
  ],
  createdAt: "2026-07-17T12:00:00.000Z",
  ownerProof: "M2Yka2PNzFBTOr-I2vg_zh11vZFK_XLJ2FMu1bDR_hwfmnVmu7gYnNLtGOj1lz0x0QgwSQu7yNQYq9771G9f5g",
};

describe("ownership transfer links", () => {
  it("round-trips a signed transfer without exposing it as loose query fields", () => {
    const link = buildOwnershipTransferLink("https://keyneom.github.io/easy-bc/", transfer);
    const url = new URL(link);

    expect(url.searchParams.has(OWNERSHIP_TRANSFER_PARAM)).toBe(true);
    expect(url.searchParams.has("toKeyId")).toBe(false);
    expect(parseOwnershipTransferLink(link)).toEqual(transfer);
  });

  it("rejects malformed transfer payloads", () => {
    expect(parseOwnershipTransferLink("https://example.test/?sk-owner-transfer=bad"))
      .toBeNull();
  });
});
