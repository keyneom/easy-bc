package com.easybc.planner.sync.shared

import com.easybc.planner.sync.SyncDayEvent
import com.easybc.planner.sync.SyncDayLog
import com.easybc.planner.sync.SyncPayloadV1
import com.easybc.planner.sync.ProfileMetaV1
import com.easybc.planner.sync.SyncPeriodRecord
import com.easybc.planner.sync.TimestampedBoolean
import com.keyneom.synckit.sharing.SharingDatasetGrantV1
import com.keyneom.synckit.sharing.SharingRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyBcDatasetsTest {

    private fun samplePayload(): SyncPayloadV1 {
        val ecEvent = SyncDayEvent(
            id = "ev-ec",
            kind = "plan_b_taken",
            ecType = "levonorgestrel",
            hoursFromAct = 12.0,
            occurredAt = "2026-07-02T10:00:00.000Z",
        )
        val incident = SyncDayEvent(
            id = "ev-cb",
            kind = "condom_broke",
            occurredAt = "2026-07-02T09:00:00.000Z",
        )
        return emptySharedPayload().copy(
            exportedAt = "2026-07-09T00:00:00.000Z",
            planner = emptySharedPayload().planner.copy(configured = true),
            periodRecords = listOf(SyncPeriodRecord(start = "2026-07-03")),
            deletedPeriodStarts = mapOf("2026-06-01" to "2026-06-02T00:00:00.000Z"),
            calendarDayLogs = mapOf(
                // Mixed day: cycle signals + intimacy action + EC event.
                "2026-07-02" to SyncDayLog(
                    actualAction = "U",
                    notes = "note",
                    mucus = "egg-white",
                    opk = "positive",
                    reconciled = true,
                    events = listOf(incident, ecEvent),
                    updatedAt = "2026-07-02T12:00:00.000Z",
                ),
                "2026-07-04" to SyncDayLog(bbtCelsius = 36.6, updatedAt = "2026-07-04T08:00:00.000Z"),
                "2026-07-05" to SyncDayLog(actualAction = "C", updatedAt = "2026-07-05T08:00:00.000Z"),
            ),
            voluntaryAbstinenceDates = mapOf("2026-07-06" to true),
            voluntaryAbstinenceUpdatedAt = mapOf("2026-07-06" to "2026-07-06T00:00:00.000Z"),
            ecJournal = TimestampedBoolean(value = true, updatedAt = "2026-07-02T10:00:00.000Z"),
            profileMeta = ProfileMetaV1("encoded-photo", "2026-07-02T11:00:00.000Z"),
        )
    }

    @Test
    fun `dataset ids map to parts and back`() {
        assertEquals("primary", datasetIdForPart("primary", PART_PLAN))
        assertEquals("primary.cycle", datasetIdForPart("primary", PART_CYCLE))
        assertEquals(PART_PLAN, partForDatasetId("primary", "primary"))
        assertEquals(PART_SENSITIVE, partForDatasetId("primary", "primary.sensitive"))
        assertNull(partForDatasetId("primary", "other.cycle"))
        assertEquals("daughter", baseDatasetIdOf("daughter.intimacy"))
        assertEquals("daughter", baseDatasetIdOf("daughter"))
    }

    @Test
    fun `grants round-trip through requestedGrants`() {
        val grants = mapOf(PART_CYCLE to "writer", PART_PLAN to "viewer")
        val requested = requestedGrantsFromDatasetGrants("primary", grants)
        assertEquals(
            listOf(
                SharingDatasetGrantV1("primary", SharingRole.VIEWER),
                SharingDatasetGrantV1("primary.cycle", SharingRole.WRITER),
            ),
            requested,
        )
        val parsed = grantsFromRequestedGrants(requested)
        assertEquals("primary", parsed.baseDatasetId)
        assertTrue(parsed.split)
        assertEquals(grants, parsed.grants)
    }

    @Test
    fun `single bare-base grant is a legacy share`() {
        val parsed = grantsFromRequestedGrants(
            listOf(SharingDatasetGrantV1("primary", SharingRole.WRITER)),
        )
        assertFalse(parsed.split)
        assertEquals("primary", parsed.baseDatasetId)
    }

    @Test
    fun `companion-only grant is a split share`() {
        val parsed = grantsFromRequestedGrants(
            listOf(SharingDatasetGrantV1("primary.cycle", SharingRole.VIEWER)),
        )
        assertTrue(parsed.split)
        assertEquals("primary", parsed.baseDatasetId)
        assertEquals(mapOf(PART_CYCLE to "viewer"), parsed.grants)
    }

    @Test
    fun `full payload round-trips through the four parts`() {
        val payload = samplePayload()
        val parts = DATASET_PARTS.associateWith { projectDatasetPart(payload, it) }
        val combined = combineDatasetParts(parts)
        assertEquals(payload.planner, combined.planner)
        assertEquals(payload.profileMeta, combined.profileMeta)
        assertEquals(payload.periodRecords, combined.periodRecords)
        assertEquals(payload.deletedPeriodStarts, combined.deletedPeriodStarts)
        assertEquals(payload.voluntaryAbstinenceDates, combined.voluntaryAbstinenceDates)
        assertEquals(payload.ecJournal, combined.ecJournal)
        assertEquals(payload.calendarDayLogs.keys, combined.calendarDayLogs.keys)
        val mixed = combined.calendarDayLogs.getValue("2026-07-02")
        assertEquals("U", mixed.actualAction)
        assertEquals("egg-white", mixed.mucus)
        assertEquals(setOf("ev-cb", "ev-ec"), mixed.events.map { it.id }.toSet())
        assertEquals("2026-07-02T12:00:00.000Z", mixed.updatedAt)
    }

    @Test
    fun `cycle part carries no intimacy or sensitive data`() {
        val cycle = projectDatasetPart(samplePayload(), PART_CYCLE)
        val log = cycle.calendarDayLogs.getValue("2026-07-02")
        assertEquals("egg-white", log.mucus)
        assertNull(log.actualAction)
        assertNull(log.notes)
        assertTrue(log.events.isEmpty())
        assertNull(cycle.calendarDayLogs["2026-07-05"])
        assertFalse(cycle.ecJournal.value)
        assertTrue(cycle.voluntaryAbstinenceDates.isEmpty())
    }

    @Test
    fun `EC events route to sensitive and incidents to intimacy`() {
        val payload = samplePayload()
        val intimacy = projectDatasetPart(payload, PART_INTIMACY)
        val sensitive = projectDatasetPart(payload, PART_SENSITIVE)
        assertEquals(
            listOf("condom_broke"),
            intimacy.calendarDayLogs.getValue("2026-07-02").events.map { it.kind },
        )
        assertEquals(
            listOf("plan_b_taken"),
            sensitive.calendarDayLogs.getValue("2026-07-02").events.map { it.kind },
        )
        assertTrue(sensitive.ecJournal.value)
        assertFalse(intimacy.ecJournal.value)
    }

    @Test
    fun `combining a partial grant leaves missing sections empty`() {
        val payload = samplePayload()
        val combined = combineDatasetParts(mapOf(PART_CYCLE to projectDatasetPart(payload, PART_CYCLE)))
        assertEquals(1, combined.periodRecords.size)
        assertEquals(false, combined.planner.configured)
        assertFalse(combined.ecJournal.value)
        assertNull(combined.calendarDayLogs.getValue("2026-07-02").actualAction)
    }

    @Test
    fun `whole-day deletion tombstone is preserved in every data part`() {
        val payload = emptySharedPayload().copy(
            calendarDayLogs = mapOf(
                "2026-08-12" to SyncDayLog(updatedAt = "2026-07-13T01:00:00.000Z"),
            ),
        )
        assertTrue(projectDatasetPart(payload, PART_PLAN).calendarDayLogs.isEmpty())
        listOf(PART_CYCLE, PART_INTIMACY, PART_SENSITIVE).forEach { part ->
            assertEquals(
                SyncDayLog(updatedAt = "2026-07-13T01:00:00.000Z"),
                projectDatasetPart(payload, part).calendarDayLogs["2026-08-12"],
            )
        }
    }

    @Test
    fun `targeted part tombstone does not erase another part`() {
        val payload = emptySharedPayload().copy(
            calendarDayLogs = mapOf(
                "2026-08-12" to SyncDayLog(
                    mucus = "egg-white",
                    deletedDatasetParts = mapOf(
                        PART_INTIMACY to "2026-07-13T01:00:00.000Z",
                    ),
                    updatedAt = "2026-07-13T01:00:00.000Z",
                ),
            ),
        )
        val cycle = projectDatasetPart(payload, PART_CYCLE)
        val intimacy = projectDatasetPart(payload, PART_INTIMACY)
        assertEquals("egg-white", cycle.calendarDayLogs.getValue("2026-08-12").mucus)
        assertEquals(
            SyncDayLog(updatedAt = "2026-07-13T01:00:00.000Z"),
            intimacy.calendarDayLogs["2026-08-12"],
        )
        assertNull(projectDatasetPart(payload, PART_SENSITIVE).calendarDayLogs["2026-08-12"])

        val combined = combineDatasetParts(mapOf(PART_CYCLE to cycle, PART_INTIMACY to intimacy))
        assertEquals("egg-white", combined.calendarDayLogs.getValue("2026-08-12").mucus)
        assertEquals(
            mapOf(PART_INTIMACY to "2026-07-13T01:00:00.000Z"),
            combined.calendarDayLogs.getValue("2026-08-12").deletedDatasetParts,
        )
    }

    @Test
    fun `split profile helpers respect grants`() {
        val split = ProfileRecord(
            datasetId = "primary",
            ownerEmail = "owner@example.com",
            folderName = "EasyBC — owner@example.com",
            role = "writer",
            trustedOwnerKeyId = "k",
            datasetGrants = mapOf(PART_CYCLE to "writer", PART_PLAN to "viewer"),
        )
        assertTrue(isSplitProfile(split))
        assertEquals(listOf(PART_PLAN, PART_CYCLE), grantedParts(split))
        assertEquals(listOf(PART_INTIMACY, PART_SENSITIVE), restrictedParts(split))
        assertTrue(partIsWritable(split, PART_CYCLE))
        assertFalse(partIsWritable(split, PART_PLAN))
        assertFalse(partIsWritable(split, PART_SENSITIVE))
        assertEquals(listOf("primary", "primary.cycle"), profileDatasetIds(split))
        val legacy = split.copy(datasetGrants = null)
        assertFalse(isSplitProfile(legacy))
        assertEquals(DATASET_PARTS, grantedParts(legacy))
        assertTrue(restrictedParts(legacy).isEmpty())
    }

    @Test
    fun `ready control dataset is included as a writer grant`() {
        val profile = ProfileRecord(
            datasetId = "primary",
            ownerEmail = "owner@example.com",
            folderName = "EasyBC — owner@example.com",
            role = "owner",
            trustedOwnerKeyId = "owner-key",
            controlDatasetId = "primary.control",
            datasetRecords = mapOf(
                "primary.control" to CompanionDatasetRecord(fileId = "control-file"),
            ),
        )
        val grants = requestedGrantsWithControl(
            profile,
            listOf(SharingDatasetGrantV1("primary", SharingRole.VIEWER)),
        )
        assertEquals(
            listOf(
                SharingDatasetGrantV1("primary", SharingRole.VIEWER),
                SharingDatasetGrantV1("primary.control", SharingRole.WRITER),
            ),
            grants,
        )
        assertEquals(
            1,
            requestedGrantsWithControl(profile.copy(datasetRecords = null), grants.take(1)).size,
        )
    }
}
