package com.easybc.planner.util

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient

/**
 * Opens the web app's Google Picker grant page in a full browser tab. Two
 * constraints shape this: the browser package must be forced because the app
 * owns the keyneom.github.io App Link (an unaddressed VIEW intent routes
 * straight back into the app), and it must be a regular tab rather than a
 * Custom Tab because Google Identity Services' popup token flow breaks
 * inside Custom Tabs — the popup replaces the page and the token never
 * reaches the opener.
 *
 * Returns false when no browser could be resolved (the caller should fall
 * back to copying the link for the user to open manually).
 */
fun launchGrantInBrowser(activity: Activity, url: String): Boolean {
    val uri = Uri.parse(url)
    // Resolve the default browser with a neutral URL (resolving our own URL
    // would return this app's App Link); fall back to any Custom Tabs
    // provider, still launched as a plain tab.
    val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    val browser = activity.packageManager
        .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
        ?.takeIf { it != activity.packageName && it != "android" }
        ?: CustomTabsClient.getPackageName(activity, null)
        ?: return false
    activity.startActivity(
        Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(browser),
    )
    return true
}
