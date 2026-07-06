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

## Added after the 2026-07 live sharing debugging

Every sharing failure in that session lived in an app/sync-kit seam. These
close the seams; consumers keep UI, copy, persistence, and lifecycle.

### Own the join-link format end to end (highest value)

`SharingJoinParams` only carries `appFolderId`/`exchangeId`; EasyBC appends
`owner`/`invitation` itself and hand-rolled parsers on Android and web — the
parameter mismatch broke every join link shipped between v0.1.26 and v0.1.36.
Extend `SharingJoinParams` with `invitationFileId` and `ownerEmail`, make
`appendSharingJoinParams`/`parseSharingJoinParams` round-trip the complete
payload on both platforms, and pin it with a cross-platform fixture (a link
generated on Kotlin must parse on TS and vice versa).

### Grant hand-off convention

The `grant-folder=1` marker is protocol, not app UI: web must interpret it
(run the Picker, skip auto-join), native must generate it and must not
consume it. Define the constant plus `appendGrantOnlyMarker`/`isGrantOnly`
helpers next to the join-param helpers.

### Android grant-browser helper + merged manifest queries

Opening the Picker hand-off page requires forcing a real browser package
(the consumer app typically owns the landing-page App Link, so unaddressed
VIEW intents loop back) and requires `<queries>` for Custom Tabs providers
and https browsers — invisible-by-default on Android 11+. sync-kit-android
should ship the launcher helper and the `<queries>` block in its library
manifest so every consumer inherits both.

### `createOrAdoptDataset(datasetId, value, { requireOwned })`

The setup dance — list datasets, adopt when an owned one exists (interrupted
setup, reinstall, reconnect), otherwise create, and surface "exists but not
decryptable by this identity" distinctly — is protocol-level and both
platforms repeat it.

### Distinct error for scope-invisible files

The Drive transport should map 404s on exchange/dataset reads to a dedicated
error (e.g. `access`/`not-visible`) with guidance, because with `drive.file`
a 404 usually means "the user has access but the app was never granted the
file" — consumers keep translating that into UX copy blind.
