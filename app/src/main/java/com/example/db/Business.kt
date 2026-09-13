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
    @ColumnInfo(defaultValue = "''") val reference: String = "",
) { fun clock() = Clock(duration, served, resumed, state == "ACTIVE") }

@Entity(tableName = "settings")
data class BusinessSettings(@PrimaryKey val id: Int = 1, val graceMinutes: Int = 30, val premiumBps: Int = 2500,
    val usdCents: Long = 0, val bankRate: Long = 0, val cycleStart: Long = 0, val cycleEnd: Long = 0, val expenses: Long = 0,
    @ColumnInfo(defaultValue = "50") val maxSubscribers: Int = 50)

@Entity(tableName = "sequences")
data class Sequence(@PrimaryKey val name: String = "subscriber", val next: Long = 1)

@Entity(tableName = "manual_sales")
data class ManualSale(@PrimaryKey val id: String, val at: Long, val count: Int, val unitPrice: Long,
    val amount: Long, val cashEquivalent: Long, val payment: String, val premiumBps: Int)

@Entity(tableName = "slot_reservations")
data class SlotReservation(@PrimaryKey val number: Int, val sessionId: String, val expires: Long)

@Dao
interface BusinessDao {
    @Query("SELECT EXISTS(SELECT 1 FROM manual_sales WHERE id LIKE :prefix)") suspend fun hasSaleBatch(prefix: String): Boolean
    @Query("SELECT * FROM manual_sales ORDER BY at DESC") fun observeManualSales(): Flow<List<ManualSale>>
    @Query("SELECT * FROM manual_sales ORDER BY at DESC") suspend fun manualSales(): List<ManualSale>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertManualSale(sale: ManualSale): Long
    @Query("SELECT * FROM slot_reservations") suspend fun reservations(): List<SlotReservation>
    @Insert suspend fun reserve(reservation: SlotReservation)
    @Query("DELETE FROM slot_reservations WHERE sessionId = :id") suspend fun release(id: String)
    @Query("DELETE FROM slot_reservations WHERE expires <= :now") suspend fun clearExpiredReservations(now: Long)

    @Query("SELECT `next` FROM sequences WHERE name = 'subscriber'") suspend fun nextReference(): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun sequence(sequence: Sequence)
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
