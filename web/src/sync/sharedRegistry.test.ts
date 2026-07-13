import { describe, expect, it } from "vitest";
import {
  createInitialSharedSyncState,
  ProfileScopedSharedBackupRegistry,
} from "./sharedRegistry";
import { profileKey } from "./sharedFolderName";
import type { SharedSyncState } from "./sharedTypes";

describe("profile-scoped sharing registry", () => {
  it("can initialize directly on a recovered cutover generation", () => {
    const state = createInitialSharedSyncState({
      rpId: "example.com",
      ownerEmail: "owner@example.com",
      folderName: "EasyBC — owner@example.com",
      trustedOwnerKeyId: "owner-key",
      datasetId: "primary.g2",
    });

    expect(state.activeProfileKey).toBe(profileKey("owner@example.com", "primary.g2"));
    expect(state.profiles[0].datasetId).toBe("primary.g2");
  });

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
