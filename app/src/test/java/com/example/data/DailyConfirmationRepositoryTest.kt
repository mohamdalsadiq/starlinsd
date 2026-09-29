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
 * Integration tests for the Phase 4 daily device confirmation (§40 report point H):
 * confirmDailyRevenue upsert semantics against a real in-memory Room database.
 * No sleeps; the repository clock is injected (spec 33).
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

    /** 500×1 + 1000×2 lands as two rev- rows, visible in the ledger for the event day. */
    @Test fun groupedConfirmationIsVisibleInLedgerForEventDay() = runBlocking {
        repo.confirmDailyRevenue(eventDay, 3, listOf(1 to 50000L, 2 to 100000L), "CASH")
        val rows = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay)
        assertEquals(listOf("rev-$eventDay:0", "rev-$eventDay:1"), rows.map { it.id })
        assertEquals(250000L, rows.sumOf { it.amount })
        val day = Revenue.day(rows[0].at)
        val ledgerDay = ledger().filter { Revenue.day(it.at) == day && !it.voided }
        // Manual review rows are the only money that day: the ledger shows them for the event day.
        assertEquals(250000L, ledgerDay.sumOf { it.value })
        assertEquals(2, ledgerDay.size)
    }

    /** Confirming twice with the same groups stores one copy of the money (spec 14). */
    @Test fun repeatedConfirmationDoesNotDoubleCount() = runBlocking {
        repo.confirmDailyRevenue(eventDay, 3, listOf(1 to 50000L, 2 to 100000L), "CASH")
        now += 60 * 60_000L // reopen the review an hour later and confirm again
        repo.confirmDailyRevenue(eventDay, 3, listOf(1 to 50000L, 2 to 100000L), "CASH")
        val rows = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay)
        assertEquals(2, rows.size)
        assertEquals(250000L, rows.sumOf { it.amount })
    }

    /** Editing 2500→3000 rewrites in place instead of adding a second entry (spec 15). */
    @Test fun editingConfirmedGroupsReplacesNotAppends() = runBlocking {
        repo.confirmDailyRevenue(eventDay, 3, listOf(1 to 50000L, 2 to 100000L), "CASH")
        now += 60 * 60_000L
        repo.confirmDailyRevenue(eventDay, 3, listOf(3 to 100000L), "CASH")
        val rows = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay)
        assertEquals(listOf("rev-$eventDay:0"), rows.map { it.id })
        assertEquals(300000L, rows[0].amount)
        assertEquals(3, rows[0].count)
    }

    /** A shorter replacement list deletes the superseded rows. */
    @Test fun shrinkingGroupsDeletesSupersededRows() = runBlocking {
        repo.confirmDailyRevenue(eventDay, 3, listOf(1 to 50000L, 2 to 100000L), "CASH")
        now += 60 * 60_000L
        repo.confirmDailyRevenue(eventDay, 3, listOf(2 to 100000L), "CASH")
        val rows = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay)
        assertEquals(listOf("rev-$eventDay:0"), rows.map { it.id })
        assertEquals(200000L, rows.sumOf { it.amount })
    }

    /** Different days never collide: each event day owns its own deterministic rows. */
    @Test fun otherDaysAreUntouched() = runBlocking {
        repo.confirmDailyRevenue(eventDay, 3, listOf(1 to 50000L), "CASH")
        repo.confirmDailyRevenue("2026-09-29", 2, listOf(1 to 50000L), "CASH")
        assertEquals(1, DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay).size)
        assertEquals(1, DailyReconciliation.rowsFor(repo.dao.manualSales(), "2026-09-29").size)
    }

    /** Spec 21 enforced at the repository layer, not only in the UI. */
    @Test fun overAllocationIsRejectedWithoutWriting() = runBlocking {
        try {
            repo.confirmDailyRevenue(eventDay, 3, listOf(5 to 100000L), "CASH")
            fail("allocating 5 devices against 3 must fail")
        } catch (expected: IllegalArgumentException) { }
        assertTrue(repo.dao.manualSales().none { DailyReconciliation.dayKeyOf(it.id) == eventDay })
    }

    /** Bank payment keeps the project's premium conversion rule. */
    @Test fun bankPaymentAppliesPremiumLikeAddSales() = runBlocking {
        repo.confirmDailyRevenue(eventDay, 1, listOf(1 to 125000L), "BANK")
        val row = DailyReconciliation.rowsFor(repo.dao.manualSales(), eventDay).single()
        assertEquals(100000L, row.cashEquivalent)
    }
}
