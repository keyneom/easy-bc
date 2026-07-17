# sync-kit spec: dataset codec routing parity on Web

## Problem

EasyBC profile discovery in `@keyneom/sync-kit@0.2.0-rc.17` can discover and
verify a split profile, then fail while adopting its control dataset. EasyBC
configures the application controller with `codecForDataset`, but the Web
controller's ordinary dataset lifecycle methods decrypt with the default
application codec:

- `createDataset`
- `adoptDataset`
- `loadDataset`
- `syncDataset`

The same Android controller selects `codecFor(datasetId)` in all four methods.
Web currently selects the per-dataset codec only in participant-management
paths. This is a cross-platform behavior mismatch, and it allows a package-owned
sharing control ledger to be passed to the consumer's EasyBC health-payload
codec.

The TypeScript option comment says ordinary lifecycle calls use the default
codec, while the Android implementation and the release checklist claim
per-dataset selection across load/sync. The contract is therefore internally
inconsistent even apart from EasyBC.

## Required behavior

Make dataset ID the single codec-routing authority in both implementations.
Every controller operation that serializes, parses, merges, fingerprints, or
rewrites a dataset must select its codec exactly once with the dataset ID and
use that selected codec for the complete operation.

At minimum this includes:

- create, adopt, load, and sync;
- invitation acceptance and direct participant grants;
- participant role changes and revocation/rekey;
- any migration, fork, or repair path that decrypts and republishes an existing
  envelope.

The selected codec must be used consistently for parse, merge, fingerprint,
and serialize. A path must never parse with one codec and rewrite with another.
Unknown dataset IDs continue to fall back to the controller's default codec.

Update the TypeScript API documentation so it no longer says ordinary
lifecycle calls bypass `codecForDataset`. If supporting a non-`T` return type
through the application controller is intentionally disallowed, add a
type-safe per-call codec API instead; do not retain different Web and Android
runtime behavior.

## Security and data-safety requirements

- Keep signature verification, pinned owner trust, participant checks, and
  revision/fork checks unchanged and ahead of any write.
- Preserve dataset ID, file ID, participants, roles, ACLs, owner trust, and
  encryption identity.
- Do not delete or recreate a file to correct codec routing.
- A codec parse failure must not persist a new verified head or mutate the
  remote dataset.
- Conditional writes and conflict reread/merge behavior must remain intact.

## Tests

Add sentinel application and control codecs whose parsers reject each other's
wire shapes. Exercise both codecs through every operation listed above.

Required assertions:

1. Web adopts and loads a control dataset through `codecForDataset` without
   invoking the application codec.
2. Web syncs a writable control dataset with the control codec for parse,
   merge, fingerprint, and serialize.
3. Creation uses the selected codec when the API permits creating an override
   dataset.
4. Application datasets still use the default codec.
5. A wrong/throwing override leaves the registry head and remote file
   unchanged.
6. A revision conflict rereads and merges with the same selected codec.
7. Equivalent Kotlin tests assert the same calls and outcomes.
8. A fixed Web/Kotlin parity fixture covers one application dataset plus one
   sharing control dataset.

The repository's full `npm run check` gate must pass, including Android and
cross-platform fixtures.

## EasyBC integration gate

Pack the candidate Web package and consume it from EasyBC. With the existing
`primary.g2` profile:

1. Discover the profile and its stable control dataset automatically.
2. Adopt all granted data parts and the control ledger without routing the
   control JSON to the EasyBC payload codec.
3. Confirm no `profile-load-skipped` diagnostic is added.
4. Reload and rediscover; the result must be “Profiles are up to date.”
5. Open the same profile on Android from the coordinated release and confirm
   identical behavior without duplicate passkey prompts.

EasyBC should pass ordinary control-dataset lifecycle operations through its
application controller and rely on the corrected `codecForDataset` contract.
The dedicated control controller remains appropriate for the higher-level
sharing-control API itself, but is not a fallback for application-controller
adoption.
