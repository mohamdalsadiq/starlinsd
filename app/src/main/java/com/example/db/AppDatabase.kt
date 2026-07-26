package com.example.db

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
    val phrase: String
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
}

@Database(entities = [Device::class, Shortcut::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun shortcutDao(): ShortcutDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "app_db")
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
