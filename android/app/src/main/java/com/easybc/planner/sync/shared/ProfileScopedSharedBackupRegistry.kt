package com.easybc.planner.sync.shared

import com.keyneom.synckit.sharing.SharedBackupRegistry
import com.keyneom.synckit.sharing.SharedDatasetRegistryRecord

class ProfileScopedSharedBackupRegistry(
    private val registry: SharedSyncRegistry,
    private val profileKey: String,
) : SharedBackupRegistry {
    override suspend fun get(datasetId: String): SharedDatasetRegistryRecord? {
        val state = registry.load() ?: return null
        val profile = state.profiles.firstOrNull {
            com.easybc.planner.sync.shared.profileKey(it.ownerEmail, it.datasetId) == profileKey
        } ?: return null
        if (profile.datasetId != datasetId) return null
        return profile.toRegistryRecord()
    }

    override suspend fun set(record: SharedDatasetRegistryRecord) {
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = state.profiles.firstOrNull {
            com.easybc.planner.sync.shared.profileKey(it.ownerEmail, it.datasetId) == profileKey
        } ?: error("The active encrypted sync profile is missing.")
        registry.upsertProfile(profile.applyRegistryRecord(record))
    }

    override suspend fun delete(datasetId: String) {
        val state = registry.load() ?: return
        val ownerEmail = profileKey.substringBefore('/')
        registry.save(
            state.copy(
                profiles = state.profiles.filter {
                    !(it.datasetId == datasetId &&
                        it.ownerEmail.equals(ownerEmail, ignoreCase = true))
                },
            ),
        )
    }

    private fun ProfileRecord.toRegistryRecord(): SharedDatasetRegistryRecord =
        SharedDatasetRegistryRecord(
            datasetId = datasetId,
            fileId = fileId,
            trustedOwnerKeyId = trustedOwnerKeyId,
            lastRevisionId = lastRevisionId,
            seenRevisionIds = seenRevisionIds,
            participantPermissionIds = participantPermissionIds,
        )

    private fun ProfileRecord.applyRegistryRecord(
        record: SharedDatasetRegistryRecord,
    ): ProfileRecord = copy(
        fileId = record.fileId ?: fileId,
        trustedOwnerKeyId = record.trustedOwnerKeyId,
        lastRevisionId = record.lastRevisionId ?: lastRevisionId,
        seenRevisionIds = record.seenRevisionIds ?: seenRevisionIds,
        participantPermissionIds = record.participantPermissionIds ?: participantPermissionIds,
    )
}
