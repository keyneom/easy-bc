package com.easybc.planner.sync

import androidx.room.withTransaction
import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.data.db.DayLog
import com.easybc.planner.data.db.DayEventEntity
import com.easybc.planner.data.db.PeriodRecord
import com.easybc.planner.data.db.SyncMetadataEntity
import com.easybc.planner.data.db.UserSettingsEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate

interface SyncPayloadGateway {
    suspend fun localPayload(): SyncPayloadV1
    suspend fun apply(payload: SyncPayloadV1)
    suspend fun rememberSync(fileId: String, syncedAt: String)
    suspend fun forgetSync()
}

class SyncPayloadStore(private val db: AppDatabase) : SyncPayloadGateway {
    private val json get() = SyncCrypto.json

    override suspend fun localPayload(): SyncPayloadV1 {
        val settings = db.userSettingsDao().getSettings() ?: UserSettingsEntity()
        val metadata = db.syncMetadataDao().getAll().associate { it.key to it.value }
        val preserved = metadata[META_PRESERVED_WEB]?.let {
            runCatching { json.decodeFromString<PreservedWebState>(it) }.getOrNull()
        } ?: PreservedWebState()

        val localDayRows = db.dayLogDao().getAll()
        val eventsByDate = db.dayEventDao().getAll().groupBy { it.date }
        val dayLogs = localDayRows.associate { row ->
            date(row.date) to SyncDayLog(
                actualAction = row.actualAction.takeIf(String::isNotBlank),
                notes = row.notes,
                mucus = row.mucus?.let { if (it == "eggwhite") "egg-white" else it },
                bbtCelsius = row.bbtCelsius,
                opk = row.opk,
                mittelschmerz = row.mittelschmerz.takeIf { it },
                breastTender = row.breastTender.takeIf { it },
                reconciled = row.reconciled.takeIf { it },
                events = eventsByDate[row.date].orEmpty().map { event ->
                    SyncDayEvent(
                        id = event.id,
                        kind = event.kind,
                        ecType = event.ecType,
                        hoursFromAct = event.hoursFromAct,
                        occurredAt = instant(event.occurredAt),
                        notes = event.notes,
                    )
                },
                updatedAt = instant(row.updatedAt),
            )
        }.toMutableMap()
        eventsByDate.forEach { (epochDay, events) ->
            val iso = date(epochDay)
            if (dayLogs[iso] == null) {
                dayLogs[iso] = SyncDayLog(
                    events = events.map { event ->
                        SyncDayEvent(
                            id = event.id,
                            kind = event.kind,
                            ecType = event.ecType,
                            hoursFromAct = event.hoursFromAct,
                            occurredAt = instant(event.occurredAt),
                            notes = event.notes,
                        )
                    },
                    updatedAt = instant(events.maxOfOrNull { it.updatedAt } ?: 0L),
                )
            }
        }
        metadata.entries.filter { it.key.startsWith(DAY_DELETED_PREFIX) }.forEach { (key, value) ->
            val epochDay = key.removePrefix(DAY_DELETED_PREFIX).toLongOrNull() ?: return@forEach
            val deletedAt = value.toLongOrNull() ?: 0L
            val iso = date(epochDay)
            if (timestamp(dayLogs[iso]?.updatedAt) <= deletedAt) {
                dayLogs[iso] = SyncDayLog(updatedAt = instant(deletedAt))
            }
        }

        // Android's NONE outcome and the web's voluntary-abstinence credit
        // describe the same as-lived event. Keep both representations in
        // lockstep so either UI can edit it without losing semantics.
        val voluntaryDates = preserved.voluntaryAbstinenceDates.toMutableMap()
        val voluntaryUpdated = preserved.voluntaryAbstinenceUpdatedAt.toMutableMap()
        val voluntaryDeleted = preserved.deletedVoluntaryAbstinenceDates.toMutableMap()
        localDayRows.forEach { row ->
            val iso = date(row.date)
            val rowTime = instant(row.updatedAt)
            if (row.actualAction == "NONE") {
                if (timestamp(rowTime) >= timestamp(voluntaryUpdated[iso]) &&
                    timestamp(rowTime) > timestamp(voluntaryDeleted[iso])) {
                    voluntaryDates[iso] = true
                    voluntaryUpdated[iso] = rowTime
                    voluntaryDeleted.remove(iso)
                }
            } else if (voluntaryDates[iso] == true && timestamp(rowTime) > timestamp(voluntaryUpdated[iso])) {
                voluntaryDates.remove(iso)
                voluntaryUpdated.remove(iso)
                voluntaryDeleted[iso] = rowTime
            }
        }
        metadata.entries.filter { it.key.startsWith(DAY_DELETED_PREFIX) }.forEach { (key, value) ->
            val epochDay = key.removePrefix(DAY_DELETED_PREFIX).toLongOrNull() ?: return@forEach
            val iso = date(epochDay)
            val deletedAt = instant(value.toLongOrNull() ?: 0L)
            if (voluntaryDates[iso] == true && timestamp(deletedAt) > timestamp(voluntaryUpdated[iso])) {
                voluntaryDates.remove(iso)
                voluntaryUpdated.remove(iso)
                voluntaryDeleted[iso] = deletedAt
            }
        }

        val periods = db.periodRecordDao().getAllAsc().map { row ->
            SyncPeriodRecord(
                start = date(row.startDate),
                end = row.endDate?.let(::date),
                note = row.note,
                excludeFromStats = row.excludeFromStats.takeIf { it },
                updatedAt = instant(row.updatedAt),
            )
        }
        val periodTombstones = metadata.entries
            .filter { it.key.startsWith(PERIOD_DELETED_PREFIX) }
            .mapNotNull { (key, value) ->
                val epochDay = key.removePrefix(PERIOD_DELETED_PREFIX).toLongOrNull() ?: return@mapNotNull null
                date(epochDay) to instant(value.toLongOrNull() ?: 0L)
            }.toMap()

        return SyncPayloadV1(
            exportedAt = Instant.now().toString(),
            planner = TimestampedPlanner(
                value = SyncPlannerOptions(
                    ageYears = settings.ageYears,
                    horizonYears = settings.horizonYears,
                    targetCumulativeFailure = settings.targetCumulativeFailure,
                    cycleLengthDays = settings.cycleLengthDays,
                    actsPerWeek = settings.actsPerWeek,
                    persistentMethod = settings.persistentMethod,
                    protectedDayMethod = settings.protectedDayMethod,
                    condomMode = settings.condomMode,
                    streakAversion = settings.streakAversion,
                    holdLifecycleConstant = settings.holdLifecycleConstant,
                    realizedCumulativeRisk = preserved.realizedCumulativeRisk,
                    withdrawalMode = settings.withdrawalMode,
                    withdrawalTypicalAnnualFailure = settings.withdrawalTypicalAnnualFailure,
                    withdrawalRelativeRisk = settings.withdrawalRelativeRisk,
                    useWithdrawalBackupOnProtectedDays = settings.useWithdrawalBackupOnProtectedDays,
                    combinedMethodIndependence = settings.combinedMethodIndependence,
                    ovulationSdDays = settings.ovulationSdDays,
                    bodySignals = preserved.bodySignals,
                    customCondomResidual = settings.customCondomResidual,
                ),
                updatedAt = instant(settings.updatedAt),
                configured = settings.onboardingComplete,
            ),
            periodRecords = periods,
            deletedPeriodStarts = periodTombstones,
            calendarDayLogs = dayLogs,
            voluntaryAbstinenceDates = voluntaryDates,
            voluntaryAbstinenceUpdatedAt = voluntaryUpdated,
            deletedVoluntaryAbstinenceDates = voluntaryDeleted,
            ecJournal = preserved.ecJournal,
            androidPreferences = TimestampedAndroidPreferences(
                value = AndroidPreferences(
                    calendarLabelPeriod = settings.calendarLabelPeriod,
                    calendarLabelFertile = settings.calendarLabelFertile,
                    calendarLabelActionU = settings.calendarLabelActionU,
                    calendarLabelActionC = settings.calendarLabelActionC,
                    calendarLabelActionA = settings.calendarLabelActionA,
                    calendarLabelActionW = settings.calendarLabelActionW,
                    reminderHour = settings.reminderHour,
                    reminderMinute = settings.reminderMinute,
                ),
                updatedAt = instant(settings.androidPreferencesUpdatedAt),
            ),
        )
    }

