package com.easybc.planner.sync

import android.app.Activity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CloudSyncCoordinator(
    private val store: SyncPayloadStore,
    private val drive: GoogleDriveSyncClient = GoogleDriveSyncClient(),
    private val passkeys: PasskeyPrfClient = PasskeyPrfClient(),
    private val keySession: CloudSyncKeySession = CloudSyncKeySession,
) {
    suspend fun execute(
        activity: Activity,
        operation: CloudSyncOperation,
        accessToken: String,
    ): String = operationMutex.withLock {
        executeLocked(activity, operation, accessToken)
    }

    private suspend fun executeLocked(
        activity: Activity,
        operation: CloudSyncOperation,
        accessToken: String,
    ): String {
        val existing = drive.findSnapshot(accessToken)
        val local = store.localPayload()

        if (operation == CloudSyncOperation.SETUP) {
            require(existing == null) {
                "An encrypted EasyBC cloud snapshot already exists in this Drive. Use Enable encrypted cloud sync on this device instead."
            }
            val passkey = passkeys.create(activity)
            val contentKey = try {
                SyncCrypto.deriveContentKey(passkey.secret, passkey.kdfSalt)
            } finally {
                passkey.secret.fill(0)
            }
            try {
                val envelope = SyncCrypto.encryptWithContentKey(
                    local,
                    contentKey,
                    passkey.credentialId,
                    passkey.rpId,
                    passkey.prfInput,
                    passkey.kdfSalt,
                )
                val fileId = drive.writeSnapshot(accessToken, envelope)
                store.rememberSync(fileId, envelope.updatedAt)
                keySession.remember(envelope, contentKey)
                return "Encrypted cloud sync is set up and unlocked for this app session."
            } finally {
                contentKey.fill(0)
            }
        }

        requireNotNull(existing) { "No EasyBC encrypted snapshot was found in this Google Drive." }
        if (operation == CloudSyncOperation.DELETE) {
            drive.deleteSnapshot(accessToken, existing.fileId)
            store.forgetSync()
            keySession.clear()
            return "The encrypted EasyBC cloud snapshot was deleted from Google Drive."
        }
        if (operation == CloudSyncOperation.RESET) {
            keySession.clear()
            val passkey = passkeys.create(activity)
            val contentKey = try {
                SyncCrypto.deriveContentKey(passkey.secret, passkey.kdfSalt)
            } finally {
                passkey.secret.fill(0)
            }
            try {
                val envelope = SyncCrypto.encryptWithContentKey(
                    local,
                    contentKey,
                    passkey.credentialId,
                    passkey.rpId,
                    passkey.prfInput,
                    passkey.kdfSalt,
                )
                drive.writeSnapshot(accessToken, envelope, existing.fileId)
                store.rememberSync(existing.fileId, envelope.updatedAt)
                keySession.remember(envelope, contentKey)
                return "The encrypted cloud snapshot now uses the new passkey and this device's local data."
            } finally {
                contentKey.fill(0)
            }
        }

        require(existing.envelope.rpId == SYNC_RP_ID) {
            "This snapshot belongs to ${existing.envelope.rpId}, not $SYNC_RP_ID."
        }
        val contentKey = keySession.get(existing.envelope) ?: run {
            val secret = passkeys.unlock(
                activity,
                existing.envelope.credentialId,
                existing.envelope.prfInput,
            )
            try {
                SyncCrypto.deriveContentKey(
                    secret,
                    SyncCrypto.decodeBase64Url(existing.envelope.kdfSalt),
                )
            } finally {
                secret.fill(0)
            }
        }
        try {
            val remote = try {
                SyncCrypto.decryptWithContentKey(existing.envelope, contentKey)
            } catch (error: Exception) {
                keySession.clear()
                throw error
            }
            keySession.remember(existing.envelope, contentKey)
            val merged = SyncMerge.merge(remote, local)
            val envelope = SyncCrypto.encryptWithContentKey(
                merged,
                contentKey,
                existing.envelope.credentialId,
                existing.envelope.rpId,
                SyncCrypto.decodeBase64Url(existing.envelope.prfInput),
                SyncCrypto.decodeBase64Url(existing.envelope.kdfSalt),
            )
            drive.writeSnapshot(accessToken, envelope, existing.fileId)
            store.apply(merged)
            store.rememberSync(existing.fileId, envelope.updatedAt)
            return if (operation == CloudSyncOperation.ENABLE) {
                "Encrypted cloud sync is enabled on this device and the latest records were merged."
            } else {
                "Encrypted cloud data, records, and settings are up to date."
            }
        } finally {
            contentKey.fill(0)
        }
    }

    companion object {
        private val operationMutex = Mutex()
    }
}
