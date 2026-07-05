# sync-kit upstream improvements (EasyBC)

EasyBC implements shared encrypted sync at the app layer today. These abstractions
would reduce duplication across consumers and are recommended for `@keyneom/sync-kit`.

## High priority

### `buildSyncKitFolderName({ appDisplayName, ownerLabel, format? })`

Sanitize Drive folder names with documented max length and forbidden characters.
EasyBC currently uses `EasyBC — owner@email` via [`web/src/sync/sharedFolderName.ts`](../web/src/sync/sharedFolderName.ts).

### `IndexedDbSharedBackupRegistry`

Persist `SharedBackupRegistry` records in IndexedDB, mirroring
`IndexedDbProtectedSharingIdentityStore`.

### `buildSharingJoinUrl` / `parseSharingJoinUrl`

Typed query params for `exchangeId`, `appFolderId`, `invitationFileId`, `ownerEmail`, `appId`.
EasyBC currently parses `?sync=join&…` in [`web/src/sync/sharedJoin.ts`](../web/src/sync/sharedJoin.ts).

### `createSharingJoinRouter`

Orchestrate OAuth → folder selection → `submitKeyResponse` from URL/Open-with state.

## Medium priority

- **`inviteParticipant({ landingUrl })`** — append app join link to Drive invitation email body
- **`listAccessibleAppFolders()`** — enumerate shared folders with owner metadata for recipients
- **`SharedDatasetProfile` registry helpers** — `{ displayName, ownerEmail, role, folderName }`
- **`reconcileDrivePermissions`** — already spec'd in sync-kit docs, not implemented

## Deferred

- **Android `/sharing` port** — required before Android participates in shared encrypted sync

## Tracking

File issues or PRs in [keyneom/sync-kit](https://github.com/keyneom/sync-kit) referencing this document when starting upstream work.
