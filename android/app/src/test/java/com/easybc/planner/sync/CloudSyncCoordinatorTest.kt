package com.easybc.planner.sync

import android.app.Activity
import com.keyneom.synckit.core.Authorization
import com.keyneom.synckit.core.CloudStore
import com.keyneom.synckit.core.CreatedKey
import com.keyneom.synckit.core.KeyProvider
import com.keyneom.synckit.core.StoredEnvelope
import com.keyneom.synckit.crypto.SyncEnvelopeV1
import com.keyneom.synckit.crypto.V1KeyMetadata
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

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
    private val metadata = V1KeyMetadata(credentialId, SYNC_RP_ID, prfInputBytes, salt)

    private lateinit var contentKey: ByteArray
    private lateinit var remoteEnvelope: SyncEnvelopeV1

    private val store = FakePayloadGateway(basePayload)
    private val drive = FakeDrive()
    private val passkeys = FakeKeyProvider()

    private lateinit var coordinator: CloudSyncCoordinator

    @Before
    fun setUp() {
        EasyBcSyncRuntime.lock()
        contentKey = EasyBcSyncRuntime.envelopeCrypto.deriveContentKey(secret, salt)
        remoteEnvelope = EasyBcSyncRuntime.envelopeCrypto.encrypt(
            basePayload,
            contentKey,
            metadata,
        )
        passkeys.seed(metadata, contentKey, secret)
        drive.snapshot = StoredEnvelope("file", remoteEnvelope)
        coordinator = CloudSyncCoordinator(store, drive, passkeys)
    }

    @After
    fun tearDown() {
        EasyBcSyncRuntime.lock()
        passkeys.clear()
    }

    @Test
    fun cachedKeyAvoidsPasskeyPromptAndSkipsNoOpUpload() = runBlocking {
        passkeys.remember(metadata, contentKey)

        val message = coordinator.execute(activity, CloudSyncOperation.SYNC, "token")

        assertEquals(0, passkeys.unlockCalls)
        assertEquals(0, drive.writeCalls)
        assertTrue(message.startsWith("Encrypted cloud data"))
        assertEquals(1, store.applyCalls)
        assertEquals(remoteEnvelope.updatedAt, store.lastSyncedAt)
    }

    @Test
    fun syncUploadsAndRefreshesCacheWhenLocalChanged() = runBlocking {
        passkeys.remember(metadata, contentKey)
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
        assertTrue(passkeys.isUnlockedFor(drive.snapshot!!.envelope))
    }

    @Test
    fun decryptFailureClearsCachedKey() = runBlocking {
        drive.snapshot = StoredEnvelope("file", remoteEnvelope.copy(ciphertext = "AAAA"))
        passkeys.remember(metadata, contentKey)

        try {
            coordinator.execute(activity, CloudSyncOperation.SYNC, "token")
            fail("Expected decrypt failure")
        } catch (_: Exception) {
        }

        assertEquals(false, passkeys.isUnlockedFor(remoteEnvelope))
        assertEquals(0, drive.writeCalls)
    }

    @Test
    fun deleteClearsCachedKeyAndForgetsState() = runBlocking {
        passkeys.remember(metadata, contentKey)

        coordinator.execute(activity, CloudSyncOperation.DELETE, "token")

        assertEquals("file", drive.deletedFileId)
        assertTrue(store.forgotten)
        assertEquals(false, passkeys.isUnlockedFor(remoteEnvelope))
    }

    @Test
    fun resetReplacesKeyAndUnlocksForCurrentSession() = runBlocking {
        passkeys.remember(metadata, contentKey)
        val newSecret = ByteArray(32) { (it + 97).toByte() }
        val newSalt = ByteArray(32) { (it + 129).toByte() }
        val newMetadata = V1KeyMetadata("new-credential", SYNC_RP_ID, prfInputBytes, newSalt)
        val newKey = EasyBcSyncRuntime.envelopeCrypto.deriveContentKey(newSecret, newSalt)
        passkeys.nextCreate = CreatedKey(newMetadata, newKey.copyOf())

        coordinator.execute(activity, CloudSyncOperation.RESET, "token")

        assertEquals(1, passkeys.createCalls)
        assertEquals(1, drive.writeCalls)
        val refreshed = drive.snapshot?.envelope
        assertNotNull(refreshed)
        assertEquals("new-credential", refreshed!!.credentialId)
        assertTrue(passkeys.isUnlockedFor(refreshed))
    }

    @Test
    fun setupRefusesWhenSnapshotAlreadyExists() = runBlocking {
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
        drive.snapshot = StoredEnvelope("file", foreign)

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

        override suspend fun localPayload(): SyncPayloadV1 = local
        override suspend fun apply(payload: SyncPayloadV1) {
            applyCalls += 1
        }
        override suspend fun rememberSync(fileId: String, syncedAt: String) {
            lastSyncedAt = syncedAt
        }
        override suspend fun forgetSync() {
            forgotten = true
        }
    }

    private class FakeDrive : CloudStore {
        var snapshot: StoredEnvelope? = null
        var writeCalls = 0
        var deletedFileId: String? = null

        override suspend fun find(appId: String, authorization: Authorization): StoredEnvelope? =
            snapshot

        override suspend fun write(
            appId: String,
            envelope: SyncEnvelopeV1,
            authorization: Authorization,
            existingId: String?,
        ): String {
            writeCalls += 1
            val effectiveId = existingId ?: "new-file"
            snapshot = StoredEnvelope(effectiveId, envelope)
            return effectiveId
        }

        override suspend fun delete(appId: String, fileId: String, authorization: Authorization) {
            deletedFileId = fileId
            snapshot = null
        }
    }

    /** Test double that caches derived keys without Credential Manager. */
    private class FakeKeyProvider : KeyProvider {
        private var cachedIdentity: String? = null
        private var cachedKey: ByteArray? = null
        private var unlockSecret: ByteArray = ByteArray(0)
        var nextCreate: CreatedKey? = null
        var createCalls = 0
        var unlockCalls = 0

        fun seed(metadata: V1KeyMetadata, key: ByteArray, secret: ByteArray) {
            unlockSecret = secret.copyOf()
            nextCreate = CreatedKey(metadata, key.copyOf())
        }

        fun remember(metadata: V1KeyMetadata, key: ByteArray) {
            cachedIdentity = metadata.identity()
            cachedKey = key.copyOf()
        }

        fun isUnlockedFor(envelope: SyncEnvelopeV1): Boolean =
            cachedIdentity == envelope.metadata().identity() && cachedKey != null

        override suspend fun create(activity: Activity, appId: String): CreatedKey {
            createCalls += 1
            val created = nextCreate ?: error("missing nextCreate")
            remember(created.metadata, created.key)
            return CreatedKey(created.metadata, created.key.copyOf())
        }

        override suspend fun unlock(activity: Activity, envelope: SyncEnvelopeV1): ByteArray {
            if (envelope.rpId != SYNC_RP_ID) {
                throw IllegalArgumentException(
                    "The protected key belongs to ${envelope.rpId}, not $SYNC_RP_ID.",
                )
            }
            cachedKey?.let { cached ->
                if (cachedIdentity == envelope.metadata().identity()) {
                    return cached.copyOf()
                }
            }
            unlockCalls += 1
            val key = EasyBcSyncRuntime.envelopeCrypto.deriveContentKey(
                unlockSecret,
                envelope.metadata().kdfSalt,
            )
            remember(envelope.metadata(), key)
            return key.copyOf()
        }

        override fun clear() {
            cachedKey?.fill(0)
            cachedKey = null
            cachedIdentity = null
        }
    }
}
