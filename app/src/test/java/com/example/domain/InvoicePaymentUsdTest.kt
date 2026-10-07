package com.example.domain

import com.example.db.*
import org.junit.Assert.*
import org.junit.Test

/**
 * §9 (owner's rule 2026-10-04): partial USD bill payments. The owner buys the
 * $85 bill in parts at his ACTUAL purchase rate — e.g. $30 at 8100 SDG/USD —
 * while his accounting bankk rate stays FIXED at 8500.
 * - realized profit subtracts the ACTUAL outlay (243,000), never the
 *   accounting value (255,000);
 * - the remaining bill is tracked in USD ($55) and displayed at the FIXED
 *   rate (467,500 SDG);
 * - the existing cost/profit accounting is untouched (additive only).
 */
class InvoicePaymentUsdTest {
    private val now = 1789390800000L
    private val cycleId = "c1"
    private val config = BusinessSettings(
        usdCents = 8500, bankRate = 8500,
        cycleStart = now - 86400000L, cycleEnd = now + 86400000L * 29,
        cycleId = cycleId)

    private fun payment(usdCents: Long, ratePerUsd: Long) =
        InvoicePayment("p-$usdCents-$ratePerUsd", cycleId, now, usdCents, ratePerUsd,
            usdCents * ratePerUsd / 100, "")

    private fun snapshot(payments: List<InvoicePayment>, income: Long = 0L): FinancialSnapshot {
        val sales = if (income > 0) listOf(ManualSale("s1", now, 1, income, income, income, "CASH", 2500)) else emptyList()
        val data = FinancialData(emptyList(), sales, emptyList(), config,
            emptyList(), emptyList(), emptyList(), invoicePayments = payments)
        return FinancialReportCache().get(data, now)
    }

    @Test fun realizedProfitSubtractsActualOutlayNotAccountingValue() {
        // $30 at 8100 → actual outlay 243,000 (accounting value would be 255,000).
        val result = snapshot(listOf(payment(3000, 8100)), income = 500_000L)
        assertEquals(243_000L, result.revenue.invoicePaid)
        assertEquals(257_000L, result.revenue.realizedProfit) // 500k − 243k actual
    }

    @Test fun billProgressTracksUsdAndFixedRateDisplay() {
        val result = snapshot(listOf(payment(3000, 8100)))
        val bp = requireNotNull(result.billProgress)
        assertEquals(8500L, bp.billUsdCents)
        assertEquals(3000L, bp.boughtUsdCents)
        assertEquals(5500L, bp.remainingUsdCents)
        assertEquals(467_500L, bp.remainingSdg) // $55 × fixed 8500, never × 8100
        assertEquals(243_000L, bp.totalPaidSdg)
        assertEquals(8500L, bp.fixedRate)
        assertFalse(bp.isFullyPaid)
    }

    @Test fun twoPartPaymentCompletesBill() {
        val result = snapshot(listOf(payment(5000, 8100), payment(3500, 8200)))
        val bp = requireNotNull(result.billProgress)
        assertEquals(0L, bp.remainingUsdCents)
        assertEquals(0L, bp.remainingSdg)
        assertTrue(bp.isFullyPaid)
        // Actual outlays: 405,000 + 287,000 = 692,000 — cheaper than the 722,500 accounting.
        assertEquals(692_000L, bp.totalPaidSdg)
        assertEquals(692_000L, result.revenue.invoicePaid)
    }

    @Test fun projectedProfitStillUsesFullAccountingCost() {
        val result = snapshot(listOf(payment(3000, 8100)), income = 1_000_000L)
        // Full accounting cost is untouched by actual payments: 722,500 bill at
        // 8500 → bankToCash(2500bps) = 578,000.
        assertEquals(578_000L, result.revenue.cost)
        assertEquals(422_000L, result.revenue.cycleProfit) // projected: 1M − 578k
        assertEquals(757_000L, result.revenue.realizedProfit) // realized: 1M − 243k actual
    }

    @Test fun noBillConfigMeansNoProgress() {
        val data = FinancialData(emptyList(), emptyList(), emptyList(),
            BusinessSettings(), emptyList(), emptyList(), emptyList(),
            invoicePayments = listOf(payment(3000, 8100)))
        assertNull(FinancialReportCache().get(data, now).billProgress)
    }

    @Test fun paymentsOfOtherCyclesAreIgnored() {
        val foreign = payment(3000, 8100).copy(id = "foreign", cycleId = "other")
        val result = snapshot(listOf(foreign))
        assertEquals(0L, result.revenue.invoicePaid)
        val bp = requireNotNull(result.billProgress)
        assertEquals(0L, bp.boughtUsdCents)
        assertEquals(8500L, bp.remainingUsdCents)
    }
}
