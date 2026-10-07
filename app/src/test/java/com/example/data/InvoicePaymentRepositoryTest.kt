package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.BillingCycle
import com.example.db.BusinessSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §9 (owner's rule 2026-10-04): partial USD bill payments against a real
 * in-memory Room database. $85 bill, fixed accounting rate 8500.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InvoicePaymentRepositoryTest {
    private lateinit var db: com.example.db.AppDatabase
    private lateinit var repo: SubscriptionRepository
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, com.example.db.AppDatabase::class.java).build()
        repo = SubscriptionRepository(context, db) { System.currentTimeMillis() }
        repo.initialize()
        val dao = db.businessDao()
        dao.settings(BusinessSettings(usdCents = 8500, bankRate = 8500, cycleId = "c1",
            cycleStart = 1L, cycleEnd = 2L))
        dao.cycle(BillingCycle("c1", 1L, 2L, 722500L))
    }

    @After fun close() { db.close() }

    @Test fun recordsUsdPaymentWithActualOutlay() = runBlocking {
        // $30 at 8100 → actual outlay 243,000 (not the 255,000 accounting value).
        repo.payInvoice(3000, 8100, "شراء أول")
        val rows = db.businessDao().invoicePaymentsForCycle("c1")
        assertEquals(1, rows.size)
        assertEquals(3000L, rows[0].usdCents)
        assertEquals(8100L, rows[0].ratePerUsd)
        assertEquals(243000L, rows[0].sdgPaid)
        assertEquals("c1", rows[0].cycleId)
    }

    @Test fun cannotBuyMoreThanRemainingUsd() = runBlocking {
        repo.payInvoice(3000, 8100, "")
        try {
            repo.payInvoice(6000, 8100, "")
            fail("expected overpayment to fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("المتبقي"))
        }
        // Exactly the remaining $55 is fine; anything after is rejected.
        repo.payInvoice(5500, 8200, "")
        try {
            repo.payInvoice(1, 8100, "")
            fail("expected post-completion payment to fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("المتبقي"))
        }
        val rows = db.businessDao().invoicePaymentsForCycle("c1")
        assertEquals(8500L, rows.sumOf { it.usdCents })
    }

    @Test fun rejectsInvalidInput() = runBlocking {
        for ((usd, rate) in listOf(0L to 8100L, -5L to 8100L, 3000L to 0L, 3000L to -1L)) {
            try {
                repo.payInvoice(usd, rate, "")
                fail("expected validation failure for $usd/$rate")
            } catch (e: IllegalArgumentException) { /* expected */ }
        }
        assertTrue(db.businessDao().invoicePaymentsForCycle("c1").isEmpty())
    }

    @Test fun stableAuditDeviceIdIsStableNegativeAndDeterministic() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val first = DailyReconciliation.stableAuditDeviceId(id)
        val second = DailyReconciliation.stableAuditDeviceId(id)
        assertEquals(first, second)
        assertTrue("audit key must be negative, got $first", first < 0)
        val other = DailyReconciliation.stableAuditDeviceId("123e4567-e89b-12d3-a456-426614174001")
        assertNotEquals(first, other)
        assertTrue(other < 0)
    }
}
