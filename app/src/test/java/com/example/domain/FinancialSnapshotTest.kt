package com.example.domain

import com.example.db.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class FinancialSnapshotTest {
    private val now = 1789390800000L
    private val day = Revenue.day(now)
    private fun sale(id: String = "sale", at: Long = now, amount: Long = 16100000) =
        ManualSale(id, at, 1, amount, amount, amount, "CASH", 2500)
    private fun data(sales: List<ManualSale> = listOf(sale())) = FinancialData(emptyList(), sales, emptyList(),
        BusinessSettings(usdCents = 100, bankRate = 58925000, cycleStart = day - 9 * 86400000L, cycleEnd = day + 22 * 86400000L),
        emptyList(), listOf(Debt("debt", "دين", 5000000, day, day + 86400000L)), emptyList())

    @Test fun coverageUsesWholeIncomeAndDoesNotChangeAllocationRules() {
        val input = data()
        val result = FinancialReportCache().get(input, now)
        assertEquals(47140000L, result.revenue.cost)
        assertEquals(31040000L, result.revenue.remainingCost)
        assertEquals(0L, result.revenue.cycleProfit)
        val cycles = listOf(BillingCycle("current", input.config.cycleStart, input.config.cycleEnd, 47140000))
        assertEquals(Finance.report(result.ledger, cycles, input.debts, input.payments, now), result.budget)
        assertTrue(result.budget.day(now).billReserved < result.revenue.covered)
    }

    @Test fun twoThousandRecordsAndRepeatedClockTicksReuseOneReport() {
        val input = data(List(2000) { sale("sale-$it", now - it, 50000) })
        val cache = FinancialReportCache()
        val initial = cache.get(input, now)
        repeat(240) { assertSame(initial, cache.get(input, now + it * 15000L)) }
        assertEquals(100000000L, initial.today.revenue)
    }

    @Test fun newIncomeCorrectionsAndPaymentsInvalidateImmediately() {
        val input = data()
        val cache = FinancialReportCache()
        val original = cache.get(input, now)
        val added = cache.get(input.copy(sales = input.sales + sale("added", amount = 100000)), now)
        assertEquals(original.today.revenue + 100000, added.today.revenue)
        val correctedData = input.copy(corrections = listOf(RevenueCorrection(1, "manual:sale", 0, 0, 1, true, now, "حذف")))
        val corrected = cache.get(correctedData, now)
        assertEquals(0L, corrected.today.revenue)
        assertEquals(47140000L, corrected.revenue.remainingCost)
        val payment = cache.get(input.copy(payments = listOf(DebtPayment("paid", "debt", now, 10000))), now)
        assertEquals(10000L, payment.budget.debts.single().paid)
    }

    @Test fun midnightAndFutureRestoredEntriesInvalidateWithoutDatabaseChanges() {
        val input = data(listOf(sale(at = now + 60000)))
        val cache = FinancialReportCache()
        val before = cache.get(input, now)
        assertEquals(0L, before.today.revenue)
        val after = cache.get(input, now + 60000)
        assertEquals(16100000L, after.today.revenue)
        val next = cache.get(input, day + 86400000L)
        assertNotSame(after, next)
        assertEquals(0L, next.today.revenue)
        assertEquals(16100000L, next.revenue.cycleRevenue)
        assertEquals(0L, cache.get(input, now).today.revenue)
    }

    @Test fun timezoneChangeInvalidatesGrouping() {
        val original = TimeZone.getDefault()
        try {
            val cache = FinancialReportCache()
            val input = data()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utc = cache.get(input, now)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val tokyo = cache.get(input, now)
            assertNotSame(utc, tokyo)
            assertEquals(Revenue.day(now), tokyo.day)
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun legacyBankAndHouseholdSnapshotsRemainUnchanged() {
        val input = data(listOf(sale().copy(amount = 125000, cashEquivalent = 100000, payment = "BANK")))
        val result = FinancialReportCache().get(input, now)
        assertEquals(100000L, result.today.revenue)
        assertEquals(125000L, result.today.bank)
        assertEquals(input.sales, result.data.sales)
    }
}
