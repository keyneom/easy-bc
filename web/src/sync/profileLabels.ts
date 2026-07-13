import {
  isLocalProfile,
  PRIMARY_DATASET_ID,
  type ProfileRecord,
  type SharedSyncState,
} from "./sharedTypes";

const MAX_DATASET_ID_LENGTH = 40;

export function slugifyDatasetId(displayName: string): string {
  const slug = displayName
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, MAX_DATASET_ID_LENGTH);
  if (!slug || slug === PRIMARY_DATASET_ID) {
    throw new Error("Choose a profile name with letters or numbers.");
  }
  return slug;
}

export function uniqueOwnedDatasetId(
  displayName: string,
  ownerEmail: string,
  profiles: ProfileRecord[],
): string {
  const base = slugifyDatasetId(displayName);
  const ownedIds = new Set(
    profiles
      .filter(
        (profile) =>
          profile.ownerEmail.toLowerCase() === ownerEmail.toLowerCase() &&
          profile.role === "owner",
      )
      .map((profile) => profile.datasetId),
  );
  if (!ownedIds.has(base)) return base;
  let suffix = 2;
  while (ownedIds.has(`${base}-${suffix}`)) suffix += 1;
  const candidate = `${base}-${suffix}`;
  if (candidate.length > MAX_DATASET_ID_LENGTH) {
    throw new Error("Too many profiles with similar names. Choose a different name.");
  }
  return candidate;
}

/** New profile identity is opaque; labels are mutable metadata, not storage keys. */
export function newOwnedDatasetId(
  existingDatasetIds: Iterable<string>,
  randomUUID: () => string = () => crypto.randomUUID(),
): string {
  const existing = new Set(existingDatasetIds);
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const candidate = `p-${randomUUID().toLowerCase()}`;
    if (candidate.length <= MAX_DATASET_ID_LENGTH && !existing.has(candidate)) {
      return candidate;
    }
  }
  throw new Error("Could not create a unique profile identifier. Try again.");
}

export function isOwnedProfile(state: SharedSyncState, profile: ProfileRecord): boolean {
  return (
    profile.ownerEmail.toLowerCase() === state.ownerEmail.toLowerCase() &&
    profile.role === "owner"
  );
}

export function profileDisplayLabel(state: SharedSyncState, profile: ProfileRecord): string {
  if (isLocalProfile(profile)) {
    return profile.displayName?.trim() || "Local profile";
  }
  if (profile.localDisplayName?.trim()) return profile.localDisplayName.trim();
  if (profile.displayName?.trim()) return profile.displayName.trim();
  if (isOwnedProfile(state, profile)) {
    if (profile.datasetId === PRIMARY_DATASET_ID) return "My data";
    return profile.datasetId;
  }
  return profile.folderName;
}

export function disambiguatedProfileLabel(
  state: SharedSyncState,
  profile: ProfileRecord,
): string {
  const label = profileDisplayLabel(state, profile);
  const duplicates = state.profiles.filter(
    (candidate) =>
      profileDisplayLabel(state, candidate).toLocaleLowerCase() === label.toLocaleLowerCase(),
  );
  return duplicates.length > 1 ? `${label} — ${profile.ownerEmail}` : label;
}

export function findOwnedPrimaryProfile(
  state: SharedSyncState,
): ProfileRecord | undefined {
  return state.profiles.find(
    (profile) =>
      isOwnedProfile(state, profile) && profile.datasetId === PRIMARY_DATASET_ID,
  );
}

export function findOwnedStorageProfile(
  state: SharedSyncState,
): ProfileRecord | undefined {
  return findOwnedPrimaryProfile(state) ??
    state.profiles.find((profile) => isOwnedProfile(state, profile) && profile.appFolderId);
}
