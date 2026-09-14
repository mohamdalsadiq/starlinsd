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
    }
    @Test fun householdTemplateDoesNotProduceTimeCodeOrMoney() {
        assertEquals("✅", TextRules.householdTemplate("✅ %end% %code% %price% %duration% %time+3h%"))
        assertEquals("✅ أهل البيت", TextRules.householdTemplate("%client% — الاشتراك %duration% دقيقة، ينتهي %end%، السعر %price% جنيه."))
    }
}
