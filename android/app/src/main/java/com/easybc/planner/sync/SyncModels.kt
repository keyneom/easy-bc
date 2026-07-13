package com.easybc.planner.sync

import kotlinx.serialization.Serializable

/** Profile display metadata synced via the plan dataset (avatar, etc.). */
@Serializable
data class ProfileMetaV1(
    /** Base64 WebP bytes, no data-URL prefix. */
    val avatarWebp: String? = null,
    /** Avatar update/removal timestamp retained for v1 compatibility. */
    val updatedAt: String = SYNC_EPOCH,
    /** User-facing profile label; encrypted with the plan dataset payload. */
    val displayName: String? = null,
    /** Independent timestamp so renames never overwrite concurrent avatar changes. */
    val displayNameUpdatedAt: String? = null,
)

@Serializable
data class SyncPayloadV1(
    val schemaVersion: Int = 1,
    val exportedAt: String,
    val planner: TimestampedPlanner,
    val periodRecords: List<SyncPeriodRecord> = emptyList(),
    val deletedPeriodStarts: Map<String, String> = emptyMap(),
    val calendarDayLogs: Map<String, SyncDayLog> = emptyMap(),
    val voluntaryAbstinenceDates: Map<String, Boolean> = emptyMap(),
    val voluntaryAbstinenceUpdatedAt: Map<String, String> = emptyMap(),
    val deletedVoluntaryAbstinenceDates: Map<String, String> = emptyMap(),
    val ecJournal: TimestampedBoolean = TimestampedBoolean(),
    /**
     * Profile display metadata (name and avatar). Lives in the plan dataset part only.
     * Absent on snapshots written before avatar support.
     */
    val profileMeta: ProfileMetaV1? = null,
    val androidPreferences: TimestampedAndroidPreferences? = null,
)

@Serializable
data class TimestampedPlanner(
    val value: SyncPlannerOptions,
    val updatedAt: String = SYNC_EPOCH,
    /** Null only for snapshots written before v0.1.19. */
    val configured: Boolean? = null,
)

@Serializable
data class SyncPlannerOptions(
    val ageYears: Int = 34,
    val horizonYears: Int = 20,
    val targetCumulativeFailure: Double = 0.05,
    val cycleLengthDays: Int = 28,
    val actsPerWeek: Double = 3.0,
    val persistentMethod: String = "none",
    val protectedDayMethod: String = "external_condom",
    val condomMode: String = "typical",
    val streakAversion: Double = 0.5,
    val holdLifecycleConstant: Boolean = false,
    val realizedCumulativeRisk: Double = 0.0,
    val withdrawalMode: String = "none",
    val withdrawalTypicalAnnualFailure: Double = 0.20,
    val withdrawalRelativeRisk: Double = 0.35,
    val useWithdrawalBackupOnProtectedDays: Boolean = false,
    val combinedMethodIndependence: Double = 0.35,
    val ovulationSdDays: Double = 3.0,
    val bodySignals: SyncBodySignals? = null,
    val customCondomResidual: Double? = null,
)

@Serializable
data class SyncBodySignals(
    val cervicalMucusPeakDay: Int? = null,
    val basalBodyTemperatureShiftDay: Int? = null,
    val lhSurgeDay: Int? = null,
    val wearableTemperatureShiftDay: Int? = null,
)

@Serializable
data class SyncPeriodRecord(
    val start: String,
    val end: String? = null,
    val note: String? = null,
    val excludeFromStats: Boolean? = null,
    val updatedAt: String? = null,
)

@Serializable
data class SyncDayLog(
    val actualAction: String? = null,
    val notes: String? = null,
    val mucus: String? = null,
    val bbtCelsius: Double? = null,
    val opk: String? = null,
    val mittelschmerz: Boolean? = null,
    val breastTender: Boolean? = null,
    val reconciled: Boolean? = null,
    val events: List<SyncDayEvent> = emptyList(),
    /**
     * Per-dataset deletion clocks used by split profiles. A timestamp-only
     * row is the on-wire tombstone inside an individual dataset file; this
     * map preserves which part that tombstone came from after the files are
     * recombined into one app payload.
     */
    val deletedDatasetParts: Map<String, String> = emptyMap(),
    val updatedAt: String? = null,
) {
    fun hasUserData(): Boolean = actualAction != null || notes != null || mucus != null ||
        bbtCelsius != null || opk != null || mittelschmerz == true || breastTender == true ||
        reconciled == true || events.isNotEmpty()
}

const val DAY_LOG_PART_CYCLE = "cycle"
const val DAY_LOG_PART_INTIMACY = "intimacy"
const val DAY_LOG_PART_SENSITIVE = "sensitive"

fun SyncDayLog.hasDataForDatasetPart(part: String): Boolean = when (part) {
    DAY_LOG_PART_CYCLE -> mucus != null || bbtCelsius != null || opk != null ||
        mittelschmerz == true || breastTender == true
    DAY_LOG_PART_INTIMACY -> !actualAction.isNullOrBlank() || !notes.isNullOrBlank() || reconciled == true ||
        events.any { it.kind != "plan_b_taken" }
    DAY_LOG_PART_SENSITIVE -> events.any { it.kind == "plan_b_taken" }
    else -> false
}

@Serializable
data class SyncDayEvent(
    val id: String,
    val kind: String,
    val ecType: String? = null,
    val hoursFromAct: Double? = null,
    val occurredAt: String,
    val notes: String? = null,
)

@Serializable
data class TimestampedBoolean(
    val value: Boolean = false,
    val updatedAt: String = SYNC_EPOCH,
)

@Serializable
data class TimestampedAndroidPreferences(
    val value: AndroidPreferences,
    val updatedAt: String = SYNC_EPOCH,
)

@Serializable
data class AndroidPreferences(
    val calendarLabelPeriod: String = "P",
    val calendarLabelFertile: String = "F",
    val calendarLabelActionU: String = "U",
    val calendarLabelActionC: String = "C",
    val calendarLabelActionA: String = "A",
    val calendarLabelActionW: String = "W",
    val reminderHour: Int = 9,
    val reminderMinute: Int = 0,
)

@Serializable
internal data class PreservedWebState(
    val realizedCumulativeRisk: Double = 0.0,
    val bodySignals: SyncBodySignals? = null,
    val voluntaryAbstinenceDates: Map<String, Boolean> = emptyMap(),
    val voluntaryAbstinenceUpdatedAt: Map<String, String> = emptyMap(),
    val deletedVoluntaryAbstinenceDates: Map<String, String> = emptyMap(),
    val ecJournal: TimestampedBoolean = TimestampedBoolean(),
    val profileMeta: ProfileMetaV1? = null,
)
