import { defineV1CompatibilityProfile } from "@keyneom/sync-kit/crypto";

/**
 * EasyBC owns these persisted v1 values. They must stay aligned with the
 * Android implementation before any writer-version or label change.
 */
export const easyBcV1Profile = defineV1CompatibilityProfile({
  appId: "easy-bc",
  filename: "easybc-sync-v1.json",
  aad: "easy-bc-sync-envelope-v1",
  hkdfInfo: "easy-bc-cloud-content-key-v1",
  compression: "gzip-if-smaller",
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
