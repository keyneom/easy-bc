package com.easybc.planner.sync

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncKeySessionTest {
    @After
    fun clearSession() {
        CloudSyncKeySession.clear()
    }

    @Test
    fun copiesAndReusesOnlyTheMatchingDerivedKey() {
        val envelope = envelope()
        val original = ByteArray(32) { it.toByte() }
        CloudSyncKeySession.remember(envelope, original)
        original.fill(99)

        val first = CloudSyncKeySession.get(envelope)
        assertArrayEquals(ByteArray(32) { it.toByte() }, first)
        first!!.fill(88)
        assertArrayEquals(ByteArray(32) { it.toByte() }, CloudSyncKeySession.get(envelope))
        assertTrue(CloudSyncKeySession.isUnlockedFor(envelope))

        val replacement = envelope.copy(credentialId = "replacement")
        assertNull(CloudSyncKeySession.get(replacement))
        assertFalse(CloudSyncKeySession.isUnlockedFor(envelope))
    }

    private fun envelope() = SyncEnvelopeV1(
        credentialId = "credential",
        rpId = SYNC_RP_ID,
        prfInput = "input",
        kdfSalt = "salt",
        nonce = "nonce",
        ciphertext = "ciphertext",
        updatedAt = "2026-06-29T00:00:00.000Z",
    )
}
