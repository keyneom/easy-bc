package com.easybc.planner.sync.shared

import com.keyneom.synckit.sharing.SharingControlMemberV1
import com.keyneom.synckit.sharing.SharingPublicKeyV1
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlMemberMetadataTest {
    private fun member(
        keyId: String,
        email: String? = null,
        googleSubject: String? = null,
        drivePermissionId: String? = null,
    ) = SharingControlMemberV1(
        publicKey = SharingPublicKeyV1(
            keyId = keyId,
            encryptionAlgorithm = "ECDH-P256",
            encryptionPublicKey = "encryption-key",
            signatureAlgorithm = "ECDSA-P256-SHA256-P1363",
            signingPublicKey = "signing-key",
        ),
        email = email,
        googleSubject = googleSubject,
        drivePermissionId = drivePermissionId,
    )

    @Test
    fun `complete directory survives a new participant update`() {
        val merged = mergedControlMemberMetadata(
            members = mapOf(
                "owner-key" to member(
                    "owner-key",
                    email = "owner@example.com",
                    googleSubject = "owner-subject",
                    drivePermissionId = "owner-permission",
                ),
                "existing-key" to member(
                    "existing-key",
                    googleSubject = "existing-subject",
                ),
            ),
            participantEmails = mapOf("existing-key" to "existing@example.com"),
            keyId = "new-key",
            email = "new@example.com",
        )

        assertEquals("owner@example.com", merged.getValue("owner-key").email)
        assertEquals("owner-subject", merged.getValue("owner-key").googleSubject)
        assertEquals("owner-permission", merged.getValue("owner-key").drivePermissionId)
        assertEquals("existing@example.com", merged.getValue("existing-key").email)
        assertEquals("existing-subject", merged.getValue("existing-key").googleSubject)
        assertEquals("new@example.com", merged.getValue("new-key").email)
    }
}
