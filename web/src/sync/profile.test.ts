import { describe, expect, it } from "vitest";
import { easyBcV1Profile } from "./profile";

describe("EasyBC-owned v1 compatibility profile", () => {
  it("freezes the web and Android wire-format constants", () => {
    expect(easyBcV1Profile).toEqual({
      appId: "easy-bc",
      filename: "easybc-sync-v1.json",
      aad: "easy-bc-sync-envelope-v1",
      hkdfInfo: "easy-bc-cloud-content-key-v1",
      algorithm: "AES-256-GCM+HKDF-SHA-256",
      readVersions: [1],
      writeVersion: 1,
      compression: "gzip-if-smaller",
      nonceBytes: 12,
      kdfSaltBytes: 32,
      prfInputBytes: 32,
      tagBits: 128,
      passkey: {
        rpName: "EasyBC",
        userName: "encrypted-sync",
        userDisplayName: "EasyBC encrypted sync",
        algorithm: -7,
        residentKey: "required",
        userVerification: "required",
        timeoutMs: 60_000,
      },
    });
  });
});
