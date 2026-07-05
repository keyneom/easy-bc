package com.easybc.planner.sync.shared

import com.keyneom.synckit.sharing.SharingRole
import com.keyneom.synckit.stores.GoogleDriveSharedBackupTransport

internal object EasyBcSharedTransport {
    fun forProfile(
        state: SharedSyncState,
        profile: ProfileRecord,
        auth: SharedDriveAuth,
    ): GoogleDriveSharedBackupTransport {
        val owned = profile.ownerEmail.equals(state.ownerEmail, ignoreCase = true) &&
            profile.role.equals("owner", ignoreCase = true)
        return GoogleDriveSharedBackupTransport(
            appId = EASY_BC_APP_ID,
            authorizationProvider = auth.provider(),
            folderName = profile.folderName,
            selectedAppFolderId = if (owned) {
                null
            } else {
                profile.appFolderId ?: state.selectedAppFolderId
            },
        )
    }
}
