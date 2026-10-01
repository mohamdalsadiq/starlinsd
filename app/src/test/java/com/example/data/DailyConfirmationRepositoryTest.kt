package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.domain.Finance
import com.example.domain.Revenue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for the per-device daily confirmation (§40 report point H,
 * redesigned by device-identity-reconciliation-v1): confirmDailyDevices upsert
 * semantics against a real in-memory Room database. No sleeps; the repository
 * clock is injected (spec 33).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DailyConfirmationRepositoryTest {
    private lateinit var db: com.example.db.AppDatabase
    private lateinit var repo: SubscriptionRepository
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1759000000000L
    private val eventDay = "2026-09-28"

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, com.example.db.AppDatabase::class.java).build()
        repo = SubscriptionRepository(context, db) { now }
        repo.initialize()
    }
    @After fun close() { db.close() }

    private suspend fun ledger() = Finance.ledger(repo.dao.sessions(), repo.dao.manualSales(), emptyList())
    private fun amount(deviceId: Long, sessionId: String, confirmed: Long) =
        SubscriptionRepository.DailyDeviceAmount(deviceId, sessionId, confirmed)

    /** Per-device entries land as (dayKey, deviceId) rows plus ONE ledger row for the event day. */
    @Test fun perDeviceConfirmationIsVisibleInLedgerForEventDay() = runBlocking {
        repo.confirmDailyDevices(eventDay, listOf(amount(1, "", 50000L), amount(2, "", 100000L)), "CASH")
        val rows = repo.dao.dayConfirmations(eventDay).sortedBy { it.deviceId }
        assertEquals(listOf(1L, 2L), rows.map { it.deviceId })
        assertEquals(150000L, rows.sumOf { it.confirmed })
        val ledgerRow = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay).single()
        assertEquals(DailyReconciliation.dailyRowId(eventDay), ledgerRow.id)
        assertEquals(150000L, ledgerRow.amount)
        assertEquals(Revenue.day(ledgerRow.at), Revenue.day(ledgerRow.id.removePrefix("rev-").take(10).let {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(it)!!.time
        }.coerceAtLeast(0)) )
    }

    /** Confirming twice with the same devices stores one copy of the money (spec 14). */
    @Test fun repeatedConfirmationDoesNotDoubleCount() = runBlocking {
        repo.confirmDailyDevices(eventDay, listOf(amount(1, "", 50000L), amount(2, "", 100000L)), "CASH")
        now += 60 * 60_000L // reopen the review an hour later and confirm again
        repo.confirmDailyDevices(eventDay, listOf(amount(1, "", 50000L), amount(2, "", 100000L)), "CASH")
        val rows = repo.dao.dayConfirmations(eventDay)
        assertEquals(2, rows.size)
        val ledger = ledger().filter { it.id.startsWith("manual:") && !it.voided }
        assertEquals(1, ledger.size)
        assertEquals(150000L, ledger.single().value)
    }

    /** Editing 1000→500 rewrites the SAME device row in place instead of adding one (spec 15). */
    @Test fun editingConfirmedAmountReplacesNotAppends() = runBlocking {
        repo.confirmDailyDevices(eventDay, listOf(amount(1, "", 100000L)), "CASH")
        now += 60 * 60_000L
        repo.confirmDailyDevices(eventDay, listOf(amount(1, "", 50000L)), "CASH")
        val rows = repo.dao.dayConfirmations(eventDay)
        assertEquals(1, rows.size)
        assertEquals(50000L, rows[0].confirmed)
        assertEquals(DailyReconciliation.dailyRowId(eventDay), DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay).single().id)
        assertEquals(50000L, DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay).single().amount)
    }

    /** A device dropped from the new list loses its stored row (and its money). */
    @Test fun droppedDevicesDeleteTheirStoredRow() = runBlocking {
        repo.confirmDailyDevices(eventDay, listOf(amount(1, "", 50000L), amount(2, "", 100000L)), "CASH")
        now += 60 * 60_000L
        repo.confirmDailyDevices(eventDay, listOf(amount(2, "", 100000L)), "CASH")
        val rows = repo.dao.dayConfirmations(eventDay)
        assertEquals(listOf(2L), rows.map { it.deviceId })
        assertEquals(100000L, DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay).single().amount)
    }

    /** Different days never collide: each event day owns its own deterministic rows. */
    @Test fun otherDaysAreUntouched() = runBlocking {
        repo.confirmDailyDevices(eventDay, listOf(amount(1, "", 50000L)), "CASH")
        repo.confirmDailyDevices("2026-09-29", listOf(amount(1, "", 50000L)), "CASH")
        assertEquals(1, repo.dao.dayConfirmations(eventDay).size)
        assertEquals(1, repo.dao.dayConfirmations("2026-09-29").size)
    }

    /** Duplicate device entries are rejected before any write. */
    @Test fun duplicateDeviceEntriesAreRejectedWithoutWriting() = runBlocking {
        try {
            repo.confirmDailyDevices(eventDay, listOf(amount(7, "", 50000L), amount(7, "", 30000L)), "CASH")
            fail("the same deviceId twice must fail")
        } catch (expected: IllegalArgumentException) { }
        assertTrue(repo.dao.dayConfirmations(eventDay).isEmpty())
        assertTrue(repo.dao.manualSales().isEmpty())
    }

    /** Bank payment is recorded; the ledger row stays cash-equivalent (single conversion). */
    @Test fun bankPaymentIsRecordedOnTheDeviceRow() = runBlocking {
        repo.confirmDailyDevices(eventDay, listOf(amount(1, "", 125000L)), "BANK")
        val row = repo.dao.dayConfirmations(eventDay).single()
        assertEquals("BANK", row.payment)
        assertEquals(125000L, row.confirmed)
        val ledgerRow = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay).single()
        assertEquals(125000L, ledgerRow.amount)
        assertEquals("BANK", ledgerRow.payment)
        assertEquals(100000L, ledgerRow.cashEquivalent)
    }

    /**
     * Shortcut binding writes the router clientId onto the session: clientId is
     * the permanent identity (IP/name are display only). A later IP change must
     * not detach the subscription.
     */
    @Test fun bindDeviceRecordsClientIdIdentity() = runBlocking {
        val sessionId = boundSession(101L)
        val s = repo.dao.session(sessionId)!!
        assertEquals(101L, s.deviceClientId)
        // The DHCP address is stored for display; identity stays the clientId.
        assertEquals("192.168.1.50", s.deviceIp)
    }

    /** A device already bound to an ACTIVE session is never a binding candidate. */
    @Test fun boundDeviceIsExcludedFromBindingCandidates() = runBlocking {
        boundSession(101L)
        val bound = repo.dao.sessions()
            .filter { !it.home && it.state in listOf("ACTIVE", "PAUSED") && it.deviceClientId != null }
            .mapNotNull { it.deviceClientId }.toSet()
        assertTrue(bound.contains(101L))
        val now = System.currentTimeMillis()
        val live = listOf(
            TrackedDevice(101L, "[9] subscribed", "192.168.1.50", "aa:bb:cc:dd:ee:ff", IpLists.Category.UNKNOWN),
            TrackedDevice(102L, "[9] free", "192.168.1.51", "aa:bb:cc:dd:ee:00", IpLists.Category.UNKNOWN),
        )
        val sighted = mapOf(
            "${DeviceAlerts.dayKey(now)}:101:9" to now,
            "${DeviceAlerts.dayKey(now)}:102:9" to now,
        )
        val picked = ShortcutBinding.matchStamp(live, "9", now - 60_000L, sighted, now,
            bound, emptySet(), emptySet(), emptySet())
        assertEquals(102L, picked?.clientId)
    }

    private suspend fun boundSession(clientId: Long): String {
        val plan = repo.dao.plans().first { it.minutes == 180 && !it.home }
        val prepared = repo.prepare("مشترك", plan.id, "CASH", "test")
        repo.insert(prepared)
        repo.bindDevice(prepared.id, TrackedDevice(clientId, "جهاز", "192.168.1.50", "aa:bb:cc:dd:ee:ff", IpLists.Category.UNKNOWN))
        return prepared.id
    }

    /**
     * Bug E regression: a subscribed device's amount is audit-only. Confirming the
     * day must NOT write the registered subscription amount into manual_sales —
     * it already exists as session revenue. Only the unregistered device creates
     * reconciliation money (spec 14).
     */
    @Test fun subscribedAmountsDoNotCreateLedgerMoney() = runBlocking {
        val sessionId = boundSession(101L)
        repo.confirmDailyDevices(eventDay, listOf(
            amount(101L, sessionId, 50000L),  // subscribed: audit row only
            amount(102L, "", 100000L),       // unregistered: creates ledger money
        ), "CASH")
        val rows = repo.dao.dayConfirmations(eventDay).sortedBy { it.deviceId }
        assertEquals(listOf(101L, 102L), rows.map { it.deviceId })
        val sales = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay)
        assertEquals(1, sales.size)
        assertEquals(DailyReconciliation.dailyRowId(eventDay), sales.single().id)
        assertEquals(100000L, sales.single().amount)
        assertEquals(1, sales.single().count)
    }

    /** A day with only subscribed devices writes no ledger row: nothing new to reconcile. */
    @Test fun subscribedOnlyDayWritesNoLedgerRow() = runBlocking {
        val sessionId = boundSession(101L)
        repo.confirmDailyDevices(eventDay, listOf(amount(101L, sessionId, 50000L)), "CASH")
        assertEquals(1, repo.dao.dayConfirmations(eventDay).size)
        assertTrue(DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay).isEmpty())
    }

    // ---- Aggregate unregistered summary ----

    /** The summary writes one aggregate row and one ledger row; re-saving rewrites both in place. */
    @Test fun unregisteredSummaryIsAggregateAndIdempotent() = runBlocking {
        repo.confirmUnregisteredSummary(eventDay, 3, 50000L, 1, "CASH")
        var summary = repo.dao.unregisteredSummary(eventDay)!!
        assertEquals(3, summary.deviceCount)
        assertEquals(1, summary.unpaidCount)
        assertEquals(50000L, summary.tariff)
        assertEquals(100000L, summary.netTotal)
        var ledgers = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay)
        assertEquals(1, ledgers.size)
        assertEquals(DailyReconciliation.dailyRowId(eventDay), ledgers.single().id)
        assertEquals(100000L, ledgers.single().amount)
        assertEquals(2, ledgers.single().count)
        // Re-confirm with different numbers: the SAME rows, rewritten — never a second ledger row.
        repo.confirmUnregisteredSummary(eventDay, 4, 50000L, 0, "CASH")
        summary = repo.dao.unregisteredSummary(eventDay)!!
        assertEquals(4, summary.deviceCount)
        assertEquals(200000L, summary.netTotal)
        ledgers = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay)
        assertEquals(1, ledgers.size)
        assertEquals(200000L, ledgers.single().amount)
    }

    /** Subscribed sessions stay out of the summary ledger: the day's money is counted once. */
    @Test fun subscribedSessionsDoNotEnterSummaryLedger() = runBlocking {
        val sessionId = boundSession(101L)
        val session = repo.dao.session(sessionId)!!
        // Subscribed pass: audit row only, no ledger money.
        repo.confirmDailyDevices(eventDay, listOf(amount(101L, sessionId, session.amount)), "CASH")
        // Summary pass: only the unregistered aggregate becomes ledger money.
        repo.confirmUnregisteredSummary(eventDay, 2, 50000L, 0, "CASH")
        val ledgers = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay)
        assertEquals(1, ledgers.size)
        assertEquals(100000L, ledgers.single().amount)
        assertEquals(1, repo.dao.dayConfirmations(eventDay).size) // the subscribed audit row survives
    }

    /** Invalid summary input fails before any write. */
    @Test fun invalidSummaryIsRejectedWithoutWriting() = runBlocking {
        try {
            repo.confirmUnregisteredSummary(eventDay, 2, 50000L, 5, "CASH")
            fail("unpaid > count must fail")
        } catch (expected: IllegalArgumentException) { }
        assertNull(repo.dao.unregisteredSummary(eventDay))
        assertTrue(DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay).isEmpty())
    }

    /** Legacy per-device unregistered rows are cleaned when the aggregate is saved. */
    @Test fun summarySaveCleansLegacyPerDeviceUnregisteredRows() = runBlocking {
        repo.confirmDailyDevices(eventDay, listOf(amount(7, "", 50000L)), "CASH")
        assertEquals(1, repo.dao.dayConfirmations(eventDay).size)
        repo.confirmUnregisteredSummary(eventDay, 1, 50000L, 0, "CASH")
        assertTrue(repo.dao.dayConfirmations(eventDay).isEmpty())
        assertEquals(50000L, DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay).single().amount)
    }
}
