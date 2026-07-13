package com.easybc.planner.sync.shared

import android.content.Context
import android.util.Log
import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.diagnostics.DeveloperLog
import com.easybc.planner.sync.SYNC_RP_ID
import com.easybc.planner.sync.ProfileMetaV1
import com.easybc.planner.sync.SyncPayloadGateway
import com.easybc.planner.sync.SyncPayloadV1
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.sharing.AcceptedDatasetResult
import com.keyneom.synckit.sharing.InviteParticipantInput
import com.keyneom.synckit.sharing.SharedBackupController
import com.keyneom.synckit.sharing.SharedBackupControllerCodec
import com.keyneom.synckit.sharing.SharingCrypto
import com.keyneom.synckit.sharing.SharingCryptoOptions
import com.keyneom.synckit.sharing.SharingDatasetGrantV1
import com.keyneom.synckit.sharing.SharingJoinParamStyle
import com.keyneom.synckit.sharing.SharingJoinParams
import com.keyneom.synckit.sharing.SharingRole
import com.keyneom.synckit.sharing.SharingControlDataset
import com.keyneom.synckit.sharing.SharingControlMemberMetadataV1
import com.keyneom.synckit.sharing.SharingControlMigrationRequirementV1
import com.keyneom.synckit.sharing.SharingControlMigrationTargetV1
import com.keyneom.synckit.sharing.SharingControlMigrationV1
import com.keyneom.synckit.sharing.SharingControlStateV1
import com.keyneom.synckit.sharing.createSharingControlCodec
import com.keyneom.synckit.sharing.VerifySharedBackupOptions
import com.keyneom.synckit.sharing.appendSharingJoinParams
import com.keyneom.synckit.sharing.buildSharingJoinLinkV1
import com.keyneom.synckit.sharing.buildSharingResponseLinkV1
import com.keyneom.synckit.sharing.parseSharingJoinLinkV1
import com.keyneom.synckit.sharing.parseSharingResponseLinkV1
import com.keyneom.synckit.sharing.sharedBackupParticipants
import com.keyneom.synckit.stores.GoogleDriveSharedBackupTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class SharedSyncCoordinator(
    private val context: Context,
    private val db: AppDatabase,
    private val store: SyncPayloadGateway,
) {
    private val registry = SharedSyncRegistry(db)
    private val driveAuth = SharedDriveAuth(context.applicationContext)
    // The sharing identity lives in drive.appdata (authorized by the remembered
    // token) so it follows the Google account across devices.
    private val identityStore = SharingIdentityStore(context.applicationContext) {
        driveAuth.provider().authorize()
    }
    private val pendingInvites = PendingInviteStore(context.applicationContext)
    private val developerLog = DeveloperLog(context.applicationContext)

    suspend fun loadState(): SharedSyncState? = registry.load()

    suspend fun ensureProfileState(): SharedSyncState {
        registry.load()?.let { return it }
        val created = newLocalProfile("My data")
        val key = profileKey(created.ownerEmail, created.datasetId)
        val state = SharedSyncState(
            rpId = SYNC_RP_ID,
            ownerEmail = created.ownerEmail,
            activeProfileKey = key,
            profiles = listOf(created),
        )
        registry.save(state)
        registry.saveLocalPayload(key, store.localPayload())
        return state
    }

    suspend fun isConfigured(): Boolean {
        val state = registry.load() ?: return false
        return state.profiles.any { !it.fileId.isNullOrBlank() }
    }

    suspend fun clearIncompleteSetup() {
        val state = registry.load() ?: return
        val retained = state.profiles.filter {
            isLocalProfile(it) || !it.fileId.isNullOrBlank()
        }
        if (retained.isEmpty()) {
            registry.clear()
            registry.clearCheckpoint()
        } else if (retained.size != state.profiles.size) {
            val activeKey = state.activeProfileKey.takeIf { key ->
                retained.any { profileKey(it.ownerEmail, it.datasetId) == key }
            } ?: profileKey(retained.first().ownerEmail, retained.first().datasetId)
            registry.save(state.copy(activeProfileKey = activeKey, profiles = retained))
        }
    }

    suspend fun setup(accessToken: String): SharedSyncState {
        clearIncompleteSetup()
        rememberAccess(accessToken)
        val ownerEmail = fetchGoogleAccountEmail(accessToken)
        val folderName = easyBcSyncFolderName(ownerEmail)
        val identity = identityStore.getOrCreate()
        val previousState = registry.load()
        val activeBeforeSetup = previousState?.let {
            findProfile(it, it.activeProfileKey)
        }
        val preservedLocalProfiles = previousState?.profiles.orEmpty().filter {
            isLocalProfile(it) &&
                profileKey(it.ownerEmail, it.datasetId) != previousState?.activeProfileKey
        }
        val connectedProfile = ProfileRecord(
            datasetId = PRIMARY_DATASET_ID,
            ownerEmail = ownerEmail,
            folderName = folderName,
            displayName = activeBeforeSetup?.displayName,
            role = SharingRole.OWNER.name.lowercase(),
            trustedOwnerKeyId = identity.publicKey.keyId,
            controlDatasetId = controlDatasetIdFor(PRIMARY_DATASET_ID),
            controlEnrollment = "pending",
            syncMode = "encrypted",
        )
        val provisional = SharedSyncState(
            rpId = SYNC_RP_ID,
            ownerEmail = ownerEmail,
            activeProfileKey = profileKey(ownerEmail, PRIMARY_DATASET_ID),
            profiles = preservedLocalProfiles + connectedProfile,
        )
        return try {
            registry.save(provisional)
            val controller = controllerFor(provisional, connectedProfile)
            val storage = controller.ensureStorage()
            val local = sharedPayload(store.localPayload()).withProfileAvatar(connectedProfile)
            // Adopt an existing primary dataset (interrupted setup, reinstall,
            // reconnecting device) instead of failing with "already exists";
            // create only when the folder has none. A fresh folder gets the
            // multi-file dataset group; a folder that already contains
            // companion datasets (created on web) is adopted as a split
            // profile — every companion is adopted so this device never
            // publishes a full payload into one file.
            val datasets = controller.listDatasets()
            val existing = datasets.firstOrNull { it.datasetId == PRIMARY_DATASET_ID }
            val existingControl = datasets.firstOrNull {
                it.datasetId == connectedProfile.controlDatasetId
            }
            val companionIds = datasets.mapNotNull { entry ->
                partForDatasetId(PRIMARY_DATASET_ID, entry.datasetId)
                    ?.takeIf { it != PART_PLAN }
                    ?.let { entry.datasetId }
            }
            val split = existing == null || companionIds.isNotEmpty()
            val appliedPayload: SyncPayloadV1
            val createdFileId: String
            val createdRevisionId: String
            if (existing != null) {
                try {
                    controller.adoptDataset(PRIMARY_DATASET_ID, requireOwned = true)
                    for (companionId in companionIds) {
                        controller.adoptDataset(companionId, requireOwned = true)
                    }
                    if (existingControl != null) {
                        controller.adoptDataset(existingControl.datasetId, requireOwned = true)
                    }
                } catch (error: Exception) {
                    throw IllegalArgumentException(
                        "An encrypted sync dataset already exists in your Drive folder, " +
                            "but this device cannot unlock it. Use Reset encrypted sync to " +
                            "replace it with this device's data.",
                        error,
                    )
                }
                if (companionIds.isNotEmpty()) {
                    // A web-created split group: create any companion that an
                    // interrupted creation left missing, then group-sync.
                    for (part in DATASET_PARTS) {
                        if (part == PART_PLAN) continue
                        val id = datasetIdForPart(PRIMARY_DATASET_ID, part)
                        if (datasets.none { it.datasetId == id }) {
                            controller.createDataset(id, projectDatasetPart(local, part))
                        }
                    }
                    val grouped = syncProfileDatasetGroup(
                        controller,
                        connectedProfile.copy(datasetGrants = OWNER_DATASET_GRANTS),
                        local,
                        loadOnly = false,
                    )
                    appliedPayload = grouped.payload
                    createdFileId = grouped.fileId
                    createdRevisionId = grouped.revisionId
                } else {
                    val synced = controller.syncDataset(PRIMARY_DATASET_ID, local)
                    appliedPayload = synced.value
                    createdFileId = synced.fileId
                    createdRevisionId = synced.revisionId
                }
            } else {
                val (fileId, revisionId) = createProfileDatasetGroup(
                    controller,
                    PRIMARY_DATASET_ID,
                    local,
                )
                appliedPayload = local
                createdFileId = fileId
                createdRevisionId = revisionId
            }
            if (existingControl == null && existing == null) {
                val currentState = registry.load() ?: provisional
                val currentProfile = findProfile(
                    currentState,
                    profileKey(ownerEmail, PRIMARY_DATASET_ID),
                ) ?: connectedProfile
                controlDatasetFor(currentState, currentProfile).create(
                    SharingControlMemberMetadataV1(email = ownerEmail),
                )
            }
            // Companion registry records were persisted onto the provisional
            // profile during the operations above — carry them forward.
            val persistedProfile = registry.load()?.let {
                findProfile(it, profileKey(ownerEmail, PRIMARY_DATASET_ID))
            }
            val profile = ProfileRecord(
                datasetId = PRIMARY_DATASET_ID,
                ownerEmail = ownerEmail,
                folderName = folderName,
                role = SharingRole.OWNER.name.lowercase(),
                trustedOwnerKeyId = identity.publicKey.keyId,
                appFolderId = storage.appFolderId,
                fileId = createdFileId,
                lastRevisionId = createdRevisionId,
                lastSyncedAt = java.time.Instant.now().toString(),
                displayName = connectedProfile.displayName,
                avatarWebp = appliedPayload.profileMeta?.avatarWebp ?: connectedProfile.avatarWebp,
                avatarUpdatedAt = appliedPayload.profileMeta?.updatedAt
                    ?: connectedProfile.avatarUpdatedAt,
                controlDatasetId = connectedProfile.controlDatasetId,
                controlEnrollment = if (existingControl != null || existing == null) {
                    "enrolled"
                } else {
                    "pending"
                },
                syncMode = "encrypted",
                datasetGrants = if (split) OWNER_DATASET_GRANTS else null,
                datasetRecords = persistedProfile?.datasetRecords,
            )
            val state = provisional.copy(
                activeProfileKey = profileKey(ownerEmail, PRIMARY_DATASET_ID),
                profiles = preservedLocalProfiles + profile,
            )
            registry.save(state)
            if (activeBeforeSetup != null && isLocalProfile(activeBeforeSetup)) {
                registry.deleteLocalPayload(previousState!!.activeProfileKey)
            }
            store.apply(appliedPayload.withLocalAndroidPreferences(store.localPayload()))
            store.rememberSync(createdFileId, profile.lastSyncedAt!!)
            SharingSyncScheduler.schedule(context.applicationContext, driveAuth.tokenExpiresAt())
            state
        } catch (error: Exception) {
            if (previousState == null) {
                registry.clear()
                registry.clearCheckpoint()
            } else {
                registry.save(previousState)
            }
            throw error
        }
    }

    suspend fun sync(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        var state = registry.load() ?: error("Shared sync is not configured on this device.")
        var profile = registry.activeProfile(state)
        require(!isLocalProfile(profile)) {
            "This profile is local only. Connect encrypted sync before syncing it."
        }
        var controller = controllerFor(state, profile)
        // An open hard-cutover this device hasn't followed yet: adopt/rebase
        // (owner or already-acked participant) or freeze until acknowledged.
        var freeze = false
        val reconciled = reconcileOpenMigration(controller, state, profile)
        if (reconciled.rebased) {
            state = registry.load() ?: state
            profile = registry.activeProfile(state)
            controller = controllerFor(state, profile)
        } else {
            profile = reconciled.profile
            freeze = reconciled.freeze
        }
        val local = sharedPayload(store.localPayload()).withProfileAvatar(profile)
        val result = syncProfileDatasetGroup(
            controller,
            profile,
            local,
            loadOnly = freeze || shouldLoadRemoteBeforePublish(profile),
        )
        store.apply(result.payload.withLocalAndroidPreferences(store.localPayload()))
        val syncedAt = java.time.Instant.now().toString()
        val nextProfile = refreshedProfile(profile).withAvatarFrom(result.payload).copy(
            fileId = result.fileId,
            lastRevisionId = result.revisionId,
            lastSyncedAt = syncedAt,
            needsInitialLoad = false,
        )
        val next = registry.upsertProfile(nextProfile)
        store.rememberSync(result.fileId, syncedAt)
        SharingSyncScheduler.schedule(context.applicationContext, driveAuth.tokenExpiresAt())
        return refreshControlEnrollment(next, nextProfile)
    }

    suspend fun invite(
        accessToken: String,
        emailAddress: String,
        role: String,
    ): String {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(canAdministerRole(profile.role)) { "Only owners/admins can invite participants." }
        val sharingRole = sharingRoleFromString(role)
        require(sharingRole != SharingRole.OWNER) { "Cannot invite as owner." }
        val controller = controllerFor(state, profile)
        val invited = controller.inviteParticipant(
            InviteParticipantInput(
                emailAddress = emailAddress.trim(),
                requestedGrants = requestedGrantsWithControl(
                    profile,
                    listOf(SharingDatasetGrantV1(profile.datasetId, sharingRole)),
                ),
                joinLandingUrl = EASY_BC_JOIN_LANDING_URL,
                appDisplayName = "EasyBC",
            ),
        )
        return appendSharingJoinParams(
            EASY_BC_JOIN_LANDING_URL,
            SharingJoinParams(
                appFolderId = invited.invitation.appFolderId,
                exchangeId = invited.invitation.exchangeId,
            ),
            SharingJoinParamStyle.SYNC_KIT,
        ) + "&owner=${enc(state.ownerEmail)}&invitation=${enc(invited.invitationFileId)}"
    }

    /**
     * Link-carried invite: per-email shares the dataset file(s) and returns a
     * join link carrying the signed invitation + file list. The invitation is
     * persisted (by exchange id) so this device can accept the response link.
     */
    suspend fun inviteForLink(
        accessToken: String,
        emailAddress: String,
        role: String,
        /**
         * Split profiles: which dataset parts to share at which role (the
         * invite presets, part -> lowercase role). Null = every part at
         * [role]. Ignored for legacy single-file profiles.
         */
        grants: Map<String, String>? = null,
    ): String {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(canAdministerRole(profile.role)) { "Only owners/admins can invite participants." }
        val sharingRole = sharingRoleFromString(role)
        require(sharingRole != SharingRole.OWNER) { "Cannot invite as owner." }
        val dataGrants = if (isSplitProfile(profile)) {
            requestedGrantsFromDatasetGrants(
                profile.datasetId,
                grants ?: DATASET_PARTS.associateWith { role.lowercase() },
            )
        } else {
            listOf(SharingDatasetGrantV1(profile.datasetId, sharingRole))
        }
        val requestedGrants = requestedGrantsWithControl(profile, dataGrants)
        require(requestedGrants.isNotEmpty()) { "Choose at least one dataset to share." }
        val controller = controllerFor(state, profile)
        val invited = controller.inviteParticipantForLink(
            emailAddress = emailAddress.trim(),
            requestedGrants = requestedGrants,
        )
        pendingInvites.save(
            invited.invitation,
            emailAddress.trim(),
            profileKey(profile.ownerEmail, profile.datasetId),
        )
        return buildSharingJoinLinkV1(
            EASY_BC_JOIN_LANDING_URL,
            invited.invitation,
            invited.files,
        ) + "&owner=${enc(profile.ownerEmail)}"
    }

    /**
     * Link-carried join: verifies the invitation from the join link, records the
     * joined profile, produces a signed key response, and returns a response link
     * to send back to the owner. Reading the dataset later requires granting the
     * shared file(s) via the browser Picker (drive.file has no native Android UI).
     */
    suspend fun joinFromLink(accessToken: String, joinLinkUrl: String): String {
        rememberAccess(accessToken)
        val parsed = parseSharingJoinLinkV1(joinLinkUrl)
            ?: throw IllegalArgumentException("That join link is missing its invitation details.")
        val localBeforeJoin = sharedPayload(store.localPayload())
        var existing = registry.load()
        ensureDatasetFilesVisible(accessToken, parsed.files.map { it.fileId })
        val ownerEmail = android.net.Uri.parse(joinLinkUrl).getQueryParameter("owner")
            ?: fetchGoogleAccountEmail(accessToken)
        val selfEmail = fetchGoogleAccountEmail(accessToken)
        if (existing == null && hasMeaningfulSharedData(localBeforeJoin)) {
            existing = setup(accessToken)
        }
        val controlGrant = parsed.invitation.requestedGrants.firstOrNull {
            it.datasetId.endsWith(CONTROL_DATASET_SUFFIX)
        }
        val appGrants = parsed.invitation.requestedGrants.filter {
            it.datasetId != controlGrant?.datasetId
        }
        val grant = appGrants.firstOrNull()
            ?: error("Invitation has no dataset grants.")
        // A split share grants a subset of the profile's dataset files; the
        // profile is keyed by the base dataset id and remembers per-part
        // roles so sync and the UI know exactly what this device can see.
        val parsedGrants = grantsFromRequestedGrants(appGrants)
        val joinProfile = ProfileRecord(
            datasetId = parsedGrants.baseDatasetId,
            ownerEmail = ownerEmail,
            folderName = easyBcSyncFolderName(ownerEmail),
            role = if (parsedGrants.split) {
                highestGrantedRole(parsedGrants.grants)
            } else {
                grant.role.name.lowercase()
            },
            trustedOwnerKeyId = parsed.invitation.trustedOwnerKeyId,
            appFolderId = parsed.invitation.appFolderId,
            needsInitialLoad = true,
            datasetGrants = if (parsedGrants.split) parsedGrants.grants else null,
            controlDatasetId = controlGrant?.datasetId,
            controlEnrollment = if (controlGrant == null) "none" else "pending",
        )
        val profileKeyValue = profileKey(joinProfile.ownerEmail, joinProfile.datasetId)
        existing?.let {
            val active = registry.activeProfile(it)
            if (isLocalProfile(active)) {
                registry.saveLocalPayload(it.activeProfileKey, store.localPayload())
            } else if (
                !active.fileId.isNullOrBlank() &&
                canPublishRole(active.role) &&
                !active.needsInitialLoad
            ) {
                existing = sync(accessToken)
            }
        }
        val state = if (existing == null) {
            SharedSyncState(
                rpId = SYNC_RP_ID,
                ownerEmail = selfEmail,
                activeProfileKey = profileKeyValue,
                profiles = listOf(joinProfile),
                selectedAppFolderId = parsed.invitation.appFolderId,
            )
        } else {
            existing.copy(
                selectedAppFolderId = parsed.invitation.appFolderId,
                activeProfileKey = profileKeyValue,
                profiles = existing.profiles.filter {
                    profileKey(it.ownerEmail, it.datasetId) != profileKeyValue
                } + joinProfile,
            )
        }
        registry.save(state)
        return try {
            val controller = controllerFor(state, joinProfile)
            val response = controller.submitKeyResponseFromInvitation(parsed.invitation, parsed.files)
            store.apply(
                emptySharedPayload().withLocalAndroidPreferences(store.localPayload()),
            )
            SharingSyncScheduler.schedule(context.applicationContext, driveAuth.tokenExpiresAt())
            buildSharingResponseLinkV1(EASY_BC_JOIN_LANDING_URL, response)
        } catch (error: Exception) {
            if (existing == null) {
                registry.clear()
                registry.clearCheckpoint()
            } else {
                registry.save(existing!!)
            }
            throw error
        }
    }

    /**
     * Owner side: accepts a recipient's response link, adding them to the dataset
     * key grants and per-email sharing the dataset file(s).
     */
    suspend fun acceptResponseFromLink(accessToken: String, responseLinkUrl: String) {
        rememberAccess(accessToken)
        val parsed = parseSharingResponseLinkV1(responseLinkUrl)
            ?: throw IllegalArgumentException("That response link is not valid.")
        val pending = pendingInvites.load(parsed.response.exchangeId)
            ?: throw IllegalArgumentException(
                "No pending invitation matches this response. Send a fresh invite link.",
            )
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        // The first grant may be a companion file of a split profile; the
        // profile record is keyed by the base dataset id.
        val datasetId = baseDatasetIdOf(
            pending.invitation.requestedGrants.firstOrNull {
                !it.datasetId.endsWith(CONTROL_DATASET_SUFFIX)
            }?.datasetId ?: PRIMARY_DATASET_ID,
        )
        val profile = pending.profileKey
            ?.let { findProfile(state, it) }
            ?: state.profiles.firstOrNull {
                it.datasetId == datasetId &&
                    it.trustedOwnerKeyId == pending.invitation.trustedOwnerKeyId &&
                    canAdministerRole(it.role)
            }
            ?: throw IllegalArgumentException(
                "The profile that created this invitation is no longer available.",
            )
        val controller = controllerFor(state, profile)
        val results = controller.acceptKeyResponseFromPayload(
            pending.invitation,
            parsed.response,
            pending.recipientEmail,
        )
        requireAllAccepted(results)
        val refreshed = registry.load() ?: state
        val acceptedProfile = findProfile(
            refreshed,
            profileKey(profile.ownerEmail, profile.datasetId),
        ) ?: profile
        val updatedProfile = acceptedProfile.copy(
            participantEmails = acceptedProfile.participantEmails.orEmpty() +
                (parsed.response.keyId to pending.recipientEmail),
        )
        val updatedState = registry.upsertProfile(updatedProfile)
        synchronizeControlMembers(
            updatedState,
            refreshedProfile(updatedProfile),
            parsed.response.keyId,
            pending.recipientEmail,
        )
        pendingInvites.delete(parsed.response.exchangeId)
    }

    suspend fun join(
        accessToken: String,
        invitationFileId: String,
        ownerFolderId: String,
        ownerEmail: String,
    ): SharedSyncState {
        rememberAccess(accessToken)
        val selfEmail = fetchGoogleAccountEmail(accessToken)
        val folderName = easyBcSyncFolderName(ownerEmail)
        val transport = EasyBcSharedTransport.forProfile(
            SharedSyncState(
                rpId = SYNC_RP_ID,
                ownerEmail = ownerEmail,
                activeProfileKey = profileKey(ownerEmail, PRIMARY_DATASET_ID),
                profiles = emptyList(),
                selectedAppFolderId = ownerFolderId,
            ),
            ProfileRecord(
                datasetId = PRIMARY_DATASET_ID,
                ownerEmail = ownerEmail,
                folderName = folderName,
                role = SharingRole.VIEWER.name.lowercase(),
                trustedOwnerKeyId = "",
                appFolderId = ownerFolderId,
            ),
            driveAuth,
        )
        // With drive.file, another account's shares are invisible (Drive
        // answers 404) until the user grants the folder through the Google
        // Picker — the app hands off to the web app's picker for that.
        val invitation = try {
            transport.readInvitation(invitationFileId)
        } catch (error: Exception) {
            if (com.easybc.planner.sync.CloudSyncCoordinator.isNotFound(error)) {
                val probe = probeDriveVisibility(accessToken, ownerFolderId, invitationFileId)
                android.util.Log.w("EasyBcSync", "join visibility probe -> $probe")
                throw IllegalArgumentException(
                    "EasyBC can't see the shared folder yet ($probe). Tap " +
                        "\"Grant folder access\" to allow it in your browser (sign in " +
                        "with this same Google account), then try joining again.",
                    error,
                )
            }
            throw error
        }
        val controlGrant = invitation.requestedGrants.firstOrNull {
            it.datasetId.endsWith(CONTROL_DATASET_SUFFIX)
        }
        val appGrants = invitation.requestedGrants.filter {
            it.datasetId != controlGrant?.datasetId
        }
        val grant = appGrants.firstOrNull()
            ?: error("Invitation has no dataset grants.")
        val parsedGrants = grantsFromRequestedGrants(appGrants)
        val joinProfile = ProfileRecord(
            datasetId = parsedGrants.baseDatasetId,
            ownerEmail = ownerEmail,
            folderName = folderName,
            role = if (parsedGrants.split) {
                highestGrantedRole(parsedGrants.grants)
            } else {
                grant.role.name.lowercase()
            },
            trustedOwnerKeyId = invitation.trustedOwnerKeyId,
            appFolderId = ownerFolderId,
            needsInitialLoad = true,
            datasetGrants = if (parsedGrants.split) parsedGrants.grants else null,
            controlDatasetId = controlGrant?.datasetId,
            controlEnrollment = if (controlGrant == null) "none" else "pending",
        )
        val profileKeyValue = profileKey(joinProfile.ownerEmail, joinProfile.datasetId)
        val localBeforeJoin = sharedPayload(store.localPayload())
        var existing = registry.load()
        if (existing == null && hasMeaningfulSharedData(localBeforeJoin)) {
            existing = setup(accessToken)
        }
        existing?.let {
            val active = registry.activeProfile(it)
            if (isLocalProfile(active)) {
                registry.saveLocalPayload(it.activeProfileKey, store.localPayload())
            } else if (
                !active.fileId.isNullOrBlank() &&
                canPublishRole(active.role) &&
                !active.needsInitialLoad
            ) {
                existing = sync(accessToken)
            }
        }
        val state = if (existing == null) {
            SharedSyncState(
                rpId = SYNC_RP_ID,
                ownerEmail = selfEmail,
                activeProfileKey = profileKeyValue,
                profiles = listOf(joinProfile),
                selectedAppFolderId = ownerFolderId,
            )
        } else {
            existing.copy(
                selectedAppFolderId = ownerFolderId,
                activeProfileKey = profileKeyValue,
                profiles = existing.profiles.filter {
                    profileKey(it.ownerEmail, it.datasetId) != profileKeyValue
                } + joinProfile,
            )
        }
        registry.save(state)
        return try {
            val controller = controllerFor(state, joinProfile)
            controller.submitKeyResponse(invitationFileId)
            store.apply(emptySharedPayload().withLocalAndroidPreferences(store.localPayload()))
            SharingSyncScheduler.schedule(context.applicationContext, driveAuth.tokenExpiresAt())
            state
        } catch (error: Exception) {
            if (existing == null) {
                registry.clear()
                registry.clearCheckpoint()
            } else {
                registry.save(existing!!)
            }
            throw error
        }
    }

    suspend fun acceptKeyResponse(
        accessToken: String,
        invitationFileId: String,
        responseFileId: String,
        recipientEmailAddress: String,
    ) {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(canAdministerRole(profile.role)) { "Only owners/admins can accept participants." }
        val controller = controllerFor(state, profile)
        val transport = EasyBcSharedTransport.forProfile(state, profile, driveAuth)
        val invitation = transport.readInvitation(invitationFileId)
        val response = transport.readKeyResponse(
            responseFileId,
            invitation.recipientDrivePermissionId,
        )
        val results = controller.acceptKeyResponse(
            invitation = invitation,
            responseFileId = responseFileId,
            recipientEmailAddress = recipientEmailAddress.trim(),
        )
        requireAllAccepted(results)
        controller.reconcileDrivePermissions(
            datasetId = profile.datasetId,
            participantEmails = mapOf(response.response.keyId to recipientEmailAddress.trim()),
        )
        val refreshed = registry.load() ?: state
        val current = findProfile(
            refreshed,
            profileKey(profile.ownerEmail, profile.datasetId),
        ) ?: profile
        val updatedProfile = current.copy(
            participantEmails = current.participantEmails.orEmpty() +
                (response.response.keyId to recipientEmailAddress.trim()),
        )
        val updatedState = registry.upsertProfile(updatedProfile)
        synchronizeControlMembers(
            updatedState,
            refreshedProfile(updatedProfile),
            response.response.keyId,
            recipientEmailAddress.trim(),
        )
    }

    suspend fun enrollActiveControlDataset(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        var state = registry.load() ?: error("Shared sync is not configured on this device.")
        var profile = registry.activeProfile(state)
        require(!isLocalProfile(profile) && canAdministerRole(profile.role)) {
            "Only an owner or admin can enroll sharing coordination."
        }
        val controlDatasetId = profile.controlDatasetId ?: controlDatasetIdFor(profile.datasetId)
        profile = profile.copy(
            controlDatasetId = controlDatasetId,
            controlEnrollment = "pending",
        )
        state = registry.upsertProfile(profile)
        if (profile.datasetRecords?.get(controlDatasetId)?.fileId == null) {
            controlDatasetFor(state, profile).create(
                SharingControlMemberMetadataV1(email = state.ownerEmail),
            )
        }
        val refreshed = registry.load() ?: state
        val current = findProfile(refreshed, refreshed.activeProfileKey) ?: profile
        val required = requiredProfileMemberKeyIds(refreshed, current)
        val verified = readControlWithLegacyRepair(refreshed, current)
        return registry.upsertProfile(
            current.copy(
                controlDatasetId = controlDatasetId,
                controlEnrollment = if (required.all(verified.members::containsKey)) {
                    "enrolled"
                } else {
                    "pending"
                },
            ),
        )
    }

    /**
     * Upgrade the active owned legacy profile to the per-dataset split
     * layout (docs/sync-kit-multi-file-datasets.md). Hard replace, no
     * dual-writing: the freshest merged payload is split into the four
     * dataset files, the three companions are created first (so an
     * interrupted run is resumable by running the upgrade again), and the
     * old single file is deleted only after its content has a new home.
     * Every file gets a fresh key — merely republishing a plan projection
     * into the old file would leave the full legacy payload readable in its
     * revision history once the plan file is later shared.
     *
     * Restricted to profiles nobody else can access. With participants this
     * must run the control-dataset cutover ceremony (announce → Picker
     * adopt → ack → close) instead, which EasyBC has not shipped yet; the
     * UI routes owners through remove access → upgrade → re-invite in the
     * meantime, which lands on the same end state because participants must
     * re-grant the new files via the Picker either way.
     */
    suspend fun upgradeActiveProfileToSplit(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(!isLocalProfile(profile)) {
            "Turn on encrypted sync for this profile first — the split layout lives in your Drive."
        }
        require(profile.role == "owner") {
            "Only the profile owner can upgrade its sharing layout."
        }
        require(!isSplitProfile(profile)) {
            "This profile already uses per-dataset sharing."
        }
        require(profile.participantEmails.orEmpty().isEmpty()) {
            "Remove everyone's access first. Upgrading creates new files with new keys, " +
                "so the people you share with must be re-invited with per-dataset access afterward."
        }
        val controller = controllerFor(state, profile)
        val local = sharedPayload(store.localPayload()).withProfileAvatar(profile)
        val files = controller.listDatasets()
        val hasBase = files.any { it.datasetId == profile.datasetId }
        // Freshest snapshot: merge local + remote through the normal sync.
        // When the base file is already gone we are resuming an interrupted
        // upgrade and the local store is the source of truth.
        val payload = if (hasBase) {
            controller.syncDataset(profile.datasetId, local).value
        } else {
            local
        }
        for (part in DATASET_PARTS) {
            if (part == PART_PLAN) continue
            val datasetId = datasetIdForPart(profile.datasetId, part)
            if (files.any { it.datasetId == datasetId }) continue
            controller.createDataset(datasetId, projectDatasetPart(payload, part))
        }
        if (hasBase) controller.deleteDataset(profile.datasetId)
        val created = controller.createDataset(
            profile.datasetId,
            projectDatasetPart(payload, PART_PLAN),
        )
        store.apply(payload.withLocalAndroidPreferences(store.localPayload()))
        val syncedAt = java.time.Instant.now().toString()
        val nextProfile = refreshedProfile(profile).withAvatarFrom(payload).copy(
            datasetGrants = OWNER_DATASET_GRANTS,
            fileId = created.fileId,
            lastRevisionId = created.revisionId,
            lastSyncedAt = syncedAt,
            needsInitialLoad = false,
        )
        val next = registry.upsertProfile(nextProfile)
        store.rememberSync(created.fileId, syncedAt)
        return next
    }

    /* ---------- Hard-cutover split migration (docs/sync-kit-multi-file-datasets.md §ceremony) ---------- */

    private fun participantPublicKey(
        participant: com.keyneom.synckit.sharing.SharedBackupParticipantV1,
    ): com.keyneom.synckit.sharing.SharingPublicKeyV1 =
        com.keyneom.synckit.sharing.SharingPublicKeyV1(
            keyId = participant.keyId,
            encryptionAlgorithm = participant.encryptionAlgorithm,
            encryptionPublicKey = participant.encryptionPublicKey,
            signatureAlgorithm = participant.signatureAlgorithm,
            signingPublicKey = participant.signingPublicKey,
        )

    /**
     * Replace a profile record whose base dataset id changed generation. The
     * profile key derives from the dataset id, so the active key moves with
     * it; the control dataset id (and control event continuity) is untouched.
     */
    private suspend fun rebaseProfileRecord(oldKey: String, next: ProfileRecord): SharedSyncState {
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val newKey = profileKey(next.ownerEmail, next.datasetId)
        val rebased = state.copy(
            activeProfileKey = if (state.activeProfileKey == oldKey) newKey else state.activeProfileKey,
            profiles = state.profiles.filter {
                profileKey(it.ownerEmail, it.datasetId) != oldKey
            } + next,
        )
        registry.save(rebased)
        registry.clearCheckpoint()
        return rebased
    }

    /**
     * Owner side, phase 1 (announce). Resumable: every step is an upsert or
     * a skip-if-present, and an already-announced migration is reused. Ends
     * with this device rebased onto the target generation — the owner never
     * writes the retired source again (the structural freeze).
     */
    suspend fun beginSplitMigration(
        accessToken: String,
        grantsByKeyId: Map<String, Map<String, String>>,
    ): SharedSyncState {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(!isLocalProfile(profile)) { "Turn on encrypted sync for this profile first." }
        require(profile.role == "owner") { "Only the profile owner can reorganize its sharing layout." }
        require(!isSplitProfile(profile)) { "This profile already uses per-dataset sharing." }
        val emails = profile.participantEmails.orEmpty()
        require(emails.isNotEmpty()) {
            "This profile has no participants — use the direct upgrade instead."
        }
        val controller = controllerFor(state, profile)

        val participants = controller.getDatasetParticipants(profile.datasetId).participants
            .filter { it.role != SharingRole.OWNER }
        for (participant in participants) {
            require(emails[participant.keyId] != null) {
                "No email is known for key ${participant.keyId.take(10)}… — EasyBC cannot safely update their Drive access."
            }
            val grants = grantsByKeyId[participant.keyId]
            require(grants != null && DATASET_PARTS.any { grants[it] != null }) {
                "Choose access for every person first. To cut someone off entirely, remove their access before upgrading."
            }
        }

        // Final publish of the source — the last write before the freeze.
        val local = sharedPayload(store.localPayload()).withProfileAvatar(profile)
        val payload = controller.syncDataset(profile.datasetId, local).value

        // The control dataset is the coordination channel; make sure it
        // exists and every participant can write to it (acks are signed).
        val controlDatasetId = profile.controlDatasetId ?: controlDatasetIdFor(profile.datasetId)
        var currentProfile = profile
        if (currentProfile.controlDatasetId != controlDatasetId) {
            currentProfile = currentProfile.copy(controlDatasetId = controlDatasetId)
            registry.upsertProfile(currentProfile)
        }
        if (refreshedProfile(currentProfile).datasetRecords?.get(controlDatasetId)?.fileId == null) {
            controlDatasetFor(registry.load()!!, currentProfile).create(
                SharingControlMemberMetadataV1(email = state.ownerEmail),
            )
        }
        for (participant in participants) {
            controller.addDatasetParticipant(
                datasetId = controlDatasetId,
                publicKey = participantPublicKey(participant),
                role = SharingRole.WRITER,
                emailAddress = emails.getValue(participant.keyId),
            )
        }

        // Target generation: reuse an announced migration's targets or an
        // interrupted attempt's files before minting a new generation.
        val control = controlDatasetFor(registry.load()!!, currentProfile)
        val verified = runCatching { readControlWithLegacyRepair(state, currentProfile) }.getOrNull()
        val open = verified?.migrations?.values?.firstOrNull {
            !verified.closedMigrations.contains(it.migrationId) &&
                it.sourceDatasetIds.contains(profile.datasetId)
        }
        var files = controller.listDatasets()
        val datasetIds = files.map { it.datasetId }
        val targetBase = when {
            open != null -> baseDatasetIdOf(open.targets.first().datasetId)
            else -> newerSplitBaseId(profile.datasetId, datasetIds)
                ?: nextSplitBaseId(profile.datasetId, datasetIds)
        }
        for (part in DATASET_PARTS) {
            val datasetId = datasetIdForPart(targetBase, part)
            if (files.any { it.datasetId == datasetId }) continue
            controller.createDataset(datasetId, projectDatasetPart(payload, part))
        }
        files = controller.listDatasets()
        val targets = DATASET_PARTS.map { part ->
            val datasetId = datasetIdForPart(targetBase, part)
            val file = files.firstOrNull { it.datasetId == datasetId }
                ?: error("The $part dataset was not created.")
            datasetId to file.fileId
        }

        // Share each target with its intended recipients — their existing
        // public keys, fresh per-file content keys, no re-invites.
        for (participant in participants) {
            val grants = grantsByKeyId.getValue(participant.keyId)
            for (part in DATASET_PARTS) {
                val role = grants[part] ?: continue
                if (role == "owner") continue
                controller.addDatasetParticipant(
                    datasetId = datasetIdForPart(targetBase, part),
                    publicKey = participantPublicKey(participant),
                    role = sharingRoleFromString(role),
                    emailAddress = emails.getValue(participant.keyId),
                )
            }
        }

        var migrationId = open?.migrationId
        if (migrationId == null) {
            migrationId = java.util.UUID.randomUUID().toString()
            control.announceMigration(
                SharingControlMigrationV1(
                    migrationId = migrationId,
                    sourceDatasetIds = listOf(profile.datasetId),
                    targets = targets.map { (datasetId, fileId) ->
                        SharingControlMigrationTargetV1(datasetId = datasetId, fileId = fileId)
                    },
                    requiredAcks = participants.map { participant ->
                        SharingControlMigrationRequirementV1(
                            keyId = participant.keyId,
                            targetFileIds = DATASET_PARTS.filter { part ->
                                val role = grantsByKeyId.getValue(participant.keyId)[part]
                                role != null && role != "owner"
                            }.map { part ->
                                targets.first { it.first == datasetIdForPart(targetBase, part) }.second
                            },
                        )
                    },
                    mode = "hard-cutover",
                ),
            )
        }

        // Rebase this device onto the target generation; promote the base
        // target's head from datasetRecords to the top-level fields.
        val persisted = refreshedProfile(currentProfile)
        val baseHead = persisted.datasetRecords?.get(targetBase)
        val rebased = persisted.copy(
            datasetId = targetBase,
            datasetGrants = OWNER_DATASET_GRANTS,
            datasetRecords = persisted.datasetRecords.orEmpty() - targetBase,
            fileId = baseHead?.fileId,
            lastRevisionId = baseHead?.lastRevisionId,
            lastSyncedAt = java.time.Instant.now().toString(),
            retiredDatasetId = profile.datasetId,
            openMigrationId = migrationId,
        )
        val next = rebaseProfileRecord(profileKey(profile.ownerEmail, profile.datasetId), rebased)
        store.apply(payload.withLocalAndroidPreferences(store.localPayload()))
        baseHead?.fileId?.let { store.rememberSync(it, rebased.lastSyncedAt!!) }
        return next
    }

    /**
     * Participant side, after the browser Picker grant: adopt and verify
     * every granted target, acknowledge through the control file, and
     * rebase onto the target generation with the roles read from the
     * adopted envelopes.
     */
    suspend fun acknowledgeSplitMigration(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        val pending = profile.pendingMigration
            ?: error("This profile has no migration waiting for you.")
        val controller = controllerFor(state, profile)
        val myKeyId = identityStore.getOrCreate().publicKey.keyId

        val grants = mutableMapOf<String, String>()
        for (target in pending.targets) {
            if (!pending.requiredFileIds.contains(target.fileId)) continue
            val part = partForDatasetId(pending.targetBaseId, target.datasetId) ?: continue
            controller.adoptDataset(target.datasetId)
            val me = controller.getDatasetParticipants(target.datasetId)
                .participants.firstOrNull { it.keyId == myKeyId }
                ?: error(
                    "You have no key for the ${datasetPartLabel(part)} file — ask the owner to re-run the upgrade.",
                )
            if (me.role != SharingRole.OWNER) grants[part] = me.role.name.lowercase()
        }
        require(grants.isNotEmpty()) {
            "None of the reorganized files could be opened yet — grant access in the browser first."
        }

        readControlWithLegacyRepair(state, profile)
        controlDatasetFor(state, profile).acknowledgeMigration(
            migrationId = pending.migrationId,
            openedFileIds = pending.requiredFileIds,
        )

        val persisted = refreshedProfile(profile)
        val baseHead = persisted.datasetRecords?.get(pending.targetBaseId)
        val rebased = persisted.copy(
            datasetId = pending.targetBaseId,
            datasetGrants = grants,
            datasetRecords = persisted.datasetRecords.orEmpty() - pending.targetBaseId,
            fileId = baseHead?.fileId,
            lastRevisionId = baseHead?.lastRevisionId,
            pendingMigration = null,
            needsInitialLoad = true,
        )
        rebaseProfileRecord(profileKey(profile.ownerEmail, profile.datasetId), rebased)

        // Load the new group immediately so the user's data continues.
        val freshState = registry.load()!!
        val fresh = registry.activeProfile(freshState)
        val freshController = controllerFor(freshState, fresh)
        val loaded = syncProfileDatasetGroup(
            freshController,
            fresh,
            sharedPayload(store.localPayload()),
            loadOnly = true,
        )
        store.apply(loaded.payload.withLocalAndroidPreferences(store.localPayload()))
        val syncedAt = java.time.Instant.now().toString()
        val final = refreshedProfile(fresh).copy(
            fileId = loaded.fileId,
            lastRevisionId = loaded.revisionId,
            lastSyncedAt = syncedAt,
            needsInitialLoad = false,
        )
        val next = registry.upsertProfile(final)
        store.rememberSync(loaded.fileId, syncedAt)
        return next
    }

    data class MigrationAckStatus(
        val acknowledged: List<Pair<String, String?>>,
        val pending: List<Pair<String, String?>>,
        val closed: Boolean,
    )

    /** Owner: who has completed the Picker re-selection and acknowledged. */
    suspend fun splitMigrationStatus(accessToken: String): MigrationAckStatus {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        val migrationId = profile.openMigrationId ?: error("This profile has no open migration.")
        readControlWithLegacyRepair(state, profile)
        val status = controlDatasetFor(state, profile).migrationStatus(migrationId)
        val emails = profile.participantEmails.orEmpty()
        return MigrationAckStatus(
            acknowledged = status.acknowledgedKeyIds.map { it to emails[it] },
            pending = status.pendingKeyIds.map { it to emails[it] },
            closed = status.closed,
        )
    }

    /**
     * Owner: close the migration once every required acknowledgement is
     * present and trash (not delete) the retired source file.
     */
    suspend fun closeSplitMigration(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        val migrationId = profile.openMigrationId ?: error("This profile has no open migration.")
        val retired = profile.retiredDatasetId ?: error("This profile has no open migration.")
        require(profile.role == "owner") { "Only the owner can close a migration." }
        val control = controlDatasetFor(state, profile)
        readControlWithLegacyRepair(state, profile)
        val status = control.migrationStatus(migrationId)
        if (!status.closed) {
            require(status.pendingKeyIds.isEmpty()) {
                "Waiting for everyone to reselect the new files — closing now would cut them off."
            }
            control.closeMigration(migrationId = migrationId)
        }
        controllerFor(registry.load()!!, refreshedProfile(profile)).trashDataset(retired)
        val persisted = refreshedProfile(profile)
        return registry.upsertProfile(
            persisted.copy(retiredDatasetId = null, openMigrationId = null),
        )
    }

    private data class MigrationReconcileOutcome(
        val profile: ProfileRecord,
        val freeze: Boolean,
        val rebased: Boolean,
    )

    /**
     * Runs before a legacy profile syncs: detect an open hard-cutover this
     * device hasn't followed. Owners and post-ack participants adopt +
     * rebase immediately; participants who still owe an acknowledgement get
     * a persisted pendingMigration marker and a frozen (load-only) source.
     * Memoized per coordinator instance.
     */
    private val migrationReconcileChecked = mutableSetOf<String>()
    private suspend fun reconcileOpenMigration(
        controller: SharedBackupController<SyncPayloadV1>,
        state: SharedSyncState,
        profile: ProfileRecord,
    ): MigrationReconcileOutcome {
        val none = MigrationReconcileOutcome(profile, freeze = false, rebased = false)
        val controlFileKnown = profile.controlDatasetId != null &&
            profile.datasetRecords?.get(profile.controlDatasetId)?.fileId != null
        val split = isSplitProfile(profile)
        val local = isLocalProfile(profile)
        developerLog.append(
            "migration",
            "reconcile-start",
            mapOf(
                "datasetId" to profile.datasetId,
                "role" to profile.role,
                "split" to split,
                "local" to local,
                "controlDatasetId" to (profile.controlDatasetId ?: "missing"),
                "controlFileKnown" to controlFileKnown,
                "pendingMigration" to (profile.pendingMigration != null),
            ),
        )
        if (split || local || !controlFileKnown) {
            developerLog.append(
                "migration",
                "reconcile-skipped",
                mapOf(
                    "reason" to when {
                        split -> "profile-already-split"
                        local -> "profile-local"
                        profile.controlDatasetId == null -> "control-dataset-id-missing"
                        else -> "control-file-record-missing"
                    },
                ),
            )
            return none
        }
        if (profile.pendingMigration != null) {
            developerLog.append(
                "migration",
                "pending-already-surfaced",
                mapOf("migrationId" to profile.pendingMigration.migrationId),
            )
            return none.copy(freeze = true)
        }
        val memoKey = profileKey(profile.ownerEmail, profile.datasetId)
        if (!migrationReconcileChecked.add(memoKey)) {
            developerLog.append(
                "migration",
                "reconcile-skipped",
                mapOf("reason" to "already-checked-this-session"),
            )
            return none
        }
        val verified = try {
            readControlWithLegacyRepair(state, profile).also { control ->
                developerLog.append(
                    "migration",
                    "control-read-succeeded",
                    mapOf(
                        "migrations" to control.migrations.size,
                        "closedMigrations" to control.closedMigrations.size,
                        "members" to control.members.size,
                    ),
                )
            }
        } catch (error: Exception) {
            developerLog.append(
                "migration",
                "control-read-failed",
                mapOf("error" to error),
            )
            runCatching { logControlSignatureOrderProbe(state, profile) }
                .onFailure { probeError ->
                    developerLog.append(
                        "migration",
                        "signature-order-probe-failed",
                        mapOf("error" to probeError),
                    )
                    Log.w("EasyBCMigration", "Control signature order probe failed", probeError)
                }
            return none
        }
        val open = verified.migrations.values.firstOrNull {
            !verified.closedMigrations.contains(it.migrationId) &&
                it.sourceDatasetIds.contains(profile.datasetId)
        }
        if (open == null) {
            developerLog.append(
                "migration",
                "no-open-migration",
                mapOf(
                    "sourceDatasetId" to profile.datasetId,
                    "knownMigrationIds" to verified.migrations.keys.joinToString(",").ifBlank { "none" },
                ),
            )
            return none
        }
        val myKeyId = identityStore.getOrCreate().publicKey.keyId
        val targetBase = baseDatasetIdOf(open.targets.first().datasetId)
        val myAck = verified.acknowledgements[open.migrationId]?.containsKey(myKeyId) == true
        val myRequirement = open.requiredAcks.firstOrNull { it.keyId == myKeyId }
        developerLog.append(
            "migration",
            "open-migration-found",
            mapOf(
                "migrationId" to open.migrationId,
                "identityKeyPrefix" to myKeyId.take(10),
                "acknowledged" to myAck,
                "requirementFound" to (myRequirement != null),
                "requiredFiles" to (myRequirement?.targetFileIds?.size ?: 0),
                "targets" to open.targets.size,
            ),
        )

        if (profile.role == "owner" || myAck) {
            // Stale device of the owner (or of a participant who already
            // acknowledged elsewhere): adopt what we can decrypt and rebase.
            val grants = mutableMapOf<String, String>()
            for (target in open.targets) {
                val part = partForDatasetId(targetBase, target.datasetId) ?: continue
                try {
                    controller.adoptDataset(target.datasetId, requireOwned = profile.role == "owner")
                    val me = controller.getDatasetParticipants(target.datasetId)
                        .participants.firstOrNull { it.keyId == myKeyId }
                    if (me != null) grants[part] = me.role.name.lowercase()
                } catch (_: Exception) {
                    // A dataset this identity was not granted — structural.
                }
            }
            if (grants.isEmpty()) return none.copy(freeze = true)
            val persisted = refreshedProfile(profile)
            val baseHead = persisted.datasetRecords?.get(targetBase)
            val rebasedProfile = persisted.copy(
                datasetId = targetBase,
                datasetGrants = if (profile.role == "owner") OWNER_DATASET_GRANTS else grants,
                datasetRecords = persisted.datasetRecords.orEmpty() - targetBase,
                fileId = baseHead?.fileId,
                lastRevisionId = baseHead?.lastRevisionId,
                retiredDatasetId = if (profile.role == "owner") profile.datasetId else persisted.retiredDatasetId,
                openMigrationId = if (profile.role == "owner") open.migrationId else persisted.openMigrationId,
                needsInitialLoad = profile.role != "owner",
            )
            rebaseProfileRecord(memoKey, rebasedProfile)
            developerLog.append(
                "migration",
                "profile-rebased",
                mapOf(
                    "migrationId" to open.migrationId,
                    "targetDatasetId" to targetBase,
                    "role" to profile.role,
                ),
            )
            return MigrationReconcileOutcome(rebasedProfile, freeze = false, rebased = true)
        }

        if (myRequirement != null) {
            val marked = refreshedProfile(profile).copy(
                pendingMigration = PendingMigrationRecord(
                    migrationId = open.migrationId,
                    targetBaseId = targetBase,
                    requiredFileIds = myRequirement.targetFileIds,
                    targets = open.targets.map { MigrationTargetRecord(it.datasetId, it.fileId) },
                ),
            )
            registry.upsertProfile(marked)
            developerLog.append(
                "migration",
                "pending-migration-persisted",
                mapOf(
                    "migrationId" to open.migrationId,
                    "targetDatasetId" to targetBase,
                    "requiredFiles" to myRequirement.targetFileIds.size,
                ),
            )
            return MigrationReconcileOutcome(marked, freeze = true, rebased = false)
        }
        developerLog.append(
            "migration",
            "identity-not-required",
            mapOf(
                "migrationId" to open.migrationId,
                "identityKeyPrefix" to myKeyId.take(10),
                "requiredKeyPrefixes" to open.requiredAcks.joinToString(",") { it.keyId.take(10) },
            ),
        )
        return none
    }

    suspend fun listPendingResponses(accessToken: String): List<PendingResponse> {
        rememberAccess(accessToken)
        val state = registry.load() ?: return emptyList()
        val profile = registry.activeProfile(state)
        if (!canAdministerRole(profile.role)) return emptyList()
        val controller = controllerFor(state, profile)
        val invitations = controller.listExchanges(null, "invitation")
            .associate { it.exchangeId to it.fileId }
        return controller.listExchanges(null, "key-response").mapNotNull { response ->
            val invitationFileId = invitations[response.exchangeId] ?: return@mapNotNull null
            PendingResponse(
                responseFileId = response.fileId,
                invitationFileId = invitationFileId,
                exchangeId = response.exchangeId,
            )
        }
    }

    suspend fun listActiveParticipants(accessToken: String): List<ProfileParticipant> {
        rememberAccess(accessToken)
        val state = registry.load() ?: return emptyList()
        val profile = registry.activeProfile(state)
        if (isLocalProfile(profile)) return emptyList()
        // Split profiles: aggregate participants across every granted file,
        // remembering which part each role applies to.
        val files: List<Pair<String, String>> = if (isSplitProfile(profile)) {
            grantedParts(profile).mapNotNull { part ->
                val fileId = if (part == PART_PLAN) {
                    profile.fileId
                } else {
                    profile.datasetRecords
                        ?.get(datasetIdForPart(profile.datasetId, part))
                        ?.fileId
                }
                fileId?.let { part to it }
            }
        } else {
            profile.fileId?.let { listOf(PART_PLAN to it) }.orEmpty()
        }
        if (files.isEmpty()) return emptyList()
        val transport = EasyBcSharedTransport.forProfile(state, profile, driveAuth)
        val currentKeyId = identityStore.getOrCreate().publicKey.keyId
        val accountVerifiedKeyIds = if (
            profile.controlDatasetId != null &&
            profile.datasetRecords?.get(profile.controlDatasetId)?.fileId != null
        ) {
            runCatching {
                readControlWithLegacyRepair(state, profile).members
                    .filterValues { !it.googleSubject.isNullOrBlank() }
                    .keys
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val aggregated = linkedMapOf<String, ProfileParticipant>()
        for ((part, fileId) in files) {
            val stored = transport.readDataset(fileId)
            SharingCrypto.verifySharedBackupEnvelopeV1(
                stored.envelope,
                VerifySharedBackupOptions(trustedOwnerKeyId = profile.trustedOwnerKeyId),
            )
            for (participant in sharedBackupParticipants(stored.envelope)) {
                val role = participant.role.name.lowercase()
                val existing = aggregated[participant.keyId]
                aggregated[participant.keyId] = ProfileParticipant(
                    keyId = participant.keyId,
                    role = existing?.role?.let { highestGrantedRole(mapOf("a" to it, "b" to role)) }
                        ?: role,
                    emailAddress = profile.participantEmails?.get(participant.keyId),
                    isCurrentDevice = participant.keyId == currentKeyId,
                    accountVerified = participant.keyId in accountVerifiedKeyIds,
                    datasetRoles = if (isSplitProfile(profile)) {
                        existing?.datasetRoles.orEmpty() + (part to role)
                    } else {
                        null
                    },
                )
            }
        }
        return aggregated.values.toList()
    }

    suspend fun updateParticipantRole(
        accessToken: String,
        keyId: String,
        emailAddress: String,
        role: String,
    ): List<ProfileParticipant> {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(!isLocalProfile(profile)) { "That profile is local only." }
        require(canAdministerRole(profile.role)) { "Only owners/admins can change participant access." }
        val sharingRole = sharingRoleFromString(role)
        require(sharingRole != SharingRole.OWNER) { "Owner transfer is not supported." }
        val trimmedEmail = emailAddress.trim()
        require(trimmedEmail.isNotEmpty()) {
            "EasyBC needs the participant email to update their Drive access."
        }
        val controller = controllerFor(state, profile)
        // Split profiles: apply the change to every dataset file the
        // participant currently has; a per-part role editor can narrow later.
        var applied = 0
        var lastError: Exception? = null
        for (datasetId in profileDatasetIds(profile)) {
            try {
                controller.setDatasetRole(
                    datasetId = datasetId,
                    keyId = keyId,
                    role = sharingRole,
                    emailAddress = trimmedEmail,
                )
                applied += 1
            } catch (error: Exception) {
                lastError = error
            }
        }
        if (applied == 0) {
            throw lastError
                ?: IllegalStateException("That participant was not found in any shared dataset.")
        }
        registry.upsertProfile(
            refreshedProfile(profile).copy(
                participantEmails = profile.participantEmails.orEmpty() +
                    (keyId to trimmedEmail),
            ),
        )
        return listActiveParticipants(accessToken)
    }

    /**
     * Change one participant's access to a single dataset part of a split
     * profile. `"none"` revokes just that dataset file; `viewer`/`writer`
     * adjust an existing grant. Granting a dataset the participant has never
     * held needs the invite key exchange, so callers only offer view/edit for
     * parts already in the participant's [ProfileParticipant.datasetRoles].
     */
    suspend fun updateParticipantDatasetRole(
        accessToken: String,
        keyId: String,
        emailAddress: String,
        part: String,
        level: String,
    ): List<ProfileParticipant> {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(!isLocalProfile(profile)) { "That profile is local only." }
        require(canAdministerRole(profile.role)) { "Only owners/admins can change participant access." }
        require(isSplitProfile(profile)) {
            "This profile shares everything as one dataset; per-dataset access needs the split."
        }
        val trimmedEmail = emailAddress.trim()
        require(trimmedEmail.isNotEmpty()) {
            "EasyBC needs the participant email to update their Drive access."
        }
        val datasetId = datasetIdForPart(profile.datasetId, part)
        val controller = controllerFor(state, profile)
        if (level == "none") {
            controller.revokeDatasetKey(
                datasetId = datasetId,
                keyId = keyId,
                emailAddress = trimmedEmail,
            )
        } else {
            controller.setDatasetRole(
                datasetId = datasetId,
                keyId = keyId,
                role = sharingRoleFromString(level),
                emailAddress = trimmedEmail,
            )
        }
        registry.upsertProfile(
            refreshedProfile(profile).copy(
                participantEmails = profile.participantEmails.orEmpty() +
                    (keyId to trimmedEmail),
            ),
        )
        return listActiveParticipants(accessToken)
    }

    suspend fun revokeParticipant(
        accessToken: String,
        keyId: String,
        emailAddress: String,
    ): List<ProfileParticipant> {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(!isLocalProfile(profile)) { "That profile is local only." }
        require(canAdministerRole(profile.role)) { "Only owners/admins can remove participants." }
        val trimmedEmail = emailAddress.trim()
        require(trimmedEmail.isNotEmpty()) {
            "EasyBC needs the participant email to remove their Drive access."
        }
        val controller = controllerFor(state, profile)
        // Revoke from every dataset file the participant was granted; each
        // revocation re-keys that file and removes its tracked Drive permission.
        var revoked = 0
        var lastError: Exception? = null
        for (datasetId in profileDatasetIdsIncludingControl(profile)) {
            try {
                controller.revokeDatasetKey(
                    datasetId = datasetId,
                    keyId = keyId,
                    emailAddress = trimmedEmail,
                )
                revoked += 1
            } catch (error: Exception) {
                lastError = error
            }
        }
        if (revoked == 0) {
            throw lastError
                ?: IllegalStateException("That participant was not found in any shared dataset.")
        }
        val refreshed = refreshedProfile(profile)
        val participantEmails = refreshed.participantEmails.orEmpty().filterKeys { it != keyId }
        registry.upsertProfile(refreshed.copy(participantEmails = participantEmails))
        return listActiveParticipants(accessToken)
    }

    suspend fun setActiveProfile(profileKeyValue: String): SharedSyncState =
        registry.setActiveProfile(profileKeyValue)

    suspend fun switchActiveProfile(
        accessToken: String?,
        profileKeyValue: String,
    ): SharedSyncState {
        accessToken?.let(::rememberAccess)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val targetBeforeSwitch = findProfile(state, profileKeyValue)
            ?: error("That profile is not available on this device.")
        if (!isLocalProfile(targetBeforeSwitch)) {
            requireNotNull(accessToken) {
                "Google authorization is required to open an encrypted profile."
            }
        }
        val previousProfileKey = state.activeProfileKey
        val current = registry.activeProfile(state)
        if (isLocalProfile(current)) {
            registry.saveLocalPayload(state.activeProfileKey, store.localPayload())
        } else if (
            !current.fileId.isNullOrBlank() &&
            canPublishRole(current.role) &&
            !current.needsInitialLoad
        ) {
            sync(requireNotNull(accessToken) { "Google authorization is required to switch encrypted profiles." })
        }
        val next = registry.setActiveProfile(profileKeyValue)
        val target = registry.activeProfile(next)
        if (isLocalProfile(target)) {
            val local = registry.loadLocalPayload(profileKeyValue) ?: emptySharedPayload()
            store.apply(local.withLocalAndroidPreferences(store.localPayload()))
            registry.clearCheckpoint()
            return registry.upsertProfile(target.withAvatarFrom(local))
        }
        return try {
            loadActiveProfile(requireNotNull(accessToken))
        } catch (error: Exception) {
            registry.setActiveProfile(previousProfileKey)
            throw error
        }
    }

    suspend fun loadActiveProfile(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(!isLocalProfile(profile)) { "This profile is stored only on this device." }
        val controller = controllerFor(state, profile)
        val loaded = syncProfileDatasetGroup(
            controller,
            profile,
            emptySharedPayload(),
            loadOnly = true,
        )
        store.apply(loaded.payload.withLocalAndroidPreferences(store.localPayload()))
        val syncedAt = java.time.Instant.now().toString()
        val nextProfile = refreshedProfile(profile).withAvatarFrom(loaded.payload).copy(
            fileId = loaded.fileId,
            lastRevisionId = loaded.revisionId,
            lastSyncedAt = syncedAt,
            needsInitialLoad = false,
        )
        store.rememberSync(loaded.fileId, syncedAt)
        val next = registry.upsertProfile(nextProfile)
        return refreshControlEnrollment(next, nextProfile)
    }

    suspend fun createOwnedProfile(accessToken: String, displayName: String): SharedSyncState {
        rememberAccess(accessToken)
        val trimmed = displayName.trim()
        require(trimmed.isNotEmpty()) { "Enter a profile name." }
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val primary = findOwnedPrimaryProfile(state)
            ?: error("Your encrypted sync folder is not ready yet. Merge changes once, then try again.")
        val appFolderId = primary.appFolderId
            ?: error("Your encrypted sync folder is not ready yet. Merge changes once, then try again.")
        val datasetId = uniqueOwnedDatasetId(trimmed, state.ownerEmail, state.profiles)
        val profileKeyValue = profileKey(state.ownerEmail, datasetId)
        // Profile record first — companion registry records land on it — and
        // the controller scoped to the NEW profile's key.
        val newProfile = ProfileRecord(
            datasetId = datasetId,
            ownerEmail = state.ownerEmail,
            folderName = primary.folderName,
            displayName = trimmed,
            role = SharingRole.OWNER.name.lowercase(),
            trustedOwnerKeyId = primary.trustedOwnerKeyId,
            appFolderId = appFolderId,
            controlDatasetId = controlDatasetIdFor(datasetId),
            controlEnrollment = "pending",
            datasetGrants = OWNER_DATASET_GRANTS,
        )
        val provisional = registry.upsertProfile(newProfile)
        val empty = emptySharedPayload()
        val (createdFileId, createdRevisionId) = try {
            val created = createProfileDatasetGroup(
                controllerFor(provisional, newProfile),
                datasetId,
                empty,
            )
            val afterDataCreate = registry.load() ?: provisional
            val controlProfile = findProfile(afterDataCreate, profileKeyValue) ?: newProfile
            controlDatasetFor(afterDataCreate, controlProfile).create(
                SharingControlMemberMetadataV1(email = state.ownerEmail),
            )
            created
        } catch (error: Exception) {
            registry.save(state)
            throw error
        }
        val syncedAt = java.time.Instant.now().toString()
        registry.upsertProfile(
            refreshedProfile(newProfile).copy(
                fileId = createdFileId,
                lastRevisionId = createdRevisionId,
                lastSyncedAt = syncedAt,
                controlEnrollment = "enrolled",
            ),
        )
        registry.setActiveProfile(profileKeyValue)
        store.apply(empty.withLocalAndroidPreferences(store.localPayload()))
        store.rememberSync(createdFileId, syncedAt)
        registry.clearCheckpoint()
        SharingSyncScheduler.schedule(context.applicationContext, driveAuth.tokenExpiresAt())
        return registry.load() ?: error("Shared sync is not configured on this device.")
    }

    suspend fun connectActiveLocalProfile(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("No profile registry is available on this device.")
        val active = registry.activeProfile(state)
        require(isLocalProfile(active)) { "This profile already uses encrypted sync." }
        val primary = findOwnedPrimaryProfile(state) ?: return setup(accessToken)
        val appFolderId = primary.appFolderId
            ?: error("Your encrypted sync folder is not ready yet. Sync once, then try again.")
        val displayName = active.displayName?.trim().orEmpty().ifEmpty { "Profile" }
        val datasetId = uniqueOwnedDatasetId(displayName, state.ownerEmail, state.profiles)
        val local = sharedPayload(store.localPayload()).withProfileAvatar(active)
        // Profile record first (companion registry records land on it),
        // scoped controller second, dataset group last.
        val connected = ProfileRecord(
            datasetId = datasetId,
            ownerEmail = state.ownerEmail,
            folderName = primary.folderName,
            displayName = displayName,
            avatarWebp = active.avatarWebp,
            avatarUpdatedAt = active.avatarUpdatedAt,
            role = SharingRole.OWNER.name.lowercase(),
            trustedOwnerKeyId = primary.trustedOwnerKeyId,
            appFolderId = appFolderId,
            controlDatasetId = controlDatasetIdFor(datasetId),
            controlEnrollment = "pending",
            syncMode = "encrypted",
            datasetGrants = OWNER_DATASET_GRANTS,
        )
        val connectedKey = profileKey(connected.ownerEmail, connected.datasetId)
        val provisional = state.copy(
            activeProfileKey = connectedKey,
            profiles = state.profiles.filter {
                profileKey(it.ownerEmail, it.datasetId) != state.activeProfileKey
            } + connected,
        )
        registry.save(provisional)
        val (createdFileId, createdRevisionId) = try {
            val created = createProfileDatasetGroup(
                controllerFor(provisional, connected),
                datasetId,
                local,
            )
            val afterDataCreate = registry.load() ?: provisional
            val controlProfile = findProfile(afterDataCreate, connectedKey) ?: connected
            controlDatasetFor(afterDataCreate, controlProfile).create(
                SharingControlMemberMetadataV1(email = state.ownerEmail),
            )
            created
        } catch (error: Exception) {
            registry.save(state)
            throw error
        }
        val syncedAt = java.time.Instant.now().toString()
        val next = registry.upsertProfile(
            refreshedProfile(connected).copy(
                fileId = createdFileId,
                lastRevisionId = createdRevisionId,
                lastSyncedAt = syncedAt,
                controlEnrollment = "enrolled",
            ),
        )
        registry.deleteLocalPayload(state.activeProfileKey)
        registry.clearCheckpoint()
        store.apply(local.withLocalAndroidPreferences(store.localPayload()))
        store.rememberSync(createdFileId, syncedAt)
        SharingSyncScheduler.schedule(context.applicationContext, driveAuth.tokenExpiresAt())
        return next
    }

    suspend fun createLocalProfile(accessToken: String?, displayName: String): SharedSyncState {
        val trimmed = displayName.trim()
        require(trimmed.isNotEmpty()) { "Enter a profile name." }
        var state = ensureProfileState()
        val current = registry.activeProfile(state)
        if (isLocalProfile(current)) {
            registry.saveLocalPayload(state.activeProfileKey, store.localPayload())
        } else if (
            !current.fileId.isNullOrBlank() &&
            canPublishRole(current.role) &&
            !current.needsInitialLoad
        ) {
            state = sync(
                requireNotNull(accessToken) {
                    "Google authorization is required before leaving an encrypted profile."
                },
            )
        }
        val profile = newLocalProfile(trimmed)
        val key = profileKey(profile.ownerEmail, profile.datasetId)
        state = state.copy(
            activeProfileKey = key,
            profiles = state.profiles + profile,
        )
        val empty = emptySharedPayload()
        registry.save(state)
        registry.saveLocalPayload(key, empty)
        registry.clearCheckpoint()
        store.apply(empty.withLocalAndroidPreferences(store.localPayload()))
        return state
    }

    suspend fun renameProfile(profileKeyValue: String, displayName: String): SharedSyncState {
        val trimmed = displayName.trim()
        require(trimmed.isNotEmpty()) { "Enter a profile name." }
        val state = registry.load() ?: error("No profile registry is available on this device.")
        val profile = findProfile(state, profileKeyValue)
            ?: error("That profile is not available on this device.")
        return registry.upsertProfile(profile.copy(displayName = trimmed))
    }

    suspend fun updateProfileAvatar(
        profileKeyValue: String,
        avatarWebp: String?,
    ): SharedSyncState {
        val state = registry.load() ?: error("No profile registry is available on this device.")
        val profile = findProfile(state, profileKeyValue)
            ?: error("That profile is not available on this device.")
        return registry.upsertProfile(
            profile.copy(
                avatarWebp = avatarWebp,
                avatarUpdatedAt = java.time.Instant.now().toString(),
            ),
        )
    }

    suspend fun disconnectActiveProfileToLocal(): SharedSyncState {
        val state = registry.load() ?: error("No profile registry is available on this device.")
        val active = registry.activeProfile(state)
        require(!isLocalProfile(active)) { "This profile is already local only." }
        val localProfile = newLocalProfile(active.displayName ?: "Local copy")
        val localKey = profileKey(localProfile.ownerEmail, localProfile.datasetId)
        val next = state.copy(
            activeProfileKey = localKey,
            profiles = state.profiles.filter {
                profileKey(it.ownerEmail, it.datasetId) != state.activeProfileKey
            } + localProfile,
        )
        registry.save(next)
        registry.saveLocalPayload(localKey, store.localPayload())
        registry.clearCheckpoint()
        return next
    }

    suspend fun deleteProfile(
        accessToken: String?,
        profileKeyValue: String,
        deleteEverywhere: Boolean,
    ): SharedSyncState {
        var state = registry.load() ?: error("No profile registry is available on this device.")
        require(state.profiles.size > 1) {
            "Create or join another profile before deleting the only profile."
        }
        val profile = findProfile(state, profileKeyValue)
            ?: error("That profile is not available on this device.")
        if (state.activeProfileKey == profileKeyValue) {
            val fallback = state.profiles.first {
                profileKey(it.ownerEmail, it.datasetId) != profileKeyValue
            }
            state = switchActiveProfile(
                accessToken,
                profileKey(fallback.ownerEmail, fallback.datasetId),
            )
        }
        if (deleteEverywhere) {
            require(!isLocalProfile(profile) && profile.role.equals("owner", true)) {
                "Only the owner can delete an encrypted profile everywhere."
            }
            rememberAccess(
                requireNotNull(accessToken) {
                    "Google authorization is required to delete an encrypted profile."
                },
            )
            // Companions first; the base dataset's registry delete also
            // removes the profile record, so it must go last.
            val controller = controllerFor(state, profile)
            for (datasetId in profileDatasetIdsIncludingControl(profile).reversed()) {
                controller.deleteDataset(datasetId)
            }
        }
        registry.deleteLocalPayload(profileKeyValue)
        return registry.removeProfile(profileKeyValue)
    }

    suspend fun forget() {
        identityStore.clear()
        driveAuth.clear()
        registry.clear()
        registry.clearCheckpoint()
        store.forgetSync()
        SharingSyncScheduler.cancel(context.applicationContext)
    }

    /** Replace only the selected owned encrypted profile with this device's data. */
    suspend fun reset(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        var state = registry.load() ?: error("No profile registry is available on this device.")
        val profile = registry.activeProfile(state)
        require(!isLocalProfile(profile) && profile.role.equals("owner", true)) {
            "Only an owned encrypted profile can be reset."
        }
        val transport = EasyBcSharedTransport.forProfile(state, profile, driveAuth)
        profile.fileId?.let { transport.deleteDataset(it) }
        profile.datasetRecords.orEmpty().values.forEach { companion ->
            companion.fileId?.let { transport.deleteDataset(it) }
        }
        // Reset always recreates in the multi-file layout.
        val cleared = profile.copy(
            fileId = null,
            lastRevisionId = null,
            seenRevisionIds = null,
            participantPermissionIds = emptyMap(),
            participantEmails = emptyMap(),
            lastSyncedAt = null,
            datasetGrants = OWNER_DATASET_GRANTS,
            datasetRecords = null,
            controlDatasetId = profile.controlDatasetId ?: controlDatasetIdFor(profile.datasetId),
            controlEnrollment = "pending",
        )
        state = registry.upsertProfile(cleared)
        val local = sharedPayload(store.localPayload()).withProfileAvatar(cleared)
        val (createdFileId, createdRevisionId) = createProfileDatasetGroup(
            controllerFor(state, cleared),
            profile.datasetId,
            local,
        )
        val afterDataCreate = registry.load() ?: state
        val controlProfile = findProfile(afterDataCreate, state.activeProfileKey) ?: cleared
        controlDatasetFor(afterDataCreate, controlProfile).create(
            SharingControlMemberMetadataV1(email = state.ownerEmail),
        )
        val syncedAt = java.time.Instant.now().toString()
        val restored = refreshedProfile(cleared).copy(
            fileId = createdFileId,
            lastRevisionId = createdRevisionId,
            lastSyncedAt = syncedAt,
            controlEnrollment = "enrolled",
        )
        val next = registry.upsertProfile(restored)
        store.apply(local.withLocalAndroidPreferences(store.localPayload()))
        store.rememberSync(createdFileId, syncedAt)
        SharingSyncScheduler.schedule(context.applicationContext, driveAuth.tokenExpiresAt())
        return next
    }

    suspend fun transportForPolling(): GoogleDriveSharedBackupTransport? {
        val state = registry.load() ?: return null
        val profile = registry.activeProfile(state)
        return EasyBcSharedTransport.forProfile(state, profile, driveAuth)
    }

    private fun rememberAccess(accessToken: String) {
        driveAuth.remember(accessToken)
    }

    /* ---- Multi-file dataset groups (docs/sync-kit-multi-file-datasets.md) ---- */

    private data class GroupResult(
        val payload: SyncPayloadV1,
        val fileId: String,
        val revisionId: String,
    )

    /**
     * Sync or load every dataset file this device is granted and reassemble
     * the app payload from the parts. Legacy single-file profiles pass
     * through to the old behavior. Read-only parts are loaded, never
     * published, and sections without a grant are never projected out —
     * partial access is structural, not advisory.
     */
    /**
     * Detect that another of this owner's devices upgraded a legacy profile
     * to the split layout (companion dataset files exist in the Drive
     * folder) and adopt it here, so this device never publishes a full
     * payload into what is now the plan file. Checked once per coordinator
     * per profile; the explicit upgrade path sets datasetGrants directly and
     * never reaches this. Only the owner runs detection — participants
     * can't list or adopt files they were never granted.
     */
    private val splitLayoutChecked = mutableSetOf<String>()
    private suspend fun maybeAdoptSplitLayout(
        controller: SharedBackupController<SyncPayloadV1>,
        profile: ProfileRecord,
    ): ProfileRecord? {
        if (isSplitProfile(profile) || isLocalProfile(profile) || profile.role != "owner") {
            return null
        }
        if (!splitLayoutChecked.add(profileKey(profile.ownerEmail, profile.datasetId))) {
            return null
        }
        val files = controller.listDatasets()
        val companionParts = DATASET_PARTS.filter { part ->
            part != PART_PLAN &&
                files.any { it.datasetId == datasetIdForPart(profile.datasetId, part) }
        }
        if (companionParts.isEmpty()) return null
        for (part in companionParts) {
            controller.adoptDataset(datasetIdForPart(profile.datasetId, part), requireOwned = true)
        }
        val upgraded = refreshedProfile(profile).copy(datasetGrants = OWNER_DATASET_GRANTS)
        registry.upsertProfile(upgraded)
        return upgraded
    }

    private suspend fun syncProfileDatasetGroup(
        controller: SharedBackupController<SyncPayloadV1>,
        profile: ProfileRecord,
        local: SyncPayloadV1,
        loadOnly: Boolean,
    ): GroupResult {
        if (!isSplitProfile(profile)) {
            val upgraded = maybeAdoptSplitLayout(controller, profile)
            if (upgraded != null) {
                return syncProfileDatasetGroup(controller, upgraded, local, loadOnly)
            }
            val result = if (loadOnly) {
                controller.loadDataset(profile.datasetId)
            } else {
                controller.syncDataset(profile.datasetId, local)
            }
            return GroupResult(result.value, result.fileId, result.revisionId)
        }
        val values = mutableMapOf<String, SyncPayloadV1>()
        var baseFileId: String? = null
        var baseRevisionId: String? = null
        for (part in grantedParts(profile)) {
            val datasetId = datasetIdForPart(profile.datasetId, part)
            val writable = !loadOnly && partIsWritable(profile, part)
            val result = if (writable) {
                controller.syncDataset(datasetId, projectDatasetPart(local, part))
            } else {
                controller.loadDataset(datasetId)
            }
            values[part] = result.value
            if (part == PART_PLAN || baseRevisionId == null) {
                baseFileId = result.fileId
                baseRevisionId = result.revisionId
            }
        }
        return GroupResult(
            payload = combineDatasetParts(values),
            fileId = requireNotNull(baseFileId) {
                "No dataset in this profile is accessible from this device."
            },
            revisionId = requireNotNull(baseRevisionId),
        )
    }

    /**
     * Create the dataset group of a split profile. The profile record must
     * already be saved (companion registry records land on it) and the
     * controller must be scoped to this profile's key.
     */
    private suspend fun createProfileDatasetGroup(
        controller: SharedBackupController<SyncPayloadV1>,
        baseDatasetId: String,
        payload: SyncPayloadV1,
    ): Pair<String, String> {
        val created = controller.createDataset(
            baseDatasetId,
            projectDatasetPart(payload, PART_PLAN),
        )
        for (part in DATASET_PARTS) {
            if (part == PART_PLAN) continue
            controller.createDataset(
                datasetIdForPart(baseDatasetId, part),
                projectDatasetPart(payload, part),
            )
        }
        return created.fileId to created.revisionId
    }

    private suspend fun refreshedProfile(profile: ProfileRecord): ProfileRecord =
        registry.load()
            ?.let { findProfile(it, profileKey(profile.ownerEmail, profile.datasetId)) }
            ?: profile

    private fun requireAllAccepted(results: List<AcceptedDatasetResult>) {
        val failures = results.filter { it.status != "accepted" }
        if (failures.isEmpty()) return
        val detail = failures.joinToString("; ") {
            "${it.datasetId}: ${it.error?.message ?: "failed"}"
        }
        throw IllegalStateException(
            "The recipient was not added to every dataset ($detail). Try accepting again.",
        )
    }

    private suspend fun synchronizeControlMembers(
        state: SharedSyncState,
        profile: ProfileRecord,
        keyId: String,
        email: String,
    ) {
        if (profile.controlDatasetId == null) return
        val control = controlDatasetFor(state, profile)
        readControlWithLegacyRepair(state, profile)
        control.synchronizeMembers(
            mapOf(keyId to SharingControlMemberMetadataV1(email = email)),
        )
        val verified = readControlWithLegacyRepair(state, profile)
        val required = requiredProfileMemberKeyIds(state, profile)
        registry.upsertProfile(
            refreshedProfile(profile).copy(
                controlEnrollment = if (required.all(verified.members::containsKey)) {
                    "enrolled"
                } else {
                    "pending"
                },
            ),
        )
    }

    private suspend fun requiredProfileMemberKeyIds(
        state: SharedSyncState,
        profile: ProfileRecord,
    ): Set<String> {
        val controller = controllerFor(state, profile)
        return profileDatasetIds(profile).flatMap { datasetId ->
            controller.getDatasetParticipants(datasetId).participants.map { it.keyId }
        }.toSet()
    }

    private suspend fun refreshControlEnrollment(
        state: SharedSyncState,
        profile: ProfileRecord,
    ): SharedSyncState {
        val controlDatasetId = profile.controlDatasetId ?: return state
        val current = findProfile(state, profileKey(profile.ownerEmail, profile.datasetId)) ?: profile
        if (current.datasetRecords?.get(controlDatasetId)?.fileId == null) return state
        val enrolled = runCatching {
            val verified = readControlWithLegacyRepair(state, current)
            verified.members.containsKey(identityStore.getOrCreate().publicKey.keyId)
        }.getOrDefault(false)
        return if (enrolled && current.controlEnrollment != "enrolled") {
            registry.upsertProfile(current.copy(controlEnrollment = "enrolled"))
        } else {
            state
        }
    }

    private suspend fun controllerFor(
        state: SharedSyncState,
        profile: ProfileRecord,
    ): SharedBackupController<SyncPayloadV1> {
        val scopeKey = profileKey(profile.ownerEmail, profile.datasetId)
        return SharedBackupController(
            appId = EASY_BC_APP_ID,
            codec = EasyBcSharedCodec,
            codecForDataset = { datasetId ->
                createSharingControlCodec().takeIf { datasetId == profile.controlDatasetId }
            },
            identity = { identityStore.getOrCreate() },
            transport = EasyBcSharedTransport.forProfile(state, profile, driveAuth),
            registry = ProfileScopedSharedBackupRegistry(registry, scopeKey),
            cryptoOptions = SharingCryptoOptions(),
            createAccountBinding = null,
            verifyAccountBinding = null,
            requireAccountBinding = false,
            resolveFork = { _ -> "merge" },
        )
    }

    private fun controlDatasetFor(
        state: SharedSyncState,
        profile: ProfileRecord,
    ): SharingControlDataset {
        val controlDatasetId = profile.controlDatasetId
            ?: error("This profile has no sharing control dataset.")
        // The control profileId must survive hard-cutover rebases (the
        // profile's base dataset id changes generation, the control dataset
        // never does), so it derives from the control id's base — identical
        // to the profile key at creation time, stable forever after.
        val controlProfileId = profileKey(
            profile.ownerEmail,
            if (controlDatasetId.endsWith(CONTROL_DATASET_SUFFIX)) {
                controlDatasetId.removeSuffix(CONTROL_DATASET_SUFFIX)
            } else {
                profile.datasetId
            },
        )
        return SharingControlDataset(
            controller = controlControllerFor(state, profile),
            datasetId = controlDatasetId,
            profileId = controlProfileId,
            identity = { identityStore.getOrCreate() },
        )
    }

    private fun controlControllerFor(
        state: SharedSyncState,
        profile: ProfileRecord,
        codec: SharedBackupControllerCodec<SharingControlStateV1> = createSharingControlCodec(),
    ): SharedBackupController<SharingControlStateV1> {
        val scopeKey = profileKey(profile.ownerEmail, profile.datasetId)
        return SharedBackupController(
            appId = EASY_BC_APP_ID,
            codec = codec,
            identity = { identityStore.getOrCreate() },
            transport = EasyBcSharedTransport.forProfile(state, profile, driveAuth),
            registry = ProfileScopedSharedBackupRegistry(registry, scopeKey),
            cryptoOptions = SharingCryptoOptions(),
            resolveFork = { _ -> "merge" },
        )
    }

    private suspend fun readControlWithLegacyRepair(
        state: SharedSyncState,
        profile: ProfileRecord,
    ): com.keyneom.synckit.sharing.VerifiedSharingControlStateV1 {
        val control = controlDatasetFor(state, profile)
        try {
            return control.read()
        } catch (original: Exception) {
            if (!profile.role.equals("owner", ignoreCase = true)) throw original
            val datasetId = profile.controlDatasetId ?: throw original
            val repairController = controlControllerFor(state, profile, LegacyControlRepairCodec)
            val loaded = repairController.loadDataset(datasetId)
            val raw = LegacyControlRepairCodec.serialize(loaded.value).jsonObject
            val repair = runCatching {
                repairLegacyControlSignature(raw, identityStore.getOrCreate())
            }.getOrNull() ?: throw original
            repairController.syncDataset(datasetId, repair.state)
            developerLog.append(
                "migration",
                "control-signature-repaired",
                mapOf("eventId" to repair.eventId, "repair" to "rc15-target-order"),
            )
            Log.i(
                "EasyBCMigration",
                "control-signature-repaired eventId=${repair.eventId} repair=rc15-target-order",
            )
            val refreshedState = registry.load() ?: state
            val refreshed = findProfile(
                refreshedState,
                profileKey(profile.ownerEmail, profile.datasetId),
            ) ?: profile
            return controlDatasetFor(refreshedState, refreshed).read()
        }
    }

    private suspend fun logControlSignatureOrderProbe(
        state: SharedSyncState,
        profile: ProfileRecord,
    ) {
        val controlDatasetId = requireNotNull(profile.controlDatasetId)
        val loaded = controlControllerFor(state, profile).loadDataset(controlDatasetId)
        val raw = createSharingControlCodec().serialize(loaded.value).jsonObject
        probeControlSignatureOrdering(raw).forEach { result ->
            val details = mapOf(
                "eventId" to result.eventId,
                "type" to result.type,
                "sequence" to result.sequence,
                "match" to (result.match ?: "none"),
                "attempts" to result.attempts,
            )
            developerLog.append("migration", "signature-order-probe", details)
            Log.i(
                "EasyBCMigration",
                "signature-order-probe " + details.entries.joinToString(" ") { (key, value) -> "$key=$value" },
            )
        }
    }

    // Must stay off the main thread: callers run in viewModelScope and raw
    // HttpURLConnection I/O there throws NetworkOnMainThreadException.
    // Drive about.get is authorized by the Drive scopes we already request;
    // the OpenID userinfo endpoint would need an extra email/openid grant.
    private suspend fun fetchGoogleAccountEmail(accessToken: String): String =
        withContext(Dispatchers.IO) {
            val connection = (URL(
                "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)",
            ).openConnection() as HttpURLConnection)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val body = connection.inputStream.bufferedReader().readText()
            val email = SyncKitJson.instance
                .parseToJsonElement(body)
                .jsonObject["user"]
                ?.jsonObject
                ?.get("emailAddress")
                ?.jsonPrimitive
                ?.content
            require(!email.isNullOrBlank()) {
                "Your Google account has no email address for encrypted sync."
            }
            email
        }

    private suspend fun ensureDatasetFilesVisible(
        accessToken: String,
        fileIds: List<String>,
    ) = withContext(Dispatchers.IO) {
        require(fileIds.isNotEmpty()) { "The join link does not list any shared dataset files." }
        for (fileId in fileIds.distinct()) {
            val connection = (URL(
                "https://www.googleapis.com/drive/v3/files/${enc(fileId)}" +
                    "?fields=id,name&supportsAllDrives=true",
            ).openConnection() as HttpURLConnection)
            try {
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalArgumentException(
                        "EasyBC cannot read the shared sync file yet. Tap \"Grant shared file " +
                            "access\", select the *.sync-kit.json file with this same Google " +
                            "account, then tap Join shared profile again.",
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    // Diagnostic parity with sharedSync.ts#probeDriveVisibility: with
    // drive.file, another account's shares can be invisible to our token (404)
    // even after a Picker grant. Probing files.get for both the shared folder
    // and the invitation file inside it distinguishes the hypotheses:
    //   folder 200 + invitation 404 -> folder grant does not cover descendants
    //   folder 404 + invitation 404 -> grant never reached this OAuth client
    //   folder 200 + invitation 403 -> visible but not authorized
    private suspend fun probeDriveVisibility(
        accessToken: String,
        folderId: String,
        invitationFileId: String,
    ): String = withContext(Dispatchers.IO) {
        fun status(id: String): String {
            if (id.isBlank()) return "0(no id)"
            return try {
                val connection = (URL(
                    "https://www.googleapis.com/drive/v3/files/${enc(id)}" +
                        "?fields=id,name,parents&supportsAllDrives=true",
                ).openConnection() as HttpURLConnection)
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
                "$code $body"
            } catch (error: Exception) {
                "error: ${error.message}"
            }
        }
        "folder[$folderId]=${status(folderId)} | invitation[$invitationFileId]=${status(invitationFileId)}"
    }

    private fun sharedPayload(payload: SyncPayloadV1): SyncPayloadV1 =
        payload.copy(androidPreferences = null)

    /** Prefer payload profileMeta; fall back to the ProfileRecord avatar cache. */
    private fun SyncPayloadV1.withProfileAvatar(profile: ProfileRecord): SyncPayloadV1 {
        val cachedAt = profile.avatarUpdatedAt ?: return this
        if (profileMeta != null && profileMeta.updatedAt >= cachedAt) return this
        return copy(profileMeta = ProfileMetaV1(profile.avatarWebp, cachedAt))
    }

    private fun ProfileRecord.withAvatarFrom(payload: SyncPayloadV1): ProfileRecord {
        val meta = payload.profileMeta ?: return this
        return if (meta.avatarWebp == avatarWebp && meta.updatedAt == avatarUpdatedAt) {
            this
        } else {
            copy(avatarWebp = meta.avatarWebp, avatarUpdatedAt = meta.updatedAt)
        }
    }

    private fun newLocalProfile(displayName: String): ProfileRecord =
        ProfileRecord(
            datasetId = "profile",
            ownerEmail = "local-${UUID.randomUUID()}",
            folderName = "",
            displayName = displayName.trim().ifEmpty { "My data" },
            role = SharingRole.OWNER.name.lowercase(),
            trustedOwnerKeyId = "",
            syncMode = "local",
        )

    private fun SyncPayloadV1.withLocalAndroidPreferences(local: SyncPayloadV1): SyncPayloadV1 =
        copy(androidPreferences = local.androidPreferences)

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    data class PendingResponse(
        val responseFileId: String,
        val invitationFileId: String,
        val exchangeId: String,
    )

    data class ProfileParticipant(
        val keyId: String,
        val role: String,
        val emailAddress: String?,
        val isCurrentDevice: Boolean,
        val accountVerified: Boolean = false,
        /** Split profiles: the participant's role per dataset part they can see. */
        val datasetRoles: Map<String, String>? = null,
    )
}
