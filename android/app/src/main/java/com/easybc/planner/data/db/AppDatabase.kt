package com.easybc.planner.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ── Entities ──

@Entity(tableName = "period_records")
data class PeriodRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch day (LocalDate.toEpochDay()) of period start. */
    val startDate: Long,
    /** Epoch day of last bleeding day. Null if period is ongoing. */
    val endDate: Long? = null,
    /**
     * Free-text note about this period — set by the user from the History
     * edit dialog. Use cases: medications, illness, travel, anything they
     * want to remember about why this period was unusual.
     */
    val note: String? = null,
    /**
     * When true, this period is excluded from cycle-statistics calculations
     * (cycle length posterior, mean bleed duration, atypical-flag scoring).
     * Lets users keep an atypical period on the calendar without polluting
     * the prediction baseline. The cycle from THIS period to the previous
     * one and from THIS period to the next one are both excluded — bridging
     * across an excluded record would invent a phantom 2x-length cycle.
     */
    val excludeFromStats: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "day_logs")
data class DayLog(
    /** Epoch day — one log per calendar day. */
    @PrimaryKey val date: Long,
    /**
     * What the user actually did. Planner-recommended actions:
     * - "U" unprotected
     * - "W" withdrawal
     * - "C" protected (barrier)
     * - "A" abstain (planner-recommended; no intercourse)
     *
     * Reconciliation outcomes (not planner-recommended; user's post-hoc
     * confirmation of what happened on a day that had been planned):
     * - "NONE" no intercourse happened (whether the plan was C/U/W/A — this
     *   is treated as voluntary abstinence for risk purposes: zero realized
     *   risk for the day, which yields "abstinence credit" for later in the
     *   cycle).
     * - "CB"   planned C, but the barrier broke — treated as realized U for
     *   risk purposes.
     */
    val actualAction: String,
    val notes: String? = null,
    // ── Optional body signals (all opt-in, never required) ──
    /**
     * Cervical-mucus observation:
     * "dry" | "sticky" | "creamy" | "eggwhite" | "spotting" | null.
     * Values loosely correspond to rising fertility. Used (if present) to
     * narrow and shift the predicted fertile window.
     */
    val mucus: String? = null,
    /**
     * Basal body temperature in °C (manual entry). Used to detect the
     * post-ovulation thermal shift retrospectively.
     */
    val bbtCelsius: Double? = null,
    /**
     * Ovulation predictor (LH) test result:
     * "negative" | "positive" | "peak" | null.
     */
    val opk: String? = null,
    /** Ovulation pain (Mittelschmerz) reported for the day. */
    val mittelschmerz: Boolean = false,
    /** Breast tenderness reported for the day. */
    val breastTender: Boolean = false,
    /**
     * True once the user has confirmed what actually happened on this day
     * (either matching the plan or correcting it). Lets the reconciliation
     * chip know which past days it still needs to prompt for.
     *
     * Days the user logs proactively (e.g. via the Day Detail sheet) count
     * as reconciled automatically; the chip only surfaces days the user
     * hasn't touched.
     */
    val reconciled: Boolean = false,
)

