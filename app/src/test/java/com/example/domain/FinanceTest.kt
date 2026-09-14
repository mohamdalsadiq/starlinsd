package com.example.domain

import com.example.db.*
import org.junit.Assert.*
import org.junit.Test

class FinanceTest {
    private val day = Revenue.day(1700000000000)
    @Test fun dailySurplusIsIndependentOfFullCycleProfitAndDebtDuePriority() {
        val cycle = BillingCycle("cycle", day, day + 30 * 86400000L, 60000000)
        val income = LedgerEntry("manual:1", day, "بيع", 3050000, 3050000, false, 2500, 30)
        val later = Debt("later", "لاحق", 1000000, day, day + 10 * 86400000L)
        val earlier = Debt("first", "أقرب", 600000, day, day + 3 * 86400000L)
        val report = Finance.report(listOf(income), listOf(cycle), listOf(later, earlier), emptyList(), day + 1000)
        val daily = report.days.getValue(day)
        assertEquals(2000000L, daily.billTarget); assertEquals(1050000L, daily.surplus)
        assertEquals(600000L, report.debts.first().allocated)
        assertEquals(450000L, report.debts.last().allocated)
        assertEquals(0L, daily.available)
        assertEquals(0L, Revenue.report(listOf(income.income()), cycle.start, cycle.end, cycle.cost, day + 1000).cycleProfit)
        val tomorrow = Finance.report(listOf(income), listOf(cycle), emptyList(), emptyList(), day + 86400000L)
        assertEquals(daily.billTarget, tomorrow.days.getValue(day + 86400000L).billTarget)
        assertEquals(0L, report.day(day + 86400000L).available)
        assertEquals(2000000L, report.day(day + 86400000L).billTarget)
    }
    @Test fun householdTemplateDoesNotProduceTimeCodeOrMoney() {
        assertEquals("✅", TextRules.householdTemplate("✅ %end% %code% %price% %duration% %time+3h%"))
        assertEquals("✅ أهل البيت", TextRules.householdTemplate("%client% — الاشتراك %duration% دقيقة، ينتهي %end%، السعر %price% جنيه."))
    }
    @Test fun paidMoneyCannotBeReservedAgainAfterPriorityOrRevenueEdits() {
        val cycle = BillingCycle("cycle", day, day + 86400000, 100)
        val row = LedgerEntry("manual:1", day, "بيع", 200, 200, false, 0, 1)
        val earlier = Debt("earlier", "أقرب", 100, day, day + 1000)
        val paid = Debt("paid", "تم دفعه", 100, day, day + 2000)
        val payment = DebtPayment("payment", paid.id, day, 100)
        val report = Finance.report(listOf(row), listOf(cycle), listOf(earlier, paid), listOf(payment), day + 1)
        assertEquals(0L, report.debts.first().reserved)
        assertEquals(100L, report.debts.last().allocated)
        assertEquals(0L, report.debts.sumOf { it.reserved })
    }

}
