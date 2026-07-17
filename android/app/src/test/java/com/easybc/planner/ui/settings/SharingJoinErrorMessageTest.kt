package com.easybc.planner.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SharingJoinErrorMessageTest {
    @Test
    fun `pre 0_2_1 invite explains that the owner must recreate it`() {
        val error = IllegalArgumentException(
            "The sharing link file manifest is not authenticated by its invitation.",
        )

        assertEquals(
            "This invite was created by an older EasyBC release. " +
                "Ask the owner to create and send a new invite link.",
            sharingJoinErrorMessage(error),
        )
    }

    @Test
    fun `unrelated join errors retain their original message`() {
        assertEquals("No access", sharingJoinErrorMessage(IllegalStateException("No access")))
    }
}
