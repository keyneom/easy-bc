package com.easybc.planner.sync.shared

import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.data.db.SyncMetadataEntity
import com.easybc.planner.sync.SyncPayloadV1
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

    suspend fun saveLocalPayload(profileKeyValue: String, payload: SyncPayloadV1) {
        db.syncMetadataDao().put(
            SyncMetadataEntity(
                "$LOCAL_PAYLOAD_PREFIX$profileKeyValue",
                json.encodeToString(SyncPayloadV1.serializer(), payload),
            ),
        )
    }

    suspend fun loadLocalPayload(profileKeyValue: String): SyncPayloadV1? {
        val raw = db.syncMetadataDao().get("$LOCAL_PAYLOAD_PREFIX$profileKeyValue")?.value
            ?: return null
        return runCatching {
            json.decodeFromString(SyncPayloadV1.serializer(), raw)
        }.getOrNull()
    }

    suspend fun deleteLocalPayload(profileKeyValue: String) {
        db.syncMetadataDao().delete("$LOCAL_PAYLOAD_PREFIX$profileKeyValue")
    }

    suspend fun removeProfile(profileKeyValue: String): SharedSyncState {
        val state = load() ?: error("No profile registry is available on this device.")
        val next = state.copy(
            profiles = state.profiles.filter {
                profileKey(it.ownerEmail, it.datasetId) != profileKeyValue
            },
        )
        save(next)
        return next
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
        findProfile(state, state.activeProfileKey)
            ?: error("The active encrypted sync profile is missing.")

    companion object {
        const val META_KEY = "shared_sync_state"
        const val CHECKPOINT_KEY = "sharing_sync_checkpoint"
        private const val LOCAL_PAYLOAD_PREFIX = "local_profile_payload:"
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
