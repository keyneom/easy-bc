# sync-kit: hybrid identity + appdata substrate for sharing

Follow-up to [`sync-kit-upstream-issues.md`](sync-kit-upstream-issues.md). Framed
as a change to sync-kit so both web and Android consumers benefit. Written
2026-07-07 based on live debugging with keyneom122 as recipient, leslie.tumulak
as owner.

## Implementation status — sync-kit 0.2.0-rc.7 (2026-07-08)

Implemented and unit/parity-tested on both platforms:

- **Crypto core (Kotlin):** `ProtectedSharingIdentityCrypto` (wrap/unwrap) in
  `sync-kit-android`, byte-compatible with the web `ProtectedSharingIdentityV1`.
  A frozen cross-language fixture (`fixtures/sharing-v1/protected-identity.json`)
  is unlocked by both the TS and Kotlin suites; WebCrypto↔JCE PKCS#8 import was
  verified both directions.
- **Stores:** `DriveAppDataProtectedSharingIdentityStore` and
  `MigratingProtectedSharingIdentityStore` in both TS and Kotlin. (Kotlin class
  is named `DriveAppDataProtectedSharingIdentityStore`, not the doc's earlier
  `GoogleDriveAppDataSharingIdentityStore`.)
- **Passkey:** `AndroidPasskeyKeyProvider.unlockMetadata` derives the sharing
  wrapping key without disturbing the personal-sync cache.
- **Consumers:** web `sharedIdentity.ts` composes appdata(primary)+IndexedDB
  (legacy); Android `SharingIdentityStore` composes appdata + a passkey-wrap
  migration of the legacy raw EncryptedSharedPreferences keypair (wrapped in
  place, never regenerated). A foreground-Activity tracker
  (`EasyBcForegroundActivity`) supplies the passkey UI Activity.

Deviations from the design below:

- **§4 multi-blob keyed by credentialId — deferred.** rc.7 uses a single blob
  per appId (`sync-kit-sharing-identity-<appId>.json`). This covers "one identity
  across a user's devices *within one passkey ecosystem*" (Google Password
  Manager, or iCloud Keychain), which is the stated requirement. A user holding
  *both* an iCloud and a GPM passkey for the same account is not yet covered;
  that is the §4 follow-up.
- **§3 `drive.appdata` scope — already present.** Both platforms already request
  `drive.appdata` alongside `drive.file`; no scope change was needed.

Remaining before release: end-to-end device + web validation (see
`sharing-ux-backlog.md`), then publish rc.7 and the app updates.

## Problem

The current sharing scheme (`@keyneom/sync-kit/sharing`, `sync-kit-android`
`sharing/*`) generates a fresh ECDH-P256 + ECDSA-P256 keypair on every device
where sharing is set up:

- Web: [`sync-kit/src/sharing/web-passkey.ts#createProtectedSharingIdentityV1`](../../sync-kit/src/sharing/web-passkey.ts) at lines 162–171 — `subtle.generateKey(...)` twice, then wrap the exported private keys with the passkey-derived AES key and persist the wrapped blob in browser IndexedDB (`IndexedDbProtectedSharingIdentityStore`).
- Android: [`easy-bc/android/.../SharingIdentityStore.kt#generateIdentity`](../android/app/src/main/java/com/easybc/planner/sync/shared/SharingIdentityStore.kt) — `SharingCrypto.generateIdentity()`, stored in `EncryptedSharedPreferences` (Android Keystore-wrapped).

Consequence: two devices of the **same Google user** are cryptographically two
different participants. Live symptoms:

1. Web "Set up encrypted sync" for an account that already has a dataset (from
   Android) hits [`sharedSync.ts:416`](../web/src/sync/sharedSync.ts) —
   `adoptDataset(PRIMARY_DATASET_ID, { requireOwned: true })` throws because the
   new web identity isn't in the dataset's `participants[]`/`keyGrants[]`.
   Surfaces as "already exists but cannot decrypt."
2. Every new device of an existing owner requires an out-of-band self-join
   ceremony to be granted a `keyGrant` — architecturally treated as a stranger
   despite being the same Google account.

## What we used to do (that worked)

The pre-sharing personal encrypted sync (v1 profile) derived a symmetric AES-GCM
content key deterministically:

```
content_key = HKDF(prf_secret, kdfSalt, hkdfInfo)
```

where `prf_secret` came from the passkey PRF extension (WebAuthn) — the same
passkey PRF on any device produces the same secret. The ciphertext lived in
`drive.appdata` — Google's per-app-per-user private folder, invisible in the
Drive UI, not affected by folder sharing, scoped to a single Google account.

Both platforms had this working:

- Web: `WebPasskeyProvider` + `GoogleDriveAppDataStore` in sync-kit.
- Android: [`AndroidPasskeyKeyProvider`](../../sync-kit/android/synckit/src/main/java/com/keyneom/synckit/keys/AndroidPasskeyKeyProvider.kt) + `GoogleDriveAppDataStore` in sync-kit-android.

Same passkey (synced via iCloud Keychain / Google Password Manager) → same PRF
secret → same content key → same ciphertext decrypted on any device.

When sharing was added, the substrate moved from `drive.appdata` to shared
`drive.file` folders and the crypto moved from symmetric-deterministic to
asymmetric-participant-based. Both changes were required *for the multi-user
case*, but they were applied to the single-user case too, breaking cross-device
continuity for one's own account.

## Requirement

One sharing identity per Google user, identical on every device that user signs
into, with no ceremony to bring a new device online.

Sharing (per-participant key grants) stays unchanged for the multi-user case —
that model is correct when the participants are actually different people.

## Design

### 1. Wrap identity with the passkey PRF secret (already done on web)

