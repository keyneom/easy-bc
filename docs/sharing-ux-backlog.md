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

Live-confirmed 2026-07-06: the folder share landed in the recipient Drive's
own spam section and had to be marked "not spam"; no email ever arrived. If
Drive quarantines the share, the recipient may not be able to read the
invitation file until they unmark it — the join flow's error now says what
failed, and onboarding copy should tell recipients to check
drive.google.com → Spam if a join fails with a not-found/permission error.

### 2c. Cross-account access is invisible to drive.file — PICKER HAND-OFF v0.1.39
Live-confirmed: with only `drive.file`, files shared from another account are
invisible to the app's token — Drive returns 404 ("File not found:
<invitationFileId>") even though the recipient's *account* can browse the
shared folder in the Drive UI. `drive.file` covers only files the app created
for this user or that the user explicitly granted via the Google Picker
(which is why `sharedPicker.ts` exists on web). Every cross-account step is
affected: the recipient reading the invitation and dataset, writing the
key-response into the owner's exchanges folder, and the owner reading that
response back.

v0.1.38 briefly requested the full `drive` scope; v0.1.39 reverts that
(restricted scope, disproportionate access) in favor of matching the web:
the app opens the web app in a Custom Tab with `grant-folder=1`, the user
runs the Google Picker there ("Select the shared folder"), and because
Picker grants are keyed to the Cloud *project* (not the OAuth client), the
grant covers the Android token too. Then the user returns to the app and
joins. The join error message walks the user through this. Note: Android's
system Files app (SAF) cannot substitute — SAF grants are device-local
content-provider permissions and never reach the Drive API's per-app ACL.

Open validation: whether a Picker folder grant covers descendants
(invitation inside `exchanges/`, the dataset file) — the web design assumes
it does; first live cross-account join will confirm. Owner-side accept
(reading the recipient's response file) may need the same grant dance on the
owner's device if creating-in-folder doesn't self-grant.

### 2b. Join links did not work at all — FIXED v0.1.37
The link generator (sync-kit `appendSharingJoinParams`, SYNC_KIT style) emits
`sync-kit-join/sync-kit-folder/sync-kit-exchange` plus app-appended
`owner`/`invitation`, but the Android deep-link handler required `sync=join`
+ `folder`, and the v0.1.35 paste parser required `folder` — so every link
"opened the app and did nothing" (the handler also swallowed all errors
silently). Both paths now share one parser (`SharedJoinLink.kt`) accepting
both styles, and the deep-link join reports success/failure with a toast and
logs under `EasyBcSync`.

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
