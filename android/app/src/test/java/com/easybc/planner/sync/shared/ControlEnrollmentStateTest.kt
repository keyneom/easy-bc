package com.easybc.planner.sync.shared

import com.keyneom.synckit.crypto.SyncKitJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlEnrollmentStateTest {
    @Test
    fun `control dataset registry and enrollment survive persistence`() {
        val profile = ProfileRecord(
            datasetId = "primary",
            ownerEmail = "owner@example.com",
            folderName = "EasyBC — owner@example.com",
            role = "owner",
            trustedOwnerKeyId = "owner-key",
            controlDatasetId = "primary.control",
            controlEnrollment = "pending",
            datasetRecords = mapOf(
                "primary.control" to CompanionDatasetRecord(
                    fileId = "control-file",
                    lastRevisionId = "control-revision",
                ),
            ),
        )
        val state = SharedSyncState(
            rpId = "example.com",
            ownerEmail = profile.ownerEmail,
            activeProfileKey = profileKey(profile.ownerEmail, profile.datasetId),
            profiles = listOf(profile),
        )

        val encoded = SyncKitJson.instance.encodeToString(SharedSyncState.serializer(), state)
        val decoded = SyncKitJson.instance.decodeFromString<SharedSyncState>(encoded)

        assertEquals("primary.control", decoded.profiles.single().controlDatasetId)
        assertEquals("pending", decoded.profiles.single().controlEnrollment)
        assertEquals(
            "control-file",
            decoded.profiles.single().datasetRecords?.get("primary.control")?.fileId,
        )
    }
}
