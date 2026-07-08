package com.easybc.planner.sync.shared

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.sharing.SharingInvitationV1
import kotlinx.serialization.Serializable

/**
 * Remembers invitations this device issued via a link (keyed by exchange id) so
 * the owner can accept the recipient's response link later. The recipient email
 * is kept for the per-email dataset-file re-share at accept time.
 */
class PendingInviteStore(context: Context) {
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

    fun save(invitation: SharingInvitationV1, recipientEmail: String) {
        val record = Pending(invitation, recipientEmail)
        prefs.edit()
            .putString(invitation.exchangeId, json.encodeToString(Pending.serializer(), record))
            .apply()
    }

    fun load(exchangeId: String): Pending? {
        val encoded = prefs.getString(exchangeId, null) ?: return null
        return json.decodeFromString(Pending.serializer(), encoded)
    }

    fun delete(exchangeId: String) {
        prefs.edit().remove(exchangeId).apply()
    }

    @Serializable
    data class Pending(
        val invitation: SharingInvitationV1,
        val recipientEmail: String,
    )

    companion object {
        private const val PREFS_NAME = "easybc_pending_invites"
    }
}
