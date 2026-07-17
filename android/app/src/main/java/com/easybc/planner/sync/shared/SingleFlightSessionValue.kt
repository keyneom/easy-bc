package com.easybc.planner.sync.shared

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps one sensitive value in process memory and coalesces concurrent loads.
 * Clearing invalidates both the cached value and any load still in flight.
 */
internal class SingleFlightSessionValue<T : Any> {
    private val mutex = Mutex()

    @Volatile
    private var cached: T? = null

    @Volatile
    private var generation: Long = 0

    suspend fun getOrLoad(loader: suspend () -> T): T {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return@withLock it }
            val loadGeneration = generation
            val loaded = loader()
            if (generation == loadGeneration) cached = loaded
            loaded
        }
    }

    fun get(): T? = cached

    @Synchronized
    fun clear() {
        generation += 1
        cached = null
    }
}
