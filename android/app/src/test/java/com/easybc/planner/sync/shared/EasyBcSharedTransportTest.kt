package com.easybc.planner.sync.shared

import com.keyneom.synckit.sharing.SharingRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EasyBcSharedTransportTest {
    private fun profile(
        ownerEmail: String,
        role: SharingRole,
        appFolderId: String? = null,
    ) = ProfileRecord(
        datasetId = PRIMARY_DATASET_ID,
        ownerEmail = ownerEmail,
        folderName = easyBcSyncFolderName(ownerEmail),
        role = role.name.lowercase(),
        trustedOwnerKeyId = "$ownerEmail-key",
        appFolderId = appFolderId,
    )

    private fun state(profiles: List<ProfileRecord>) = SharedSyncState(
        rpId = "keyneom.github.io",
        ownerEmail = "owner@example.com",
        activeProfileKey = "owner@example.com/primary",
        profiles = profiles,
        selectedAppFolderId = "legacy-recipient-folder",
    )

    @Test
    fun ownedProfileUsesPersistedFolderId() {
        val owned = profile("owner@example.com", SharingRole.OWNER, "owned-folder")
        assertEquals("owned-folder", selectedAppFolderIdForProfile(state(listOf(owned)), owned))
    }

    @Test
    fun uninitializedOwnerDoesNotUseRecipientFallback() {
        val owned = profile("owner@example.com", SharingRole.OWNER)
        assertNull(selectedAppFolderIdForProfile(state(listOf(owned)), owned))
    }

    @Test
    fun recipientPrefersPersistedFolderIdAndRetainsLegacyFallback() {
        val routed = profile("other@example.com", SharingRole.VIEWER, "shared-folder")
        val legacy = profile("other@example.com", SharingRole.VIEWER)
        assertEquals("shared-folder", selectedAppFolderIdForProfile(state(listOf(routed)), routed))
        assertEquals(
            "legacy-recipient-folder",
            selectedAppFolderIdForProfile(state(listOf(legacy)), legacy),
        )
    }
}
