package com.easybc.planner.sync.shared

import com.keyneom.synckit.sharing.SharingRole
import kotlinx.serialization.Serializable

const val EASY_BC_APP_ID = "easy-bc"
const val PRIMARY_DATASET_ID = "primary"
const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
const val EASY_BC_JOIN_LANDING_URL = "https://keyneom.github.io/easy-bc/"

private const val MAX_DRIVE_NAME_LENGTH = 255

/** Matches web `easyBcSyncFolderName` for cross-platform folder parity. */
fun easyBcSyncFolderName(ownerEmail: String): String {
    val label = ownerEmail.trim().lowercase()
    val name = "EasyBC — $label"
    return if (name.length <= MAX_DRIVE_NAME_LENGTH) name else name.take(MAX_DRIVE_NAME_LENGTH)
}

fun profileKey(ownerEmail: String, datasetId: String): String =
    "${ownerEmail.trim().lowercase()}/$datasetId"

fun canPublishRole(role: String): Boolean =
    role.equals(SharingRole.OWNER.name, true) ||
        role.equals(SharingRole.ADMIN.name, true) ||
        role.equals(SharingRole.WRITER.name, true)

fun canAdministerRole(role: String): Boolean =
    role.equals(SharingRole.OWNER.name, true) ||
        role.equals(SharingRole.ADMIN.name, true)

fun isLocalProfile(profile: ProfileRecord): Boolean = profile.syncMode == "local"

fun isEncryptedProfile(profile: ProfileRecord): Boolean = !isLocalProfile(profile)

fun sharingRoleFromString(role: String): SharingRole =
    when (role.lowercase()) {
        "owner" -> SharingRole.OWNER
        "admin" -> SharingRole.ADMIN
        "writer" -> SharingRole.WRITER
        else -> SharingRole.VIEWER
    }

fun shouldLoadRemoteBeforePublish(profile: ProfileRecord): Boolean =
    isEncryptedProfile(profile) &&
        (profile.needsInitialLoad || !canPublishRole(profile.role))

fun findProfile(state: SharedSyncState, profileKeyValue: String): ProfileRecord? =
    state.profiles.firstOrNull {
        profileKey(it.ownerEmail, it.datasetId) == profileKeyValue
    }

@Serializable
data class ProfileRecord(
    val datasetId: String,
    val ownerEmail: String,
    val folderName: String,
    val displayName: String? = null,
    /** Timestamp for encrypted cross-device display-name merge. */
    val displayNameUpdatedAt: String? = null,
    /**
     * Local cache of the plan-dataset avatar (base64 WebP, no data-URL prefix).
     * Lets chips/headers render without decrypting the plan file.
     */
    val avatarWebp: String? = null,
    /** Timestamp for avatar changes, including removal tombstones. */
    val avatarUpdatedAt: String? = null,
    /** Protocol-owned coordination ledger for membership and migrations. */
    val controlDatasetId: String? = null,
    val controlEnrollment: String = "none",
    val role: String,
    val trustedOwnerKeyId: String,
    val appFolderId: String? = null,
    val fileId: String? = null,
    val lastRevisionId: String? = null,
    val seenRevisionIds: List<String>? = null,
    val participantPermissionIds: Map<String, String>? = null,
    val lastSyncedAt: String? = null,
    /** Profiles exist independently of encrypted sync. Older records are encrypted. */
    val syncMode: String? = null,
    /** Legacy/local fallback; verified control members are authoritative for participant email. */
    val participantEmails: Map<String, String>? = null,
    /**
     * A joined writer must load the remote dataset before publishing so the
     * prior profile's local working copy cannot leak into this dataset.
     */
    val needsInitialLoad: Boolean = false,
    /**
     * Split profiles: lowercase role per dataset part (plan/cycle/intimacy/
     * sensitive — docs/sync-kit-multi-file-datasets.md). Null = legacy
     * single-file profile; everything lives in `datasetId` at `role`.
     */
    val datasetGrants: Map<String, String>? = null,
    /**
     * Split profiles: sync-kit registry state for companion dataset files,
     * keyed by full dataset id ("<base>.cycle", …). The base dataset keeps
     * using the top-level fileId/lastRevisionId fields.
     */
    val datasetRecords: Map<String, CompanionDatasetRecord>? = null,
    /**
     * Participant side of an open hard-cutover migration
     * (docs/sync-kit-multi-file-datasets.md §ceremony): the owner
     * reorganized this profile into new dataset files and this device must
     * re-select them in the Picker and acknowledge before it may publish
     * again. While present the source dataset is read-only (the freeze).
     */
    val pendingMigration: PendingMigrationRecord? = null,
    /**
     * Owner side of an open migration: the retired source dataset id, kept
     * until every required acknowledgement arrives and the owner closes the
     * migration — at which point the source file is trashed (not deleted).
     */
    val retiredDatasetId: String? = null,
    val openMigrationId: String? = null,
)

@Serializable
data class PendingMigrationRecord(
    val migrationId: String,
    val targetBaseId: String,
    /** Target files this device was granted and must open via the Picker. */
    val requiredFileIds: List<String>,
    val targets: List<MigrationTargetRecord>,
)

@Serializable
data class MigrationTargetRecord(
    val datasetId: String,
    val fileId: String,
)

@Serializable
data class CompanionDatasetRecord(
    val fileId: String? = null,
    val lastRevisionId: String? = null,
    val seenRevisionIds: List<String>? = null,
    val participantPermissionIds: Map<String, String>? = null,
)

@Serializable
data class SharedSyncState(
    val schemaVersion: Int = 1,
    val rpId: String,
    val ownerEmail: String,
    val activeProfileKey: String,
    val profiles: List<ProfileRecord> = emptyList(),
    val selectedAppFolderId: String? = null,
)