    override suspend fun apply(payload: SyncPayloadV1) = db.withTransaction {
        val current = db.userSettingsDao().getSettings() ?: UserSettingsEntity()
        val planner = payload.planner.value
        val prefs = payload.androidPreferences?.value
        db.userSettingsDao().save(
            current.copy(
                ageYears = planner.ageYears,
                horizonYears = planner.horizonYears,
                targetCumulativeFailure = planner.targetCumulativeFailure,
                cycleLengthDays = planner.cycleLengthDays,
                actsPerWeek = planner.actsPerWeek,
                persistentMethod = planner.persistentMethod,
                protectedDayMethod = planner.protectedDayMethod,
                condomMode = planner.condomMode,
                customCondomResidual = planner.customCondomResidual ?: current.customCondomResidual,
                streakAversion = planner.streakAversion,
                holdLifecycleConstant = planner.holdLifecycleConstant,
                withdrawalMode = planner.withdrawalMode,
                withdrawalTypicalAnnualFailure = planner.withdrawalTypicalAnnualFailure,
                withdrawalRelativeRisk = planner.withdrawalRelativeRisk,
                useWithdrawalBackupOnProtectedDays = planner.useWithdrawalBackupOnProtectedDays,
                combinedMethodIndependence = planner.combinedMethodIndependence,
                ovulationSdDays = planner.ovulationSdDays,
                onboardingComplete = payload.planner.configured
                    ?: (current.onboardingComplete || payload.periodRecords.isNotEmpty()),
                // Permission-bound enablement remains local to each device.
                calendarSyncEnabled = current.calendarSyncEnabled,
                calendarLabelPeriod = prefs?.calendarLabelPeriod ?: current.calendarLabelPeriod,
                calendarLabelFertile = prefs?.calendarLabelFertile ?: current.calendarLabelFertile,
                calendarLabelActionU = prefs?.calendarLabelActionU ?: current.calendarLabelActionU,
                calendarLabelActionC = prefs?.calendarLabelActionC ?: current.calendarLabelActionC,
                calendarLabelActionA = prefs?.calendarLabelActionA ?: current.calendarLabelActionA,
                calendarLabelActionW = prefs?.calendarLabelActionW ?: current.calendarLabelActionW,
                reminderEnabled = current.reminderEnabled,
                reminderHour = prefs?.reminderHour ?: current.reminderHour,
                reminderMinute = prefs?.reminderMinute ?: current.reminderMinute,
                updatedAt = timestamp(payload.planner.updatedAt),
                androidPreferencesUpdatedAt = payload.androidPreferences?.updatedAt
                    ?.let(::timestamp) ?: current.androidPreferencesUpdatedAt,
            )
        )

        db.periodRecordDao().deleteAll()
        db.dayLogDao().deleteAll()
        db.dayEventDao().deleteAll()
        db.syncMetadataDao().deleteByPrefix(PERIOD_DELETED_PREFIX)
        db.syncMetadataDao().deleteByPrefix(DAY_DELETED_PREFIX)

        payload.periodRecords.forEach { row ->
            db.periodRecordDao().insert(
                PeriodRecord(
                    startDate = LocalDate.parse(row.start).toEpochDay(),
                    endDate = row.end?.let { LocalDate.parse(it).toEpochDay() },
                    note = row.note,
                    excludeFromStats = row.excludeFromStats == true,
                    createdAt = timestamp(row.updatedAt),
                    updatedAt = timestamp(row.updatedAt),
                )
            )
        }
        payload.deletedPeriodStarts.forEach { (start, deletedAt) ->
            putMetadata(PERIOD_DELETED_PREFIX + LocalDate.parse(start).toEpochDay(), timestamp(deletedAt).toString())
        }
        val effectiveDayLogs = payload.calendarDayLogs.toMutableMap()
        payload.voluntaryAbstinenceDates.keys.forEach { iso ->
            val activeAt = payload.voluntaryAbstinenceUpdatedAt[iso] ?: SYNC_EPOCH
            val currentLog = effectiveDayLogs[iso]
            if (currentLog == null || timestamp(activeAt) > timestamp(currentLog.updatedAt)) {
                effectiveDayLogs[iso] = SyncDayLog(
                    actualAction = "NONE",
                    reconciled = true,
                    updatedAt = activeAt,
                )
            }
        }
        payload.deletedVoluntaryAbstinenceDates.forEach { (iso, deletedAt) ->
            val currentLog = effectiveDayLogs[iso]
            if (currentLog?.actualAction == "NONE" && timestamp(deletedAt) >= timestamp(currentLog.updatedAt)) {
                effectiveDayLogs[iso] = currentLog.copy(
                    actualAction = null,
                    reconciled = null,
                    updatedAt = deletedAt,
                )
            }
        }
        effectiveDayLogs.forEach { (iso, row) ->
            val epochDay = LocalDate.parse(iso).toEpochDay()
            if (row.hasUserData()) {
                db.dayLogDao().upsert(
                    DayLog(
                        date = epochDay,
                        actualAction = row.actualAction.orEmpty(),
                        notes = row.notes,
                        mucus = row.mucus?.let { if (it == "egg-white") "eggwhite" else it },
                        bbtCelsius = row.bbtCelsius,
                        opk = row.opk,
                        mittelschmerz = row.mittelschmerz == true,
                        breastTender = row.breastTender == true,
                        reconciled = row.reconciled ?: !row.actualAction.isNullOrBlank(),
                        updatedAt = timestamp(row.updatedAt),
                    )
                )
                row.events.forEach { event ->
                    db.dayEventDao().upsert(
                        DayEventEntity(
                            id = event.id,
                            date = epochDay,
                            kind = event.kind,
                            ecType = event.ecType,
                            hoursFromAct = event.hoursFromAct,
                            occurredAt = timestamp(event.occurredAt),
                            notes = event.notes,
                            updatedAt = timestamp(row.updatedAt),
                        )
                    )
                }
            } else {
                putMetadata(DAY_DELETED_PREFIX + epochDay, timestamp(row.updatedAt).toString())
            }
        }
        putMetadata(
            META_PRESERVED_WEB,
            json.encodeToString(
                PreservedWebState(
                    realizedCumulativeRisk = planner.realizedCumulativeRisk,
                    bodySignals = planner.bodySignals,
                    voluntaryAbstinenceDates = payload.voluntaryAbstinenceDates,
                    voluntaryAbstinenceUpdatedAt = payload.voluntaryAbstinenceUpdatedAt,
                    deletedVoluntaryAbstinenceDates = payload.deletedVoluntaryAbstinenceDates,
                    ecJournal = payload.ecJournal,
                )
            )
        )
    }

