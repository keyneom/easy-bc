# EasyBC on sync-kit rc.11: multi-dataset sharing & topology migration

Updated 2026-07-10 for **sync-kit 0.2.0-rc.11 ("sharing control datasets")**.

## Implementation status (v0.1.50, 2026-07-10)

The multi-file split is **implemented on both platforms** for profiles
created from v0.1.50 on (fresh setup, new owned profiles, connect-local,
and every Reset): four files per profile (`<base>` = plan, `<base>.cycle`,
`<base>.intimacy`, `<base>.sensitive`), each with its own content key and
ACL. Model: `web/src/sync/datasets.ts` ↔ `sync/shared/EasyBcDatasets.kt`
(round-trip unit-tested, EC events route to `sensitive`, incidents to
`intimacy`, body signals to `cycle`, day logs split field-level). Invites on
split profiles use the presets (per-part `requestedGrants`); joiners store
per-part grants and sync exactly the granted files; a partial writer only
ever publishes projections of its granted parts, so non-granted sections
can never leak into a shared file's ciphertext. Role changes and revocation
apply per file; Android adopting a web-created split folder detects the
companions and group-syncs. Partial access is structural in the UI: hidden
day-sheet sections, "what you can see" dataset rows, and a calendar banner.

**Legacy profiles** (created before v0.1.50) keep their single file and
all-or-nothing sharing; migrating them to the split layout is exactly the
hard-cutover topology migration below and waits on the control-dataset
integration.
This replaces the earlier speculative proposal that lived in this file; rc.11
settled the protocol differently (and better). The authoritative protocol doc
is `sync-kit/docs/sharing-control-datasets.md`; this file records what EasyBC
adopts from it and the app-side plan. UI surface:
[settings-profiles-redesign.md](settings-profiles-redesign.md) §6.

## What rc.11 chose (vs. the earlier draft here)

| Earlier draft in this file | rc.11 reality | Why rc.11 is right |
| --- | --- | --- |
| Plaintext-but-signed `manifest.json` roster | **Encrypted control dataset** — a shared file whose payload is package-owned protocol state (keys, provenance, emails, migration records, acks) | Coordination data is still membership metadata; encrypting it avoids plaintext-merge/authz complexity, and giving everyone `writer` on a coordination-only file solves the viewer-can't-ack problem cleanly |
| Per-participant `attest/<keyId>.json` files owner-mirrors into a manifest | Individually **signed events inside the control payload**, merge-safe union; `control.synchronizeMembers(...)` mirrors accepted keys + email/Google provenance | One durable channel instead of N files; no drive.file fan-out for reading other participants' files |
| Email↔key verification via Drive `revisions.list` author | **Google ID-token + passkey account binding** establishes the durable Google `sub`; email is a contact/ACL label; a Drive revision author is corroboration only | Revision metadata is not a cryptographic identity; the account binding is |
| `bridge()` folds straggler old-file writes forward during transition | **Hard cutover**: a prior release ships a migration **freeze** and old-topology edits stop; no dual-writing | Dual-writing after a split re-leaks data into the wide-ACL file the split was meant to narrow |
| "Last acker closes" the old file | **Owner closes** via `migrationStatus()` once every required ack is present, or uses an explicit, recorded force-close policy | Closure is an authorization decision, not a race; the record keeps it auditable |

Kept from the earlier draft (still true in rc.11): one invitation carries all
dataset grants (`requestedGrants[]`), per-dataset roles per participant, fresh
content key per target file, no re-join ceremony for existing members, Picker
multi-select as the one unavoidable manual grant, and trash-don't-delete
retention as app policy.

## EasyBC dataset split (application-owned)

The split itself remains EasyBC's choice; sync-kit coordinates it.

| datasetId | Contents | Why its own file |
| --- | --- | --- |
| `cycle` | period records, cycle stats, phase estimates | the friend-share case; most commonly shared |
| `plan` | planner options, plan outputs, profile settings, avatar | coordination without disclosing logs |
| `intimacy` | day logs of acts, protection used, incidents, realized risk | the marital-privacy boundary |
| `sensitive` | EC use, pregnancy start/end events | highest sensitivity; separable even from a full partner |
| `control` | **sync-kit protocol state only** — never application data | rc.11 control dataset; every participant is a `writer` here even when viewer elsewhere |

