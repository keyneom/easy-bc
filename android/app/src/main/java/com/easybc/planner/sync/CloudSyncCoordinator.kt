package com.easybc.planner.sync

import android.app.Activity
import com.keyneom.synckit.core.Authorization
import com.keyneom.synckit.core.AuthorizationProvider
import com.keyneom.synckit.core.CloudStore
import com.keyneom.synckit.core.KeyProvider
import com.keyneom.synckit.core.SyncKitError
import com.keyneom.synckit.core.SyncKitErrorCode
import com.keyneom.synckit.core.SyncReason
import com.keyneom.synckit.crypto.V1EnvelopeCrypto
import com.keyneom.synckit.snapshot.SnapshotSyncController
import com.keyneom.synckit.snapshot.SnapshotSyncOptions

class CloudSyncCoordinator(
    private val store: SyncPayloadGateway,
    private val cloudStore: CloudStore = EasyBcSyncRuntime.cloudStore,
    private val keyProvider: KeyProvider = EasyBcSyncRuntime.keyProvider,
    private val envelopeCrypto: V1EnvelopeCrypto<SyncPayloadV1> = EasyBcSyncRuntime.envelopeCrypto,
) {
    suspend fun execute(
        activity: Activity,
        operation: CloudSyncOperation,
        accessToken: String,
    ): String {
        val controller = SnapshotSyncController(
            SnapshotSyncOptions(
                appId = easyBcV1Profile.appId,
                codec = EasyBcSyncCodec,
                envelopeCrypto = envelopeCrypto,
                keyProvider = keyProvider,
                authorizationProvider = object : AuthorizationProvider {
                    override suspend fun authorize(): Authorization = Authorization(accessToken)
                },
                cloudStore = cloudStore,
                readLocal = { store.localPayload() },
                applyMerged = { store.apply(it) },
                activity = { activity },
            ),
        )

        if (operation == CloudSyncOperation.DELETE) {
            try {
                controller.delete()
            } catch (error: SyncKitError) {
                if (error.code != SyncKitErrorCode.NOT_FOUND) {
                    throw mapError(error)
                }
            }
            store.forgetSync()
            EasyBcSyncRuntime.lock()
            return "The encrypted EasyBC cloud snapshot was deleted from Google Drive."
        }

        val result = try {
            when (operation) {
                CloudSyncOperation.SETUP -> controller.setup()
                CloudSyncOperation.ENABLE -> controller.enable()
                CloudSyncOperation.SYNC -> controller.sync(SyncReason.MANUAL)
                CloudSyncOperation.RESET -> controller.reset()
                CloudSyncOperation.DELETE -> error("unreachable")
            }
        } catch (error: SyncKitError) {
            throw mapError(error)
        }

        val fileId = result.fileId
        val syncedAt = result.syncedAt
        if (fileId != null && syncedAt != null) {
            store.rememberSync(fileId, syncedAt)
        }
        return when (operation) {
            CloudSyncOperation.SETUP ->
                "Encrypted cloud sync is set up and unlocked for this app session."
            CloudSyncOperation.ENABLE ->
                "Encrypted cloud sync is enabled on this device and the latest records were merged."
            CloudSyncOperation.RESET ->
                "The encrypted cloud snapshot now uses the new passkey and this device's local data."
            CloudSyncOperation.SYNC ->
                "Encrypted cloud data, records, and settings are up to date."
            CloudSyncOperation.DELETE ->
                "The encrypted EasyBC cloud snapshot was deleted from Google Drive."
        }
    }

    suspend fun enableOrForgetIfMissing(
        activity: Activity,
        accessToken: String,
    ): Boolean {
        return try {
            execute(activity, CloudSyncOperation.ENABLE, accessToken)
            true
        } catch (error: Exception) {
            if (isNotFound(error)) {
                store.forgetSync()
                false
            } else {
                throw error
            }
        }
    }

    private fun mapError(error: SyncKitError): Exception = when (error.code) {
        SyncKitErrorCode.STATE,
        SyncKitErrorCode.NOT_FOUND,
        SyncKitErrorCode.COMPATIBILITY,
        SyncKitErrorCode.KEY,
        SyncKitErrorCode.CRYPTO,
        -> IllegalArgumentException(error.message, error)
        else -> error
    }

    companion object {
        fun isNotFound(error: Throwable): Boolean {
            val syncError = when (error) {
                is SyncKitError -> error
                is IllegalArgumentException -> error.cause as? SyncKitError
                else -> null
            }
            return syncError?.code == SyncKitErrorCode.NOT_FOUND
        }
    }
}

enum class CloudSyncOperation { SETUP, ENABLE, SYNC, RESET, DELETE }
