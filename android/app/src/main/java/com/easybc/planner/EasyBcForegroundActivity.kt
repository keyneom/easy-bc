package com.easybc.planner

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Tracks the current resumed Activity so the encrypted-sharing passkey flow can
 * show Credential Manager UI without threading an Activity through every sync
 * call. The reference is weak and cleared on destroy so it never leaks.
 */
object EasyBcForegroundActivity {
    @Volatile
    private var reference: WeakReference<Activity> = WeakReference(null)

    val current: Activity?
        get() = reference.get()

    fun set(activity: Activity?) {
        reference = WeakReference(activity)
    }
}