`PasskeyProtectedSharingIdentityProvider` already wraps the exported PKCS#8
private keys with an AES-GCM key derived from `PRF(prfInput)` and `kdfSalt`.
Reuse this unchanged on web.

On Android, replace `SharingIdentityStore`'s Android-Keystore wrap with the
same passkey-PRF wrap by porting `PasskeyProtectedSharingIdentityProvider` to
Kotlin on top of `AndroidPasskeyKeyProvider`. sync-kit-android already has the
PRF plumbing.

### 2. Move the wrapped identity blob from device-local storage to `drive.appdata`

Replace both `IndexedDbProtectedSharingIdentityStore` (web) and the raw-prefs
Kotlin equivalent with a new store implementation:

```ts
// sync-kit/src/sharing/appdata-identity-store.ts (new)
export class DriveAppDataProtectedSharingIdentityStore
  implements ProtectedSharingIdentityStore {
  constructor(options: { authorization(): Promise<Authorization>; filename?: string });
  load(appId): Promise<ProtectedSharingIdentityV1 | null>;
  save(record: ProtectedSharingIdentityV1): Promise<void>;
  delete(appId): Promise<void>;
}
```

Reads/writes go against `spaces=appDataFolder` on the Drive v3 files API. Since
the blob is already AES-GCM-encrypted with the passkey PRF secret, `drive.appdata`
is a plaintext-safe transport: Drive sees an opaque JSON with an encrypted
`encryptedPrivateKeys` field.

Provide a symmetric Kotlin implementation in `sync-kit-android`:
`GoogleDriveAppDataSharingIdentityStore` on top of the existing
`GoogleDriveAppDataStore` transport.

### 3. Bring `drive.appdata` scope back to sharing setup

Currently only `drive.file` is requested for the sharing feature. Add
`drive.appdata` on both platforms. This does not broaden user-visible access —
appdata is invisible in Drive UI and inaccessible to other apps and other users.

### 4. Multi-blob keyed by credentialId

Support N wrapped blobs indexed by `credentialId`:

```
appdata/sync-kit-sharing-identity/<appId>/<credentialId>.json
```

Any registered passkey the user has can unwrap. Handles passkey rotation and
the case where a user has both an iCloud Keychain passkey and a Google Password
Manager passkey. Load path tries each blob against the current unlock until one
succeeds.

## Behavior after the change

### First device (owner)
1. Sign in, grant `drive.file` + `drive.appdata`.
2. Create passkey, evaluate PRF, generate ECDH+ECDSA identity.
3. Wrap private keys with PRF-derived AES-GCM key.
4. Write wrapped blob to `appdata/…<credentialId>.json`.
5. Create the shared Drive folder + dataset, encrypted for this identity's
   public key, as today.

### Second device (same owner, different device)
1. Sign in with the same Google account, grant same scopes.
2. Check `drive.appdata` for a `sharing-identity` blob → present.
3. Present the synced passkey (Credential Manager / WebAuthn), evaluate PRF.
4. Unwrap the blob → import the same ECDH + ECDSA private keys.
5. Cache in local IndexedDB / EncryptedSharedPreferences as a hot path.
6. `adoptDataset(PRIMARY_DATASET_ID, { requireOwned: true })` now succeeds
   because the identity's `keyId` matches the dataset's participant record.

No self-join, no owner-add ceremony, no manual pairing.

### Recipient (different Google user)
Unchanged. Recipient's identity is theirs alone, wrapped in *their* appdata
under *their* Google account. They join via the current invitation/keyGrant
flow. This is exactly what the sharing protocol is designed for.

## Migration

- Legacy device with sharing identity only in local IndexedDB / EncryptedSharedPreferences:
  on next auth, if appdata blob is missing, upload the local wrapped blob to
  appdata (already have private keys unlocked). From then on appdata is
  authoritative and local storage becomes a cache.
- Fresh install for a returning user: no local identity, appdata blob present →
  unwrap and load — no fresh identity generated, no adopt failure.

## Non-goals

- Does not change the sharing protocol (participants + keyGrants unchanged).
- Does not merge two distinct users into one identity — cross-user separation
  is still cryptographic.
- Does not attempt to solve the recipient-side `drive.file` visibility problem
  ([`sharing-ux-backlog.md`](sharing-ux-backlog.md) §2c/§2d) — that is a
  separate scope-shaped issue and is tracked separately.

## Interface changes to publish

- New: `DriveAppDataProtectedSharingIdentityStore` (TS) +
  `GoogleDriveAppDataSharingIdentityStore` (Kotlin).
- New: constructor options on `PasskeyProtectedSharingIdentityProvider` accepting
  a multi-blob store abstraction, or default to `credentialId`-keyed lookup when
  the store returns a list.
- Existing consumers (EasyBC) swap the store implementation at
  [`web/src/sync/sharedIdentity.ts`](../web/src/sync/sharedIdentity.ts) and
  [`android/.../SharingIdentityStore.kt`](../android/app/src/main/java/com/easybc/planner/sync/shared/SharingIdentityStore.kt).
- Auth request adds `drive.appdata` alongside `drive.file` for sharing setup on
  both platforms.

## Test fixtures

A cross-platform integration test to pin the invariant:

1. Web: create passkey P, run sharing setup on account A → assert appdata blob
   at `sharing-identity/<credentialId>.json` is present and the identity's
   `keyId` is stable.
2. Android with the same Google Password Manager passkey P: run sharing setup
   on account A → assert the same `keyId` is loaded (no new key generation)
   and no `adoptDataset` self-ceremony was invoked.
3. Web (fresh browser origin, same passkey P): repeat step 2 to prove
   device-N works identically.

If the identity keyId matches across all three without any add-participant
ceremony, the substrate is correct.
