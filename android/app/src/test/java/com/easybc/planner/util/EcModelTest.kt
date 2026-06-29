package com.easybc.planner.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcModelTest {
    @Test
    fun doseMatchesOnlyLatestCalendarCompatibleIncident() {
        val incidents = listOf(
            EcModel.TimedIncident("old", 0, 10, 10L),
            EcModel.TimedIncident("recent", 0, 14, 20L),
        )
        val dose = EcModel.TimedDose(
            id = "dose",
            row = 0,
            day = 14,
            type = EcModel.EcType.LEVONORGESTREL,
            hoursFromAct = 12.0,
        )

        assertEquals("recent", EcModel.matchedIncidentId(dose, incidents))
    }

    @Test
    fun missingContradictoryAndOutOfWindowTimingReceiveNoMatch() {
        val incident = listOf(EcModel.TimedIncident("act", 0, 10, 10L))

        assertNull(
            EcModel.matchedIncidentId(
                EcModel.TimedDose("missing", 0, 10, EcModel.EcType.LEVONORGESTREL, null),
                incident,
            )
        )
        assertNull(
            EcModel.matchedIncidentId(
                EcModel.TimedDose("contradictory", 0, 14, EcModel.EcType.LEVONORGESTREL, 12.0),
                incident,
            )
        )
        assertNull(
            EcModel.matchedIncidentId(
                EcModel.TimedDose("late", 0, 13, EcModel.EcType.LEVONORGESTREL, 73.0),
                incident,
            )
        )
    }
}
