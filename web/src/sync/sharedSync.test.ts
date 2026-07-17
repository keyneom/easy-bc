import { afterEach, describe, expect, it, vi } from "vitest";
import {
  assertAcceptedDatasetResults,
  assertSplitUpgradeAllowed,
  mergedControlMemberMetadata,
  sharedSyncConfigFromEnv,
} from "./sharedSync";

describe("sharedSyncConfigFromEnv", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("returns null without a client id", () => {
    vi.stubEnv("VITE_GOOGLE_WEB_CLIENT_ID", "");
    expect(sharedSyncConfigFromEnv("keyneom.github.io")).toBeNull();
  });

  it("builds config when a client id is set", () => {
    vi.stubEnv("VITE_GOOGLE_WEB_CLIENT_ID", "123.apps.googleusercontent.com");
    expect(sharedSyncConfigFromEnv("keyneom.github.io")).toEqual({
      clientId: "123.apps.googleusercontent.com",
      rpId: "keyneom.github.io",
      googleAudience: "123.apps.googleusercontent.com",
      allowedOrigins: [],
    });
  });
});

describe("assertAcceptedDatasetResults", () => {
  it("keeps a pending invite retryable when any dataset failed", () => {
    expect(() =>
      assertAcceptedDatasetResults([
        { datasetId: "primary", status: "failed", error: new Error("Drive write failed") },
      ]),
    ).toThrow(/primary: Drive write failed/);
  });

  it("accepts an all-success result", () => {
    expect(() =>
      assertAcceptedDatasetResults([{ datasetId: "primary", status: "accepted" }]),
    ).not.toThrow();
  });
});

describe("assertSplitUpgradeAllowed", () => {
  const ownedLegacy = {
    datasetId: "primary",
    ownerEmail: "leslie@example.com",
    folderName: "EasyBC — leslie@example.com",
    role: "owner" as const,
    trustedOwnerKeyId: "owner-key",
    syncMode: "encrypted" as const,
  };

  it("allows an owned encrypted legacy profile with no participants", () => {
    expect(() => assertSplitUpgradeAllowed(ownedLegacy)).not.toThrow();
  });

  it("rejects local profiles — the split layout lives in Drive", () => {
    expect(() =>
      assertSplitUpgradeAllowed({ ...ownedLegacy, syncMode: "local" as const }),
    ).toThrow(/encrypted sync/i);
  });

  it("rejects non-owners: writers, admins, viewers", () => {
    for (const role of ["writer", "admin", "viewer"] as const) {
      expect(() => assertSplitUpgradeAllowed({ ...ownedLegacy, role })).toThrow(/owner/i);
    }
  });

  it("rejects profiles already using the split layout", () => {
    expect(() =>
      assertSplitUpgradeAllowed({
        ...ownedLegacy,
        datasetGrants: { plan: "owner" as const, cycle: "owner" as const },
      }),
    ).toThrow(/already/i);
  });

  it("rejects shared profiles until access is removed — new keys strand participants", () => {
    expect(() =>
      assertSplitUpgradeAllowed({
        ...ownedLegacy,
        participantEmails: { "key-1": "mark@example.com" },
      }),
    ).toThrow(/remove everyone's access/i);
  });
});

describe("mergedControlMemberMetadata", () => {
  it("preserves the complete verified directory while adding fresh invite data", () => {
    const members = new Map([
      ["owner-key", {
        email: "owner@example.com",
        googleSubject: "owner-subject",
        drivePermissionId: "owner-permission",
      }],
      ["existing-key", { googleSubject: "existing-subject" }],
    ]);
    const profile = {
      datasetId: "primary",
      ownerEmail: "owner@example.com",
      folderName: "EasyBC — owner@example.com",
      role: "owner" as const,
      trustedOwnerKeyId: "owner-key",
      syncMode: "encrypted" as const,
      participantEmails: { "existing-key": "existing@example.com" },
    };

    expect(
      mergedControlMemberMetadata(members, profile, {
        "new-key": { email: "new@example.com" },
      }),
    ).toEqual({
      "owner-key": {
        email: "owner@example.com",
        googleSubject: "owner-subject",
        drivePermissionId: "owner-permission",
      },
      "existing-key": {
        email: "existing@example.com",
        googleSubject: "existing-subject",
      },
      "new-key": { email: "new@example.com" },
    });
  });
});
