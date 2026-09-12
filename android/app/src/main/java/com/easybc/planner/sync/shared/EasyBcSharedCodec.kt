package com.easybc.planner.sync.shared

import com.easybc.planner.sync.SyncMerge
import com.easybc.planner.sync.SyncPayloadV1
import com.easybc.planner.sync.SYNC_EPOCH
import com.keyneom.synckit.crypto.SyncKitJson
import com.keyneom.synckit.sharing.SharedBackupControllerCodec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

object EasyBcSharedCodec : SharedBackupControllerCodec<SyncPayloadV1> {
    private val json get() = SyncKitJson.instance

    override fun serialize(value: SyncPayloadV1): JsonElement =
        json.encodeToJsonElement(SyncPayloadV1.serializer(), value)

    override fun parse(element: JsonElement): SyncPayloadV1 =
        json.decodeFromJsonElement(SyncPayloadV1.serializer(), element).also { payload ->
            require((payload.profileMeta?.avatarWebp?.length ?: 0) <= 16_384) {
                "The Drive snapshot contains an oversized profile photo."
            }
            payload.profileMeta?.displayName?.let { displayName ->
                require(displayName.isNotBlank() && displayName.length <= 120) {
                    "The Drive snapshot contains an invalid profile name."
                }
                require(!payload.profileMeta.displayNameUpdatedAt.isNullOrBlank()) {
                    "The Drive snapshot contains profile name metadata without a timestamp."
                }
            }
        }

    // The argument swap is load-bearing, not cosmetic. SyncMerge.merge resolves
    // ties to its FIRST argument, so swapping makes this codec resolve ties to
    // `remote` — and sync-kit's apply guard calls codec.merge(merged, committed),
    // which therefore resolves tie fields to `committed` itself and is trivially
    // satisfied. See SyncPayloadApplyMergedTest's tie test: that and this swap
    // are two independent reasons the guard passes, and losing both turns an
    // equal-timestamp field into a STATE error thrown after the cloud write
    // already succeeded.
    override fun merge(local: SyncPayloadV1, remote: SyncPayloadV1): SyncPayloadV1 =
        SyncMerge.merge(remote, local)

    override fun fingerprint(value: SyncPayloadV1): String =
        json.encodeToString(
            SyncPayloadV1.serializer(),
            value.copy(exportedAt = SYNC_EPOCH, androidPreferences = null),
        )
}