@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val ageYears: Int = 34,
    val horizonYears: Int = 20,
    val targetCumulativeFailure: Double = 0.05,
    val cycleLengthDays: Int = 28,
    val actsPerWeek: Double = 3.0,
    /** Stored as lowercase string: "none", "pill_or_ring", "patch", etc. */
    val persistentMethod: String = "none",
    /** Stored as lowercase string: "none", "external_condom", "internal_condom", etc. */
    val protectedDayMethod: String = "external_condom",
    /** Stored as lowercase string: "perfect", "typical", "custom". */
    val condomMode: String = "typical",
    val customCondomResidual: Double = 0.08,
    val streakAversion: Double = 0.5,
    val holdLifecycleConstant: Boolean = false,
    /** Stored as lowercase string: "none", "typical", "custom". */
    val withdrawalMode: String = "none",
    val withdrawalTypicalAnnualFailure: Double = 0.20,
    val withdrawalRelativeRisk: Double = 0.35,
    val useWithdrawalBackupOnProtectedDays: Boolean = false,
    val combinedMethodIndependence: Double = 0.35,
    val ovulationSdDays: Double = 3.0,
    val onboardingComplete: Boolean = false,
    /**
     * When true, the app keeps the on-device "EasyBC Planner" calendar in
     * sync automatically whenever periods / day-logs / settings change.
     * Turning this on prompts for WRITE_CALENDAR permission the first time.
     */
    val calendarSyncEnabled: Boolean = false,
    // ── Calendar event labels ──
    // What the app writes as the event title in the device calendar.
    // Defaults are deliberately cryptic single letters so a glance at the
    // phone's calendar by a bystander doesn't reveal what's being tracked.
    // The event's *description* is set to the same value (see
    // EasyBCCalendarSync) so tapping an event also reveals nothing new.
    val calendarLabelPeriod: String = "P",
    val calendarLabelFertile: String = "F",
    val calendarLabelActionU: String = "U",
    val calendarLabelActionC: String = "C",
    val calendarLabelActionA: String = "A",
    val calendarLabelActionW: String = "W",
    /**
     * If true, the app schedules a local daily reminder to prompt the user
     * to reconcile the previous day's plan. Purely opt-in; no default-on.
     */
    val reminderEnabled: Boolean = false,
    /**
     * Local hour-of-day (0..23) for the daily reminder. Defaults to 9 am
     * because the reminder is intentionally framed as "about yesterday" —
     * we can only ask the user to reconcile a day that's already over, so
     * a morning-after time is the natural default.
     */
    val reminderHour: Int = 9,
    /** Local minute-of-hour (0..59) for the daily reminder. */
    val reminderMinute: Int = 0,
)

// ── DAOs ──

@Dao
interface PeriodRecordDao {
    @Query("SELECT * FROM period_records ORDER BY startDate DESC")
    fun getAllFlow(): Flow<List<PeriodRecord>>

    @Query("SELECT * FROM period_records ORDER BY startDate ASC")
    fun getAllAscFlow(): Flow<List<PeriodRecord>>

    @Query("SELECT * FROM period_records ORDER BY startDate ASC")
    suspend fun getAllAsc(): List<PeriodRecord>

    @Query("SELECT * FROM period_records ORDER BY startDate DESC LIMIT 1")
    suspend fun getLatest(): PeriodRecord?

    @Insert
    suspend fun insert(record: PeriodRecord): Long

    @Update
    suspend fun update(record: PeriodRecord)

    @Delete
    suspend fun delete(record: PeriodRecord)

    @Query("DELETE FROM period_records")
    suspend fun deleteAll()
}

@Dao
interface DayLogDao {
    @Query("SELECT * FROM day_logs ORDER BY date DESC")
    fun getAllFlow(): Flow<List<DayLog>>

    @Query("SELECT * FROM day_logs WHERE date BETWEEN :startEpochDay AND :endEpochDay ORDER BY date ASC")
    fun getForRangeFlow(startEpochDay: Long, endEpochDay: Long): Flow<List<DayLog>>

    @Query("SELECT * FROM day_logs WHERE date = :epochDay")
    suspend fun getForDate(epochDay: Long): DayLog?

    @Upsert
    suspend fun upsert(log: DayLog)

    @Delete
    suspend fun delete(log: DayLog)

    @Query("SELECT * FROM day_logs ORDER BY date ASC")
    suspend fun getAll(): List<DayLog>

    /**
     * Flow of days that happened on or after [fromEpochDay], strictly before
     * [beforeEpochDay], that the user hasn't explicitly reconciled. Used by
     * the reconciliation chip to surface only past days the user hasn't
     * already confirmed.
     */
    @Query(
        "SELECT * FROM day_logs " +
            "WHERE date >= :fromEpochDay AND date < :beforeEpochDay AND reconciled = 0 " +
            "ORDER BY date ASC"
    )
    fun getUnreconciledInRangeFlow(fromEpochDay: Long, beforeEpochDay: Long): Flow<List<DayLog>>

