import { describe, expect, it } from "vitest";
import { ProfileScopedSharedBackupRegistry } from "./sharedRegistry";
import { profileKey } from "./sharedFolderName";
import type { SharedSyncState } from "./sharedTypes";

describe("profile-scoped sharing registry", () => {
  it("composes split and control records from the latest persisted state", async () => {
    const key = profileKey("owner@example.com", "primary");
    let state: SharedSyncState = {
      schemaVersion: 1,
      rpId: "example.com",
      ownerEmail: "owner@example.com",
      activeProfileKey: key,
      profiles: [{
        datasetId: "primary",
        ownerEmail: "owner@example.com",
        folderName: "EasyBC — owner@example.com",
        role: "owner",
        trustedOwnerKeyId: "owner-key",
        controlDatasetId: "primary.control",
      }],
    };
    const registry = new ProfileScopedSharedBackupRegistry(
      async () => state,
      key,
      async (next) => { state = next; },
    );

    await registry.set({
      datasetId: "primary.cycle",
      fileId: "cycle-file",
      trustedOwnerKeyId: "owner-key",
    });
    await registry.set({
      datasetId: "primary.control",
      fileId: "control-file",
      trustedOwnerKeyId: "owner-key",
    });

    expect(state.profiles[0].datasetRecords).toMatchObject({
      "primary.cycle": { fileId: "cycle-file" },
      "primary.control": { fileId: "control-file" },
    });
  });
});
