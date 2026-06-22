package com.easybc.planner.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    @Test
    fun newestSettingsAndDayLogWin() {
        val old = payload(30, "2026-01-01T00:00:00Z").copy(
            calendarDayLogs = mapOf(
                "2026-01-02" to SyncDayLog(notes = "old", updatedAt = "2026-01-02T00:00:00Z")
            )
        )
        val recent = payload(35, "2026-02-01T00:00:00Z").copy(
            calendarDayLogs = mapOf(
                "2026-01-02" to SyncDayLog(notes = "new", updatedAt = "2026-01-03T00:00:00Z")
            )
        )

        val merged = SyncMerge.merge(old, recent)

        assertEquals(35, merged.planner.value.ageYears)
        assertEquals("new", merged.calendarDayLogs["2026-01-02"]?.notes)
    }

    @Test
    fun periodDeletionWinsOverOlderRecord() {
        val active = payload(30, "2026-01-01T00:00:00Z").copy(
            periodRecords = listOf(
                SyncPeriodRecord(start = "2026-01-10", updatedAt = "2026-01-10T00:00:00Z")
            )
        )
        val deleted = payload(30, "2026-01-01T00:00:00Z").copy(
            deletedPeriodStarts = mapOf("2026-01-10" to "2026-01-20T00:00:00Z")
        )

        val merged = SyncMerge.merge(active, deleted)

        assertTrue(merged.periodRecords.isEmpty())
        assertEquals("2026-01-20T00:00:00Z", merged.deletedPeriodStarts["2026-01-10"])
    }

    @Test
    fun explicitConfiguredMarkerUpgradesEqualTimeLegacyPlanner() {
        val legacy = payload(35, "2026-02-01T00:00:00Z")
        val current = payload(35, "2026-02-01T00:00:00Z").copy(
            planner = TimestampedPlanner(
                value = SyncPlannerOptions(ageYears = 35),
                updatedAt = "2026-02-01T00:00:00Z",
                configured = true,
            )
        )

        assertEquals(true, SyncMerge.merge(legacy, current).planner.configured)
    }

    @Test
    fun periodHistoryUpgradesLegacyPlannerAsConfigured() {
        val legacy = payload(35, "2026-02-01T00:00:00Z").copy(
            periodRecords = listOf(SyncPeriodRecord(start = "2026-01-01"))
        )

        assertEquals(true, SyncMerge.merge(legacy, payload(34, SYNC_EPOCH)).planner.configured)
    }

    private fun payload(age: Int, updatedAt: String) = SyncPayloadV1(
        exportedAt = updatedAt,
        planner = TimestampedPlanner(SyncPlannerOptions(ageYears = age), updatedAt),
    )
}
