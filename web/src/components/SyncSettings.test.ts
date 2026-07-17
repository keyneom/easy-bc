import { describe, expect, it } from "vitest";
import {
  canManageParticipantAccess,
  safeParseSharingJoinLinkV1,
  safeParseSharingResponseLinkV1,
  sharingJoinErrorMessage,
  shouldAutoSubmitJoinDeepLink,
} from "./SyncSettings";

const participant = {
  role: "writer" as const,
  isCurrentDevice: false,
  emailAddress: "person@example.com",
  trust: "invite" as const,
};

describe("participant access controls", () => {
  it("allows only owners and admins to manage another identified participant", () => {
    expect(canManageParticipantAccess("owner", participant)).toBe(true);
    expect(canManageParticipantAccess("admin", participant)).toBe(true);
    expect(canManageParticipantAccess("writer", participant)).toBe(false);
    expect(canManageParticipantAccess("viewer", participant)).toBe(false);
  });

  it("does not offer controls for owners, the current device, or an unknown email", () => {
    expect(canManageParticipantAccess("owner", { ...participant, role: "owner" })).toBe(false);
    expect(canManageParticipantAccess("owner", { ...participant, isCurrentDevice: true })).toBe(false);
    expect(canManageParticipantAccess("owner", { ...participant, emailAddress: undefined })).toBe(false);
  });
});

describe("sharing deep-link parsing", () => {
  it("treats malformed invite payloads as invalid instead of throwing during render", () => {
    expect(
      safeParseSharingJoinLinkV1(
        "?sync-kit-join=1&sk-inv=not-base64&sk-files=not-base64",
      ),
    ).toBeNull();
  });

  it("treats malformed response payloads as invalid instead of crashing a deep link", () => {
    expect(
      safeParseSharingResponseLinkV1("?sk-resp=1&sk-kr=not-base64"),
    ).toBeNull();
  });

  it("keeps the standalone join flow on its signed-offer preview", () => {
    expect(shouldAutoSubmitJoinDeepLink("join", true)).toBe(false);
    expect(shouldAutoSubmitJoinDeepLink("detail", true)).toBe(true);
    expect(shouldAutoSubmitJoinDeepLink("detail", false)).toBe(false);
  });

  it("explains why a pre-0.2.1 invite must be recreated", () => {
    expect(
      sharingJoinErrorMessage(
        new Error("The sharing link file manifest is not authenticated by its invitation."),
      ),
    ).toBe(
      "This invite was created by an older EasyBC release. Ask the owner to create and send a new invite link.",
    );
  });
});
