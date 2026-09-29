package com.example.data

import com.example.db.ManualSale
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure math for the Phase 4 daily device confirmation (§40 report point H):
 * grouped amounts, count validation, deterministic upsert ids, event-day rows.
 * No Android runtime, no sleeps (spec 33).
 */
class DailyReconciliationTest {
    @Test
    fun groupedAmountsSumCorrectly() {
        // 500×1 + 1000×2 = 2500 (spec 12 example, minor units ×100)
        val groups = listOf(DailyReconciliation.Group(1, 50000), DailyReconciliation.Group(2, 100000))
        assertEquals(250000L, groups.sumOf { it.total })
    }

    @Test
    fun upsertIdsAreDeterministicPerDayAndIndex() {
        assertEquals("rev-2026-09-28:0", DailyReconciliation.upsertId("2026-09-28", 0))
        assertEquals("rev-2026-09-28:1", DailyReconciliation.upsertId("2026-09-28", 1))
        assertEquals("rev-2026-09-29:0", DailyReconciliation.upsertId("2026-09-29", 0))
        // Same inputs, same id: rewriting a confirmation cannot fork a second row.
        assertEquals(DailyReconciliation.upsertId("2026-09-28", 0), DailyReconciliation.upsertId("2026-09-28", 0))
    }

    @Test
    fun dayKeyOfExtractsEventDayFromRowId() {
        assertEquals("2026-09-28", DailyReconciliation.dayKeyOf("rev-2026-09-28:2"))
        assertEquals("2026-01-02", DailyReconciliation.dayKeyOf("rev-2026-01-02:0"))
        assertNull(DailyReconciliation.dayKeyOf("3f1c9e2a-1111-2222-3333-444455556666:0"))
        assertNull(DailyReconciliation.dayKeyOf("session:x"))
    }

    @Test
    fun rowsForPicksOnlyThisDaysReviewRows() {
        val rows = listOf(
            ManualSale("rev-2026-09-28:0", 100L, 1, 50000, 50000, 50000, "CASH", 2500),
            ManualSale("rev-2026-09-28:1", 100L, 2, 100000, 200000, 200000, "CASH", 2500),
            ManualSale("rev-2026-09-29:0", 200L, 1, 70000, 70000, 70000, "CASH", 2500),
            ManualSale("3f1c9e2a-1111-2222-3333-444455556666:0", 300L, 5, 1000, 5000, 5000, "CASH", 2500)
        )
        val day = DailyReconciliation.rowsFor(rows, "2026-09-28")
        assertEquals(listOf("rev-2026-09-28:0", "rev-2026-09-28:1"), day.map { it.id })
        assertEquals(250000L, DailyReconciliation.confirmedTotal(rows, "2026-09-28"))
        assertEquals(3, DailyReconciliation.confirmedCount(rows, "2026-09-28"))
    }

    @Test
    fun countCannotExceedUnregisteredDevices() {
        // 3 unregistered devices, groups try to allocate 5 → rejected (spec 21).
        try {
            DailyReconciliation.validate(listOf(DailyReconciliation.Group(1, 50000), DailyReconciliation.Group(4, 100000)), 3, "CASH")
            fail("allocating 5 devices against 3 must fail")
        } catch (expected: IllegalArgumentException) { }
        // Exactly 3 is allowed.
        DailyReconciliation.validate(listOf(DailyReconciliation.Group(1, 50000), DailyReconciliation.Group(2, 100000)), 3, "CASH")
    }

    @Test
    fun validationRejectsEmptyGroupsBadPaymentAndNonPositivePrices() {
        try { DailyReconciliation.validate(emptyList(), 3, "CASH"); fail("empty groups must fail") } catch (expected: IllegalArgumentException) { }
        try { DailyReconciliation.validate(listOf(DailyReconciliation.Group(1, 50000)), 3, "GIFT"); fail("bad payment must fail") } catch (expected: IllegalArgumentException) { }
        try { DailyReconciliation.validate(listOf(DailyReconciliation.Group(1, 0)), 3, "CASH"); fail("zero price must fail") } catch (expected: IllegalArgumentException) { }
        try { DailyReconciliation.validate(listOf(DailyReconciliation.Group(0, 50000)), 3, "CASH"); fail("zero count must fail") } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun planProducesEventDayRowsWithDeterministicIds() {
        val at = 1759000000000L // confirm moment (any time on the confirm day)
        val rows = DailyReconciliation.plan("2026-09-28", at,
            listOf(DailyReconciliation.Group(1, 50000), DailyReconciliation.Group(2, 100000)), "CASH", 2500)
        assertEquals(listOf("rev-2026-09-28:0", "rev-2026-09-28:1"), rows.map { it.id })
        // Row `at` lands inside the event day, never at confirm time (spec 22).
        assertEquals(com.example.domain.Revenue.day(at) + 12 * 60 * 60_000L, rows[0].at)
        assertEquals(50000L, rows[0].amount)
        assertEquals(200000L, rows[1].amount)
        assertEquals(3, rows.sumOf { it.count })
    }

    @Test
    fun planConvertsBankPaymentsThroughPremium() {
        val rows = DailyReconciliation.plan("2026-09-28", 1759000000000L, listOf(DailyReconciliation.Group(1, 125000)), "BANK", 2500)
        // 125000 bank at 25% premium = 100000 cash equivalent (project rule).
        assertEquals(100000L, rows[0].cashEquivalent)
        assertEquals("BANK", rows[0].payment)
    }
}
