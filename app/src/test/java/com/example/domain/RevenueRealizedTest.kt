package com.example.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * §9: the invoice/profit repair. [RevenueReport.cycleProfit] stays the
 * PROJECTED profit after the FULL cycle cost; [RevenueReport.realizedProfit] is
 * collected minus ACTUALLY recorded bill payments — the only realized number.
 */
class RevenueRealizedTest {
    private val now = 1_760_000_000_000L
    private val start = Revenue.day(now - 86400_000L * 30)
    private val end = start + 86400_000L * 30
    private val cost = 100_000L

    private fun incomes(vararg values: Long) =
        values.mapIndexed { i, v -> Income(at = start + (i + 1) * 3_600_000L, value = v, amount = v, bank = false) }

    @Test fun realizedEqualsCollectedMinusActuallyPaid() {
        val report = Revenue.report(incomes(60_000L, 60_000L), start, end, cost, now, invoicePaid = 30_000L)
        assertEquals(120_000L, report.cycleRevenue)
        assertEquals(20_000L, report.cycleProfit) // projected: 120k - full 100k cost
        assertEquals(30_000L, report.invoicePaid)
        assertEquals(90_000L, report.realizedProfit) // realized: 120k - 30k actually paid
    }

    @Test fun noPaymentsMeansRealizedEqualsCollected() {
        val report = Revenue.report(incomes(60_000L), start, end, cost, now)
        assertEquals(0L, report.invoicePaid)
        assertEquals(60_000L, report.realizedProfit)
        assertEquals(0L, report.cycleProfit) // 60k < 100k cost: nothing projected yet
    }

    @Test fun overpaymentNeverGoesNegative() {
        val report = Revenue.report(incomes(10_000L), start, end, cost, now, invoicePaid = 50_000L)
        assertEquals(0L, report.realizedProfit)
    }

    @Test fun noCycleMeansNoRealizedProfit() {
        val report = Revenue.report(incomes(60_000L), 0L, 0L, null, now, invoicePaid = 10_000L)
        assertNull(report.cycleProfit)
        assertNull(report.realizedProfit)
    }

    @Test fun invoicePaidDefaultsToZero() {
        val report = Revenue.report(incomes(60_000L), start, end, cost, now)
        assertEquals(0L, report.invoicePaid)
    }
}
