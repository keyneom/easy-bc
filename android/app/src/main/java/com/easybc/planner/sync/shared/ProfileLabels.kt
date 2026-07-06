package com.easybc.planner.sync.shared

import com.keyneom.synckit.sharing.SharingRole

private const val MAX_DATASET_ID_LENGTH = 40

fun slugifyDatasetId(displayName: String): String {
    val slug = displayName
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(MAX_DATASET_ID_LENGTH)
    require(slug.isNotEmpty() && slug != PRIMARY_DATASET_ID) {
        "Choose a profile name with letters or numbers."
    }
    return slug
}

fun uniqueOwnedDatasetId(
    displayName: String,
    ownerEmail: String,
    profiles: List<ProfileRecord>,
): String {
    val base = slugifyDatasetId(displayName)
    val ownedIds = profiles
        .filter {
            it.ownerEmail.equals(ownerEmail, ignoreCase = true) &&
                it.role.equals(SharingRole.OWNER.name, ignoreCase = true)
        }
        .map { it.datasetId }
        .toSet()
    if (!ownedIds.contains(base)) return base
    var suffix = 2
    while (ownedIds.contains("$base-$suffix")) suffix += 1
    val candidate = "$base-$suffix"
    require(candidate.length <= MAX_DATASET_ID_LENGTH) {
        "Too many profiles with similar names. Choose a different name."
    }
    return candidate
}

fun isOwnedProfile(state: SharedSyncState, profile: ProfileRecord): Boolean =
    profile.ownerEmail.equals(state.ownerEmail, ignoreCase = true) &&
        profile.role.equals(SharingRole.OWNER.name, ignoreCase = true)

fun profileDisplayLabel(state: SharedSyncState, profile: ProfileRecord): String {
    if (!isOwnedProfile(state, profile)) return profile.folderName
    profile.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (profile.datasetId == PRIMARY_DATASET_ID) return "My data"
    return profile.datasetId
}

fun findOwnedPrimaryProfile(state: SharedSyncState): ProfileRecord? =
    state.profiles.firstOrNull {
        isOwnedProfile(state, it) && it.datasetId == PRIMARY_DATASET_ID
    }
