package com.easybc.planner.io

import android.content.Context
import android.net.Uri
import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.data.db.DayLog
import com.easybc.planner.data.db.PeriodRecord
import com.easybc.planner.data.db.UserSettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * JSON export / import of the entire app database.
 *
 * This is the cross-device migration path: there is no cloud account, so a
 * user moving phones writes the file here (SAF picker or Share target),
 * copies it however they want (AirDrop, email to self, cloud drive of
 * choice, QR chunk — up to them), and imports it on the new device.
 *
 * Versioned so we can evolve the schema without breaking old backups.
 */
object DataBackup {

    const val VERSION = 1
    const val MIME_TYPE = "application/json"
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    // ── DTOs ─────────────────────────────────────────────────────────────
    // Mirror Room entities explicitly so DB schema changes don't silently
    // break backup compatibility. Defaults keep old exports readable when
    // we add fields later.

    @Serializable
    data class Backup(
        val version: Int = VERSION,
        val exportedAt: String,
        val settings: SettingsDto?,
        val periods: List<PeriodDto>,
        val dayLogs: List<DayLogDto>,
    )

    @Serializable
    data class SettingsDto(
        val ageYears: Int = 34,
        val horizonYears: Int = 20,
        val targetCumulativeFailure: Double = 0.05,
        val cycleLengthDays: Int = 28,
        val actsPerWeek: Double = 3.0,
        val persistentMethod: String = "none",
        val protectedDayMethod: String = "external_condom",
        val condomMode: String = "typical",
        val customCondomResidual: Double = 0.08,
        val streakAversion: Double = 0.5,
        val holdLifecycleConstant: Boolean = false,
        val withdrawalMode: String = "none",
        val withdrawalTypicalAnnualFailure: Double = 0.20,
        val withdrawalRelativeRisk: Double = 0.35,
        val useWithdrawalBackupOnProtectedDays: Boolean = false,
        val combinedMethodIndependence: Double = 0.35,
        val ovulationSdDays: Double = 3.0,
        val onboardingComplete: Boolean = false,
        val calendarSyncEnabled: Boolean = false,
        val calendarLabelPeriod: String = "P",
        val calendarLabelFertile: String = "F",
        val calendarLabelActionU: String = "U",
        val calendarLabelActionC: String = "C",
        val calendarLabelActionA: String = "A",
        val calendarLabelActionW: String = "W",
    )

    @Serializable
    data class PeriodDto(
        val startDate: Long,
        val endDate: Long? = null,
        val createdAt: Long = 0L,
    )

    @Serializable
    data class DayLogDto(
        val date: Long,
        val actualAction: String,
        val notes: String? = null,
    )

    // ── Export ───────────────────────────────────────────────────────────

    /**
     * Read everything out of the DB, encode as JSON, and write it to the
     * SAF URI the user picked via ACTION_CREATE_DOCUMENT.
     */
    suspend fun exportTo(context: Context, uri: Uri, db: AppDatabase): Int =
        withContext(Dispatchers.IO) {
            val settings = db.userSettingsDao().getSettings()?.toDto()
            val periods = db.periodRecordDao().getAllAsc().map { it.toDto() }
            val dayLogs = readAllDayLogs(db)
            val payload = Backup(
                exportedAt = Instant.now().toString(),
                settings = settings,
                periods = periods,
                dayLogs = dayLogs,
            )
            val text = json.encodeToString(payload)
            context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: error("Could not open output stream for $uri")
            text.length
        }

    /**
     * Read JSON from the SAF URI the user picked via ACTION_OPEN_DOCUMENT,
     * validate it, then replace the DB contents.
     *
     * **Destructive:** wipes period_records and day_logs, overwrites settings.
     * Callers should gate this behind a confirmation dialog.
     */
    suspend fun importFrom(context: Context, uri: Uri, db: AppDatabase): ImportSummary =
        withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error("Could not open input stream for $uri")
            val payload = try {
                json.decodeFromString<Backup>(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid EasyBC backup file: ${e.message}", e)
            }
            if (payload.version > VERSION) {
                throw IllegalArgumentException(
                    "Backup version ${payload.version} is newer than this app (supports $VERSION). Update EasyBC first."
                )
            }

            // Wipe first so we don't end up with duplicate periods. We don't
            // use a single transaction because Room's Dao APIs don't expose
            // one trivially across these three tables — acceptable: all three
            // wipes/writes are idempotent-ish for a migration.
            db.periodRecordDao().deleteAll()
            db.dayLogDao().deleteAll()

            for (p in payload.periods) {
                db.periodRecordDao().insert(
                    PeriodRecord(
                        startDate = p.startDate,
                        endDate = p.endDate,
                        createdAt = if (p.createdAt > 0) p.createdAt else System.currentTimeMillis(),
                    )
                )
            }
            for (d in payload.dayLogs) {
                db.dayLogDao().upsert(
                    DayLog(date = d.date, actualAction = d.actualAction, notes = d.notes)
                )
            }
            payload.settings?.let { db.userSettingsDao().save(it.toEntity()) }

            ImportSummary(
                periodsImported = payload.periods.size,
                dayLogsImported = payload.dayLogs.size,
                settingsImported = payload.settings != null,
                exportedAt = payload.exportedAt,
            )
        }

