# sync-kit: accessible dataset discovery across `drive.file` grants

Written 2026-07-16 against sync-kit `0.2.0-rc.16` and EasyBC `v0.1.62`.

## Implementation status — sync-kit `0.2.0-rc.17`

Implemented upstream on both TypeScript and Kotlin:

- `listAccessibleSyncKitDatasets()` owns the managed sharing-file query,
  pagination, filtering, deduplication, and deterministic ordering;
- the sharing protocol discriminator remains internal to sync-kit;
- `GoogleDriveSharedBackupTransport.listDatasets()` resolves folders without
  creating storage;
- shared fixture coverage pins TypeScript/Kotlin parity and read-only behavior.

EasyBC now consumes rc.17 on Web and Android, has removed its private protocol
query implementations and per-folder discovery listing, and routes both owned
and recipient profiles by a persisted `appFolderId` when available. Local Web
typecheck/tests/build and Android unit tests pass. The live two-account
Web/Android OAuth + Picker probe below remains the final release-validation
step before promoting sync-kit `0.2.0` to stable.

## Decision

The Google Drive enumeration added for EasyBC profile discovery belongs in
sync-kit on both TypeScript and Kotlin. It depends on sync-kit's managed-file
metadata, sharing protocol discriminator, Drive pagination, and transport
semantics. Keeping it in EasyBC duplicates protocol knowledge and makes Web and
Android drift independently.

EasyBC still owns the product-specific layer: when discovery runs, profile and
split-dataset grouping, account/passkey acceptance policy, trusted-owner pinning,
profile labels, local registry updates, active-profile choice, and UI.

## Problem

`drive.file` access is granted per file. A recipient can therefore read dataset
files selected through Google Picker while the parent sync-kit app-root folder
is not listable to that OAuth client. `listAccessibleSyncKitAppFolders()` alone
misses those profiles.

EasyBC currently compensates by querying Drive for sync-kit dataset metadata in
both consumers. That compensation has three boundary problems:

1. It duplicates the package-owned `sharing-v1` discriminator and managed-file
   property selection in application code.
2. Web and Android maintain separate pagination, filtering, grouping, and
   deduplication implementations.
3. Discovery also calls `GoogleDriveSharedBackupTransport.listDatasets()`.
   That method currently calls `ensureStorage()`, which can create the
   `exchanges` folder. Profile discovery must be a read-only operation.

## Required sync-kit API

Add matching TypeScript and Kotlin APIs for enumerating every managed sharing
dataset already accessible to the current `drive.file` token, independently of
whether its parent folder can be listed.

Suggested TypeScript surface under
`@keyneom/sync-kit/stores/google-drive/sharing`:

```ts
export type AccessibleSyncKitDataset = {
  datasetId: string;
  fileId: string;
  name: string;
  appFolderId?: string;
  canEdit?: boolean;
  modifiedTime?: string;
};

export type ListAccessibleSyncKitDatasetsOptions = {
  appId: string;
  authorization: Authorization;
  drive?: GoogleDriveFileStore;
};

export function listAccessibleSyncKitDatasets(
  options: ListAccessibleSyncKitDatasetsOptions,
): Promise<AccessibleSyncKitDataset[]>;
```

Add behaviorally identical support to `sync-kit-android`, using Kotlin data
classes and a suspend function with the same names where Kotlin conventions
allow.

The API belongs in the sharing-specific Google Drive export rather than a
consumer because only sync-kit should know that a dataset is selected by:

- the supplied `appId`;
- sync-kit's current sharing protocol property/value;
- sync-kit's managed kind for datasets; and
- sync-kit's dataset-ID property.

Do not export a new public `SHARING_PROTOCOL = "sharing-v1"` constant merely so
consumers can reconstruct this query. Keep that wire detail internal and expose
the operation.

## Required behavior

`listAccessibleSyncKitDatasets()` must:

1. Reject a blank `appId` before making a request.
2. Query normal Drive space with the existing `drive.file` authorization. It
   must not request broader Drive scopes.
3. Select non-trashed managed sharing dataset files for the supplied `appId`
   using sync-kit's internal metadata contract.
