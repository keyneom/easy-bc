package com.easybc.planner.sync.shared

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.keyneom.synckit.core.Authorization
import com.keyneom.synckit.core.AuthorizationProvider

/**
 * Remembers the latest Google Drive access token from Play Services authorization
 * so sync-kit transports and background workers can authorize without an Activity.
 */
class SharedDriveAuth(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun remember(accessToken: String, expiresAt: Long? = null) {
        prefs.edit()
            .putString(KEY_TOKEN, accessToken)
            .apply {
                if (expiresAt != null) putLong(KEY_EXPIRES_AT, expiresAt)
                else remove(KEY_EXPIRES_AT)
            }
            .apply()
    }

    fun tokenExpiresAt(): Long? {
        val value = prefs.getLong(KEY_EXPIRES_AT, Long.MIN_VALUE)
        return if (value == Long.MIN_VALUE) null else value
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun provider(): AuthorizationProvider = object : AuthorizationProvider {
        override suspend fun authorize(): Authorization {
            val token = prefs.getString(KEY_TOKEN, null)
                ?: error("Google Drive authorization is required for encrypted sync.")
            val expiresAt = tokenExpiresAt()
            return Authorization(token, expiresAt)
        }

        override fun clear() = this@SharedDriveAuth.clear()
    }

    companion object {
        private const val PREFS_NAME = "easybc_shared_drive_auth"
        private const val KEY_TOKEN = "access_token"
        private const val KEY_EXPIRES_AT = "expires_at"
    }
}
