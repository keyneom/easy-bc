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
        if (profile.datasetId == datasetId) return profile.toRegistryRecord()
        // Companion dataset of a split profile ("<base>.cycle", …).
        val companion = profile.datasetRecords?.get(datasetId) ?: return null
        return SharedDatasetRegistryRecord(
            datasetId = datasetId,
            fileId = companion.fileId,
            trustedOwnerKeyId = profile.trustedOwnerKeyId,
            lastRevisionId = companion.lastRevisionId,
            seenRevisionIds = companion.seenRevisionIds,
            participantPermissionIds = companion.participantPermissionIds,
        )
    }

    override suspend fun set(record: SharedDatasetRegistryRecord) {
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = state.profiles.firstOrNull {
            com.easybc.planner.sync.shared.profileKey(it.ownerEmail, it.datasetId) == profileKey
        } ?: error("The active encrypted sync profile is missing.")
        if (record.datasetId != profile.datasetId) {
            if (partForDatasetId(profile.datasetId, record.datasetId) != null) {
                // Companion dataset state stays inside the scoped profile
                // record — it must never surface as a profile of its own.
                registry.upsertProfile(
                    profile.copy(
                        datasetRecords = profile.datasetRecords.orEmpty() + (
                            record.datasetId to CompanionDatasetRecord(
                                fileId = record.fileId,
                                lastRevisionId = record.lastRevisionId,
                                seenRevisionIds = record.seenRevisionIds,
                                participantPermissionIds = record.participantPermissionIds,
                            )
                            ),
                    ),
                )
                return
            }
            // A different owned dataset created through this scope (a second
            // profile created via the primary controller): record it on ITS
            // OWN profile record — never onto the scoped profile.
            val ownerEmail = profileKey.substringBefore('/')
            val foreign = state.profiles.firstOrNull {
                it.datasetId == record.datasetId &&
                    it.ownerEmail.equals(ownerEmail, ignoreCase = true)
            } ?: ProfileRecord(
                datasetId = record.datasetId,
                ownerEmail = ownerEmail,
                folderName = profile.folderName,
                role = "owner",
                trustedOwnerKeyId = record.trustedOwnerKeyId,
            )
            registry.upsertProfile(foreign.applyRegistryRecord(record))
            return
        }
        registry.upsertProfile(profile.applyRegistryRecord(record))
    }

    override suspend fun delete(datasetId: String) {
        val state = registry.load() ?: return
        val ownerEmail = profileKey.substringBefore('/')
        val scopedDatasetId = profileKey.substringAfter('/')
        if (datasetId != scopedDatasetId) {
            val profile = state.profiles.firstOrNull {
                com.easybc.planner.sync.shared.profileKey(it.ownerEmail, it.datasetId) == profileKey
            }
            if (profile?.datasetRecords?.containsKey(datasetId) == true) {
                registry.upsertProfile(
                    profile.copy(datasetRecords = profile.datasetRecords - datasetId),
                )
                return
            }
        }
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
