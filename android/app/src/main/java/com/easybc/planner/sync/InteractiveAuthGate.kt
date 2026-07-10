package com.easybc.planner.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single-flight gate for every interactive authentication sequence (Google
 * authorization UI, passkey create/unlock). Two rules (docs/join-flow.md):
 *
 * 1. Interactive auth sequences never overlap — the second caller suspends
 *    until the first finishes, so the user never sees a passkey sheet stack
 *    on top of a Google consent screen.
 * 2. A link-driven flow (join / grant-return / response accept) owns the
 *    auth UI: background auto-sync must wait for it via
 *    [awaitNoDeepLinkFlow] instead of racing its own prompt at startup.
 */
object InteractiveAuthGate {
    private val mutex = Mutex()
    private val _deepLinkFlowActive = MutableStateFlow(false)

    /** True while a join/response deep-link flow is in progress. */
    val deepLinkFlowActive: StateFlow<Boolean> = _deepLinkFlowActive

    /** Called by MainActivity the moment a sharing link intent arrives. */
    fun deepLinkFlowStarted() {
        _deepLinkFlowActive.value = true
    }

    /** Called when the flow completes, fails terminally, or its link is cleared. */
    fun deepLinkFlowFinished() {
        _deepLinkFlowActive.value = false
    }

    /**
     * Run an auth-then-operate sequence exclusively. Everything that can show
     * auth UI (authorize + passkey + the dependent sync-kit operation) belongs
     * inside one [run] block so the acquired session stays warm for the
     * operation that needed it.
     */
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Background flows call this before acquiring a token. Times out rather
     * than deferring forever if a flow is abandoned mid-way.
     */
    suspend fun awaitNoDeepLinkFlow(timeoutMs: Long = DEFAULT_DEFER_TIMEOUT_MS) {
        withTimeoutOrNull(timeoutMs) {
            _deepLinkFlowActive.first { active -> !active }
        }
    }

    private const val DEFAULT_DEFER_TIMEOUT_MS = 120_000L
}
