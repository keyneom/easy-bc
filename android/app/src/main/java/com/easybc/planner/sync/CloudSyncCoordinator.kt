package com.easybc.planner.sync

import android.app.Activity

class CloudSyncCoordinator(
    private val store: SyncPayloadStore,
    private val drive: GoogleDriveSyncClient = GoogleDriveSyncClient(),
    private val passkeys: PasskeyPrfClient = PasskeyPrfClient(),
) {
    suspend fun execute(
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
            try {
                val envelope = SyncCrypto.encrypt(
                    local,
                    passkey.secret,
                    passkey.credentialId,
                    passkey.rpId,
                    passkey.prfInput,
                    passkey.kdfSalt,
                )
                val fileId = drive.writeSnapshot(accessToken, envelope)
                store.rememberSync(fileId, envelope.updatedAt)
                return "Encrypted cloud sync is set up. The cloud key was discarded after upload."
            } finally {
                passkey.secret.fill(0)
            }
        }

        requireNotNull(existing) { "No EasyBC encrypted snapshot was found in this Google Drive." }
        if (operation == CloudSyncOperation.DELETE) {
            drive.deleteSnapshot(accessToken, existing.fileId)
            store.forgetSync()
            return "The encrypted EasyBC cloud snapshot was deleted from Google Drive."
        }
        if (operation == CloudSyncOperation.RESET) {
            val passkey = passkeys.create(activity)
            try {
                val envelope = SyncCrypto.encrypt(
                    local,
                    passkey.secret,
                    passkey.credentialId,
                    passkey.rpId,
                    passkey.prfInput,
                    passkey.kdfSalt,
                )
                drive.writeSnapshot(accessToken, envelope, existing.fileId)
                store.rememberSync(existing.fileId, envelope.updatedAt)
                return "The encrypted cloud snapshot now uses the new passkey and this device's local data."
            } finally {
                passkey.secret.fill(0)
            }
        }

        require(existing.envelope.rpId == SYNC_RP_ID) {
            "This snapshot belongs to ${existing.envelope.rpId}, not $SYNC_RP_ID."
        }
        val secret = passkeys.unlock(
            activity,
            existing.envelope.credentialId,
            existing.envelope.prfInput,
        )
        try {
            val remote = SyncCrypto.decrypt(existing.envelope, secret)
            val merged = SyncMerge.merge(remote, local)
            val envelope = SyncCrypto.encrypt(
                merged,
                secret,
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
            secret.fill(0)
        }
    }
}
