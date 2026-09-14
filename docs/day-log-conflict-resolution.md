# Day-log conflict handling

Status: proposed follow-up to the split-file commit repair. The repair preserves
sibling dataset parts when applying one file; it does **not** replace the
whole-row, last-write-wins policy inside `SyncMerge` / `mergeSyncPayloads`.

## What failed

A calendar date contains action/notes, body signals, and a list of events.
Projection puts incidents into `intimacy` and EC events into `sensitive`.
The previous live commit compared a projected row with the full local row.
On a timestamp tie, the projected row won and removed its siblings. This can
happen on one device with no conflicting user edits at all.

The section-aware commit now merges the incoming section against its own
live projection and recombines it with the other local sections. Android does
the read, merge, and replacement in a Room transaction. Event additions and
deletions also update their day clocks and tombstones transactionally.

## Remaining cross-device limitation

Within one section, two independently added incidents still compete as entire
day rows. Similarly, one device changing an action and another changing notes
can overwrite each other. A newer timestamp is not evidence that the newer
writer saw the older edit. The current payload has no causal history for those
edits and cannot reliably distinguish a sequential correction from concurrent
contradictory edits.

Blindly unioning event arrays is insufficient: it resurrects deleted events.
Changing which argument wins a tie is also insufficient: it merely changes
which edit is discarded.

## Proposed behavior

- Merge independent fields independently, including explicit clears.
- Merge independent events by their stable event IDs.
- Retain an explicit deletion for each removed event ID; removing and re-adding
  an event creates a new ID and must not affect unrelated events.
- Record which version(s) each edit supersedes. Sequential edits replace their
  predecessors without prompting; independent edits remain independent.
- For concurrent contradictory edits to the same field/event, preserve both
  versions and surface a review item for that day. Do not report it as fully
  resolved just because the network sync succeeded.
- Resolving a review item writes a new edit that supersedes the displayed
  versions. That choice must converge on both devices and survive restart.
- Show the conflicting field, values, and edit times. Attribute a device/person
  only when that provenance is known. Never invent ownership from a timestamp.
- Keep unrelated days and fields syncing while a conflict awaits review.

The user-facing policy for genuine contradictions is still to be settled:
preserve both for review (recommended), or automatically select the latest.
Either choice requires independent field/event merging first.

## Storage and compatibility requirements

Use a versioned, causal representation for scalar fields and event records,
with explicit deletion state. Preserve it through Android Room materialization,
web session hydration, backups, profile switching, projection, recombination,
fingerprints, and sync commits. Keep conflict data inside its authorized
dataset part; EC values and their history must never leak into intimacy/cycle.

Define migration from legacy rows before shipping. Legacy timestamps cannot
reconstruct causal history that was never stored. Older clients currently
discard unknown metadata on materialization, so mixed-version operation needs
an explicit compatibility policy instead of assuming an additive JSON field
alone makes this safe. Ship matching web and Android behavior together.

## Required regression matrix

Run with two independent local stores and an encrypted remote store, with
controlled pauses between read, merge, publish, and commit:

| Scenario | Required outcome |
| --- | --- |
| Same-day incident plus EC, either entry order | Both persist through every part commit and subsequent sync |
| Device A adds incident 1; B adds incident 2 offline | Both IDs persist on both devices |
| A changes action; B edits notes/body signals | Both edits survive |
| A deletes event 1; B adds event 2 | Event 1 stays deleted; event 2 survives |
| EC removed and re-added while sync runs | New EC ID and unrelated incident survive |
| Both devices change the same action differently | Both versions retained for review under the recommended policy |
| User resolves a conflict, then another device syncs | Resolution propagates and does not reappear as the same conflict |
| Edit versus delete of the same record | Explicit, tested policy; no silent loss |
| Equal clocks, clock skew, retry, reversed sync order | Deterministic convergence without relying on wall-clock order for causality |
| Crash after remote publish but before local commit | Local edits/history remain recoverable on retry |
| Restart, profile switch, backup restore | Versions, tombstones, and unresolved conflicts persist |
| Restricted/sensitive datasets | Values and conflict history respect the same grants |
| Legacy client participates | Agreed compatibility behavior is enforced and visible |

Test merge idempotence, commutativity, and associativity over the stable payload,
in addition to sync-kit's commit/subsumption guard. A passing guard proves only
that the callback retained the controller's chosen merge, not that the chosen
merge preserved every independent user edit.
