package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.BillingCycle
import com.example.db.BusinessSettings
import com.example.db.Session
import com.example.domain.Finance
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * §2/§8: the automatic idempotent 18:00 daily cutoff against a real in-memory
 * Room database. The repository clock is injected (spec 33); no sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DailyCutoffRepositoryTest {
    private lateinit var db: com.example.db.AppDatabase
    private lateinit var repo: SubscriptionRepository
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 0L

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, com.example.db.AppDatabase::class.java).build()
        repo = SubscriptionRepository(context, db) { now }
        repo.initialize()
        now = at(5, 19, 30) // Monday 19:30 — past the 18:00 cutoff
    }
    @After fun close() { db.close() }

    private fun session(
        id: String,
        started: Long,
        state: String = "ACTIVE",
        recognized: Long = started + 300_000L,
        payment: String = "CASH",
        amount: Long = 50000L,
        home: Boolean = false,
        served: Long = 0L,
    ) = Session(id = id, client = "مشترك $id", plan = "ساعة", started = started,
        resumed = started, duration = 12 * 3_600_000L, served = served, state = state,
        amount = amount, cashEquivalent = amount, payment = payment,
        premiumBps = 2500, home = home, grace = 300_000L, recognized = recognized)

    private suspend fun byId(id: String) = repo.dao.session(id)!!

    @Test fun cutoffEndsDaySessionsAt1800AndStoresSummary() = runBlocking {
        repo.dao.insertSession(session("s1", at(5, 10)))
        repo.dao.insertSession(session("s2", at(5, 11), state = "PAUSED", served = 3_600_000L))
        repo.dao.insertSession(session("s3", at(5, 19), payment = "BANK")) // post-cutoff: next day

        repo.applyDailyCutoff(now)

        val s1 = byId("s1")
        assertEquals("ENDED", s1.state)
        assertEquals(at(5, 18) - at(5, 10), s1.served) // actual elapsed at 18:00, not full duration
        val s2 = byId("s2")
        assertEquals("ENDED", s2.state)
        assertEquals(3_600_000L, s2.served) // paused time stays frozen
        // Post-cutoff sale untouched: it belongs to the next business day.
        assertEquals("ACTIVE", byId("s3").state)

        val row = repo.dao.dailyCutoff("2026-10-05")!!
        assertEquals(at(5, 18), row.cutoffAt)
        assertEquals(2, row.sessionsEnded)
        assertEquals(2, row.subscribedCount)
        assertEquals(100_000L, row.totalRevenue)
        assertEquals(100_000L, row.cashTotal)
        assertEquals(0L, row.bankTotal)
    }

    @Test fun repeatedCutoffIsANoOp() = runBlocking {
        repo.dao.insertSession(session("s1", at(5, 10)))
        repo.applyDailyCutoff(now)
        val afterFirst = byId("s1")
        repo.applyDailyCutoff(now)
        repo.applyDailyCutoff(at(5, 23))
        assertEquals(1, repo.dao.cutoffs().size)
        assertEquals(afterFirst, byId("s1")) // nothing re-ended, nothing rewritten
        // No duplicate revenue: one ledger row per session, no extra rows.
        val ledger = Finance.ledger(repo.dao.sessions(), repo.dao.manualSales(), emptyList())
        assertEquals(1, ledger.count { it.id == "session:s1" })
    }

    @Test fun closedDaySessionsAreFrozen() = runBlocking {
        repo.dao.insertSession(session("s1", at(5, 10)))
        repo.applyDailyCutoff(now)
        // No-op actions stay silent; real mutations are rejected.
        repo.changeState("s1", "PAUSE") // ENDED already: no-op, no throw
        try { repo.rename("s1", "new name"); fail("rename on a closed day must fail") }
        catch (e: IllegalArgumentException) { }
        try { repo.bindDevice("s1", null); fail("rebind on a closed day must fail") }
        catch (e: IllegalArgumentException) { }
        try { repo.correctRevenue("session:s1", 40000L, 1, false, "test"); fail("correction on a closed day must fail") }
        catch (e: IllegalArgumentException) { }
    }

    @Test fun missedDaysAreBackfilled() = runBlocking {
        repo.dao.insertSession(session("old", at(2, 10))) // 3 days ago, still ACTIVE
        repo.applyDailyCutoff(now)
        assertEquals("ENDED", byId("old").state)
        val row = repo.dao.dailyCutoff("2026-10-02")!!
        assertEquals(at(2, 18), row.cutoffAt)
        assertEquals(1, row.sessionsEnded)
    }

    @Test fun reopeningIsANewPaidEventEndingAtNextCutoff() = runBlocking {
        // A brand-new sale after the cutoff: fresh ledger, next business day.
        val sale = session("s8", at(5, 20))
        repo.dao.insertSession(sale)
        repo.applyDailyCutoff(at(5, 21))
        assertEquals("ACTIVE", byId("s8").state) // not swept into the closed day
        repo.applyDailyCutoff(at(6, 19))
        val closed = byId("s8")
        assertEquals("ENDED", closed.state)
        // 22h elapsed but the 12h plan caps served time honestly.
        assertEquals(12 * 3_600_000L, closed.served)
        assertEquals(1, repo.dao.cutoffs().count { it.dayKey == "2026-10-06" })
    }

    @Test fun homeSessionsAreNeverCutoffCounted() = runBlocking {
        repo.dao.insertSession(session("h", at(5, 10), home = true, recognized = 0L))
        repo.applyDailyCutoff(now)
        // A day with no paid sessions gets no cutoff row at all: HOME is never money.
        assertNull(repo.dao.dailyCutoff("2026-10-05"))
        assertTrue(repo.dao.cutoffs().isEmpty())
    }

    // ---- §9: invoice payments (USD, owner's rule 2026-10-04) ----

    private suspend fun configureCycle() {
        repo.dao.settings(BusinessSettings(cycleId = "c1", usdCents = 8500, bankRate = 8500))
        repo.dao.cycle(BillingCycle("c1", at(1, 0), at(1, 0) + 30 * 86400_000L, 100_000L))
    }

    @Test fun invoicePaymentRecordedAndBoundedByRemainingUsd() = runBlocking {
        configureCycle()
        // $30 at 8100 → actual outlay 243,000.
        repo.payInvoice(3000, 8100, "دفعة أولى")
        assertEquals(243_000L, repo.dao.invoicePaymentsForCycle("c1").sumOf { it.sdgPaid })
        assertEquals(3000L, repo.dao.invoicePaymentsForCycle("c1").sumOf { it.usdCents })
        try { repo.payInvoice(6000, 8100, "زيادة"); fail("must not exceed remaining USD") }
        catch (e: IllegalArgumentException) { }
        repo.payInvoice(5500, 8200, "الباقي")
        assertEquals(8500L, repo.dao.invoicePaymentsForCycle("c1").sumOf { it.usdCents })
        try { repo.payInvoice(1, 8100, "بعد الاكتمال"); fail("must not exceed remaining USD") }
        catch (e: IllegalArgumentException) { }
    }

    @Test fun invoicePaymentNeedsConfiguredCycle() = runBlocking {
        try { repo.payInvoice(1000, 8100, "x"); fail("needs a billing cycle") }
        catch (e: IllegalArgumentException) { }
    }

    // ---- §5: HOME exclusion flips existing sessions ----

    @Test fun markingHomeExcludesExistingSessionsByClientIdOrMac() = runBlocking {
        repo.dao.insertSession(session("a", at(5, 10)).copy(deviceClientId = 42L, deviceMac = "AA:BB:CC:DD:EE:FF"))
        repo.dao.insertSession(session("b", at(5, 11)).copy(deviceMac = "11:22:33:44:55:66"))
        repo.dao.insertSession(session("c", at(5, 12)).copy(deviceClientId = 99L))
        repo.excludeHomeSessions(42L, "")
        assertTrue(byId("a").home)
        assertFalse(byId("b").home)
        repo.excludeHomeSessions(1000L, "11:22:33:44:55:66") // MAC-only match
        assertTrue(byId("b").home)
        repo.excludeHomeSessions(1000L, "XX:XX:XX:XX:XX:66") // masked MAC never matches
        assertFalse(byId("c").home)
        // Excluded sessions leave the finance ledger immediately.
        val ledger = Finance.ledger(repo.dao.sessions(), repo.dao.manualSales(), emptyList())
        assertTrue(ledger.none { it.id == "session:a" || it.id == "session:b" })
        assertEquals(1, ledger.count { it.id == "session:c" })
    }
}
