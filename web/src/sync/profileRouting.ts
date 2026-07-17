import { isOwnedProfile } from "./profileLabels";
import type { ProfileRecord, SharedSyncState } from "./sharedTypes";

/** Stable Drive routing: a persisted folder ID always wins over names/legacy state. */
export function selectedAppFolderIdForProfile(
  state: SharedSyncState,
  profile: ProfileRecord,
): string | undefined {
  if (profile.appFolderId) return profile.appFolderId;
  return isOwnedProfile(state, profile) ? undefined : state.selectedAppFolderId;
}
