package com.easybc.planner.sync.shared

import com.keyneom.synckit.sharing.SharingRole
import kotlinx.serialization.Serializable

const val EASY_BC_APP_ID = "easy-bc"
const val PRIMARY_DATASET_ID = "primary"
const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * Full Drive scope. Required on Android for cross-account sharing: with only
 * drive.file, files shared from another account are invisible to the app
 * (Drive returns 404) until the user grants them through the Google Picker —
 * which exists on web but has no Android equivalent. Covers the recipient
 * reading invitations and datasets in the owner's folder, writing the
 * key-response there, and the owner reading that response back.
 */
const val DRIVE_FULL_SCOPE = "https://www.googleapis.com/auth/drive"
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

fun sharingRoleFromString(role: String): SharingRole =
    when (role.lowercase()) {
        "owner" -> SharingRole.OWNER
        "admin" -> SharingRole.ADMIN
        "writer" -> SharingRole.WRITER
        else -> SharingRole.VIEWER
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