    suspend fun fileId(): String? = db.syncMetadataDao().get(META_FILE_ID)?.value

    suspend fun lastSyncedAt(): String? = db.syncMetadataDao().get(META_LAST_SYNCED)?.value

    override suspend fun rememberSync(fileId: String, syncedAt: String) {
        putMetadata(META_FILE_ID, fileId)
        putMetadata(META_LAST_SYNCED, syncedAt)
    }

    override suspend fun forgetSync() {
        db.syncMetadataDao().delete(META_FILE_ID)
        db.syncMetadataDao().delete(META_LAST_SYNCED)
    }

    private suspend fun putMetadata(key: String, value: String) {
        db.syncMetadataDao().put(SyncMetadataEntity(key, value))
    }

    companion object {
        const val PERIOD_DELETED_PREFIX = "period_deleted:"
        const val DAY_DELETED_PREFIX = "day_deleted:"
        private const val META_PRESERVED_WEB = "sync:preserved_web"
        private const val META_FILE_ID = "sync:file_id"
        private const val META_LAST_SYNCED = "sync:last_synced_at"

        fun timestamp(value: String?): Long = try {
            value?.let(Instant::parse)?.toEpochMilli() ?: 0L
        } catch (_: Exception) {
            0L
        }

        fun instant(value: Long): String =
            if (value > 0L) Instant.ofEpochMilli(value).toString() else SYNC_EPOCH

        fun date(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()
    }
}
