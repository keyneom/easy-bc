# Settings & profiles redesign

Design spec for making **profiles the top-level concept** of EasyBC and breaking
the single-scroll Settings page into a calm, navigable structure. Companion
visual mockup: the "EasyBC — Profiles & Settings redesign" artifact. Companion
protocol doc: [sync-kit-multi-file-datasets.md](sync-kit-multi-file-datasets.md)
(per-dataset sharing that §6 depends on).

Nothing in the current app is removed; §8 maps every existing control to its
new home. Android (Compose) is the reference implementation; web mirrors the
same IA with the same component names.

---

## 1. Principles

1. **Profile identity is always visible.** Every screen shows whose data you
   are looking at. The single most dangerous confusion in this app is logging
   a period — or an intimate act — into the wrong person's profile.
2. **Profiles own their settings.** Age, cycle length, methods, risk target,
   storage and sharing are all attributes of a profile, never global state
   (this codifies `profile-management.md` and sharing-ux-backlog #7).
3. **Storage is a property, not a feature.** "Local / Private cloud / Shared"
   is a per-profile choice presented as one control, not a pile of setup
   buttons whose availability the user must infer.
4. **Progressive disclosure.** Every settings screen shows the 20% of controls
   people actually change; the rest sits behind an "Advanced" expander or
   appears only when a prior choice makes it relevant.
5. **Predicted vs confirmed.** Mutating actions (invite, revoke, sync, mode
   change) visibly transition pending → confirmed states; never silently
   "succeed."

---

## 2. Information architecture

### Before (today)

```
Bottom nav: Calendar · Plan · History · Settings
Settings = one scroll page:
  Profile / Risk Target / Behavior / Methods / Preferences / Reminders /
  Device Calendar / Encrypted Cloud Sync (profiles + sharing + join, inline) /
  Backup / Advanced / Disclaimers
```

### After

```
Global: Profile chip (avatar) in the top app bar of Calendar, Plan, History
  └─ tap → Profile switcher sheet (switch / new / join / manage)

Bottom nav: Calendar · Plan · History · Settings

Settings (root — a hub, not a form)
├─ [Active profile header card]  → tap → Profile detail
├─ PROFILE (settings of the active profile — flat rows, no intermediate hubs)
│   ├─ Plan basics            (age, cycle length)
│   ├─ Protection             (methods, condom quality, withdrawal, layering)
│   ├─ Risk & comfort         (risk target, acts/week, streak aversion; Advanced: horizon, ovulation SD, hold lifecycle)
│   ├─ Storage & sharing      (mode selector, sync status, datasets, people)
│   └─ Data                   (per-profile export/import*, duplicate*)
├─ PROFILES
│   └─ Manage profiles        (list, new, join shared)
├─ DEVICE (this device, all profiles)
│   ├─ Reminders
│   ├─ Device calendar
│   └─ Backup & restore (whole device)
└─ ABOUT (version, disclaimers, re-run setup walkthrough)
```

`*` = follow-up operations already listed in `profile-management.md`; the IA
reserves their location now so they land without another restructure.

Navigation depth is at most 2 taps from Settings root to any control. Each
sub-screen is a plain scrolling page with a back arrow and the profile chip in
its app bar (so even deep in settings you can see whose profile you're editing).

---

## 3. The profile chip (global component)

- **Anatomy:** 32 dp circular avatar + (on wide layouts) profile name.
- **Avatar:** photo if set, else 1–2 initials on a deterministic background
  color (hash of `profileKey` → hue; fixed saturation/lightness per theme so
  every profile is distinguishable but nothing clashes).
- **Badges (overlaid, 12 dp):**
  - lock-cloud = private encrypted, two-person = shared, none = local
  - eye = read-only (viewer role) — also tints the app bar surface so
    read-only mode is ambient, not just a dismissible banner
  - hourglass = `needsInitialLoad` (waiting for owner)
- **Tap →** Profile switcher sheet.

### Profile switcher sheet (bottom sheet / popover on web)

- One row per profile: avatar · name · mode line ("Local — this device",
  "Private — your devices", "Shared — 3 people", "Shared with you — viewer ·
  leslie@…") · check on active.
- Switching runs the existing publish-before-switch rule (UX rules in
  `profile-management.md`); while switching, the sheet shows a progress row —
  no silent switch.
- Footer actions: **New profile** (opens onboarding wizard §7 scoped to a new
  profile), **Join a shared profile** (join-link flow), **Manage profiles**
  (opens Profiles page).

### Avatar photo spec

- Source: system photo picker; center-crop to square client-side.
- Stored at 128×128, WebP (JPEG fallback on iOS), quality ≈ 0.7, hard cap
  12 KB — re-encode at lower quality until under cap.
- Persistence: profile record (Room / IndexedDB). For synced profiles it rides
  the `plan` dataset (settings file) so participants see the same avatar.
  Never in the sync-kit control dataset (protocol state only).
- Fallback initials: first grapheme of first two words of display name.

---

## 4. Profiles page ("Manage profiles")

A first-class page, not a subsection of sync.

- **Profile cards:** avatar · name · owner (if not you) · mode badge · role ·
  last encrypted update · "Active" chip. Tap → Profile detail. Long-press /
  overflow: Switch to, Rename, Duplicate*, Remove from this device.
- **Primary actions:** `+ New profile` (wizard), `Join a shared profile`.
- **Join a shared profile flow** (replaces the inline paste-field cluster):
  1. Paste or open a join link (deep link lands here too).
  2. Structured preview: owner email, profile name, datasets offered + role
     per dataset, from the decoded invitation — user sees *what* they're
     joining before granting anything.
  3. Picker grant step ("Select all files in the shared folder") with the
     grant-access browser hand-off when needed (current §2c/2d flow).
  4. Response-link share step with copy/share buttons.
  Each step is a wizard page with explicit success/failure states; errors keep
  the user on the step with a retry (current behavior, but no longer buried).
- **Contextual cards** (only when their condition holds — never permanent UI):
  - "Migrate legacy encrypted sync" (legacy snapshot metadata present).
  - "A cloud copy exists this device can't unlock" → Reset escape hatch
    (current `connected && !sharedConfigured` state).

---

## 5. Profile detail & Settings root

### Settings root

Top: **active profile header card** — large avatar, name, mode line, sync
status ("Last encrypted update 2 min ago" / "Local only"), `Switch` button.
Below, plain navigation rows grouped as in §2 (leading icon, title, one-line
current-value summary, chevron). Examples of value summaries:

- Plan basics — "Age 34 · 28-day cycle"
- Protection — "Condoms (typical) + withdrawal"
- Risk & comfort — "5% over 10 years"
- Storage & sharing — "Shared with 2 people" / "This device only"

The value summary is what makes the hub scannable; every row must render one.

### Profile detail (from chip sheet or Profiles page)

Same header card plus: rename (inline edit), change photo, and the pointer to
Storage & sharing. **Danger zone** (collapsed expander, red accents, each item
states its blast radius — this device / the Drive dataset / other people):

- Remove from this device (shared-with-you: "Leave"; keeps cloud copy)
- Keep local copy & disconnect (encrypted → local)
- Delete local profile (local-only; hidden when it's the only profile)
- Delete everywhere (owner only; types profile name to confirm)
- Reset encrypted sync (owner only)
- Rotate sharing key* (reserved)

---

## 6. Storage & sharing screen

The centerpiece. One screen answers: *where does this profile live, who can
see it, and what exactly can they see?*

### 6.1 Mode selector

Three selectable cards (radio semantics), current one highlighted:

| Card | Copy | Maps to |
| --- | --- | --- |
| **This device** | "Stays on this phone. No account needed." | local profile (default) |
| **Private cloud** | "Encrypted in your Google Drive. Your other devices can unlock it with your passkey. Only you." | `SETUP` / enable private encrypted sync + hybrid identity |
| **Shared** | "Private cloud, plus invited people can view or edit what you choose." | shared dataset + invitations |

Rules:

- Transitions run the existing operations; the card shows a pending spinner
  and only flips to selected on confirmed success (predicted vs confirmed).
- Downgrade Shared/Private → This device = "Keep local copy & disconnect",
  with the confirm dialog stating cloud copy is untouched.
- Shared-with-you profiles render the selector disabled with an explainer
  ("Storage is controlled by the owner") — their actions live in danger zone.
- Below the selector: status row (last encrypted update, owner, your role) and
  **Sync now** (current "Merge encrypted changes") with a working/ok/error
  state line. `needsInitialLoad` renders as a full-width waiting card here and
  as the hourglass badge on the chip.

### 6.2 What this profile shares (per-dataset)

Requires the dataset split (companion doc). Four datasets, fixed order, each
with icon + plain-language scope line:

1. **Cycle & periods** — period dates, cycle stats, phase estimates.
2. **Plan & settings** — planner options, plan outputs, profile settings/avatar.
3. **Intimacy log** — logged acts, protection used, incidents.
4. **Sensitive events** — emergency contraception, pregnancy events.

Until the split ships, this section renders a single "All profile data"
dataset — the UI is built dataset-shaped from day one.

### 6.3 People

- **Person cards:** avatar (initials from email) · email · overall role chip ·
  **trust badge** — "Account-verified" when the sync-kit control dataset binds
  the participant key to a Google account (ID-token/passkey binding, rc.11);
  otherwise "Key from invite link".
- Expanding a card shows the **per-dataset access grid**: one row per dataset,
  role segmented control `None / View / Edit` (+ `Admin` at profile level).
  Role capability matrix unchanged from `profile-management.md`.
- Actions per person: change roles (writes both keyGrants and Drive ACL, with
  partial-failure reporting + retry, as today), **Remove** (two-step confirm;
  copy states re-encryption + ACL removal and the "can't erase what they
  already downloaded" caveat).
- **Invite flow** (owner/admin):
  1. Email + optional name.
  2. **Sharing preset** chips: `Cycle only` (cycle=View) · `Cycle partner`
     (cycle=Edit, plan=View) · `Full partner` (everything except Sensitive =
     Edit) · `Everything` · `Custom…` (opens the per-dataset grid).
     Presets are the progressive-disclosure answer to the permission matrix.
  3. Confirmation sheet → creates invitation → **pending invite card** with
     join link (copy / share sheet / QR), spam-folder warning, and
     `Resend link · Cancel invite*` actions.
- **Pending section** also hosts "Finish a share you sent" (paste/open the
  response link) — completing it flips the pending card into a person card.

---

## 7. Onboarding wizard

Used for first launch and for every "New profile". 5 steps, every step
skippable ("Use defaults"), progress dots, values editable later in Settings.

1. **Who is this profile for?** Name, avatar, age. Copy explicitly supports
   the second-person case ("Setting this up for your daughter? Use her name
   and age — every profile keeps its own settings and data.").
2. **Cycle basics.** Typical cycle length (slider w/ sensible default 28),
   optional last-period date (feeds the calendar immediately).
3. **Protection.** Persistent method chips; protected-day method; withdrawal
   — same conditional reveals as today (condom quality only for condoms,
   custom fields only for Custom).
4. **Risk comfort.** The cumulative-failure slider reframed in plain language
   ("If 100 couples followed this plan for 10 years…") with the numeric %
   shown; horizon under "Advanced".
5. **Where should this live?** The §6.1 mode cards. Default: This device.
   Choosing a cloud mode runs passkey/Drive setup inline; failure leaves the
   profile local with a "finish later in Settings" note — the profile is
   never lost to a failed cloud setup.

First-launch prepends a privacy screen ("All calculations happen on your
device"). "Re-run setup walkthrough" lives in About.

---

## 8. Where every existing control lands

| Today (single Settings scroll / inline sync section) | New location |
| --- | --- |
| Age, cycle length | Plan basics |
| Cumulative failure target | Risk & comfort |
| Horizon (years) | Risk & comfort ▸ Advanced |
| Acts per week | Risk & comfort |
| Persistent method, protected-day method, condom quality (+ custom residual), withdrawal (+ custom risk) | Protection |
| Layer withdrawal on protected days + independence slider | Protection ▸ Advanced |
| Streak aversion | Risk & comfort |
| Ovulation SD, hold lifecycle constant | Risk & comfort ▸ Advanced |
| Reset to defaults | Plan basics footer ("Reset plan settings") |
| Reminders toggle + time | Device ▸ Reminders |
| Device calendar auto-update / update now / remove | Device ▸ Device calendar |
| Backup export / import (whole device) | Device ▸ Backup & restore |
| Encrypted-sync status card | Settings-root header card + Storage & sharing status row |
| Profile list + switch | Profile chip sheet + Profiles page |
| Rename profile | Profile detail |
| New local profile | Profiles page / switcher sheet → wizard |
| Enable private encrypted sync / Set up encrypted sync | Mode selector: → Private cloud |
| Keep local copy & disconnect | Mode selector: → This device (+ danger zone) |
| Delete local profile | Profile detail ▸ Danger zone |
| Merge encrypted changes | Storage & sharing ▸ Sync now |
| Refresh people with access | People section refresh |
| People list, make viewer/writer, remove | Person cards + per-dataset grid |
| Invite by email + role | Invite flow (presets + custom grid) |
| Join link copy/share + spam warning | Pending invite card |
| Reset encrypted sync (owner) | Storage & sharing ▸ Danger zone |
| Reset escape hatch (un-adoptable cloud copy) | Contextual card, Profiles page |
| Migrate legacy encrypted sync | Contextual card, Profiles page |
| Paste join link / Join shared profile / Grant shared file access | Profiles page ▸ Join flow (wizard) |
| Accept response link ("Finish a share you sent") | People ▸ Pending ▸ Finish |
| Read-only notice / waiting-for-owner notice | Chip badge + ambient app-bar tint + waiting card |
| Delete owned dataset everywhere | Profile detail ▸ Danger zone |
| Disclaimers | About |
| First-time welcome banner + Start Planning FAB | Onboarding wizard |

Follow-ups from `profile-management.md` (duplicate, per-profile export/import,
cancel pending invite, per-profile reauth, key rotation, owner transfer) have
reserved slots: Data section, pending card, danger zone.

---

## 9. Visual language

- Base: Material 3 dynamic theming on Android; the web keeps its existing CSS
  variables and adopts the same tokens.
- Accent: the app's existing berry/rose primary; **semantic colors are not
  the accent** — mode colors: local = stone gray, private = teal, shared =
  violet; danger = the platform error red only.
- Dataset icons/colors (used in share grid + invite presets): cycle = berry
  droplet, plan = slate compass, intimacy = amber heart, sensitive = graphite
  shield.
- Cards over dividers; 16 dp screen gutters; one primary button per screen.
- Every list row: 48 dp min touch target; value summaries in
  `onSurfaceVariant`; `tabular-nums` for all numbers.

## 10. Implementation notes & rollout

- **Android:** new routes `settings` (hub), `settings/plan-basics`,
  `settings/protection`, `settings/risk`, `settings/storage`, `settings/people`,
  `profiles`, `profiles/join`, `onboarding`. `SettingsScreen.kt` splits into
  one file per route; `SettingsViewModel` mostly unchanged (draft/save per
  profile already exists). Profile chip = shared composable in each screen's
  top bar.
- **Web:** Settings tab renders the same hub; sub-screens as panels with the
  existing `SyncSettings`/`ProfileSwitcher` logic redistributed. Chip lives in
  the existing header next to the tab bar.
- **Phasing:**
  1. Restructure — **shipped on Android 2026-07-10**: hub
     (`SettingsScreen.kt`) with value-summary rows + profile header card +
     switcher sheet + Appearance (ThemeMode persisted via `ThemeModeStore`);
     sub-screens in `SettingsSectionScreens.kt` (Plan basics / Protection /
     Risk & comfort with Advanced expander / Profiles & sharing wrapping the
     existing `EncryptedSyncSection` / Reminders / Device calendar / Backup /
     About) behind real nav routes; join/response deep links land on
     `settings/storage`. Web shipped the Appearance row; its hub restructure
     follows.
  2. Onboarding wizard (replaces the first-run welcome card).
  3. Per-dataset sharing UI, gated on sync-kit rc.11 control-dataset
     integration (companion doc): the enrollment banner surface exists in
     `StorageSharingScreen` behind `CONTROL_DATASETS_WIRED`; splitting
     `EncryptedSyncSection` into the Profiles page + per-dataset People
     screen lands with it.
- Save model (shipped): plan sub-screens save on back with a toast ("Plan
  settings saved — the plan will recompute"), replacing the floating Save
  FAB; destructive/cloud actions stay explicit-confirm.

## 11. Profiles IA v2 — shipped 2026-07-17 (both platforms)

The §4/§5 structure is now the real one; the interim "Profiles & sharing"
combined screen (Android `EncryptedSyncSection`, ~1,700 lines) is deleted.

- **One entry point.** Settings hub has a single **Profiles** row. The hub's
  header card action is "Manage" (opens Profiles); the hub's private switcher
  sheet is gone — switching lives in the global chip sheet and on profile
  cards.
- **Profiles home** (`settings/profiles` / web `settingsView: "profiles"`):
  profile cards (avatar · name · mode line · Active chip / Switch) → tap →
  **Profile detail**; `+ New profile`; `Join a profile shared with you` →
  Join screen; contextual recovery banners (legacy migrate, un-unlockable
  cloud copy) live here.
- **Profile detail** (`settings/profile/{key}` / web `"sharing"` view with
  `detailProfileKey`): identity header (rename dialog, photo), §6.1 mode
  cards (now on web too via `EbModeCard`), sync status + Sync now, upgrade /
  migration-ceremony banners, per-dataset visibility, **People** (person
  cards with per-dataset access grid, **Co-manager admin toggle**, remove),
  **Invite** (presets + custom grid + join-link card), **Finish a share you
  sent** (owner/admin only), danger zone incl. owner **Delete everywhere**
  (Android parity with web). A non-active profile renders a cached summary +
  "Switch to manage" gate — sharing ops always run against the registry's
  active profile.
- **Join screen** (`settings/join` / web `"join"` view): standalone guided
  flow (Android finally wires `JoinFlowScreens.kt`): offline structured
  preview from the link (owner, sections, roles), browser grant hand-off,
  `sk-granted` auto-continue, reply-link share step. Join links deep-link
  here; response links deep-link to a dedicated accept screen so the pending
  exchange can resolve its profile even when a different profile is active.
- **Feedback model.** One snackbar per screen (`CloudStatusSnackbar` +
  `CloudActionRunner` replace ~15 copies of pending-auth plumbing) plus
  per-button busy labels; contextual `EbBanner`s are reserved for state, not
  operation feedback. Web keeps `notice` inline in the view that triggered it.
- **Chip inlining.** The dedicated chip bar is gone on Android; every screen
  hosts the chip in its own title row (`profileChip` slot from
  `AppNavigation`). Web topbar is one line on phones (subtitle hidden).
- **Participant emails.** `synchronizeMembers` call sites now always pass the
  complete member directory (verified metadata + locally known invite emails
  merged in `synchronizeControlMembers` / `mergedControlMemberMetadata`) —
  previously each acceptance rewrote every other member with `email = null`,
  which is why People showed raw key ids. Owners also backfill missing
  directory emails once when listing participants.
- **Picker.** Web joins select shared files with an app-local LIST-mode
  Picker (`web/src/sync/listPicker.ts`) — checkbox multi-select works on
  touch, replacing the one-file-per-visit grid flow.

### sync-kit 0.3.0 security compatibility and ownership transfer

- Web and Android are pinned to sync-kit `0.3.0`.
- Existing encrypted datasets and protected identities do not need migration.
- Pending join links created by sync-kit `0.2.0` or earlier must be recreated;
  both clients explain this when a recipient opens an older, unsigned-manifest
  link.
- Account binding remains optional in EasyBC until the Android origin allowlist,
  Digital Asset Links, nonce-bearing Google ID token, identity migration, and
  two-account device validation are complete. The verifier remains wired on Web,
  but `requireAccountBinding` is intentionally false on both clients.
- Normal destructive operations use sync-kit's cryptographic owner checks. The
  explicit Android reset-and-replace recovery path continues to delete through
  the authorized Drive transport so a user can recover from a cloud copy whose
  local protected identity can no longer be unlocked.
- Owners can offer a fully enrolled participant ownership from their People
  card. EasyBC transports one opaque signed proposal link; sync-kit verifies
  the exact dataset-head manifest, obtains the recipient's countersignature,
  and finalizes every dataset plus the Drive app/exchange folders. The former
  owner becomes an admin.
- Recipient clients persist the countersigned artifact before finalization, so
  an interrupted transfer resumes safely. EasyBC refreshes its cached owner
  label and local role from the authenticated ACL after sync; a stable
  `controlProfileId` keeps the existing control ledger valid when owner email
  (and therefore the display/profile key) changes.

### §11 addendum — ownership transfer polish (2026-07-20)

Why transfers use a link at all: sync-kit 0.3.0's transfer is a dual-proof
handshake (owner signs a proposal over every dataset + Drive permission ids;
recipient countersigns and finalizes on their own device — Drive's consumer
`pendingOwner` flow requires the recipient's account to accept). The signed
proposal artifact must reach the recipient's device, and 0.3.0 offers no
in-band channel for it (control-ledger events cover members/migrations only;
exchange files cover invitations/replies only), so the link carries it and
doubles as the notification. The reply direction needs no link: the former
owner reconciles roles from the authenticated ACL. A linkless in-app offer
(control-ledger event) is filed as a sync-kit follow-up.

UX rules encoded now:
- Transfer lives inside a person's expanded "Manage access" panel (never at
  card level); requires the recipient to hold every section; hidden while a
  transfer to them is already pending (card shows "transfer pending" instead).
- The offer screen names the profile and current owner (registry match by the
  proposal's exact dataset set), warns when the profile isn't on this device
  ("join first"), and has an explicit Decline with confirm. Back/gesture-back
  parks the offer (auth gate released, offer kept) and Profiles shows an
  "Ownership offer waiting — Review" banner; link-driven screens (join,
  accept-response) release the gate the same way on exit.
- The owner's transfer link persists on both platforms (encrypted prefs /
  IndexedDB) with copy · share · Discard; copy warns that Google Drive's own
  transfer email doesn't finish the switch and that discarding the link
  doesn't cancel the pending Drive transfer.
