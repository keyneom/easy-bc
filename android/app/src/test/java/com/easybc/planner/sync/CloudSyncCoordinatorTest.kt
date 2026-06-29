package com.easybc.planner.sync

import android.app.Activity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class CloudSyncCoordinatorTest {
    private class TestActivity : Activity()

    private val activity: Activity = TestActivity()

    private val basePayload = SyncPayloadV1(
        exportedAt = "2026-06-29T12:00:00.000Z",
        planner = TimestampedPlanner(
            value = SyncPlannerOptions(ageYears = 34),
            updatedAt = "2026-06-29T12:00:00.000Z",
            configured = true,
        ),
    )
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val salt = ByteArray(32) { (it + 33).toByte() }
    private val prfInputBytes = ByteArray(32) { (it + 65).toByte() }
    private val credentialId = "credential"

    private lateinit var contentKey: ByteArray
    private lateinit var remoteEnvelope: SyncEnvelopeV1

    private val store = FakePayloadGateway(basePayload)
    private val drive = FakeDrive()
    private val passkeys = FakePasskeys(
        PasskeyMaterial(credentialId, prfInputBytes, salt, secret),
    )

    private lateinit var coordinator: CloudSyncCoordinator

    @Before
    fun setUp() {
        CloudSyncKeySession.clear()
        contentKey = SyncCrypto.deriveContentKey(secret, salt)
        remoteEnvelope = SyncCrypto.encryptWithContentKey(
            basePayload,
            contentKey,
            credentialId,
            SYNC_RP_ID,
            prfInputBytes,
            salt,
        )
        coordinator = CloudSyncCoordinator(store, drive, passkeys)
    }

    @After
    fun tearDown() {
        CloudSyncKeySession.clear()
    }

    @Test
    fun cachedKeyAvoidsPasskeyPromptAndSkipsNoOpUpload() = runBlocking {
        drive.snapshot = DriveSnapshot("file", remoteEnvelope)
        CloudSyncKeySession.remember(remoteEnvelope, contentKey)

        val message = coordinator.execute(activity, CloudSyncOperation.SYNC, "token")

        assertEquals(0, passkeys.unlockCalls)
        assertEquals(0, drive.writeCalls)
        assertTrue(message.startsWith("Encrypted cloud data"))
        assertEquals(1, store.applyCalls)
        assertEquals(remoteEnvelope.updatedAt, store.lastSyncedAt)
    }

    @Test
    fun syncUploadsAndRefreshesCacheWhenLocalChanged() = runBlocking {
        drive.snapshot = DriveSnapshot("file", remoteEnvelope)
        CloudSyncKeySession.remember(remoteEnvelope, contentKey)
        store.local = basePayload.copy(
            planner = basePayload.planner.copy(
                value = basePayload.planner.value.copy(ageYears = 36),
                updatedAt = "2026-06-29T13:00:00.000Z",
            ),
        )

        coordinator.execute(activity, CloudSyncOperation.SYNC, "token")

        assertEquals(0, passkeys.unlockCalls)
        assertEquals(1, drive.writeCalls)
        assertEquals(1, store.applyCalls)
        assertNotNull(store.lastSyncedAt)
        assertTrue(CloudSyncKeySession.isUnlockedFor(remoteEnvelope))
    }

    @Test
    fun decryptFailureClearsCachedKey() = runBlocking {
        drive.snapshot = DriveSnapshot("file", remoteEnvelope.copy(ciphertext = "AAAA"))
        CloudSyncKeySession.remember(remoteEnvelope, contentKey)

        try {
            coordinator.execute(activity, CloudSyncOperation.SYNC, "token")
            fail("Expected decrypt failure")
        } catch (_: Exception) {
        }

        assertNull(CloudSyncKeySession.get(remoteEnvelope))
        assertEquals(0, drive.writeCalls)
    }

    @Test
    fun deleteClearsCachedKeyAndForgetsState() = runBlocking {
        drive.snapshot = DriveSnapshot("file", remoteEnvelope)
        CloudSyncKeySession.remember(remoteEnvelope, contentKey)

        coordinator.execute(activity, CloudSyncOperation.DELETE, "token")

        assertEquals("file", drive.deletedFileId)
        assertTrue(store.forgotten)
        assertNull(CloudSyncKeySession.get(remoteEnvelope))
    }

    @Test
    fun resetReplacesKeyAndUnlocksForCurrentSession() = runBlocking {
        drive.snapshot = DriveSnapshot("file", remoteEnvelope)
        CloudSyncKeySession.remember(remoteEnvelope, contentKey)
        val newSecret = ByteArray(32) { (it + 97).toByte() }
        val newSalt = ByteArray(32) { (it + 129).toByte() }
        passkeys.next = PasskeyMaterial("new-credential", prfInputBytes, newSalt, newSecret)

        coordinator.execute(activity, CloudSyncOperation.RESET, "token")

        assertEquals(1, passkeys.createCalls)
        assertEquals(1, drive.writeCalls)
        assertEquals("file", drive.lastWriteFileId)
        val refreshed = drive.lastWrittenEnvelope ?: fail("Missing write")
        refreshed as SyncEnvelopeV1
        assertEquals("new-credential", refreshed.credentialId)
        assertTrue(CloudSyncKeySession.isUnlockedFor(refreshed))
    }

    @Test
    fun setupRefusesWhenSnapshotAlreadyExists() = runBlocking {
        drive.snapshot = DriveSnapshot("file", remoteEnvelope)
        try {
            coordinator.execute(activity, CloudSyncOperation.SETUP, "token")
            fail("Expected setup refusal")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message?.contains("already exists") == true)
        }
    }

    @Test
    fun snapshotFromWrongRelyingPartyIsRejected() = runBlocking {
        val foreign = remoteEnvelope.copy(rpId = "evil.example")
        drive.snapshot = DriveSnapshot("file", foreign)

        try {
            coordinator.execute(activity, CloudSyncOperation.SYNC, "token")
            fail("Expected rpId mismatch")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message?.contains("evil.example") == true)
        }
    }

    private class FakePayloadGateway(initial: SyncPayloadV1) : SyncPayloadGateway {
        var local: SyncPayloadV1 = initial
        var applyCalls = 0
        var lastSyncedAt: String? = null
        var forgotten = false
        private val applied = ConcurrentLinkedQueue<SyncPayloadV1>()

        override suspend fun localPayload(): SyncPayloadV1 = local
        override suspend fun apply(payload: SyncPayloadV1) {
            applyCalls += 1
            applied.add(payload)
        }
        override suspend fun rememberSync(fileId: String, syncedAt: String) {
            lastSyncedAt = syncedAt
        }
        override suspend fun forgetSync() {
            forgotten = true
        }
    }

    private class FakeDrive : GoogleDriveSyncClient() {
        var snapshot: DriveSnapshot? = null
        var writeCalls = 0
        var lastWriteFileId: String? = null
        var lastWrittenEnvelope: SyncEnvelopeV1? = null
        var deletedFileId: String? = null

        override suspend fun findSnapshot(accessToken: String): DriveSnapshot? = snapshot

        override suspend fun writeSnapshot(
            accessToken: String,
            envelope: SyncEnvelopeV1,
            fileId: String?,
        ): String {
            writeCalls += 1
            val effectiveId = fileId ?: "new-file"
            lastWriteFileId = effectiveId
            lastWrittenEnvelope = envelope
            snapshot = DriveSnapshot(effectiveId, envelope)
            return effectiveId
        }

        override suspend fun deleteSnapshot(accessToken: String, fileId: String): String {
            deletedFileId = fileId
            snapshot = null
            return ""
        }
    }

    private class FakePasskeys(initial: PasskeyMaterial) : PasskeyPrfClient() {
        var next: PasskeyMaterial = initial
        var createCalls = 0
        var unlockCalls = 0
        private val lastSecret = initial.secret

        override suspend fun create(activity: Activity): PasskeyMaterial {
            createCalls += 1
            return next.copy(secret = next.secret.copyOf())
        }

        override suspend fun unlock(
            activity: Activity,
            credentialId: String,
            prfInput: String,
        ): ByteArray {
            unlockCalls += 1
            return lastSecret.copyOf()
        }
    }
}
