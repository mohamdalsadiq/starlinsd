package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.BillingCycle
import com.example.db.BusinessSettings
import com.example.domain.Money
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
 * §10 (owner's rule 2026-10-07): when the configured billing cycle ends, a new
 * cycle starts automatically with the same length — the old cycle is kept in
 * history and the new one starts at zero.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CycleRolloverTest {
    private lateinit var db: com.example.db.AppDatabase
    private lateinit var repo: SubscriptionRepository
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = 86400000L

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, com.example.db.AppDatabase::class.java).build()
        repo = SubscriptionRepository(context, db) { System.currentTimeMillis() }
        repo.initialize()
    }

    @After fun close() { db.close() }

    private suspend fun configure(start: Long, end: Long) {
        val dao = db.businessDao()
        // usdCents=$85, bankRate=8400, premium 30%: cost = 85*8400/1.3 + 1000 expenses.
        dao.settings(BusinessSettings(usdCents = 8500, bankRate = 840000, premiumBps = 3000,
            expenses = 100000, cycleId = "c1", cycleStart = start, cycleEnd = end))
        val cost = Math.addExact(Money.bankToCash(Money.bill(8500, 840000), 3000), 100000)
        dao.cycle(BillingCycle("c1", start, end, cost))
    }

    @Test fun noRolloverBeforeCycleEnd() = runBlocking {
        val start = Revenue.day(System.currentTimeMillis())
        configure(start, start + 30 * day)
        assertFalse(repo.ensureCurrentCycle(start + 10 * day))
        val s = db.businessDao().settings()!!
        assertEquals(start, s.cycleStart)
        assertEquals("c1", s.cycleId)
        assertEquals(1, db.businessDao().cycles().size)
    }

    @Test fun rollsOverAtCycleEndKeepingHistory() = runBlocking {
        val start = Revenue.day(System.currentTimeMillis()) - 40 * day
        val end = start + 30 * day
        configure(start, end)
        assertTrue(repo.ensureCurrentCycle(end + 3600000))
        val s = db.businessDao().settings()!!
        assertEquals(end, s.cycleStart)
        assertEquals(end + 30 * day, s.cycleEnd)
        assertNotEquals("c1", s.cycleId)
        val cycles = db.businessDao().cycles()
        assertEquals(2, cycles.size)
        assertTrue(cycles.any { it.id == "c1" && it.start == start && it.end == end })
        val fresh = cycles.single { it.id == s.cycleId }
        val expectedCost = Math.addExact(Money.bankToCash(Money.bill(8500, 840000), 3000), 100000)
        assertEquals(expectedCost, fresh.cost)
    }

    @Test fun noRolloverWhenCycleNotConfigured() = runBlocking {
        db.businessDao().settings(BusinessSettings())
        assertFalse(repo.ensureCurrentCycle(System.currentTimeMillis()))
    }
}
