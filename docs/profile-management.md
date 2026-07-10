# Profile management

Profiles are the primary unit of data ownership in EasyBC. Storage and sharing
are attributes of a profile; they are not separate global modes.

UI/IA for these operations:
[settings-profiles-redesign.md](settings-profiles-redesign.md). Per-dataset
sharing protocol: [sync-kit-multi-file-datasets.md](sync-kit-multi-file-datasets.md).

## Profile types

| Type | Stored where | Available on | Sharing |
| --- | --- | --- | --- |
| Local only | This browser or Android device | One device | Not shareable until encrypted sync is enabled |
| Private encrypted | Encrypted EasyBC dataset in the owner's Google Drive | Authorized devices using the owner's EasyBC identity | Owner only |
| Shared encrypted | Encrypted EasyBC dataset in the owner's Google Drive | Owner and accepted participants | Viewer, writer, or admin access |
| Shared with me | Another owner's encrypted dataset | Devices where the recipient joined it | Access is controlled by the owner/admin |

A device may contain any combination of these types. Dataset identity is scoped
by owner plus dataset ID, so two owners may both have a `primary` dataset without
colliding.

## Basic profile tasks

- Create a local-only profile.
- Duplicate a profile into an independent local-only profile.
- Rename a profile without changing its dataset identity.
- Switch profiles, saving the current profile before loading the next one.
- Enable private encrypted sync for a local profile.
- Sync or refresh one encrypted profile without touching another profile.
- Keep a local copy and disconnect an encrypted profile on one device.
- Remove an encrypted profile from one device without deleting the cloud copy.
- Delete a local-only profile from one device.
- Delete an owned encrypted profile everywhere, including its Drive dataset.
- Join a profile shared by another owner.
- Leave a shared profile on one device.
- Recover/reconnect an owned encrypted profile on another authorized device.
- Export or import one profile without affecting other profiles.
- Repair/re-authorize one profile's Drive connection.
- Rotate the local sharing identity/key without changing unrelated profiles.
- See whether a profile is local, private encrypted, shared encrypted, or shared
  with the current user.
- See the profile owner, current role, sync state, and last encrypted update.

## Sharing and access tasks

- Invite a participant by email as viewer, writer, or admin.
- Complete the two-link invitation/response handshake.
- See accepted participant encryption keys and known email labels.
- See pending invitations and incomplete responses.
- Copy, resend, or cancel a pending invitation.
- Change a participant's role.
- Revoke a participant.
- Reconcile encryption membership with direct Google Drive permissions.
- Retry a failed acceptance or permission update without losing the pending
  invitation.

Revocation must do both of the following:

1. Rewrite the encrypted dataset with the participant removed from the current
   encryption set, using a fresh content key for the new revision.
2. Remove the participant's tracked direct Google Drive permission.

Revocation cannot erase plaintext or old ciphertext the participant already
downloaded. A legacy folder-level or otherwise inherited Drive permission may
also require removing access at the parent folder; direct file permission
removal cannot override inherited access.

## Role capabilities

| Task | Owner | Admin | Writer | Viewer |
| --- | --- | --- | --- | --- |
| Read | Yes | Yes | Yes | Yes |
| Edit and publish | Yes | Yes | Yes | No |
| Invite | Yes | Yes | No | No |
| Change roles | Yes | Yes | Yes, when email is known | No |
| Revoke non-owner | Yes | Yes | Yes, when email is known | No |
| Delete dataset everywhere | Yes | No | No | No |
| Remove/leave on own device | Yes | Yes | Yes | Yes |

Owner transfer is not supported by sharing protocol v1. It needs a separate,
explicit protocol operation rather than treating the owner as an ordinary
participant.

## Current implementation status

- Web implements local/private/shared coexistence, create, rename, switch,
  connect, disconnect, remove, delete-everywhere, join/leave, participant list,
  role change, and participant revocation.
- Android implements local/private/shared coexistence, create, rename, switch,
  connect, disconnect, delete-local, join/leave, participant display from the
  signed envelope metadata, and participant role/revoke controls when EasyBC has
  the app-owned participant email label.
- Duplicate, profile-scoped export/import, pending-invite cancellation,
  profile-scoped reauthorization, key rotation UI, and owner transfer remain
  follow-up operations.
- Android role changes and revocation depend on sync-kit `0.2.0-rc.10` or
  newer. EasyBC should not duplicate that cryptographic protocol logic in the
  app.

## UX rules

- Profile management appears before sync and sharing controls.
- Actions apply to the selected profile only.
- Destructive actions say whether they affect this device, the Drive dataset,
  or other participants.
- Local profiles never require Google authorization.
- Switching away from a writable encrypted profile publishes pending local
  changes first; switching to a profile loads that profile before editing.
- A newly joined writer must load the remote dataset before it can publish.
- Participant removal must report partial failure and remain retryable if either
  encryption membership or Drive permission cleanup fails.
