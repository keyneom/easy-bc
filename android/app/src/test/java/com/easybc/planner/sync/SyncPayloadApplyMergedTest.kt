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
            val committed = projectDatasetPart(gateway.applyMerged(mergedPart, part), part)
            assertTrue("part=$part", subsumes(mergedPart, committed))
        }
    }

    @Test
    fun keepsBothSameDayEventsAfterEveryPartAcrossRepeatedSyncs() = runBlocking {
        for (events in listOf(listOf(incident, ec), listOf(ec, incident))) {
            val remote = eventPayload(events.take(1))
            val gateway = FakeGateway(eventPayload(events).copy(
                calendarDayLogs = mapOf(eventDate to eventPayload(events).calendarDayLogs.getValue(eventDate).copy(
                    updatedAt = "2026-09-13T10:01:00Z",
                )),
                androidPreferences = TimestampedAndroidPreferences(
                    value = AndroidPreferences(reminderHour = 7), updatedAt = eventTime,
                ),
            ))
            repeat(2) {
                for (part in DATASET_PARTS) {
                    val merged = EasyBcSharedCodec.merge(
                        projectDatasetPart(gateway.local, part), projectDatasetPart(remote, part),
                    )
                    val applied = gateway.applyMerged(merged, part)
                    val day = applied.calendarDayLogs.getValue(eventDate)
                    assertEquals("part=$part", setOf(incident, ec), day.events.toSet())
                    assertEquals(2, day.events.size)
                    assertEquals("C", day.actualAction)
                    assertEquals("dry", day.mucus)
                    assertEquals(7, applied.androidPreferences?.value?.reminderHour)
                    assertTrue("part=$part", subsumes(merged, projectDatasetPart(applied, part)))
                }
            }
        }
    }

    @Test
    fun reAddingEcDuringSyncKeepsTheIncidentAndPublishesTheReplacement() = runBlocking {
        val removedAt = "2026-09-13T10:01:00Z"
        val reAddedAt = "2026-09-13T10:02:00Z"
        val removed = eventPayload(listOf(incident)).let { original ->
            original.copy(calendarDayLogs = mapOf(eventDate to original.calendarDayLogs.getValue(eventDate).copy(
                updatedAt = removedAt,
                deletedDatasetParts = mapOf(DAY_LOG_PART_SENSITIVE to removedAt),
            )))
        }
        val pendingDelete = projectDatasetPart(removed, DAY_LOG_PART_SENSITIVE)
        val replacement = ec.copy(id = "replacement-ec", occurredAt = reAddedAt)
        val reAdded = eventPayload(listOf(incident, replacement)).let { original ->
            original.copy(calendarDayLogs = mapOf(eventDate to original.calendarDayLogs.getValue(eventDate).copy(
                updatedAt = reAddedAt,
            )))
        }
        val gateway = FakeGateway(reAdded)
        gateway.applyMerged(pendingDelete, DAY_LOG_PART_SENSITIVE)
        for (part in DATASET_PARTS) {
            gateway.applyMerged(projectDatasetPart(gateway.local, part), part)
        }
        assertEquals(listOf(incident, replacement), gateway.local.calendarDayLogs.getValue(eventDate).events)
        assertEquals(listOf(replacement), projectDatasetPart(gateway.local, DAY_LOG_PART_SENSITIVE)
            .calendarDayLogs.getValue(eventDate).events)
    }

    @Test
    fun remoteEcDeletionKeepsTheIncidentAndDoesNotResurrectEc() = runBlocking {
        val remote = eventPayload(emptyList()).copy(calendarDayLogs = mapOf(
            eventDate to SyncDayLog(updatedAt = "2026-09-13T10:01:00Z"),
        ))
        val gateway = FakeGateway(eventPayload(listOf(incident, ec)))
        gateway.applyMerged(projectDatasetPart(remote, DAY_LOG_PART_SENSITIVE), DAY_LOG_PART_SENSITIVE)
        val day = gateway.local.calendarDayLogs.getValue(eventDate)
        assertEquals(listOf(incident), day.events)
        assertEquals("2026-09-13T10:01:00Z", day.deletedDatasetParts[DAY_LOG_PART_SENSITIVE])
        gateway.applyMerged(projectDatasetPart(gateway.local, DAY_LOG_PART_SENSITIVE), DAY_LOG_PART_SENSITIVE)
        assertEquals(listOf(incident), gateway.local.calendarDayLogs.getValue(eventDate).events)
    }

    @Test
    fun keepsBothEventsWhenTheDayHasNoActionOrBodySignals() = runBlocking {
        val gateway = FakeGateway(eventPayload(emptyList()).copy(calendarDayLogs = mapOf(
            eventDate to SyncDayLog(events = listOf(incident, ec), updatedAt = eventTime),
        )))
        for (part in DATASET_PARTS) {
            gateway.applyMerged(projectDatasetPart(gateway.local, part), part)
            assertEquals("part=$part", listOf(incident, ec), gateway.local.calendarDayLogs.getValue(eventDate).events)
        }
    }

    private val eventDate = "2026-09-13"
    private val eventTime = "2026-09-13T10:00:00Z"
    private val incident = SyncDayEvent(id = "incident", kind = "condom_broke", occurredAt = eventTime)
    private val ec = SyncDayEvent(
        id = "ec", kind = "plan_b_taken", ecType = "levonorgestrel", occurredAt = eventTime,
    )

    private fun eventPayload(events: List<SyncDayEvent>) = payload(30, eventTime).copy(
        calendarDayLogs = mapOf(eventDate to SyncDayLog(
            actualAction = "C", mucus = "dry", events = events, updatedAt = eventTime,
        )),
    )

    /**
     * Two *independent* properties keep sync-kit's guard satisfied on fields
     * whose timestamps tie, and only losing both raises STATE:
     *
     *   a) [EasyBcSharedCodec.merge] swaps its arguments, so the guard's
     *      codec.merge(merged, committed) resolves ties to `committed` itself.
     *   b) [SyncPayloadGateway.applyMerged] calls SyncMerge.merge(payload, local),
     *      resolving ties toward the synced payload, so a tie field in the
     *      committed value already holds the merged value.
     *
     * In *codec* terms our apply is codec.merge(local, merged) — local-first,
     * the opposite of sync-kit's normative merged-first rule. We are safe
     * despite that, not because of it.
     */
    @Test
    fun applyMergedResolvesTiesTowardTheSyncedPayload() = runBlocking {
        val tied = "2026-04-01T00:00:00Z"
        val gateway = FakeGateway(payload(35, tied))

        gateway.applyMerged(payload(31, tied))

        assertEquals(31, gateway.local.planner.value.ageYears)
    }

    @Test
    fun codecMergeResolvesTiesTowardItsRemoteArgument() {
        val tied = "2026-04-01T00:00:00Z"

        val merged = EasyBcSharedCodec.merge(payload(31, tied), payload(35, tied))

        assertEquals(35, merged.planner.value.ageYears)
    }
}