    @Query("UPDATE day_logs SET reconciled = 1 WHERE date = :epochDay")
    suspend fun markReconciled(epochDay: Long)

    @Query("DELETE FROM day_logs")
    suspend fun deleteAll()
}

@Dao
interface UserSettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<UserSettingsEntity?>

    @Query("SELECT * FROM user_settings WHERE id = 1")
    suspend fun getSettings(): UserSettingsEntity?

    @Upsert
    suspend fun save(settings: UserSettingsEntity)
}

// ── Database ──

@Database(
    entities = [PeriodRecord::class, DayLog::class, UserSettingsEntity::class],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun periodRecordDao(): PeriodRecordDao
    abstract fun dayLogDao(): DayLogDao
    abstract fun userSettingsDao(): UserSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2: added method-library fields to user_settings.
         * The columns all have non-null defaults matching the Kotlin entity
         * defaults, so existing rows get sane values after the migration.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_settings ADD COLUMN persistentMethod TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN protectedDayMethod TEXT NOT NULL DEFAULT 'external_condom'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN withdrawalMode TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN withdrawalTypicalAnnualFailure REAL NOT NULL DEFAULT 0.20")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN useWithdrawalBackupOnProtectedDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN combinedMethodIndependence REAL NOT NULL DEFAULT 0.35")
            }
        }

        /** v2 → v3: added calendarSyncEnabled to user_settings. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_settings ADD COLUMN calendarSyncEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3 → v4: added six calendarLabel* columns for privacy-friendly
         * custom titles in the device calendar. Defaults are the same cryptic
         * single-letter values the entity declares.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_settings ADD COLUMN calendarLabelPeriod TEXT NOT NULL DEFAULT 'P'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN calendarLabelFertile TEXT NOT NULL DEFAULT 'F'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN calendarLabelActionU TEXT NOT NULL DEFAULT 'U'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN calendarLabelActionC TEXT NOT NULL DEFAULT 'C'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN calendarLabelActionA TEXT NOT NULL DEFAULT 'A'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN calendarLabelActionW TEXT NOT NULL DEFAULT 'W'")
            }
        }

        /**
         * v4 → v5: added optional body-signal columns and reconciliation flag
         * to day_logs. All new fields are nullable / false-default so existing
         * rows read back unchanged.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE day_logs ADD COLUMN mucus TEXT")
                db.execSQL("ALTER TABLE day_logs ADD COLUMN bbtCelsius REAL")
                db.execSQL("ALTER TABLE day_logs ADD COLUMN opk TEXT")
                db.execSQL("ALTER TABLE day_logs ADD COLUMN mittelschmerz INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE day_logs ADD COLUMN breastTender INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE day_logs ADD COLUMN reconciled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 → v6: added three daily-reminder fields to user_settings. All
         * defaults match the entity's Kotlin defaults (off, 9 pm).
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_settings ADD COLUMN reminderEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN reminderHour INTEGER NOT NULL DEFAULT 9")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN reminderMinute INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 → v7: added `note` (nullable) and `excludeFromStats` to
         * period_records so users can flag atypical periods (illness, blood
         * thinners, etc.) without losing the record. Existing rows take the
         * defaults: no note, included in stats.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE period_records ADD COLUMN note TEXT")
                db.execSQL("ALTER TABLE period_records ADD COLUMN excludeFromStats INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "easybc.db",
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    )
                    // NOTE: no fallbackToDestructiveMigration — we never want
                    // to silently wipe user data. If a future schema bump
                    // lacks a migration the build will crash loudly on open,
                    // which is the right failure mode.
                    .build().also { INSTANCE = it }
            }
    }
}
