package com.easybc.planner.sync.shared

import android.content.Context
import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.sync.SYNC_RP_ID
import com.easybc.planner.sync.SyncPayloadGateway
import com.easybc.planner.sync.SyncPayloadV1
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.sharing.AcceptedDatasetResult
import com.keyneom.synckit.sharing.InviteParticipantInput
import com.keyneom.synckit.sharing.SharedBackupController
import com.keyneom.synckit.sharing.SharingCrypto
import com.keyneom.synckit.sharing.SharingCryptoOptions
import com.keyneom.synckit.sharing.SharingDatasetGrantV1
import com.keyneom.synckit.sharing.SharingJoinParamStyle
import com.keyneom.synckit.sharing.SharingJoinParams
import com.keyneom.synckit.sharing.SharingRole
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
            val local = sharedPayload(store.localPayload())
            // Adopt an existing primary dataset (interrupted setup, reinstall,
            // reconnecting device) instead of failing with "already exists";
            // create only when the folder has none.
            val existing = controller.listDatasets()
                .firstOrNull { it.datasetId == PRIMARY_DATASET_ID }
            val created = if (existing != null) {
                try {
                    controller.adoptDataset(PRIMARY_DATASET_ID, requireOwned = true)
                } catch (error: Exception) {
                    throw IllegalArgumentException(
                        "An encrypted sync dataset already exists in your Drive folder, " +
                            "but this device cannot unlock it. Use Reset encrypted sync to " +
                            "replace it with this device's data.",
                        error,
                    )
                }
                controller.syncDataset(PRIMARY_DATASET_ID, local)
            } else {
                controller.createDataset(PRIMARY_DATASET_ID, local)
            }
            val profile = ProfileRecord(
                datasetId = PRIMARY_DATASET_ID,
                ownerEmail = ownerEmail,
                folderName = folderName,
                role = SharingRole.OWNER.name.lowercase(),
                trustedOwnerKeyId = identity.publicKey.keyId,
                appFolderId = storage.appFolderId,
                fileId = created.fileId,
                lastRevisionId = created.revisionId,
                lastSyncedAt = java.time.Instant.now().toString(),
                displayName = connectedProfile.displayName,
                syncMode = "encrypted",
            )
            val state = provisional.copy(
                activeProfileKey = profileKey(ownerEmail, PRIMARY_DATASET_ID),
                profiles = preservedLocalProfiles + profile,
            )
            registry.save(state)
            if (activeBeforeSetup != null && isLocalProfile(activeBeforeSetup)) {
                registry.deleteLocalPayload(previousState!!.activeProfileKey)
            }
            store.apply(created.value.withLocalAndroidPreferences(store.localPayload()))
            store.rememberSync(created.fileId, profile.lastSyncedAt!!)
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
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(!isLocalProfile(profile)) {
            "This profile is local only. Connect encrypted sync before syncing it."
        }
        val controller = controllerFor(state, profile)
        val local = sharedPayload(store.localPayload())
        val result = if (shouldLoadRemoteBeforePublish(profile)) {
            controller.loadDataset(profile.datasetId)
        } else {
            controller.syncDataset(profile.datasetId, local)
        }
        store.apply(result.value.withLocalAndroidPreferences(store.localPayload()))
        val syncedAt = java.time.Instant.now().toString()
        val nextProfile = profile.copy(
            fileId = result.fileId,
            lastRevisionId = result.revisionId,
            lastSyncedAt = syncedAt,
            needsInitialLoad = false,
        )
        val next = registry.upsertProfile(nextProfile)
        store.rememberSync(result.fileId, syncedAt)
        SharingSyncScheduler.schedule(context.applicationContext, driveAuth.tokenExpiresAt())
        return next
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
                requestedGrants = listOf(
                    SharingDatasetGrantV1(profile.datasetId, sharingRole),
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
    ): String {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        require(canAdministerRole(profile.role)) { "Only owners/admins can invite participants." }
        val sharingRole = sharingRoleFromString(role)
        require(sharingRole != SharingRole.OWNER) { "Cannot invite as owner." }
        val controller = controllerFor(state, profile)
        val invited = controller.inviteParticipantForLink(
            emailAddress = emailAddress.trim(),
            requestedGrants = listOf(SharingDatasetGrantV1(profile.datasetId, sharingRole)),
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
        val grant = parsed.invitation.requestedGrants.firstOrNull()
            ?: error("Invitation has no dataset grants.")
        val joinProfile = ProfileRecord(
            datasetId = grant.datasetId,
            ownerEmail = ownerEmail,
            folderName = easyBcSyncFolderName(ownerEmail),
            role = grant.role.name.lowercase(),
            trustedOwnerKeyId = parsed.invitation.trustedOwnerKeyId,
            appFolderId = parsed.invitation.appFolderId,
            needsInitialLoad = true,
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
        val datasetId = pending.invitation.requestedGrants.firstOrNull()?.datasetId
            ?: PRIMARY_DATASET_ID
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
        registry.upsertProfile(
            acceptedProfile.copy(
                participantEmails = acceptedProfile.participantEmails.orEmpty() +
                    (parsed.response.keyId to pending.recipientEmail),
            ),
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
        val grant = invitation.requestedGrants.firstOrNull()
            ?: error("Invitation has no dataset grants.")
        val joinProfile = ProfileRecord(
            datasetId = grant.datasetId,
            ownerEmail = ownerEmail,
            folderName = folderName,
            role = grant.role.name.lowercase(),
            trustedOwnerKeyId = invitation.trustedOwnerKeyId,
            appFolderId = ownerFolderId,
            needsInitialLoad = true,
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
        registry.upsertProfile(
            current.copy(
                participantEmails = current.participantEmails.orEmpty() +
                    (response.response.keyId to recipientEmailAddress.trim()),
            ),
        )
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
        if (isLocalProfile(profile) || profile.fileId.isNullOrBlank()) return emptyList()
        val stored = EasyBcSharedTransport.forProfile(state, profile, driveAuth)
            .readDataset(profile.fileId)
        SharingCrypto.verifySharedBackupEnvelopeV1(
            stored.envelope,
            VerifySharedBackupOptions(trustedOwnerKeyId = profile.trustedOwnerKeyId),
        )
        val currentKeyId = identityStore.getOrCreate().publicKey.keyId
        return sharedBackupParticipants(stored.envelope).map { participant ->
            ProfileParticipant(
                keyId = participant.keyId,
                role = participant.role.name.lowercase(),
                emailAddress = profile.participantEmails?.get(participant.keyId),
                isCurrentDevice = participant.keyId == currentKeyId,
            )
        }
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
        controller.setDatasetRole(
            datasetId = profile.datasetId,
            keyId = keyId,
            role = sharingRole,
            emailAddress = trimmedEmail,
        )
        registry.upsertProfile(
            profile.copy(
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
        controller.revokeDatasetKey(
            datasetId = profile.datasetId,
            keyId = keyId,
            emailAddress = trimmedEmail,
        )
        val participantEmails = profile.participantEmails.orEmpty().filterKeys { it != keyId }
        registry.upsertProfile(profile.copy(participantEmails = participantEmails))
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
            return next
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
        val loaded = controller.loadDataset(profile.datasetId)
        store.apply(loaded.value.withLocalAndroidPreferences(store.localPayload()))
        val syncedAt = java.time.Instant.now().toString()
        val nextProfile = profile.copy(
            fileId = loaded.fileId,
            lastRevisionId = loaded.revisionId,
            lastSyncedAt = syncedAt,
            needsInitialLoad = false,
        )
        store.rememberSync(loaded.fileId, syncedAt)
        return registry.upsertProfile(nextProfile)
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
        val controller = controllerFor(state, primary)
        val created = controller.createDataset(datasetId, emptySharedPayload())
        val syncedAt = java.time.Instant.now().toString()
        val newProfile = ProfileRecord(
            datasetId = datasetId,
            ownerEmail = state.ownerEmail,
            folderName = primary.folderName,
            displayName = trimmed,
            role = SharingRole.OWNER.name.lowercase(),
            trustedOwnerKeyId = primary.trustedOwnerKeyId,
            appFolderId = appFolderId,
            fileId = created.fileId,
            lastRevisionId = created.revisionId,
            lastSyncedAt = syncedAt,
        )
        registry.upsertProfile(newProfile)
        val profileKeyValue = profileKey(state.ownerEmail, datasetId)
        registry.setActiveProfile(profileKeyValue)
        store.apply(created.value.withLocalAndroidPreferences(store.localPayload()))
        store.rememberSync(created.fileId, syncedAt)
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
        val created = controllerFor(state, primary).createDataset(
            datasetId,
            sharedPayload(store.localPayload()),
        )
        val syncedAt = java.time.Instant.now().toString()
        val connected = ProfileRecord(
            datasetId = datasetId,
            ownerEmail = state.ownerEmail,
            folderName = primary.folderName,
            displayName = displayName,
            role = SharingRole.OWNER.name.lowercase(),
            trustedOwnerKeyId = primary.trustedOwnerKeyId,
            appFolderId = appFolderId,
            fileId = created.fileId,
            lastRevisionId = created.revisionId,
            lastSyncedAt = syncedAt,
            syncMode = "encrypted",
        )
        val connectedKey = profileKey(connected.ownerEmail, connected.datasetId)
        val next = state.copy(
            activeProfileKey = connectedKey,
            profiles = state.profiles.filter {
                profileKey(it.ownerEmail, it.datasetId) != state.activeProfileKey
            } + connected,
        )
        registry.save(next)
        registry.deleteLocalPayload(state.activeProfileKey)
        registry.clearCheckpoint()
        store.apply(created.value.withLocalAndroidPreferences(store.localPayload()))
        store.rememberSync(created.fileId, syncedAt)
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
            controllerFor(state, profile).deleteDataset(profile.datasetId)
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
        profile.fileId?.let {
            EasyBcSharedTransport.forProfile(state, profile, driveAuth).deleteDataset(it)
        }
        val cleared = profile.copy(
            fileId = null,
            lastRevisionId = null,
            seenRevisionIds = null,
            participantPermissionIds = emptyMap(),
            participantEmails = emptyMap(),
            lastSyncedAt = null,
        )
        state = registry.upsertProfile(cleared)
        val created = controllerFor(state, cleared).createDataset(
            profile.datasetId,
            sharedPayload(store.localPayload()),
        )
        val syncedAt = java.time.Instant.now().toString()
        val restored = cleared.copy(
            fileId = created.fileId,
            lastRevisionId = created.revisionId,
            lastSyncedAt = syncedAt,
        )
        val next = registry.upsertProfile(restored)
        store.apply(created.value.withLocalAndroidPreferences(store.localPayload()))
        store.rememberSync(created.fileId, syncedAt)
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

    private suspend fun controllerFor(
        state: SharedSyncState,
        profile: ProfileRecord,
    ): SharedBackupController<SyncPayloadV1> {
        val scopeKey = profileKey(profile.ownerEmail, profile.datasetId)
        return SharedBackupController(
            appId = EASY_BC_APP_ID,
            codec = EasyBcSharedCodec,
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
    )
}
