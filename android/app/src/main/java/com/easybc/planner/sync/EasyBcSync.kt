package com.easybc.planner.sync

import com.keyneom.synckit.core.SyncCodec
import com.keyneom.synckit.crypto.PasskeyProfile
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.crypto.V1CompatibilityProfile
import com.keyneom.synckit.crypto.V1Compression
import com.keyneom.synckit.crypto.V1EnvelopeCrypto
import com.keyneom.synckit.keys.AndroidPasskeyKeyProvider
import com.keyneom.synckit.stores.GoogleDriveAppDataStore
import kotlinx.serialization.encodeToString

const val SYNC_RP_ID = "keyneom.github.io"
const val SYNC_FILE_NAME = "easybc-sync-v1.json"
const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
const val SYNC_EPOCH = "1970-01-01T00:00:00.000Z"

val easyBcV1Profile = V1CompatibilityProfile(
    appId = "easy-bc",
    filename = SYNC_FILE_NAME,
    aad = "easy-bc-sync-envelope-v1",
    hkdfInfo = "easy-bc-cloud-content-key-v1",
    compression = V1Compression.GZIP_IF_SMALLER,
    passkey = PasskeyProfile(
        rpName = "EasyBC",
        userName = "encrypted-sync",
        userDisplayName = "EasyBC encrypted sync",
    ),
)

object EasyBcSyncCodec : SyncCodec<SyncPayloadV1> {
    private val json get() = SyncKitJson.instance

    override fun serialize(value: SyncPayloadV1): ByteArray =
        json.encodeToString(SyncPayloadV1.serializer(), value).toByteArray(Charsets.UTF_8)

    override fun parse(bytes: ByteArray): SyncPayloadV1 =
        json.decodeFromString(
            SyncPayloadV1.serializer(),
            bytes.toString(Charsets.UTF_8),
        )

    override fun merge(local: SyncPayloadV1, remote: SyncPayloadV1): SyncPayloadV1 =
        SyncMerge.merge(remote, local)

    override fun fingerprint(value: SyncPayloadV1): String =
        json.encodeToString(
            SyncPayloadV1.serializer(),
            value.copy(exportedAt = SYNC_EPOCH),
        )

    override fun updatedAt(value: SyncPayloadV1): String = value.exportedAt
}

object EasyBcSyncRuntime {
    val envelopeCrypto = V1EnvelopeCrypto(easyBcV1Profile, EasyBcSyncCodec)
    val keyProvider = AndroidPasskeyKeyProvider(easyBcV1Profile, SYNC_RP_ID, envelopeCrypto)
    val cloudStore = GoogleDriveAppDataStore(easyBcV1Profile, envelopeCrypto)

    fun lock() {
        keyProvider.clear()
    }
}
