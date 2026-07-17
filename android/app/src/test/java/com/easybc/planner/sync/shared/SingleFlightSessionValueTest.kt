package com.easybc.planner.sync.shared

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingleFlightSessionValueTest {
    @Test
    fun concurrentCallersShareOneLoad() = runBlocking {
        val session = SingleFlightSessionValue<String>()
        val loads = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val results = List(8) {
            async {
                session.getOrLoad {
                    loads.incrementAndGet()
                    release.await()
                    "unlocked"
                }
            }
        }
        release.complete(Unit)

        assertEquals(List(8) { "unlocked" }, results.awaitAll())
        assertEquals(1, loads.get())
        assertEquals("unlocked", session.get())
    }

    @Test
    fun clearRequiresOneNewLoad() = runBlocking {
        val session = SingleFlightSessionValue<String>()
        val loads = AtomicInteger()

        assertEquals("identity-1", session.getOrLoad { "identity-${loads.incrementAndGet()}" })
        assertEquals("identity-1", session.getOrLoad { "identity-${loads.incrementAndGet()}" })
        session.clear()
        assertNull(session.get())
        assertEquals("identity-2", session.getOrLoad { "identity-${loads.incrementAndGet()}" })
        assertEquals(2, loads.get())
    }
}
