import { defineV1CompatibilityProfile } from "@keyneom/sync-kit/crypto";
import { createWebPasskeyProvider } from "@keyneom/sync-kit/keys/web-passkey";
import {
  IndexedDbProtectedSharingIdentityStore,
  PasskeyProtectedSharingIdentityProvider,
} from "@keyneom/sync-kit/sharing/web-passkey";
import { EASY_BC_APP_ID } from "./sharedTypes";

export const easyBcSharingPasskeyProfile = defineV1CompatibilityProfile({
  appId: `${EASY_BC_APP_ID}-sharing`,
  filename: "unused",
  aad: "easy-bc-sharing-identity-v1",
  hkdfInfo: "easy-bc-sharing-identity-wrap-v1",
  compression: "none",
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

export function createSharingIdentityProvider(rpId: string): PasskeyProtectedSharingIdentityProvider {
  const passkeyProvider = createWebPasskeyProvider(easyBcSharingPasskeyProfile, { rpId });
  return new PasskeyProtectedSharingIdentityProvider({
    appId: EASY_BC_APP_ID,
    passkeyProvider,
    store: new IndexedDbProtectedSharingIdentityStore({ databaseName: "easy-bc-sharing" }),
  });
}
