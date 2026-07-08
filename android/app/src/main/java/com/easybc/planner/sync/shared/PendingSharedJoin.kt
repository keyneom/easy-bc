package com.easybc.planner.sync.shared

/**
 * Carries a shared-profile join link from a deep link (handled in MainActivity)
 * to the Settings join UI, so a join that can't complete immediately — e.g. the
 * shared folder isn't granted to this account's Drive token yet — lands the user
 * on the paste field pre-filled, with the "Grant folder access" recovery, rather
 * than dead-ending on a toast.
 */
object PendingSharedJoin {
    @Volatile
    var link: String? = null
        private set

    /** A response link a joiner produced, to display in Settings for copy/share. */
    @Volatile
    var responseLink: String? = null

    fun set(value: String?) {
        link = value?.takeIf { it.isNotBlank() }
    }

    /** Returns the pending link (if any) and clears it. */
    fun consume(): String? {
        val current = link
        link = null
        return current
    }
}
