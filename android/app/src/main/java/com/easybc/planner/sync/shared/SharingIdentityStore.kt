package com.easybc.planner.sync.shared

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.sharing.SharingCrypto
import com.keyneom.synckit.sharing.SharingIdentity
import com.keyneom.synckit.sharing.SharingPublicKeyV1
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

class SharingIdentityStore(context: Context) {
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

    fun getOrCreate(): SharingIdentity {
        return load() ?: generateIdentity().also(::save)
    }

    fun get(): SharingIdentity? = load()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun generateIdentity(): SharingIdentity =
        SharingCrypto.generateIdentity()

    private fun load(): SharingIdentity? {
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

    private fun save(identity: SharingIdentity) {
        val record = StoredIdentity(
            publicKey = identity.publicKey,
            encryptionPrivateKeyPkcs8 = identity.encryptionPrivateKey.encoded,
            signingPrivateKeyPkcs8 = identity.signingPrivateKey.encoded,
        )
        prefs.edit()
            .putString(KEY_RECORD, json.encodeToString(StoredIdentity.serializer(), record))
            .apply()
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
