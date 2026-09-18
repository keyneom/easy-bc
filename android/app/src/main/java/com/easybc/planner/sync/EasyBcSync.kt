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

/**
 * WebAuthn relying party for the sync passkey, and a load-bearing dependency on
 * a file that does not live in this repository.
 *
 * Android derives the passkey PRF secret only if
 * `https://keyneom.github.io/.well-known/assetlinks.json` lists this app with
 * the `delegate_permission/common.get_login_creds` relation and this build's
 * signing-cert SHA-256. That file is served from the separate
 * `keyneom.github.io` pages repo — `deploy/keyneom.github.io/.well-known/` here
 * is an empty placeholder, so searching this repo for it finds nothing and
 * proves nothing. A signing-key change breaks Android passkeys until the pages
 * repo is updated.
 *
 * Android and the browser derive the *same* secret, so one envelope serves both
 * platforms. Never add a second Android-only key path: Keyweb read a missing
 * asset link as "Android cannot do PRF", built a second envelope around it, and
 * the divergence cost a user their cloud backup. Since sync-kit 0.4.2 that
 * failure arrives as SyncKitErrorCode.KEY naming this fix rather than a bare
 * NoCredentialException.
 */
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
