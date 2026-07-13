import { describe, expect, it } from "vitest";
import {
  profileDisplayLabel,
  disambiguatedProfileLabel,
  newOwnedDatasetId,
  slugifyDatasetId,
  uniqueOwnedDatasetId,
} from "./profileLabels";
import { findProfile, type ProfileRecord, type SharedSyncState } from "./sharedTypes";

const state = (profiles: ProfileRecord[]): SharedSyncState => ({
  schemaVersion: 1,
  rpId: "keyneom.github.io",
  ownerEmail: "mom@example.com",
  activeProfileKey: "mom@example.com/primary",
  profiles,
});

const owned = (datasetId: string, displayName?: string): ProfileRecord => ({
  datasetId,
  ownerEmail: "mom@example.com",
  folderName: "EasyBC — mom@example.com",
  role: "owner",
  trustedOwnerKeyId: "key-1",
  ...(displayName ? { displayName } : {}),
});

describe("slugifyDatasetId", () => {
  it("slugifies display names", () => {
    expect(slugifyDatasetId("Daughter")).toBe("daughter");
    expect(slugifyDatasetId("Sarah's cycle")).toBe("sarah-s-cycle");
  });

  it("rejects reserved primary slug", () => {
    expect(() => slugifyDatasetId("primary")).toThrow();
  });
});

describe("uniqueOwnedDatasetId", () => {
  it("returns base slug when unused", () => {
    expect(uniqueOwnedDatasetId("Daughter", "mom@example.com", [owned("primary")])).toBe(
      "daughter",
    );
  });

  it("appends suffix on collision", () => {
    expect(
      uniqueOwnedDatasetId("Daughter", "mom@example.com", [
        owned("primary"),
        owned("daughter"),
      ]),
    ).toBe("daughter-2");
  });
});

describe("newOwnedDatasetId", () => {
  it("creates an opaque id independent of the display label", () => {
    expect(
      newOwnedDatasetId(["primary"], () => "12345678-1234-1234-1234-123456789abc"),
    ).toBe("p-12345678-1234-1234-1234-123456789abc");
  });
});

describe("profileDisplayLabel", () => {
  it("labels primary owned profile as My data", () => {
    expect(profileDisplayLabel(state([owned("primary")]), owned("primary"))).toBe("My data");
  });

  it("uses displayName for owned profiles", () => {
    expect(
      profileDisplayLabel(state([owned("daughter", "Daughter")]), owned("daughter", "Daughter")),
    ).toBe("Daughter");
  });

  it("uses folder name for shared profiles", () => {
    const shared: ProfileRecord = {
      datasetId: "primary",
      ownerEmail: "other@example.com",
      folderName: "EasyBC — other@example.com",
      role: "viewer",
      trustedOwnerKeyId: "key-2",
    };
    expect(profileDisplayLabel(state([shared]), shared)).toBe("EasyBC — other@example.com");
  });

  it("uses a device-local override without replacing the shared label", () => {
    const shared: ProfileRecord = {
      datasetId: "primary",
      ownerEmail: "other@example.com",
      folderName: "EasyBC — other@example.com",
      displayName: "Household plan",
      localDisplayName: "Alex",
      role: "viewer",
      trustedOwnerKeyId: "key-2",
    };
    expect(profileDisplayLabel(state([shared]), shared)).toBe("Alex");
  });

  it("adds owner email when visible labels conflict", () => {
    const a = { ...owned("a", "Plan"), ownerEmail: "a@example.com" };
    const b = { ...owned("b", "Plan"), ownerEmail: "b@example.com" };
    const value = state([a, b]);
    expect(disambiguatedProfileLabel(value, a)).toBe("Plan — a@example.com");
    expect(disambiguatedProfileLabel(value, b)).toBe("Plan — b@example.com");
  });

  it("labels local-only profiles independently of cloud ownership", () => {
    const local: ProfileRecord = {
      datasetId: "profile",
      ownerEmail: "local-123",
      folderName: "",
      displayName: "Offline journal",
      role: "owner",
      trustedOwnerKeyId: "",
      syncMode: "local",
    };
    expect(profileDisplayLabel(state([local]), local)).toBe("Offline journal");
  });

  it("keeps two owners' primary datasets distinct", () => {
    const local = owned("primary");
    const shared = {
      ...owned("primary"),
      ownerEmail: "other@example.com",
      folderName: "EasyBC — other@example.com",
      role: "writer" as const,
      trustedOwnerKeyId: "other-key",
    };
    const profiles = [local, shared];
    const value = state(profiles);
    expect(findProfile(value, "mom@example.com/primary")).toEqual(local);
    expect(findProfile(value, "other@example.com/primary")).toEqual(shared);
  });
});
