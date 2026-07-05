package com.easybc.planner.calendar

/**
 * Owner-scoped calendar names aligned with web encrypted sync folder naming.
 * Phase 2 uses these when projecting multiple profiles to device calendars.
 */
object EasyBcCalendarNames {
    fun sanitizeOwnerLabel(email: String): String =
        email.trim().lowercase().replace("\\s+".toRegex(), "")

    fun displayName(ownerEmail: String): String =
        "EasyBC — ${sanitizeOwnerLabel(ownerEmail)}"

    fun internalName(ownerEmail: String, datasetId: String = "primary"): String =
        "easybc_${sanitizeOwnerLabel(ownerEmail).replace("@", "_at_")}_$datasetId"
}
