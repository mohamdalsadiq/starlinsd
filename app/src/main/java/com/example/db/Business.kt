package com.example.db

import androidx.room.*
import com.example.domain.Clock
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "plans")
data class Plan(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val minutes: Int, val cash: Long, val bank: Long, val home: Boolean = false, val enabled: Boolean = true)

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey val id: String, val client: String, val plan: String, val started: Long,
    val resumed: Long, val duration: Long, val served: Long = 0, val state: String = "ACTIVE",
    val amount: Long, val cashEquivalent: Long, val payment: String, val premiumBps: Int,
    val home: Boolean, val grace: Long, val recognized: Long = 0,
    val warned: Boolean = false, val notified: Boolean = false, val source: String = "manual",
) { fun clock() = Clock(duration, served, resumed, state == "ACTIVE") }

@Entity(tableName = "settings")
data class BusinessSettings(@PrimaryKey val id: Int = 1, val graceMinutes: Int = 30, val premiumBps: Int = 2500,
    val usdCents: Long = 0, val bankRate: Long = 0, val cycleStart: Long = 0, val cycleEnd: Long = 0, val expenses: Long = 0)

@Dao
interface BusinessDao {
    @Query("SELECT * FROM plans ORDER BY minutes, id") fun observePlans(): Flow<List<Plan>>
    @Query("SELECT * FROM plans ORDER BY id") suspend fun plans(): List<Plan>
    @Query("SELECT * FROM plans WHERE id = :id") suspend fun plan(id: Long): Plan?
    @Insert suspend fun insertPlan(plan: Plan): Long
    @Update suspend fun updatePlan(plan: Plan)
    @Query("SELECT * FROM sessions ORDER BY started DESC") fun observeSessions(): Flow<List<Session>>
    @Query("SELECT * FROM sessions ORDER BY started DESC") suspend fun sessions(): List<Session>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): Session?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSession(session: Session): Long
    @Update suspend fun updateSession(session: Session)
    @Query("SELECT * FROM settings WHERE id = 1") fun observeSettings(): Flow<BusinessSettings?>
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun settings(): BusinessSettings?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun settings(settings: BusinessSettings)
}