Grant defaults on migration: a participant's old `primary` role carries to
`cycle`/`plan`/`intimacy` per the owner's confirmation screen, and `sensitive`
defaults to **no access** — a split is exactly the moment to narrow exposure.
Invite presets in the UI map to these grants (Cycle only / Cycle partner /
Full partner / Everything / Custom).

Cross-file references (e.g. an incident referencing a cycle day) must use
stable IDs; readers render "restricted" placeholders for a referenced dataset
they cannot decrypt.

## Integration points (app side)

- **Controllers:** one control controller via `createSharingControlCodec()` +
  `createSharingControlDataset(...)`; the app-data controller gets
  `codecForDataset` returning the control codec for `controlDatasetId`.
  EasyBC persists `controlDatasetId = "control"` in the profile registry
  (both platforms) — correctness never derives from the Drive filename.
- **Invitation:** the control dataset rides the *same* invitation as the data
  datasets, as a `writer` grant, so joining stays one link + one response +
  one Picker pass.
- **Accept:** after accepting a response for all datasets, the owner calls
  `control.synchronizeMembers(...)` to mirror the accepted key + email/Google
  provenance into the signed control directory.
- **Trust display:** the People UI shows "Account-verified" when the control
  directory binds the participant key to a Google account (ID-token/passkey
  binding), else "Key from invite link". No Drive-revision heuristics.
- **Enrollment of existing shares:** profiles shared before rc.11 need a
  one-time control-file enrollment (owner creates + invites into it). The UI
  surfaces this as a contextual card; **no destructive migration may start
  while any required participant lacks enrollment** (package rule).

## Picker UX contract (verbatim rules from rc.11)

One action: **"Grant access to this profile's files"** → multi-select Picker,
with the signed expected file list shown in-app.

- Folder access ≠ success; compare returned file IDs to the expected list.
- Ack only after every required file is selected **and** decrypted/verified
  (app ID, dataset ID, pinned owner, envelope signature, role).
- If one is missing, name its dataset label and reopen the Picker.
- Ignore selected files outside the signed expected set.

## The split migration, concretely (hard cutover)

1. **Freeze release (ship first).** An app version that recognizes the
   migration-freeze marker and stops old-topology edits. Clients older than
   this are a residual risk — accepted; do **not** dual-write around them.
2. **Split release.** An authorized instance (the owner's, for EasyBC):
   creates `cycle`/`plan`/`intimacy`/`sensitive`, runs the pure split
   transform, gives every target a fresh content key, shares each target
   with its intended recipients (per the owner's grant-confirmation screen).
3. **Announce.** Owner writes the `hard-cutover` migration record into the
   already-enrolled control dataset: source IDs, target file IDs/revisions,
   and the exact target files each participant must open.
4. **Adopt.** Each participant's app sees the record → Picker pass → opens
   and verifies its targets → writes its signed acknowledgement.
5. **Close.** Owner polls `migrationStatus()`; closes only on full required
   acks, or force-closes explicitly (recorded; the confirm dialog names the
   stragglers). EasyBC's retention policy: **trash** the old `primary` file
   (30-day Drive undo), never hard-delete at close.

Privacy note (from the package doc): the migration protects *future* data.
A participant who could decrypt the old consolidated file may retain that
plaintext; the split does not and cannot claw it back — the UI copy must not
overpromise.

## Open items

1. Wire `SharedSyncRegistry` (web + Android) to store `controlDatasetId` and
   enrollment state per profile.
2. Owner-side grant-confirmation screen for step 2 (defaults above).
3. Freeze-marker recognition shipping ahead of the split release — schedule
   two releases.
4. UI: migration banner, picker-grant prompt, blocked-migration surface
   ("show a blocked migration rather than infer success from silence").
