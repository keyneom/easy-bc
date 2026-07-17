import { describe, expect, it } from "vitest";
import { selectedAppFolderIdForProfile } from "./profileRouting";
import type { ProfileRecord, SharedSyncState } from "./sharedTypes";

const profile = (
  ownerEmail: string,
  role: ProfileRecord["role"],
  appFolderId?: string,
): ProfileRecord => ({
  datasetId: "primary",
  ownerEmail,
  folderName: `EasyBC — ${ownerEmail}`,
  role,
  trustedOwnerKeyId: `${ownerEmail}-key`,
  ...(appFolderId ? { appFolderId } : {}),
});

const state = (profiles: ProfileRecord[]): SharedSyncState => ({
  schemaVersion: 1,
  rpId: "keyneom.github.io",
  ownerEmail: "owner@example.com",
  activeProfileKey: "owner@example.com/primary",
  profiles,
  selectedAppFolderId: "legacy-recipient-folder",
});

describe("selectedAppFolderIdForProfile", () => {
  it("uses a discovered owned profile's stable folder ID", () => {
    const owned = profile("owner@example.com", "owner", "owned-folder");
    expect(selectedAppFolderIdForProfile(state([owned]), owned)).toBe("owned-folder");
  });

  it("does not route an uninitialized owner through recipient fallback state", () => {
    const owned = profile("owner@example.com", "owner");
    expect(selectedAppFolderIdForProfile(state([owned]), owned)).toBeUndefined();
  });

  it("prefers a recipient profile's stable ID over legacy fallback state", () => {
    const recipient = profile("other@example.com", "viewer", "shared-folder");
    expect(selectedAppFolderIdForProfile(state([recipient]), recipient)).toBe("shared-folder");
  });

  it("retains the legacy recipient fallback when no profile ID exists", () => {
    const recipient = profile("other@example.com", "viewer");
    expect(selectedAppFolderIdForProfile(state([recipient]), recipient)).toBe(
      "legacy-recipient-folder",
    );
  });
});
