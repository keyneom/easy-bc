# Sharing & profiles UX backlog

Issues observed during live testing of encrypted shared sync (v0.1.29–v0.1.34,
2026-07-05/06). Android is the reference implementation; fixes land there
first, web follows best-effort.

## Quick wins

### 1. Join link is not copyable
`SettingsScreen.kt` renders the invite result as plain
`Text("Join link: $url")` — no selection, no copy. Add a copy-to-clipboard
button and the system share sheet. The join link must become the primary
invitation channel (see #2/#3).

### 2. Invite email never arrives / 3. shared folder flagged as spam
The "invite" email is Google Drive's generic folder-share notification for
`EasyBC — owner@email` (sync-kit `grantExchangeAccess` shares the app folder
with `sendNotificationEmail = true`). Google's own notification is easily
spam-filtered and we cannot control its content or deliverability.
Mitigations:
- pass a custom `emailMessage` (sync-kit already supports it) explaining what
  the share is;
- treat email as best-effort only — surface the join link with copy/share as
  the primary channel and say so in the UI ("Send this link to …").

### 4. Migrate vs. Set up is user-hostile
Both buttons render whenever shared sync is unconfigured; the user cannot know
which applies. The app already knows: `syncStore.fileId() != null` means
legacy snapshot metadata exists. Detect and show exactly one primary action:
- legacy metadata present → "Migrate legacy encrypted sync" (with one line of
  copy about what will happen), Setup demoted or hidden;
- otherwise → "Set up encrypted sync" only.
Additionally, setup should detect an existing owned dataset in Drive
(`listDatasets`) and offer to reconnect/adopt it instead of failing with
"Dataset primary already exists" (the orphaned-dataset case; sync-kit rc.4
already rolls back new orphans, but adoption also covers reinstalls and
multi-device reconnects).

## Design work

### 5. Cannot join a share without an in-app entry point
Web has the Google Picker (`sharedPicker.ts`); Android can only join via the
deep link. Add an in-app "Join a shared profile" flow: paste a join link (and
optionally a Drive folder picker) so joining works even when the email/link
handoff is imperfect.

### 6. Viewer-only use is impossible
A recipient is forced to keep their own local primary profile; they cannot run
the app purely against a profile that was shared with them. Joining should be
possible as the *only* profile on the device.

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
