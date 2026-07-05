package com.easybc.planner.sync.shared

import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.data.db.SyncMetadataEntity
import com.keyneom.synckit.crypto.SyncKitJson
import kotlinx.serialization.encodeToString

class SharedSyncRegistry(private val db: AppDatabase) {
    private val json get() = SyncKitJson.instance

    suspend fun load(): SharedSyncState? {
        val raw = db.syncMetadataDao().get(META_KEY)?.value ?: return null
        return runCatching {
            json.decodeFromString(SharedSyncState.serializer(), raw)
        }.getOrNull()
    }

    suspend fun save(state: SharedSyncState) {
        db.syncMetadataDao().put(
            SyncMetadataEntity(META_KEY, json.encodeToString(SharedSyncState.serializer(), state)),
        )
    }

    suspend fun clear() {
        db.syncMetadataDao().delete(META_KEY)
    }

    suspend fun upsertProfile(profile: ProfileRecord): SharedSyncState {
        val state = load() ?: error("Shared sync is not configured on this device.")
        val key = profileKey(profile.ownerEmail, profile.datasetId)
        val profiles = state.profiles.filter {
            profileKey(it.ownerEmail, it.datasetId) != key
        } + profile
        val next = state.copy(profiles = profiles)
        save(next)
        return next
    }

    suspend fun setActiveProfile(profileKeyValue: String): SharedSyncState {
        val state = load() ?: error("Shared sync is not configured on this device.")
        require(state.profiles.any { profileKey(it.ownerEmail, it.datasetId) == profileKeyValue }) {
            "That encrypted sync profile is not available on this device."
        }
        val next = state.copy(activeProfileKey = profileKeyValue)
        save(next)
        return next
    }

    fun activeProfile(state: SharedSyncState): ProfileRecord =
        state.profiles.firstOrNull {
            profileKey(it.ownerEmail, it.datasetId) == state.activeProfileKey
        } ?: error("The active encrypted sync profile is missing.")

    companion object {
        const val META_KEY = "shared_sync_state"
        const val CHECKPOINT_KEY = "sharing_sync_checkpoint"
    }

    suspend fun loadCheckpoint(): com.keyneom.synckit.sharing.checkpoint.SharingSyncCheckpoint {
        val raw = db.syncMetadataDao().get(CHECKPOINT_KEY)?.value ?: return com.keyneom.synckit.sharing.checkpoint.SharingSyncCheckpoint()
        return runCatching {
            json.decodeFromString(
                com.keyneom.synckit.sharing.checkpoint.SharingSyncCheckpoint.serializer(),
                raw,
            )
        }.getOrDefault(com.keyneom.synckit.sharing.checkpoint.SharingSyncCheckpoint())
    }

    suspend fun saveCheckpoint(checkpoint: com.keyneom.synckit.sharing.checkpoint.SharingSyncCheckpoint) {
        db.syncMetadataDao().put(
            SyncMetadataEntity(
                CHECKPOINT_KEY,
                json.encodeToString(
                    com.keyneom.synckit.sharing.checkpoint.SharingSyncCheckpoint.serializer(),
                    checkpoint,
                ),
            ),
        )
    }

    suspend fun clearCheckpoint() {
        db.syncMetadataDao().delete(CHECKPOINT_KEY)
    }
}
