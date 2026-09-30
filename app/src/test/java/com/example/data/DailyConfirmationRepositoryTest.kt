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
    }
}
