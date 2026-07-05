package com.easybc.planner.sync.shared

import com.easybc.planner.sync.SyncPayloadV1
import com.easybc.planner.sync.TimestampedBoolean
import com.easybc.planner.sync.TimestampedPlanner
import com.easybc.planner.sync.SyncPlannerOptions
import com.keyneom.synckit.sharing.CreateSharedBackupEnvelopeInput
import com.keyneom.synckit.sharing.SharedBackupParticipantInput
import com.keyneom.synckit.sharing.SharingCrypto
import com.keyneom.synckit.sharing.SharingCryptoOptions
import com.keyneom.synckit.sharing.SharingIdentity
import com.keyneom.synckit.sharing.SharingRole
import com.keyneom.synckit.sharing.VerifySharedBackupOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

class SharingCryptoTest {
    @Test
    fun identityRoundTripAndEnvelopeDecrypt() {
        val identity = SharingCrypto.generateIdentity()
        val restored = restoreIdentity(identity)
        assertEquals(identity.publicKey.keyId, restored.publicKey.keyId)

        val payload = SyncPayloadV1(
            exportedAt = "2026-01-01T00:00:00.000Z",
            planner = TimestampedPlanner(
                value = SyncPlannerOptions(ageYears = 30),
                updatedAt = "2026-01-01T00:00:00.000Z",
                configured = true,
            ),
            ecJournal = TimestampedBoolean(value = false, updatedAt = "2026-01-01T00:00:00.000Z"),
        )
        val envelope = SharingCrypto.createSharedBackupEnvelopeV1(
            value = payload,
            codec = EasyBcSharedCodec,
            identity = identity,
            input = CreateSharedBackupEnvelopeInput(
                appId = EASY_BC_APP_ID,
                backupId = PRIMARY_DATASET_ID,
                participants = listOf(
                    SharedBackupParticipantInput(
                        publicKey = identity.publicKey,
                        role = SharingRole.OWNER,
                    ),
                ),
            ),
            options = SharingCryptoOptions(),
        )
        val decrypted = SharingCrypto.decryptSharedBackupEnvelopeV1(
            envelope = envelope,
            codec = EasyBcSharedCodec,
            identity = restored,
            options = VerifySharedBackupOptions(
                trustedOwnerKeyId = identity.publicKey.keyId,
            ),
        )
        assertEquals(30, decrypted.planner.value.ageYears)
        assertTrue(envelope.signature.isNotBlank())
    }

    @Test
    fun folderNameIsOwnerScoped() {
        assertEquals("EasyBC — alice@example.com", easyBcSyncFolderName("Alice@Example.com"))
    }

    private fun restoreIdentity(identity: SharingIdentity): SharingIdentity {
        val keyFactory = KeyFactory.getInstance("EC")
        val encryptionPrivateKey = keyFactory.generatePrivate(
            PKCS8EncodedKeySpec(identity.encryptionPrivateKey.encoded),
        ) as ECPrivateKey
        val signingPrivateKey = keyFactory.generatePrivate(
            PKCS8EncodedKeySpec(identity.signingPrivateKey.encoded),
        ) as ECPrivateKey
        return SharingIdentity(identity.publicKey, encryptionPrivateKey, signingPrivateKey)
    }
}
