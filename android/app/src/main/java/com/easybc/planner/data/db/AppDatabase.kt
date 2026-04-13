package com.easybc.planner.data.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entities ──

@Entity(tableName = "period_records")
data class PeriodRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch day (LocalDate.toEpochDay()) of period start. */
    val startDate: Long,
    /** Epoch day of last bleeding day. Null if period is ongoing. */
    val endDate: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "day_logs")
data class DayLog(
    /** Epoch day — one log per calendar day. */
    @PrimaryKey val date: Long,
    /** What the user actually did: "U", "W", "C", or "A". */
    val actualAction: String,
    val notes: String? = null,
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
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun periodRecordDao(): PeriodRecordDao
    abstract fun dayLogDao(): DayLogDao
    abstract fun userSettingsDao(): UserSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "easybc.db",
                ).fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
