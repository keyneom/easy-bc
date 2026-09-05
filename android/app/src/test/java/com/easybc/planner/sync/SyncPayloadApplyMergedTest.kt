package com.easybc.planner.sync

import com.easybc.planner.sync.shared.DATASET_PARTS
import com.easybc.planner.sync.shared.EasyBcSharedCodec
import com.easybc.planner.sync.shared.projectDatasetPart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data-loss window: `syncDataset` merges against a payload read before the
 * network call, so applying its result must fold back into whatever the local
 * store became while the call was in flight.
 */
class SyncPayloadApplyMergedTest {
    @Test
    fun keepsAPeriodLoggedWhileTheRoundTripWasInFlight() = runBlocking {
        val snapshot = payload(30, "2026-01-01T00:00:00Z").copy(
            periodRecords = listOf(
                SyncPeriodRecord(start = "2026-01-02", updatedAt = "2026-01-02T00:00:00Z"),
            ),
        )
        // Another device logged a period while this one was syncing.
        val remote = payload(30, "2026-01-01T00:00:00Z").copy(
            periodRecords = listOf(
                SyncPeriodRecord(start = "2026-02-01", updatedAt = "2026-02-01T00:00:00Z"),
            ),
        )
        val synced = EasyBcSharedCodecMergeOrder.merge(snapshot, remote)

        // The user logs a third period before the result comes back.
        val gateway = FakeGateway(
            snapshot.copy(
                periodRecords = snapshot.periodRecords + SyncPeriodRecord(
                    start = "2026-03-01",
                    updatedAt = "2026-03-01T00:00:00Z",
                ),
            ),
        )

        // Applying the sync result as-is is what silently dropped the edit.
        assertFalse(synced.periodRecords.any { it.start == "2026-03-01" })

        val applied = gateway.applyMerged(synced)

        assertEquals(
            listOf("2026-01-02", "2026-02-01", "2026-03-01"),
            gateway.local.periodRecords.map { it.start }.sorted(),
        )
        assertEquals(applied, gateway.local)
    }

    @Test
    fun keepsASettingsChangeMadeWhileTheRoundTripWasInFlight() = runBlocking {
        val snapshot = payload(30, "2026-01-01T00:00:00Z")
        val remote = payload(31, "2026-01-05T00:00:00Z")
        val synced = EasyBcSharedCodecMergeOrder.merge(snapshot, remote)
        assertEquals(31, synced.planner.value.ageYears)

        val gateway = FakeGateway(payload(35, "2026-01-09T00:00:00Z"))
        gateway.applyMerged(synced)

        assertEquals(35, gateway.local.planner.value.ageYears)
    }

    @Test
    fun isANoOpWhenNothingChangedDuringTheRoundTrip() = runBlocking {
        val snapshot = payload(30, "2026-01-01T00:00:00Z").copy(
            periodRecords = listOf(
                SyncPeriodRecord(start = "2026-01-02", updatedAt = "2026-01-02T00:00:00Z"),
            ),
        )
        val remote = payload(31, "2026-01-05T00:00:00Z")
        val synced = EasyBcSharedCodecMergeOrder.merge(snapshot, remote)

        // Local is still the snapshot. The result must survive untouched, or
        // every sync would look like it had an unpublished change and loop.
        val gateway = FakeGateway(snapshot)
        gateway.applyMerged(synced)

        assertEquals(synced.planner.value, gateway.local.planner.value)
        assertEquals(
            synced.periodRecords.map { it.start },
            gateway.local.periodRecords.map { it.start },
        )
    }

    @Test
    fun doesNotResurrectARecordTheRemoteDeleted() = runBlocking {
        val snapshot = payload(30, "2026-01-01T00:00:00Z").copy(
            periodRecords = listOf(
                SyncPeriodRecord(start = "2026-01-02", updatedAt = "2026-01-02T00:00:00Z"),
            ),
        )
        val remote = payload(30, "2026-01-01T00:00:00Z").copy(
            deletedPeriodStarts = mapOf("2026-01-02" to "2026-01-20T00:00:00Z"),
        )
        val synced = EasyBcSharedCodecMergeOrder.merge(snapshot, remote)
        assertTrue(synced.periodRecords.isEmpty())

        // The user changed something unrelated, so local still carries the row
        // the remote tombstoned. The newer tombstone must still win.
        val gateway = FakeGateway(snapshot.copy(planner = payload(35, "2026-01-21T00:00:00Z").planner))
        gateway.applyMerged(synced)

        assertTrue(gateway.local.periodRecords.isEmpty())
        assertEquals(35, gateway.local.planner.value.ageYears)
    }

    @Test
    fun neverTakesDeviceLocalPreferencesFromTheSyncResult() = runBlocking {
        val local = payload(30, "2026-01-01T00:00:00Z").copy(
            androidPreferences = TimestampedAndroidPreferences(
                value = AndroidPreferences(reminderHour = 7),
                updatedAt = "2026-01-01T00:00:00Z",
            ),
        )
        val synced = payload(30, "2026-01-01T00:00:00Z").copy(
            androidPreferences = TimestampedAndroidPreferences(
                value = AndroidPreferences(reminderHour = 21),
                updatedAt = "2026-06-01T00:00:00Z",
            ),
        )

        val gateway = FakeGateway(local)
        gateway.applyMerged(synced)

        assertEquals(7, gateway.local.androidPreferences?.value?.reminderHour)
    }

