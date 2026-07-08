# Link-based cross-account join (drive.file-pure, deep-linked)

Design spec — replaces the Drive-file key exchange with link/code payloads so
cross-account sharing works on `drive.file` alone (no `drive.readonly`/`drive`,
no CASA). Written 2026-07-08 after the probe proved a `drive.file` folder-grant
does not cascade to reading files inside it (folder 200, invitation 404), and
Gemini confirmed the same, plus that picking a *specific file* does grant it.

## The two permission layers (do not conflate)

1. **Drive ACL share** — makes a Google *account* able to see a file. Done by
   the **owner**.
2. **Picker file-grant** — makes an *app's `drive.file` token* able to read a
   file the account can already see. Done by the **joiner** (one Picker
   selection, or Drive "Open with").

Both are required. A file merely ACL-shared is invisible to a `drive.file`
token until the account picks it. A folder-pick grants create/list on the
folder but not read on pre-existing files inside it — that's the wall we hit.

## Why the exchange moves off Drive

The current protocol puts two files in the owner's Drive folder:
`exchanges/<id>-invitation.json` (owner-authored) and the joiner's key-response
(joiner-authored). With `drive.file`:

- the joiner can't read the owner's invitation (owner-authored, not picked), and
- the owner can't read the joiner's response (joiner-authored, not owner-picked).

Both directions 404. Making each side *pick* the other's file is possible but
means the **owner** must Picker-select every joiner's response file — unusable
at 3+ participants.

The exchange is a one-time, two-party key handshake. It doesn't need Drive at
all — it can travel over the same human channel the join link already uses
(chat). Only the **encrypted dataset** must stay in Drive, and the joiner grants
that with a single file-pick. The dataset's `keyGrants` already wrap the content
key per participant, so once added, everyone just reads the one dataset — no
per-participant file sharing, no fan-out.

## Picker discoverability (settled)

Gemini confirmed: an **anyone-with-link** file the user has never opened does
**not** appear in the Picker — the Picker searches only the user's Drive corpus
(owned, explicitly-shared-to-their-email, or previously-opened). So we use
**explicit per-email ACL**: the owner shares the dataset file(s) to the joiner's
email, and it lands in the joiner's "Shared with me" where the Picker sees it.

Because the owner already invites **by email** (existing "Invite" UI), the ACL
can be set in Phase 1 — so the joiner can pick in Phase 2 and the flow stays
**2 links + 1 pick**. (A link-only variant with no owner-known email needs a
3rd "finish" link after the owner learns the email from the response; kept as a
fallback below.)

## Target flow (email invite — 2 links + 1 pick)

Owner = O, Joiner = J. Each link is an EasyBC deep link
(`https://keyneom.github.io/easy-bc/…`).

### Phase 1 — O creates the invite (O's device)
- O enters **J's email** (existing Invite field) and picks the dataset(s)/role.
- O's app, automatically:
  - **ACL-shares each granted dataset file with J's email** — Viewer for a
    viewer grant, Writer for a writer grant. *[ACL share — by O]* This puts the
    file(s) in J's "Shared with me" so the Picker can see them.
  - Builds a **join link**: `?join=1&inv=<b64url(signed invitation)>&files=<b64url([{datasetId,fileId,role}])>&folder=<appFolderId>&owner=<email>&exchange=<id>`.
    `inv` is the existing `SharingInvitationV1` (owner public key + requested
    grants + owner signature), base64url'd — not a Drive file.
- O shares the join link with J (chat).
- **O taps: enter email → Invite → Share.**

### Phase 2 — J opens the join link (J's device)
- Deep link opens the app.
- J's app, automatically:
  - Parses and **verifies the invitation** signature from `inv`. No Drive read.
  - Opens the **Google Picker (files view, multi-select)** seeded to the shared
    folder so J selects the granted **dataset file(s)** (from "Shared with me")
    → grants J's app `drive.file` read on each. *[Picker grant — by J]*
  - Generates J's **key-response** (`SharingPublicKeyResponseV1`: J public key +
    proof) and encodes a **response link**:
    `?resp=1&exchange=<id>&kr=<b64url(response)>&owner=<email>`.
  - Shows "Send this back to <owner> to finish joining" + copy/share.
