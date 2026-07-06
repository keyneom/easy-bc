package com.easybc.planner.sync.shared

import android.content.Context
import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.sync.SYNC_RP_ID
import com.easybc.planner.sync.SyncPayloadGateway
import com.easybc.planner.sync.SyncPayloadV1
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.sharing.InviteParticipantInput
import com.keyneom.synckit.sharing.SharedBackupController
import com.keyneom.synckit.sharing.SharingCryptoOptions
import com.keyneom.synckit.sharing.SharingDatasetGrantV1
import com.keyneom.synckit.sharing.SharingJoinParamStyle
import com.keyneom.synckit.sharing.SharingJoinParams
import com.keyneom.synckit.sharing.SharingRole
import com.keyneom.synckit.sharing.appendSharingJoinParams
import com.keyneom.synckit.stores.GoogleDriveSharedBackupTransport
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

class SharedSyncCoordinator(
    private val context: Context,
    private val db: AppDatabase,
    private val store: SyncPayloadGateway,
) {
    private val registry = SharedSyncRegistry(db)
    private val identityStore = SharingIdentityStore(context.applicationContext)
    private val driveAuth = SharedDriveAuth(context.applicationContext)

    suspend fun loadState(): SharedSyncState? = registry.load()

    suspend fun setup(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        val ownerEmail = fetchGoogleAccountEmail(accessToken)
        val folderName = easyBcSyncFolderName(ownerEmail)
        val identity = identityStore.getOrCreate()
        val provisional = SharedSyncState(
            rpId = SYNC_RP_ID,
            ownerEmail = ownerEmail,
            activeProfileKey = profileKey(ownerEmail, PRIMARY_DATASET_ID),
            profiles = listOf(
                ProfileRecord(
                    datasetId = PRIMARY_DATASET_ID,
                    ownerEmail = ownerEmail,
                    folderName = folderName,
                    role = SharingRole.OWNER.name.lowercase(),
                    trustedOwnerKeyId = identity.publicKey.keyId,
                ),
            ),
        )
        registry.save(provisional)
        val controller = controllerFor(provisional, PRIMARY_DATASET_ID)
        val storage = controller.ensureStorage()
        val local = sharedPayload(store.localPayload())
        val created = controller.createDataset(PRIMARY_DATASET_ID, local)
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
        )
        val state = provisional.copy(
            activeProfileKey = profileKey(ownerEmail, PRIMARY_DATASET_ID),
            profiles = listOf(profile),
        )
        registry.save(state)
        store.apply(local)
        store.rememberSync(created.fileId, profile.lastSyncedAt!!)
        SharingSyncScheduler.schedule(context.applicationContext, driveAuth.tokenExpiresAt())
        return state
    }

    suspend fun sync(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        val controller = controllerFor(state, profile.datasetId)
        val local = sharedPayload(store.localPayload())
        val result = if (canPublishRole(profile.role)) {
            controller.syncDataset(profile.datasetId, local)
        } else {
            controller.loadDataset(profile.datasetId)
        }
        store.apply(result.value.withLocalAndroidPreferences(store.localPayload()))
        val syncedAt = java.time.Instant.now().toString()
        val nextProfile = profile.copy(
            fileId = result.fileId,
            lastRevisionId = result.revisionId,
            lastSyncedAt = syncedAt,
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
        val controller = controllerFor(state, profile.datasetId)
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
        val invitation = transport.readInvitation(invitationFileId)
        val grant = invitation.requestedGrants.firstOrNull()
            ?: error("Invitation has no dataset grants.")
        val joinProfile = ProfileRecord(
            datasetId = grant.datasetId,
            ownerEmail = ownerEmail,
            folderName = folderName,
            role = grant.role.name.lowercase(),
            trustedOwnerKeyId = invitation.trustedOwnerKeyId,
            appFolderId = ownerFolderId,
        )
        val profileKeyValue = profileKey(joinProfile.ownerEmail, joinProfile.datasetId)
        val existing = registry.load()
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
        val controller = controllerFor(state, joinProfile.datasetId)
        controller.submitKeyResponse(invitationFileId)
        SharingSyncScheduler.schedule(context.applicationContext, driveAuth.tokenExpiresAt())
        return state
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
        val controller = controllerFor(state, profile.datasetId)
        val transport = EasyBcSharedTransport.forProfile(state, profile, driveAuth)
        val invitation = transport.readInvitation(invitationFileId)
        val response = transport.readKeyResponse(
            responseFileId,
            invitation.recipientDrivePermissionId,
        )
        controller.acceptKeyResponse(
            invitation = invitation,
            responseFileId = responseFileId,
            recipientEmailAddress = recipientEmailAddress.trim(),
        )
        controller.reconcileDrivePermissions(
            datasetId = profile.datasetId,
            participantEmails = mapOf(response.response.keyId to recipientEmailAddress.trim()),
        )
    }

    suspend fun listPendingResponses(accessToken: String): List<PendingResponse> {
        rememberAccess(accessToken)
        val state = registry.load() ?: return emptyList()
        val profile = registry.activeProfile(state)
        if (!canAdministerRole(profile.role)) return emptyList()
        val controller = controllerFor(state, profile.datasetId)
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

    suspend fun setActiveProfile(profileKeyValue: String): SharedSyncState =
        registry.setActiveProfile(profileKeyValue)

    suspend fun loadActiveProfile(accessToken: String): SharedSyncState {
        rememberAccess(accessToken)
        val state = registry.load() ?: error("Shared sync is not configured on this device.")
        val profile = registry.activeProfile(state)
        val controller = controllerFor(state, profile.datasetId)
        val loaded = controller.loadDataset(profile.datasetId)
        store.apply(loaded.value.withLocalAndroidPreferences(store.localPayload()))
        val syncedAt = java.time.Instant.now().toString()
        val nextProfile = profile.copy(
            fileId = loaded.fileId,
            lastRevisionId = loaded.revisionId,
            lastSyncedAt = syncedAt,
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
        val controller = controllerFor(state, primary.datasetId)
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

    suspend fun forget() {
        identityStore.clear()
        driveAuth.clear()
        registry.clear()
        registry.clearCheckpoint()
        store.forgetSync()
        SharingSyncScheduler.cancel(context.applicationContext)
    }

    suspend fun transportForPolling(): GoogleDriveSharedBackupTransport? {
        val state = registry.load() ?: return null
        val profile = registry.activeProfile(state)
        return EasyBcSharedTransport.forProfile(state, profile, driveAuth)
    }

    private fun rememberAccess(accessToken: String) {
        driveAuth.remember(accessToken)
    }

    private suspend fun controllerFor(
        state: SharedSyncState,
        datasetId: String,
    ): SharedBackupController<SyncPayloadV1> {
        val profile = state.profiles.firstOrNull { it.datasetId == datasetId }
            ?: registry.activeProfile(state)
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

    private fun fetchGoogleAccountEmail(accessToken: String): String {
        val connection = (URL("https://www.googleapis.com/oauth2/v3/userinfo").openConnection()
            as HttpURLConnection)
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        val body = connection.inputStream.bufferedReader().readText()
        val email = SyncKitJson.instance
            .parseToJsonElement(body)
            .jsonObject["email"]
            ?.jsonPrimitive
            ?.content
        require(!email.isNullOrBlank()) {
            "Your Google account has no email address for encrypted sync."
        }
        return email
    }

    private fun sharedPayload(payload: SyncPayloadV1): SyncPayloadV1 =
        payload.copy(androidPreferences = null)

    private fun SyncPayloadV1.withLocalAndroidPreferences(local: SyncPayloadV1): SyncPayloadV1 =
        copy(androidPreferences = local.androidPreferences)

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    data class PendingResponse(
        val responseFileId: String,
        val invitationFileId: String,
        val exchangeId: String,
    )
}