    data class ImportSummary(
        val periodsImported: Int,
        val dayLogsImported: Int,
        val settingsImported: Boolean,
        val exportedAt: String,
    )

    /**
     * Default filename shown in the system SAF picker. Includes an ISO date
     * so multiple exports in a row don't clobber each other.
     */
    fun defaultFilename(now: Instant = Instant.now()): String {
        val iso = now.toString().substringBefore('T').replace("-", "")
        return "easybc-backup-$iso.json"
    }

    // ── DTO adapters ─────────────────────────────────────────────────────

    private fun UserSettingsEntity.toDto() = SettingsDto(
        ageYears = ageYears,
        horizonYears = horizonYears,
        targetCumulativeFailure = targetCumulativeFailure,
        cycleLengthDays = cycleLengthDays,
        actsPerWeek = actsPerWeek,
        persistentMethod = persistentMethod,
        protectedDayMethod = protectedDayMethod,
        condomMode = condomMode,
        customCondomResidual = customCondomResidual,
        streakAversion = streakAversion,
        holdLifecycleConstant = holdLifecycleConstant,
        withdrawalMode = withdrawalMode,
        withdrawalTypicalAnnualFailure = withdrawalTypicalAnnualFailure,
        withdrawalRelativeRisk = withdrawalRelativeRisk,
        useWithdrawalBackupOnProtectedDays = useWithdrawalBackupOnProtectedDays,
        combinedMethodIndependence = combinedMethodIndependence,
        ovulationSdDays = ovulationSdDays,
        onboardingComplete = onboardingComplete,
        calendarSyncEnabled = calendarSyncEnabled,
        calendarLabelPeriod = calendarLabelPeriod,
        calendarLabelFertile = calendarLabelFertile,
        calendarLabelActionU = calendarLabelActionU,
        calendarLabelActionC = calendarLabelActionC,
        calendarLabelActionA = calendarLabelActionA,
        calendarLabelActionW = calendarLabelActionW,
    )

    private fun SettingsDto.toEntity() = UserSettingsEntity(
        id = 1,
        ageYears = ageYears,
        horizonYears = horizonYears,
        targetCumulativeFailure = targetCumulativeFailure,
        cycleLengthDays = cycleLengthDays,
        actsPerWeek = actsPerWeek,
        persistentMethod = persistentMethod,
        protectedDayMethod = protectedDayMethod,
        condomMode = condomMode,
        customCondomResidual = customCondomResidual,
        streakAversion = streakAversion,
        holdLifecycleConstant = holdLifecycleConstant,
        withdrawalMode = withdrawalMode,
        withdrawalTypicalAnnualFailure = withdrawalTypicalAnnualFailure,
        withdrawalRelativeRisk = withdrawalRelativeRisk,
        useWithdrawalBackupOnProtectedDays = useWithdrawalBackupOnProtectedDays,
        combinedMethodIndependence = combinedMethodIndependence,
        ovulationSdDays = ovulationSdDays,
        onboardingComplete = onboardingComplete,
        calendarSyncEnabled = calendarSyncEnabled,
        calendarLabelPeriod = calendarLabelPeriod,
        calendarLabelFertile = calendarLabelFertile,
        calendarLabelActionU = calendarLabelActionU,
        calendarLabelActionC = calendarLabelActionC,
        calendarLabelActionA = calendarLabelActionA,
        calendarLabelActionW = calendarLabelActionW,
    )

    private fun PeriodRecord.toDto() = PeriodDto(
        startDate = startDate,
        endDate = endDate,
        createdAt = createdAt,
    )

    private suspend fun readAllDayLogs(db: AppDatabase): List<DayLogDto> {
        // The existing DAO doesn't have a suspend getAll; use the flow's
        // first emission via a one-shot transaction. Simpler: add nothing,
        // just read via a raw query. Use a suspend helper added below in
        // DayLogDao — if absent, fall through to an all-range lookup.
        return db.dayLogDao().getAll().map {
            DayLogDto(date = it.date, actualAction = it.actualAction, notes = it.notes)
        }
    }
}
