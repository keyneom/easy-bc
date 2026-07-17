package com.easybc.planner.sync.shared

import android.net.Uri
import com.keyneom.synckit.crypto.Base64Url
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.sharing.SharedBackupOwnershipTransferV1

const val OWNERSHIP_TRANSFER_PARAM = "sk-owner-transfer"

fun buildOwnershipTransferLink(transfer: SharedBackupOwnershipTransferV1): String {
    val encoded = Base64Url.encode(
        SyncKitJson.instance.encodeToString(
            SharedBackupOwnershipTransferV1.serializer(),
            transfer,
        ).toByteArray(Charsets.UTF_8),
    )
    return Uri.parse(EASY_BC_JOIN_LANDING_URL).buildUpon()
        .appendQueryParameter(OWNERSHIP_TRANSFER_PARAM, encoded)
        .build()
        .toString()
}

fun parseOwnershipTransferLink(input: String): SharedBackupOwnershipTransferV1? = runCatching {
    val encoded = Uri.parse(input).getQueryParameter(OWNERSHIP_TRANSFER_PARAM)
        ?: return@runCatching null
    val decoded = SyncKitJson.instance.decodeFromString(
        SharedBackupOwnershipTransferV1.serializer(),
        Base64Url.decode(encoded).toString(Charsets.UTF_8),
    )
    decoded
}.getOrNull()

/**
 * The registry profile a transfer proposal is about, identified the same way
 * the coordinator matches it at accept time: the proposal covers exactly this
 * profile's dataset files (control file included). Null when the profile
 * hasn't been joined on this device.
 */
fun profileForOwnershipTransfer(
    state: SharedSyncState,
    transfer: SharedBackupOwnershipTransferV1,
): ProfileRecord? {
    val transferIds = transfer.datasets.map { it.datasetId }.toSet()
    return state.profiles.firstOrNull { candidate ->
        !isLocalProfile(candidate) &&
            profileDatasetIdsIncludingControl(candidate).toSet() == transferIds
    }
}
