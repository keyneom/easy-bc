package com.easybc.planner.sync

import kotlinx.serialization.Serializable

internal const val SYNC_RP_ID = "keyneom.github.io"
internal const val SYNC_FILE_NAME = "easybc-sync-v1.json"
internal const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
internal const val SYNC_EPOCH = "1970-01-01T00:00:00.000Z"

@Serializable
data class SyncEnvelopeV1(
    val schemaVersion: Int = 1,
    val algorithm: String = "AES-256-GCM+HKDF-SHA-256",
    val credentialId: String,
    val rpId: String,
    val prfInput: String,
    val kdfSalt: String,
    val nonce: String,
    val ciphertext: String,
    val updatedAt: String,
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
    val androidPreferences: TimestampedAndroidPreferences? = null,
)

@Serializable
data class TimestampedPlanner(
    val value: SyncPlannerOptions,
    val updatedAt: String = SYNC_EPOCH,
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
    val updatedAt: String? = null,
) {
    fun hasUserData(): Boolean = actualAction != null || notes != null || mucus != null ||
        bbtCelsius != null || opk != null || mittelschmerz == true || breastTender == true ||
        reconciled == true
}

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
)

data class DriveSnapshot(val fileId: String, val envelope: SyncEnvelopeV1)

data class PasskeyMaterial(
    val credentialId: String,
    val prfInput: ByteArray,
    val kdfSalt: ByteArray,
    val secret: ByteArray,
    val rpId: String = SYNC_RP_ID,
)

enum class CloudSyncOperation { SETUP, ENABLE, SYNC, RESET, DELETE }
