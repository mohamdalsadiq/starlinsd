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
data class BusinessSettings(@PrimaryKey val id: Int = 1, val graceMinutes: Int = com.example.domain.Rules.RECOGNITION_MINUTES, val premiumBps: Int = 2500,
    val usdCents: Long = 0, val bankRate: Long = 0, val cycleStart: Long = 0, val cycleEnd: Long = 0, val expenses: Long = 0,
    @ColumnInfo(defaultValue = "50") val maxSubscribers: Int = 50,
    @ColumnInfo(defaultValue = "''") val cycleId: String = "")

@Entity(tableName = "sequences")
data class Sequence(@PrimaryKey val name: String = "subscriber", val next: Long = 1)

@Entity(tableName = "manual_sales")
data class ManualSale(@PrimaryKey val id: String, val at: Long, val count: Int, val unitPrice: Long,
    val amount: Long, val cashEquivalent: Long, val payment: String, val premiumBps: Int)

@Entity(tableName = "slot_reservations")
data class SlotReservation(@PrimaryKey val number: Int, val sessionId: String, val expires: Long)

@Entity(tableName = "revenue_corrections")
data class RevenueCorrection(@PrimaryKey(autoGenerate = true) val id: Long = 0, val source: String,
    val amount: Long, val cashEquivalent: Long, val count: Int, val voided: Boolean, val at: Long, val reason: String)
@Entity(tableName = "billing_cycles")
data class BillingCycle(@PrimaryKey val id: String, val start: Long, val end: Long, val cost: Long)
@Entity(tableName = "debts")
data class Debt(@PrimaryKey val id: String, val name: String, val total: Long, val start: Long, val due: Long)
@Entity(tableName = "debt_payments")
data class DebtPayment(@PrimaryKey val id: String, val debtId: String, val at: Long, val amount: Long)

@Entity(tableName = "balance_updates")
data class BalanceUpdate(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long,
    val cash: Long, val bank: Long, val cashReceived: Long, val bankReceived: Long,
    val premiumBps: Int, val reason: String, val expectedCash: Long = 0, val expectedBank: Long = 0)

@Dao
interface BusinessDao {
    @Query("SELECT * FROM balance_updates ORDER BY at, id") fun observeBalanceUpdates(): Flow<List<BalanceUpdate>>
    @Query("SELECT * FROM balance_updates ORDER BY at, id") suspend fun balanceUpdates(): List<BalanceUpdate>
    @Insert suspend fun balanceUpdate(update: BalanceUpdate): Long

    @Query("SELECT * FROM revenue_corrections ORDER BY id") fun observeCorrections(): Flow<List<RevenueCorrection>>
    @Query("SELECT * FROM revenue_corrections ORDER BY id") suspend fun corrections(): List<RevenueCorrection>
    @Insert suspend fun correct(correction: RevenueCorrection)
    @Query("SELECT * FROM billing_cycles ORDER BY start") fun observeCycles(): Flow<List<BillingCycle>>
    @Query("SELECT * FROM billing_cycles ORDER BY start") suspend fun cycles(): List<BillingCycle>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cycle(cycle: BillingCycle)
    @Query("SELECT * FROM debts ORDER BY due, id") fun observeDebts(): Flow<List<Debt>>
    @Query("SELECT * FROM debts ORDER BY due, id") suspend fun debts(): List<Debt>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun debt(debt: Debt)
    @Query("SELECT * FROM debt_payments ORDER BY at, id") fun observeDebtPayments(): Flow<List<DebtPayment>>
    @Query("SELECT * FROM debt_payments ORDER BY at, id") suspend fun debtPayments(): List<DebtPayment>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun payDebt(payment: DebtPayment)

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