- J sends the response link to O (chat).
- **J taps: (open link) → pick file(s) → Share response.**

### Phase 3 — O opens the response link (O's device)
- Deep link opens O's app.
- O's app, automatically:
  - Decodes and **verifies J's key-response**.
  - For each granted dataset, adds J to participants + **keyGrants** (wraps the
    content key for J's public key) and **writes the dataset** (O authored it →
    `drive.file` write is fine).
- Done. **O taps: (open link).**

### Phase 4 — J syncs (J's device, automatic)
- J's app polls the granted datasets (readable via the Phase-2 picks), sees its
  keyGrants, decrypts, syncs. No taps.

Total: **O** ≈ 3 taps (email+invite, share, open response), **J** ≈ 3 taps
(open, pick, share) — the pick is the one unavoidable manual grant.

### Link-only fallback (no owner-known email) — 3 links
If O has no email for J: skip the Phase-1 ACL; in Phase 3 O also ACL-shares the
files with J's email (carried in the response) and emits a **finish link**; J
taps it → Picker → pick → sync. One extra message + tap; everything else same.

## Multiple datasets / multiple ACLs per profile

A profile may split into **several dataset files with different ACLs** (e.g.
share some categories with one person, others with another). The schema is
therefore a **list**, not a single file:

- The invitation's `requestedGrants: SharingDatasetGrantV1[]` already enumerates
  the datasets + roles for this joiner. The join link carries a parallel
  `files` list `[{datasetId, fileId, role}]` so the Picker knows which files to
  offer and O knows which files to ACL-share.
- The Picker runs in **multi-select** so J grants all their files in one pass.
- O's accept adds J's `keyGrant` to **each** granted dataset (one response /
  public key covers all — the grant is per-dataset, keyed to J's single key).
- EasyBC passes a single primary dataset today; sync-kit supports N.

## Security note

Per-email ACL shares only the **ciphertext** file; the content key is only ever
a per-participant `keyGrant` wrapped to that participant's public key, so ACL
access alone can't decrypt. Standard E2E practice (public ciphertext, private
keys).

## Implementation delta

### sync-kit (protocol transport only — crypto unchanged)
- `encodeInvitationV1` / `decodeInvitationV1` — base64url of the existing
  `SharingInvitationV1`. (Signing/verification unchanged.)
- `encodeKeyResponseV1` / `decodeKeyResponseV1` — same for
  `SharingPublicKeyResponseV1`.
- Join-link / response-link builders + parsers carrying these blobs + the
  **`files` list** `[{datasetId, fileId, role}]` (not a single id).
- Controller: a join path that takes a **decoded invitation** (not
  `readInvitation` from Drive) and an accept path that takes a **decoded
  response** (not `readKeyResponse` from Drive), iterating the granted datasets.
  Reuse `createSharingInvitationV1` / `verifySharingInvitationV1` /
  `createSharingPublicKeyResponseV1` / `acceptSharingPublicKeyResponseV1`.
- Drive transport: **per-email** dataset-file ACL share (Viewer/Writer by role),
  one call per granted file; the Picker view switches folders→files with
  **multi-select**, seeded to the shared folder.
- The old Drive `exchanges/` invitation + key-response files are no longer
  created or read.

### apps (web + Android)
- Owner "Invite": build the join link + set the ACL; show/share the link.
- Joiner join deep link (`join=1`): verify invitation → Picker (dataset file) →
  build + show the response link.
- Owner response deep link (`resp=1`): accept response → add keyGrant → write
  dataset. (New deep-link type.)
- Picker config: files view seeded to the dataset file (replaces the
  folders-only view in `sharedPicker.ts` / sync-kit `picker.ts`).

### Validations during build
- A **writer** joiner can *write* the dataset after picking it (picking grants
  the account's permission level; owner's share must give writers edit).
- Multiple datasets (multi-profile) → the join link carries each dataset file id;
  J multi-selects, or joins per dataset.
```

Backwards compatibility: existing Drive-based exchanges in flight can be drained
by keeping the old read path for one release, but new invites use links only.
