package com.easybc.planner.sync.shared

import com.keyneom.synckit.stores.GoogleDriveSharedBackupTransport

internal object EasyBcSharedTransport {
    fun forProfile(
        state: SharedSyncState,
        profile: ProfileRecord,
        auth: SharedDriveAuth,
    ): GoogleDriveSharedBackupTransport {
        return GoogleDriveSharedBackupTransport(
            appId = EASY_BC_APP_ID,
            authorizationProvider = auth.provider(),
            folderName = profile.folderName,
            selectedAppFolderId = selectedAppFolderIdForProfile(state, profile),
        )
    }
}

/** Stable Drive routing: a persisted folder ID always wins over names/legacy state. */
internal fun selectedAppFolderIdForProfile(
    state: SharedSyncState,
    profile: ProfileRecord,
): String? {
    profile.appFolderId?.let { return it }
    val owned = profile.ownerEmail.equals(state.ownerEmail, ignoreCase = true) &&
        profile.role.equals("owner", ignoreCase = true)
    return if (owned) null else state.selectedAppFolderId
}
