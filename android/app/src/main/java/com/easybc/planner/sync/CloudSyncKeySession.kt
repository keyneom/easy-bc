package com.easybc.planner.sync

/**
 * Process-memory-only cache for the derived cloud content key.
 *
 * The passkey PRF output is never retained. The derived key is copied into
 * this process-local cache, and every discarded byte array is zeroed.
 */
object CloudSyncKeySession {
    private var identity: String? = null
    private var contentKey: ByteArray? = null

    @Synchronized
    fun get(envelope: SyncEnvelopeV1): ByteArray? {
        if (identity != envelope.identity()) {
            clearLocked()
            return null
        }
        return contentKey?.copyOf()
    }

    @Synchronized
    fun remember(envelope: SyncEnvelopeV1, key: ByteArray) {
        clearLocked()
        identity = envelope.identity()
        contentKey = key.copyOf()
    }

    @Synchronized
    fun clear() {
        clearLocked()
    }

    @Synchronized
    internal fun isUnlockedFor(envelope: SyncEnvelopeV1): Boolean =
        identity == envelope.identity() && contentKey != null

    private fun clearLocked() {
        contentKey?.fill(0)
        contentKey = null
        identity = null
    }

    private fun SyncEnvelopeV1.identity(): String =
        "$rpId\n$credentialId\n$kdfSalt"
}
