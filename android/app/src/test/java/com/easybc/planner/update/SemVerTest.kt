package com.easybc.planner.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    @Test
    fun comparesReleaseVersions() {
        assertTrue(SemVer.isNewer("0.1.26", "0.1.25"))
        assertFalse(SemVer.isNewer("0.1.25", "0.1.25"))
        assertFalse(SemVer.isNewer("0.1.24", "0.1.25"))
    }

    @Test
    fun normalizesLeadingV() {
        assertTrue(SemVer.isNewer("v0.2.0", "0.1.25"))
    }
}
