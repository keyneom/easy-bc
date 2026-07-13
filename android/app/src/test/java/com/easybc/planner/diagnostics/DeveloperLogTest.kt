package com.easybc.planner.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperLogTest {
    @Test
    fun formatIsStableAndReadable() {
        val formatted = formatDeveloperLogEntries(
            listOf(
                DeveloperLogEntry(
                    timestamp = "2026-07-12T22:00:00Z",
                    area = "migration",
                    event = "control-read-failed",
                    details = linkedMapOf("datasetId" to "primary", "error" to "not found"),
                ),
            ),
        )

        assertEquals(
            "2026-07-12T22:00:00Z [migration] control-read-failed datasetId=primary error=not found",
            formatted,
        )
    }

    @Test
    fun emptyLogHasUsefulCopy() {
        assertEquals("No diagnostic events recorded.", formatDeveloperLogEntries(emptyList()))
    }
}
