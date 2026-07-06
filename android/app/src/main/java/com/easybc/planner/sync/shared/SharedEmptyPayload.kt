package com.easybc.planner.sync.shared

import com.easybc.planner.sync.SYNC_EPOCH
import com.easybc.planner.sync.SyncPayloadV1
import com.easybc.planner.sync.SyncPlannerOptions
import com.easybc.planner.sync.TimestampedBoolean
import com.easybc.planner.sync.TimestampedPlanner

fun emptySharedPayload(): SyncPayloadV1 =
    SyncPayloadV1(
        exportedAt = SYNC_EPOCH,
        planner = TimestampedPlanner(
            value = SyncPlannerOptions(),
            updatedAt = SYNC_EPOCH,
            configured = false,
        ),
        ecJournal = TimestampedBoolean(value = false, updatedAt = SYNC_EPOCH),
    )
