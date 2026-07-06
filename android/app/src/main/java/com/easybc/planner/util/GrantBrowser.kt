package com.easybc.planner.util

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the web app's Google Picker grant page in a real browser. The browser
 * package must be forced because the app owns the keyneom.github.io App Link:
 * an unaddressed VIEW intent routes straight back into the app.
 *
 * Returns false when no browser could be resolved (the caller should fall
 * back to copying the link for the user to open manually).
 */
fun launchGrantInBrowser(activity: Activity, url: String): Boolean {
    val uri = Uri.parse(url)
    CustomTabsClient.getPackageName(activity, null)?.let { browser ->
        val customTab = CustomTabsIntent.Builder().build()
        customTab.intent.setPackage(browser)
        customTab.launchUrl(activity, uri)
        return true
    }
    // No Custom Tabs provider visible: resolve the default browser with a
    // neutral URL (resolving our own URL would return this app's App Link).
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val browser = activity.packageManager
        .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
        ?.takeIf { it != activity.packageName && it != "android" }
        ?: return false
    activity.startActivity(
        Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(browser),
    )
    return true
}
