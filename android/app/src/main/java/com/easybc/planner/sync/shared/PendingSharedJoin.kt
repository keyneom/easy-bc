package com.easybc.planner.sync.shared

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Durable handoff for join/response links. Deep links can arrive before the
 * Settings screen exists, and Android may kill the process while Google auth or
 * the browser Picker is open, so these values cannot live only in memory.
 */
object PendingSharedJoin {
    private const val PREFS_NAME = "easybc_pending_shared_links"
    private const val JOIN_LINK = "join_link"
    private const val RESPONSE_TO_ACCEPT = "response_to_accept"
    private const val PRODUCED_RESPONSE = "produced_response"
    private const val GRANT_COMPLETED = "grant_completed"
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun setJoinLink(context: Context, value: String?) {
        put(context, JOIN_LINK, value)
    }

    fun joinLink(context: Context): String? = prefs(context).getString(JOIN_LINK, null)

    fun clearJoinLink(context: Context) {
        prefs(context).edit().remove(JOIN_LINK).apply()
        com.easybc.planner.sync.InteractiveAuthGate.deepLinkFlowFinished()
        changed()
    }

    fun setResponseToAccept(context: Context, value: String?) {
        put(context, RESPONSE_TO_ACCEPT, value)
    }

    fun responseToAccept(context: Context): String? =
        prefs(context).getString(RESPONSE_TO_ACCEPT, null)

    fun clearResponseToAccept(context: Context) {
        prefs(context).edit().remove(RESPONSE_TO_ACCEPT).apply()
        com.easybc.planner.sync.InteractiveAuthGate.deepLinkFlowFinished()
        changed()
    }

    fun setProducedResponse(context: Context, value: String?) {
        put(context, PRODUCED_RESPONSE, value)
    }

    fun producedResponse(context: Context): String? =
        prefs(context).getString(PRODUCED_RESPONSE, null)

    /**
     * Set when the browser grant page sent the user back with `sk-granted=1`:
     * the Picker grants are complete, so the join flow should continue
     * automatically instead of waiting for another tap (docs/join-flow.md).
     */
    fun setGrantCompleted(context: Context, completed: Boolean) {
        put(context, GRANT_COMPLETED, if (completed) "1" else null)
    }

    fun grantCompleted(context: Context): Boolean =
        prefs(context).getString(GRANT_COMPLETED, null) == "1"

    private fun put(context: Context, key: String, value: String?) {
        val edit = prefs(context).edit()
        if (value.isNullOrBlank()) edit.remove(key) else edit.putString(key, value)
        edit.apply()
        changed()
    }

    private fun changed() {
        _revision.value += 1
    }
}
