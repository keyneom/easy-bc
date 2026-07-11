import { describe, expect, it } from "vitest";
import { canManageParticipantAccess } from "./SyncSettings";

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
