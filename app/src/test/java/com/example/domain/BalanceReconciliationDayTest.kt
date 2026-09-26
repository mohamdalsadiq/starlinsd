package com.example.domain

import com.example.db.BalanceUpdate
import com.example.db.BillingCycle
import com.example.db.Session
import org.junit.Assert.*
import org.junit.Test

class BalanceReconciliationDayTest {
    private val day = Revenue.day(1789909200000L)
    private val now = day + 12 * 60 * Rules.MINUTE
    private val cycle = BillingCycle("screenshot", day, day + 16 * 86400000L, 50000000)
    private val receipts = listOf(CashReceipt(now - 1000, 1700000, 0))
    private val update = BalanceUpdate(1, now, 5000000, 15930000, 1700000, 0, 3000, "الرصيد يشمل دخل اليوم")

    @Test fun screenshotSeventeenThousandAlreadyInBalanceStillCountsTowardToday() {
        val plan = BalanceBook.plan(listOf(update), receipts, cycle, now, 3000)!!
        assertEquals(32746154L, plan.remaining)
        assertEquals(CashBank(5000000, 15930000), plan.funds)
        assertEquals(2152885L, plan.target)
        assertEquals(452885L, plan.shortfall)
        assertEquals(0L, plan.surplus)
    }

    @Test fun bankReceiptsAlreadyIncludedReceiveTheSameDailyCredit() {
        val bankReceipts = listOf(CashReceipt(now - 1000, 0, 2210000))
        val checkpoint = update.copy(cashReceived = 0, bankReceived = 2210000)
        val plan = BalanceBook.plan(listOf(checkpoint), bankReceipts, cycle, now, 3000)!!
        assertEquals(32746154L, plan.remaining)
        assertEquals(2152885L, plan.target)
        assertEquals(452885L, plan.shortfall)
    }

    @Test fun laterReceiptsAndRepeatedUpdatesDoNotResetProgressOrDuplicateFunds() {
        val later = receipts + CashReceipt(now + 1000, 300000, 0) + CashReceipt(now + 5000, 900000, 0)
        val plan = BalanceBook.plan(listOf(update), later, cycle, now + 2000, 3000)!!
        assertEquals(2152885L, plan.target)
        assertEquals(152885L, plan.shortfall)
        assertEquals(32446154L, plan.remaining)
        val confirmed = update.copy(id = 2, at = now + 2000, cash = 5300000, cashReceived = 2000000)
        val again = BalanceBook.plan(listOf(update, confirmed), later, cycle, now + 2000, 3000)!!
        assertEquals(plan, again)
        val tomorrow = BalanceBook.plan(listOf(update, confirmed), later.take(2), cycle, day + 86400000L, 3000)!!
        assertEquals(2163077L, tomorrow.target)
        assertEquals(tomorrow.target, tomorrow.shortfall)
    }

    @Test fun yesterdayReceiptsAreNotCreditedAgainToday() {
        val plan = BalanceBook.plan(listOf(update), listOf(CashReceipt(day - 1, 1700000, 0)), cycle, now, 3000)!!
        assertEquals(2046635L, plan.target)
        assertEquals(plan.target, plan.shortfall)
    }

    @Test fun recognitionOfAnIncludedPaymentCannotCountTheMoneyAgain() {
        val session = Session("paid", "محمد", "3 ساعات", now - 120000, now - 120000, 10800000,
            amount = 1700000, cashEquivalent = 1700000, payment = "CASH", premiumBps = 3000,
            home = false, grace = 5 * Rules.MINUTE)
        val before = BalanceBook.plan(listOf(update), BalanceBook.receipts(listOf(session), emptyList()), cycle, now, 3000)!!
        val after = BalanceBook.plan(listOf(update), BalanceBook.receipts(listOf(session.copy(recognized = now + 180000)), emptyList()), cycle, now + 180000, 3000)!!
        assertEquals(before, after)
        assertEquals(452885L, after.shortfall)
    }

    @Test fun withdrawingAllTodaysCashDoesNotHideTheFundingGap() {
        val checkpoint = update.copy(cash = 0, bank = 0, cashReceived = 2000000)
        val plan = BalanceBook.plan(listOf(checkpoint), listOf(CashReceipt(now - 1000, 2000000, 0)),
            cycle.copy(cost = 10000000, end = day + 5 * 86400000L), now, 3000)!!
        assertEquals(10000000L, plan.remaining)
        assertEquals(2400000L, plan.target)
        assertEquals(400000L, plan.shortfall)
        assertEquals(CashBank(), plan.funds)
    }

    @Test fun lastDayShortfallEqualsTheActualRemainingBill() {
        val plan = BalanceBook.plan(listOf(update), receipts, cycle.copy(end = day + 86400000L), now, 3000)!!
        assertEquals(34446154L, plan.target)
        assertEquals(plan.remaining, plan.shortfall)
    }
}
