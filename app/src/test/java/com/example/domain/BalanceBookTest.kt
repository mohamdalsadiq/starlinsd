package com.example.domain

import com.example.db.*
import org.junit.Assert.*
import org.junit.Test

class BalanceBookTest {
    private val day = Revenue.day(1789390800000L)
    private val now = day + 12 * 60 * Rules.MINUTE
    private val cycle = BillingCycle("cycle", day, day + 5 * 86400000L, 10000000)
    private fun update(cash: Long = 2000000, bank: Long = 2500000, at: Long = now) =
        BalanceUpdate(1, at, cash, bank, 0, 0, 2500, "مطابقة")

    @Test fun actualCashAndBankReplanUsingExistingConversionWithoutInventingSales() {
        val plan = BalanceBook.plan(listOf(update()), emptyList(), cycle, now, 2500)!!
        assertEquals(6000000L, plan.remaining)
        assertEquals(1200000L, plan.target)
        assertEquals(1500000L, Money.cashToBank(plan.target, 2500))
        assertEquals(1200000L, plan.shortfall)
    }

    @Test fun receiptsReduceShortfallUntilNextDayAndWithdrawalExplicitlyReplans() {
        val receipts = listOf(CashReceipt(now + 1000, 500000, 0))
        val plan = BalanceBook.plan(listOf(update()), receipts, cycle, now + 2000, 2500)!!
        assertEquals(1200000L, plan.target); assertEquals(700000L, plan.shortfall)
        val tomorrow = BalanceBook.plan(listOf(update()), receipts, cycle, day + 86400000L, 2500)!!
        assertEquals(1375000L, tomorrow.target)
        val withdrawal = update(cash = 1000000, bank = 0, at = now + 3000).copy(id = 2, cashReceived = 500000)
        val revised = BalanceBook.plan(listOf(update(), withdrawal), receipts, cycle, now + 3000, 2500)!!
        assertEquals(1800000L, revised.target); assertEquals(9000000L, revised.remaining)
    }

    @Test fun pendingPaymentAndLaterRecognitionNeverIncreaseFundsTwice() {
        val s = Session("paid", "محمد", "3 ساعات", now - 120000, now - 120000, 10800000,
            amount = 100000, cashEquivalent = 100000, payment = "CASH", premiumBps = 2500, home = false, grace = 300000)
        val checkpoint = update(cash = 100000, bank = 0).copy(cashReceived = 100000)
        val before = BalanceBook.state(listOf(checkpoint), BalanceBook.receipts(listOf(s), emptyList()), now)!!
        val after = BalanceBook.state(listOf(checkpoint), BalanceBook.receipts(listOf(s.copy(recognized = now + 180000)), emptyList()), now + 180000)!!
        assertEquals(before.funds, after.funds)
        assertEquals(100000L, after.funds.cash)
    }

    @Test fun reconciliationChangesFundingButPreservesRevenueAndProfit() {
        val config = BusinessSettings(usdCents = 100, bankRate = 12500000, cycleStart = day, cycleEnd = cycle.end)
        val sale = ManualSale("sale", now - 1000, 1, 15000000, 15000000, 15000000, "CASH", 2500)
        val update = update(1000000, 0).copy(cashReceived = 15000000)
        val data = FinancialData(emptyList(), listOf(sale), emptyList(), config, listOf(cycle), emptyList(), emptyList(), listOf(update))
        val snapshot = FinancialReportCache().get(data, now)
        assertEquals(15000000L, snapshot.today.revenue)
        assertEquals(5000000L, snapshot.revenue.cycleProfit)
        assertEquals(9000000L, snapshot.remainingForBill)
        assertEquals(1800000L, snapshot.budget.day(now).billTarget)
        assertEquals(0L, snapshot.availableBalanceSurplus)
        val panel = com.example.notifications.PanelSnapshot.from(snapshot, now)
        assertEquals(snapshot.remainingForBill, panel.remainingBill)
        assertEquals(snapshot.budget.day(now).billTarget, panel.dailyTarget)
        assertEquals(snapshot.budget.day(now).shortfall, panel.dailyShortfall)
        val corrected = FinancialReportCache().get(data.copy(corrections = listOf(RevenueCorrection(1, "manual:sale", 1, 1, 1, true, now, "استبعاد من التقرير"))), now)
        assertEquals(snapshot.balance!!.funds, corrected.balance!!.funds)
        assertEquals(0L, corrected.today.revenue)
    }

    @Test fun fullCoverageZeroBalancesAndHistoricalPlansAreDeterministic() {
        assertEquals(0L, BalanceBook.plan(listOf(update(11000000, 0)), emptyList(), cycle, now, 2500)!!.target)
        assertEquals(1000000L, BalanceBook.plan(listOf(update(11000000, 0)), emptyList(), cycle, now, 2500)!!.availableSurplus)
        assertEquals(2000000L, BalanceBook.plan(listOf(update(0, 0)), emptyList(), cycle, now, 2500)!!.target)
        assertNull(BalanceBook.plan(listOf(update()), emptyList(), cycle, now - 1, 2500))
        assertNull(BalanceBook.plan(listOf(update()), emptyList(), cycle, cycle.end, 2500))
        val report = Finance.report(emptyList(), listOf(cycle), emptyList(), emptyList(), day + 86400000L,
            listOf(update()), emptyList(), 2500)
        assertEquals(1200000L, report.day(day).billTarget)
        assertEquals(1500000L, report.day(day + 86400000L).billTarget)
    }
}
