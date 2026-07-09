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
    /** App-owned labels for participant keys; envelopes intentionally omit email. */
    val participantEmails: Map<String, String>? = null,
    /**
     * A joined writer must load the remote dataset before publishing so the
     * prior profile's local working copy cannot leak into this dataset.
     */
    val needsInitialLoad: Boolean = false,
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
