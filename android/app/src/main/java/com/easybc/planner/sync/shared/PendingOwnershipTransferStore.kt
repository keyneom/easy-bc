package com.easybc.planner.sync.shared

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.sharing.SharedBackupOwnershipTransferV1

/** Durable transfer state so Drive finalization can resume after process death. */
class PendingOwnershipTransferStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val json get() = SyncKitJson.instance

    fun setIncomingLink(value: String?) {
        val edit = prefs.edit()
        if (value.isNullOrBlank()) edit.remove(INCOMING_LINK) else edit.putString(INCOMING_LINK, value)
        edit.apply()
    }

    fun incomingLink(): String? = prefs.getString(INCOMING_LINK, null)

    fun setOutgoingLink(value: String?) {
        val edit = prefs.edit()
        if (value.isNullOrBlank()) edit.remove(OUTGOING_LINK) else edit.putString(OUTGOING_LINK, value)
        edit.apply()
    }

    fun outgoingLink(): String? = prefs.getString(OUTGOING_LINK, null)

    fun saveAccepted(value: SharedBackupOwnershipTransferV1) {
        prefs.edit().putString(
            "$ACCEPTED_PREFIX${value.transferId}",
            json.encodeToString(SharedBackupOwnershipTransferV1.serializer(), value),
        ).apply()
    }

    fun accepted(transferId: String): SharedBackupOwnershipTransferV1? =
        prefs.getString("$ACCEPTED_PREFIX$transferId", null)?.let { encoded ->
            runCatching {
                json.decodeFromString(SharedBackupOwnershipTransferV1.serializer(), encoded)
            }.getOrNull()
        }

    fun clear(transferId: String) {
        prefs.edit()
            .remove(INCOMING_LINK)
            .remove("$ACCEPTED_PREFIX$transferId")
            .apply()
        com.easybc.planner.sync.InteractiveAuthGate.deepLinkFlowFinished()
    }

    fun deleteAccepted(transferId: String) {
        prefs.edit().remove("$ACCEPTED_PREFIX$transferId").apply()
    }

    fun clearIncoming() {
        prefs.edit().remove(INCOMING_LINK).apply()
        com.easybc.planner.sync.InteractiveAuthGate.deepLinkFlowFinished()
    }

    /**
     * The user left the offer screen without deciding: release the auth gate
     * so background sync resumes, but keep the offer stored — it stays
     * discoverable from the Profiles screen until accepted or declined.
     */
    fun parkIncoming() {
        com.easybc.planner.sync.InteractiveAuthGate.deepLinkFlowFinished()
    }

    companion object {
        private const val PREFS_NAME = "easybc_pending_ownership_transfer"
        private const val INCOMING_LINK = "incoming_link"
        private const val OUTGOING_LINK = "outgoing_link"
        private const val ACCEPTED_PREFIX = "accepted:"
    }
}