4. Follow every Drive page token on both TypeScript and Kotlin.
5. Return the first parent ID as `appFolderId` when Drive supplies one. A file
   remains readable and may still be returned when the parent is absent.
6. Deduplicate by `fileId` and produce deterministic ordering, preferably by
   `appFolderId`, `datasetId`, then `fileId` using wire-stable UTF-16 comparison
   on TypeScript and matching Kotlin ordering.
7. Ignore unrelated files. A managed-looking entry with a missing/blank dataset
   ID must either be skipped consistently on both platforms or produce the same
   typed compatibility error on both platforms; the chosen behavior must be
   documented and parity-tested. Tolerant skipping is preferable for discovery
   because one damaged file should not hide every healthy profile.
8. Be strictly read-only: no folder creation, exchange-folder creation,
   permission mutation, file write, or registry mutation.
9. Accept an injected Drive store so unit tests can assert queries, pagination,
   and absence of writes without live Google credentials.

## Transport correction

Refactor `GoogleDriveSharedBackupTransport.listDatasets()` so a read does not
create storage as a side effect.

- With `selectedAppFolderId`, list directly under that ID.
- Without a selected ID, separate read-only folder resolution from
  `ensureStorage()`; a list may return an empty result when no app-root exists,
  but must not create the app-root or `exchanges` folder.
- Keep `ensureStorage()` as the explicit mutating path used by create/invite
  operations.

This correction is independently valuable even after EasyBC switches to the
new global enumeration API.

## EasyBC adoption after the release

After the package is released on npm and Android:

1. Bump both EasyBC consumers to the same sync-kit version.
2. Delete `web/src/sync/accessibleDatasets.ts` and the private Android
   `listAccessibleSyncKitDatasets()` implementation.
3. Call the package API once per discovery run. Group returned files by
   `appFolderId`; optionally join them with
   `listAccessibleSyncKitAppFolders()` for a Drive display name.
4. Do not call per-folder `transport.listDatasets()` during discovery. The
   global file query is the authoritative set already granted to the token.
5. Continue reading each candidate through the sync-kit transport/controller
   so managed-file provenance, envelope app/dataset matching, signature,
   trusted-owner key, participant membership, and decryption are verified.
6. Continue applying EasyBC's split-dataset grouping and hard-cutover rules in
   EasyBC. Dataset parts and profile generations are application policy.
7. Persist `appFolderId` and pass it back as `selectedAppFolderId` for both
   owned and recipient profiles. Folder names are display/fallback data, never
   routing keys. This is an EasyBC fix, not a package-discovery responsibility.
8. Keep profile-discovery prompts, retries, local-only fallback, profile label
   rules, and active-profile selection in EasyBC.

## Tests and release gates

The package release is not complete until all of the following pass on both
TypeScript and Kotlin:

- a visible app-root with owned datasets;
- dataset-file grants whose parent app-root is not listable;
- mixed owned and recipient files for the same `appId`;
- unrelated app IDs and non-dataset managed files are excluded;
- multiple Drive pages are exhausted exactly once;
- duplicate file IDs are deduplicated deterministically;
- missing parent and malformed dataset metadata behavior is identical;
- the injected Drive fake records zero create/write/share/delete operations;
- `listDatasets()` itself performs no create operation;
- the normal sync-kit `npm run check` gate, including Android/native and parity
  checks, passes.

Add a cross-language fixture containing Drive metadata pages and expected
results so the JS and Kotlin outputs stay identical.

As a release-validation-only probe, use two Google accounts to confirm the
real service behavior: the owner creates a profile; the recipient selects only
the required dataset/control files in Picker; a fresh Web and Android client
for that recipient both enumerate the files without folder visibility and then
recover the same profile. This live probe validates Google OAuth/Picker behavior
but is not a runtime product requirement.

## Non-goals

- sync-kit does not decide which EasyBC datasets form one profile.
- sync-kit does not use owner email, folder title, or profile label as an ID.
- sync-kit does not decide whether a discovered participant should become an
  EasyBC profile; it only returns accessible managed-file metadata.
- sync-kit does not open Picker or own discovery UI.
- `appDataFolder` remains the protected sharing-identity substrate; it does not
  replace `drive.file` dataset enumeration.
