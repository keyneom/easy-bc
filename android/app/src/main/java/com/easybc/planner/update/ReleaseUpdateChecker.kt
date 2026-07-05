package com.easybc.planner.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val version: String,
    val downloadUrl: String,
)

class ReleaseUpdateChecker(
    private val context: Context,
    private val preferencesName: String = "easybc_update_checker",
) {
    suspend fun checkForUpdate(currentVersion: String): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val latestVersion = fetchLatestReleaseVersion() ?: return@withContext null
        if (!SemVer.isNewer(latestVersion, currentVersion)) return@withContext null
        if (isDismissed(latestVersion)) return@withContext null
        AppUpdateInfo(
            version = latestVersion,
            downloadUrl = RELEASE_PAGE_URL,
        )
    }

    fun dismiss(version: String) {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(dismissedKey(version), true.toString())
            .apply()
    }

    private fun isDismissed(version: String): Boolean =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .getString(dismissedKey(version), null) == true.toString()

    private fun dismissedKey(version: String): String =
        "dismissed:${SemVer.normalize(version)}"

    private fun fetchLatestReleaseVersion(): String? {
        val connection = (URL(RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "EasyBC-Android")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().readText()
            val tag = JSONObject(body).optString("tag_name")
            tag.takeIf { it.isNotBlank() }?.let(SemVer::normalize)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val RELEASE_API_URL =
            "https://api.github.com/repos/keyneom/easy-bc/releases/latest"
        const val RELEASE_PAGE_URL =
            "https://github.com/keyneom/easy-bc/releases/latest"
    }
}
