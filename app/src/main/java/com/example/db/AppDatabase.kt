package com.example.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "devices")
data class Device(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val ip: String,
    val name: String,
    val endTime: Long = 0,
    val isPaused: Boolean = false,
    val remainingWhenPaused: Long = 0
)

@Entity(tableName = "shortcuts")
data class Shortcut(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val keyword: String,
    val phrase: String,
    @ColumnInfo(defaultValue = "NULL") val planId: Long? = null,
    @ColumnInfo(defaultValue = "'CASH'") val payment: String = "CASH",
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices")
    fun getAll(): Flow<List<Device>>
    
    @Query("SELECT * FROM devices WHERE ip = :ip LIMIT 1")
    suspend fun getByIp(ip: String): Device?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: Device)

    @Update
    suspend fun update(device: Device)

    @Delete
    suspend fun delete(device: Device)
}

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM shortcuts")
    fun getAll(): Flow<List<Shortcut>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shortcut: Shortcut)

    @Delete
    suspend fun delete(shortcut: Shortcut)

    @Query("SELECT * FROM shortcuts") suspend fun list(): List<Shortcut>
}

@Database(entities = [Device::class, Shortcut::class, Plan::class, Session::class, BusinessSettings::class, Sequence::class, ManualSale::class, SlotReservation::class, RevenueCorrection::class, BillingCycle::class, Debt::class, DebtPayment::class, BalanceUpdate::class], version = 6, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun shortcutDao(): ShortcutDao
    abstract fun businessDao(): BusinessDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shortcuts ADD COLUMN planId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE shortcuts ADD COLUMN payment TEXT NOT NULL DEFAULT 'CASH'")
                db.execSQL("ALTER TABLE shortcuts ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE TABLE IF NOT EXISTS plans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, minutes INTEGER NOT NULL, cash INTEGER NOT NULL, bank INTEGER NOT NULL, home INTEGER NOT NULL, enabled INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sessions (id TEXT NOT NULL PRIMARY KEY, client TEXT NOT NULL, plan TEXT NOT NULL, started INTEGER NOT NULL, resumed INTEGER NOT NULL, duration INTEGER NOT NULL, served INTEGER NOT NULL, state TEXT NOT NULL, amount INTEGER NOT NULL, cashEquivalent INTEGER NOT NULL, payment TEXT NOT NULL, premiumBps INTEGER NOT NULL, home INTEGER NOT NULL, grace INTEGER NOT NULL, recognized INTEGER NOT NULL, warned INTEGER NOT NULL, notified INTEGER NOT NULL, source TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS settings (id INTEGER NOT NULL PRIMARY KEY, graceMinutes INTEGER NOT NULL, premiumBps INTEGER NOT NULL, usdCents INTEGER NOT NULL, bankRate INTEGER NOT NULL, cycleStart INTEGER NOT NULL, cycleEnd INTEGER NOT NULL, expenses INTEGER NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN reference TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS sequences (name TEXT NOT NULL PRIMARY KEY, `next` INTEGER NOT NULL)")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN maxSubscribers INTEGER NOT NULL DEFAULT 50")
                db.execSQL("CREATE TABLE IF NOT EXISTS manual_sales (id TEXT NOT NULL PRIMARY KEY, at INTEGER NOT NULL, count INTEGER NOT NULL, unitPrice INTEGER NOT NULL, amount INTEGER NOT NULL, cashEquivalent INTEGER NOT NULL, payment TEXT NOT NULL, premiumBps INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS slot_reservations (number INTEGER NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, expires INTEGER NOT NULL)")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN cycleId TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS revenue_corrections (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, source TEXT NOT NULL, amount INTEGER NOT NULL, cashEquivalent INTEGER NOT NULL, count INTEGER NOT NULL, voided INTEGER NOT NULL, at INTEGER NOT NULL, reason TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS billing_cycles (id TEXT NOT NULL PRIMARY KEY, start INTEGER NOT NULL, end INTEGER NOT NULL, cost INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS debts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, total INTEGER NOT NULL, start INTEGER NOT NULL, due INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS debt_payments (id TEXT NOT NULL PRIMARY KEY, debtId TEXT NOT NULL, at INTEGER NOT NULL, amount INTEGER NOT NULL)")
                db.execSQL("UPDATE sessions SET state = 'CANCELLED', reference = '', recognized = 0, amount = 0, cashEquivalent = 0, warned = 1, notified = 1 WHERE home = 1")
                db.execSQL("DELETE FROM slot_reservations WHERE sessionId IN (SELECT id FROM sessions WHERE home = 1)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS balance_updates (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, at INTEGER NOT NULL, cash INTEGER NOT NULL, bank INTEGER NOT NULL, cashReceived INTEGER NOT NULL, bankReceived INTEGER NOT NULL, premiumBps INTEGER NOT NULL, reason TEXT NOT NULL)")
            }
        }
        fun getDatabase(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "app_db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { instance = it }
            }
    }
}
