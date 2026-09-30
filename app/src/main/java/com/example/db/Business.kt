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
    /** Router client id (StarlinkProtocol.Client.id) — the only device↔session identity. Null = unbound. */
    @ColumnInfo(defaultValue = "NULL") val deviceClientId: Long? = null,
    @ColumnInfo(defaultValue = "''") val deviceIp: String = "",
    @ColumnInfo(defaultValue = "''") val deviceName: String = "",
    @ColumnInfo(defaultValue = "''") val deviceMac: String = "",
) { fun clock() = Clock(duration, served, resumed, state == "ACTIVE") }

@Entity(tableName = "settings")
data class BusinessSettings(@PrimaryKey val id: Int = 1, val graceMinutes: Int = com.example.domain.Rules.RECOGNITION_MINUTES, val premiumBps: Int = 2500,
    val usdCents: Long = 0, val bankRate: Long = 0, val cycleStart: Long = 0, val cycleEnd: Long = 0, val expenses: Long = 0,
    @ColumnInfo(defaultValue = "50") val maxSubscribers: Int = 50,
    @ColumnInfo(defaultValue = "''") val cycleId: String = "")

@Entity(tableName = "sequences")
data class Sequence(@PrimaryKey val name: String = "subscriber", val next: Long = 1)

/** Home-network devices (keyed by IP, per owner's request). Never tracked for pause/resume. */
@Entity(tableName = "home_ips")
data class HomeIp(@PrimaryKey val ip: String, val label: String = "", val added: Long = 0)

/** Watch-list devices (keyed by IP). Stored and classified now; notification logic comes later. */
@Entity(tableName = "watch_ips")
data class WatchIp(@PrimaryKey val ip: String, val label: String = "", val added: Long = 0)

/**
 * Identity-keyed HOME/WATCH records (schema v8). The router's stable clientId is the
 * PRIMARY identity; IP is a mutable display/last-known address only, so a DHCP move
 * can never turn a family device into "unknown" or invent a second record.
 * Legacy home_ips/watch_ips rows are never deleted; they stay as the owner's manual
 * fallback entries, while identities are auto-discovered from successful snapshots.
 */
@Entity(tableName = "device_identities")
data class DeviceIdentity(
    @PrimaryKey val deviceId: Long,
    val list: String,
    val mac: String = "",
    val name: String = "",
    val lastIp: String = "",
    val added: Long = 0,
    val updated: Long = 0,
)

/**
 * Per-device daily confirmation (schema v8): one row per device per event day.
 * The (dayKey, deviceId) primary key makes every confirm/edit idempotent —
 * reopening the review and re-saving rewrites the SAME row, never a second
 * revenue record. Registered amount comes from the bound session; confirmed is
 * what the operator actually received (editable, defaults to registered).
 */
@Entity(tableName = "daily_device_confirmations", primaryKeys = ["dayKey", "deviceId"])
data class DailyDeviceConfirmation(
    val dayKey: String,
    val deviceId: Long,
    val sessionId: String,
    val registered: Long,
    val confirmed: Long,
    val payment: String,
    val premiumBps: Int,
    val updated: Long,
)

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
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertManualSale(sale: ManualSale)
    @Delete suspend fun deleteManualSale(sale: ManualSale)
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

    @Query("SELECT * FROM home_ips ORDER BY added, ip") fun observeHomeIps(): Flow<List<HomeIp>>
    @Query("SELECT * FROM home_ips") suspend fun homeIps(): List<HomeIp>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun homeIp(entry: HomeIp)
    @Query("DELETE FROM home_ips WHERE ip = :ip") suspend fun deleteHomeIp(ip: String)

    @Query("SELECT * FROM watch_ips ORDER BY added, ip") fun observeWatchIps(): Flow<List<WatchIp>>
    @Query("SELECT * FROM watch_ips") suspend fun watchIps(): List<WatchIp>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun watchIp(entry: WatchIp)
    @Query("DELETE FROM watch_ips WHERE ip = :ip") suspend fun deleteWatchIp(ip: String)

    @Query("SELECT * FROM device_identities ORDER BY added, deviceId") fun observeIdentities(): Flow<List<DeviceIdentity>>
    @Query("SELECT * FROM device_identities") suspend fun identities(): List<DeviceIdentity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun identity(entry: DeviceIdentity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIdentity(entry: DeviceIdentity): Long
    @Query("SELECT * FROM device_identities WHERE deviceId = :deviceId") suspend fun identity(deviceId: Long): DeviceIdentity?
    @Query("SELECT * FROM device_identities WHERE mac = :mac AND mac != '' LIMIT 1") suspend fun identityByMac(mac: String): DeviceIdentity?
    @Query("SELECT * FROM device_identities WHERE lastIp = :ip LIMIT 1") suspend fun identityByIp(ip: String): DeviceIdentity?
    @Query("DELETE FROM device_identities WHERE deviceId = :deviceId") suspend fun deleteIdentity(deviceId: Long)

    @Query("SELECT * FROM daily_device_confirmations WHERE dayKey = :dayKey") suspend fun dayConfirmations(dayKey: String): List<DailyDeviceConfirmation>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertConfirmation(entry: DailyDeviceConfirmation)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertConfirmation(entry: DailyDeviceConfirmation): Long
    @Query("DELETE FROM daily_device_confirmations WHERE dayKey = :dayKey AND deviceId IN (:deviceIds)") suspend fun deleteConfirmations(dayKey: String, deviceIds: List<Long>)
}
