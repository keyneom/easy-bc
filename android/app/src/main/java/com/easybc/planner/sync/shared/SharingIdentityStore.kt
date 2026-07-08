package com.easybc.planner.sync.shared

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.easybc.planner.EasyBcForegroundActivity
import com.easybc.planner.sync.EasyBcSyncCodec
import com.easybc.planner.sync.SYNC_RP_ID
import com.keyneom.synckit.core.Authorization
import com.keyneom.synckit.core.SyncKitError
import com.keyneom.synckit.core.SyncKitErrorCode
import com.keyneom.synckit.crypto.Base64Url
import com.keyneom.synckit.crypto.PasskeyProfile
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.crypto.V1CompatibilityProfile
import com.keyneom.synckit.crypto.V1Compression
import com.keyneom.synckit.crypto.V1EnvelopeCrypto
import com.keyneom.synckit.crypto.V1KeyMetadata
import com.keyneom.synckit.keys.AndroidPasskeyKeyProvider
import com.keyneom.synckit.sharing.DriveAppDataProtectedSharingIdentityStore
import com.keyneom.synckit.sharing.ProtectedSharingIdentityCrypto
import com.keyneom.synckit.sharing.ProtectedSharingIdentityV1
import com.keyneom.synckit.sharing.SharingIdentity
import com.keyneom.synckit.sharing.SharingPublicKeyV1
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Sharing V1 profile whose HKDF wrap-info matches the web
 * `easyBcSharingPasskeyProfile`, so the same passkey PRF secret derives the same
 * identity-wrapping key on both platforms.
 */
val easyBcSharingProfile = V1CompatibilityProfile(
    appId = "$EASY_BC_APP_ID-sharing",
    filename = "unused",
    aad = "easy-bc-sharing-identity-v1",
    hkdfInfo = "easy-bc-sharing-identity-wrap-v1",
    compression = V1Compression.NONE,
    passkey = PasskeyProfile(
        rpName = "EasyBC",
        userName = "encrypted-sync",
        userDisplayName = "EasyBC encrypted sync",
    ),
)

/**
 * Hosts the sharing identity as a passkey-wrapped `ProtectedSharingIdentityV1`
 * in `drive.appdata` so one Google account carries a single sharing identity
 * across all of its devices (web included). A pre-existing device-local keypair
 * from before this scheme is migrated in place — wrapped with a passkey and
 * promoted to app-data without regenerating it, so the device keeps ownership of
 * any dataset it already created.
 *
 * The unlocked identity is cached in memory for the process session, so the
 * passkey is prompted at most once per session.
 */
class SharingIdentityStore(
    context: Context,
    private val authorization: suspend () -> Authorization,
) {
    private val json get() = SyncKitJson.instance
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val appDataStore = DriveAppDataProtectedSharingIdentityStore(authorization = authorization)
    private val passkey = AndroidPasskeyKeyProvider(
        easyBcSharingProfile,
        SYNC_RP_ID,
        V1EnvelopeCrypto(easyBcSharingProfile, EasyBcSyncCodec),
    )

    @Volatile
    private var cached: SharingIdentity? = null

    suspend fun getOrCreate(): SharingIdentity {
        cached?.let { return it }
        // 1. App-data is authoritative: the same Google account returns the same
        //    blob on every device, so the identity follows the user.
        appDataStore.load(EASY_BC_APP_ID)?.let { record ->
            return unlockRecord(record).also { cached = it }
        }
        // 2. No app-data identity yet: wrap this device's pre-existing keypair
        //    (migration) or a freshly minted one, then promote it to app-data so
        //    other devices converge on it.
        val legacy = loadLegacyRaw()
        val activity = requireForegroundActivity()
        val created = passkey.create(activity, EASY_BC_APP_ID)
        val wrapped = try {
            ProtectedSharingIdentityCrypto.create(
                EASY_BC_APP_ID,
                created.metadata,
                created.key,
                identity = legacy,
            )
        } finally {
            created.key.fill(0)
        }
        appDataStore.save(wrapped.record)
        return wrapped.identity.also { cached = it }
    }

    fun get(): SharingIdentity? = cached

    /**
     * Forgets the identity on this device only: clears the in-memory cache and
     * the legacy device-local keypair. The app-data blob is shared with the
     * user's other devices, so it is intentionally left in place.
     */
    fun clear() {
        cached = null
        prefs.edit().clear().apply()
    }

    private suspend fun unlockRecord(record: ProtectedSharingIdentityV1): SharingIdentity {
        val activity = requireForegroundActivity()
        val metadata = V1KeyMetadata(
            credentialId = record.credentialId,
            rpId = record.rpId,
            prfInput = Base64Url.decode(record.prfInput),
            kdfSalt = Base64Url.decode(record.kdfSalt),
        )
        val wrappingKey = passkey.unlockMetadata(activity, metadata)
        return try {
            ProtectedSharingIdentityCrypto.unlock(record, wrappingKey)
        } finally {
            wrappingKey.fill(0)
        }
    }

    private fun requireForegroundActivity() =
        EasyBcForegroundActivity.current ?: throw SyncKitError(
            SyncKitErrorCode.STATE,
            "Open EasyBC and try encrypted sharing again so it can unlock your passkey.",
        )

    private fun loadLegacyRaw(): SharingIdentity? {
        val encoded = prefs.getString(KEY_RECORD, null) ?: return null
        val record = json.decodeFromString(StoredIdentity.serializer(), encoded)
        val keyFactory = KeyFactory.getInstance("EC")
        val encryptionPrivateKey = keyFactory.generatePrivate(
            PKCS8EncodedKeySpec(record.encryptionPrivateKeyPkcs8),
        ) as ECPrivateKey
        val signingPrivateKey = keyFactory.generatePrivate(
            PKCS8EncodedKeySpec(record.signingPrivateKeyPkcs8),
        ) as ECPrivateKey
        return SharingIdentity(record.publicKey, encryptionPrivateKey, signingPrivateKey)
    }

    @Serializable
    private data class StoredIdentity(
        val publicKey: SharingPublicKeyV1,
        val encryptionPrivateKeyPkcs8: ByteArray,
        val signingPrivateKeyPkcs8: ByteArray,
    )

    companion object {
        private const val PREFS_NAME = "easybc_sharing_identity"
        private const val KEY_RECORD = "identity"
    }
}