    /** The controller's merge direction: local in, remote wins ties. */
    private object EasyBcSharedCodecMergeOrder {
        fun merge(local: SyncPayloadV1, remote: SyncPayloadV1): SyncPayloadV1 =
            EasyBcSharedCodec.merge(local, remote)
    }

    /**
     * sync-kit's apply guard, verbatim: merging the merged value into what
     * apply returned must add nothing (SharedBackupController.commitMerged in
     * 0.4.1). Equality is deliberately not the check — folding in newer local
     * edits is expected — but dropping part of the merge raises STATE.
     */
    private fun subsumes(merged: SyncPayloadV1, committed: SyncPayloadV1): Boolean =
        EasyBcSharedCodec.fingerprint(EasyBcSharedCodec.merge(merged, committed)) ==
            EasyBcSharedCodec.fingerprint(committed)

    private class FakeGateway(var local: SyncPayloadV1) : SyncPayloadGateway {
        override suspend fun localPayload(): SyncPayloadV1 = local

        override suspend fun apply(payload: SyncPayloadV1) {
            local = payload
        }

        override suspend fun rememberSync(fileId: String, syncedAt: String) = Unit

        override suspend fun forgetSync() = Unit
    }

    private fun payload(age: Int, updatedAt: String) = SyncPayloadV1(
        exportedAt = updatedAt,
        planner = TimestampedPlanner(SyncPlannerOptions(ageYears = age), updatedAt),
    )

    @Test
    fun committedValueSubsumesTheMergeWhenALocalEditSurvives() = runBlocking {
        val snapshot = payload(30, "2026-01-01T00:00:00Z")
        val remote = payload(31, "2026-01-05T00:00:00Z").copy(
            periodRecords = listOf(
                SyncPeriodRecord(start = "2026-02-01", updatedAt = "2026-02-01T00:00:00Z"),
            ),
        )
        val merged = EasyBcSharedCodecMergeOrder.merge(snapshot, remote)
        val gateway = FakeGateway(payload(35, "2026-01-09T00:00:00Z"))

        assertTrue(subsumes(merged, gateway.applyMerged(merged)))
    }

    @Test
    fun committedValueSubsumesTheMergeWhenNothingChanged() = runBlocking {
        val snapshot = payload(30, "2026-01-01T00:00:00Z").copy(
            periodRecords = listOf(
                SyncPeriodRecord(start = "2026-01-02", updatedAt = "2026-01-02T00:00:00Z"),
            ),
        )
        val merged = EasyBcSharedCodecMergeOrder.merge(
            snapshot,
            payload(31, "2026-01-05T00:00:00Z"),
        )
        val gateway = FakeGateway(snapshot)

        assertTrue(subsumes(merged, gateway.applyMerged(merged)))
    }

    // The tie case: SyncMerge resolves ties to its first argument, and the guard
    // computes codec.merge(merged, committed) — SyncMerge.merge(committed,
    // merged), ties to committed. Ties must land on the committed side or a
    // correct apply raises a spurious STATE error.
    @Test
    fun committedValueSubsumesTheMergeOnIdenticalTimestamps() = runBlocking {
        val sameTime = "2026-04-01T00:00:00Z"
        val record = SyncPeriodRecord(start = "2026-02-01", updatedAt = sameTime)
        val merged = EasyBcSharedCodecMergeOrder.merge(
            payload(30, sameTime),
            payload(31, sameTime).copy(periodRecords = listOf(record)),
        )
        val gateway = FakeGateway(payload(44, sameTime).copy(periodRecords = listOf(record)))

        assertTrue(subsumes(merged, gateway.applyMerged(merged)))
    }

    // Split profiles sync one dataset per part: apply returns the part's
    // projection of live local, so the guard runs against a projection on both
    // sides. Parts are disjoint, so this must hold for every one of them.
    @Test
    fun eachPartSubsumesItsMergeOnTheSplitProfilePath() = runBlocking {
        val snapshot = payload(30, "2026-01-01T00:00:00Z").copy(
            periodRecords = listOf(
                SyncPeriodRecord(start = "2026-01-02", updatedAt = "2026-01-02T00:00:00Z"),
            ),
        )
        val remote = payload(31, "2026-01-05T00:00:00Z").copy(
            periodRecords = listOf(
                SyncPeriodRecord(start = "2026-02-01", updatedAt = "2026-02-01T00:00:00Z"),
            ),
        )
        val live = payload(35, "2026-01-09T00:00:00Z").copy(
            periodRecords = snapshot.periodRecords + SyncPeriodRecord(
                start = "2026-03-01",
                updatedAt = "2026-03-01T00:00:00Z",
            ),
        )

        for (part in DATASET_PARTS) {
            val mergedPart = EasyBcSharedCodecMergeOrder.merge(
                projectDatasetPart(snapshot, part),
                projectDatasetPart(remote, part),
            )
            val gateway = FakeGateway(live)
            val committed = projectDatasetPart(gateway.applyMerged(mergedPart), part)
            assertTrue("part=$part", subsumes(mergedPart, committed))
        }
    }
}
