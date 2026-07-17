import type { Authorization } from "@keyneom/sync-kit/core";
import { defineV1CompatibilityProfile } from "@keyneom/sync-kit/crypto";
import { createWebPasskeyProvider } from "@keyneom/sync-kit/keys/web-passkey";
import { DriveAppDataProtectedSharingIdentityStore } from "@keyneom/sync-kit/sharing/appdata-identity-store";
import { MigratingProtectedSharingIdentityStore } from "@keyneom/sync-kit/sharing/migrating-identity-store";
import {
  IndexedDbProtectedSharingIdentityStore,
  PasskeyProtectedSharingIdentityProvider,
} from "@keyneom/sync-kit/sharing/web-passkey";
import { EASY_BC_APP_ID } from "./sharedTypes";
import { SingleFlightSessionValue } from "./SingleFlightSessionValue";

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

type SharingIdentity = Awaited<
  ReturnType<PasskeyProtectedSharingIdentityProvider["getOrCreate"]>
>;

const sessionIdentity = new SingleFlightSessionValue<SharingIdentity>();
const liveProviders = new Set<PasskeyProtectedSharingIdentityProvider>();
let sessionRpId: string | null = null;

class EasyBcSharingIdentityProvider {
  constructor(private readonly provider: PasskeyProtectedSharingIdentityProvider) {
    liveProviders.add(provider);
  }

  get(): Promise<SharingIdentity> {
    this.register();
    return sessionIdentity.getOrLoad(() => this.provider.get());
  }

  getOrCreate(): Promise<SharingIdentity> {
    this.register();
    return sessionIdentity.getOrLoad(() => this.provider.getOrCreate());
  }

  create(): Promise<SharingIdentity> {
    this.register();
    return sessionIdentity.getOrLoad(() => this.provider.create());
  }

  async delete(): Promise<void> {
    clearSharingIdentitySession();
    await this.provider.delete();
  }

  accountBindingCredential() {
    this.register();
    return this.provider.accountBindingCredential();
  }

  /** Drops only this operation's provider; the tab-wide unlocked identity stays warm. */
  clear(): void {
    this.provider.clear();
    liveProviders.delete(this.provider);
  }

  private register(): void {
    liveProviders.add(this.provider);
  }
}

/** Clears the decrypted sharing identity at an actual account/privacy boundary. */
export function clearSharingIdentitySession(): void {
  sessionIdentity.clear();
  for (const provider of liveProviders) provider.clear();
  liveProviders.clear();
}

export function createSharingIdentityProvider(
  rpId: string,
  authorization: () => Promise<Authorization>,
): EasyBcSharingIdentityProvider {
  if (sessionRpId !== null && sessionRpId !== rpId) {
    clearSharingIdentitySession();
  }
  sessionRpId = rpId;
  const passkeyProvider = createWebPasskeyProvider(easyBcSharingPasskeyProfile, { rpId });
  // App-data is the authoritative home for the identity so it follows the same
  // Google account across devices; the pre-existing IndexedDB blob is promoted
  // to app-data on first load so returning devices never regenerate a key.
  const store = new MigratingProtectedSharingIdentityStore({
    primary: new DriveAppDataProtectedSharingIdentityStore({ authorization }),
    legacy: new IndexedDbProtectedSharingIdentityStore({ databaseName: "easy-bc-sharing" }),
  });
  return new EasyBcSharingIdentityProvider(
    new PasskeyProtectedSharingIdentityProvider({
      appId: EASY_BC_APP_ID,
      passkeyProvider,
      store,
    }),
  );
}
