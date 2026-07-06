# Sharing & profiles UX backlog

Issues observed during live testing of encrypted shared sync (v0.1.29–v0.1.34,
2026-07-05/06). Android is the reference implementation; fixes land there
first, web follows best-effort.

## Quick wins

### 1. Join link is not copyable — SHIPPED v0.1.35
`SettingsScreen.kt` now shows the join link with a copy-to-clipboard button
and the system share sheet; the link is the primary invitation channel.

### 2. Invite email never arrives / 3. shared folder flagged as spam — MITIGATED v0.1.35
The "invite" email is Google Drive's generic folder-share notification for
`EasyBC — owner@email`; sync-kit already attaches a join-link message, but
deliverability is Google's and cannot be fixed by us. v0.1.35 states this in
the UI ("Google's share email is often filtered as spam — send this join link
directly") and makes the link copyable/shareable (#1). Remaining option if
this stays painful: suppress the notification email entirely
(`sendNotificationEmail = false`) so users never wait for it.

### 4. Migrate vs. Set up is user-hostile — SHIPPED v0.1.35
Exactly one primary action now renders: "Migrate legacy encrypted sync" when
legacy snapshot metadata exists on the device (with copy explaining it merges
into the new format), otherwise "Set up encrypted sync". Setup also adopts an
existing decryptable primary dataset in Drive (sync-kit rc.5
`adoptDataset(requireOwned)`) instead of failing with "already exists", and
"Reset encrypted sync" now really deletes the owned Drive datasets (sync-kit
rc.5 `deleteDataset`) before recreating, so an undecryptable orphan has an
in-app recovery path.

## Design work

### 5. Cannot join a share without an in-app entry point — SHIPPED v0.1.35
Settings now has "Paste a join link" + "Join a shared profile" when shared
sync is unconfigured, parsing the same invitation/folder/owner parameters as
the deep link. A Drive folder picker remains a possible future addition.

### 6. Viewer-only use is impossible — PARTIALLY ADDRESSED v0.1.35
With the paste-join flow, a device can join a shared profile without ever
setting up its own encrypted sync — the joined profile becomes the active
one. Full "no local profile at all" semantics land with #7.

### 7. Profiles must become native to the app, not a sync feature
Today "profiles" only exist inside encrypted sync (`SharedSyncRegistry`
profile records keyed by owner email + dataset). Product requirement:
- Profiles are a first-class app concept (local Room entity): a mother tracks
  herself and her daughter as two local profiles **without** enabling
  encrypted sync at all.
- Encrypted sync becomes a per-profile toggle: one profile synced/shared, the
  other purely local.
- The Settings "Encrypted Cloud Sync" section then manages sync for the
  active profile, instead of owning the profile concept.
This is the largest item and reshapes #5/#6: joining a share creates a
profile; local-only profiles and synced profiles are the same UI object.
