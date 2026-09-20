package com.example.domain

import com.example.db.BillingCycle
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DailyPlanTest {
    private val start = Revenue.day(1789390800000L)
    private fun after(days: Int) = Calendar.getInstance().apply {
        timeInMillis = start; add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis
    private val cycle get() = BillingCycle("cycle", start, after(5), 10000000)
    private fun row(id: String, at: Long, value: Long) = LedgerEntry(id, at, "بيع", value, value, false, 0, 1)
    private fun report(rows: List<LedgerEntry>, now: Long) = Finance.report(rows, listOf(cycle), emptyList(), emptyList(), now)

    @Test fun targetRemainsFixedDuringDayThenRedistributesShortfall() {
        val sale = row("sale", start + 1000, 1500000)
        assertEquals(2000000L, report(emptyList(), start).day(start).billTarget)
        val today = report(listOf(sale), sale.at).day(start)
        assertEquals(2000000L, today.billTarget)
        assertEquals(500000L, today.shortfall)
        assertEquals(2125000L, report(listOf(sale), after(1)).day(after(1)).billTarget)
    }

    @Test fun overTargetIncomeReducesTomorrowWithoutCreatingAnotherSale() {
        val rows = listOf(row("sale", start, 2500000))
        val result = report(rows, start)
        assertEquals(500000L, result.day(start).surplus)
        assertEquals(1875000L, result.day(after(1)).billTarget)
        assertEquals(2500000L, result.day(start).billReserved)
        assertEquals(0L, Revenue.report(rows.map { it.income() }, start, after(5), cycle.cost, start).cycleProfit)
    }

    @Test fun skippedDaysCorrectionsLastDayAndOutsideCycle() {
        val sale = row("sale", start, 1500000)
        assertEquals(2833334L, report(listOf(sale), after(2)).day(after(2)).billTarget)
        assertEquals(8500000L, report(listOf(sale), after(4)).day(after(4)).billTarget)
        assertEquals(2500000L, report(listOf(sale.copy(voided = true)), after(1)).day(after(1)).billTarget)
        assertNull(report(listOf(sale), after(5)).day(after(5)).billTarget)
    }

    @Test fun coveredCycleHasZeroFollowingTargetAndFutureIncomeIsExcluded() {
        val rows = listOf(row("paid", start, 11000000), row("future", after(2), 9000000))
        val result = report(rows, after(1))
        assertEquals(0L, result.day(after(1)).billTarget)
        assertEquals(10000000L, result.day(start).billReserved)
        assertEquals(11000000L, result.days.values.sumOf { it.revenue })
    }

    @Test fun localCalendarDaysRemainCorrectAcrossDaylightSaving() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val date = Calendar.getInstance().apply { clear(); set(2026, Calendar.MARCH, 7) }
            val a = date.timeInMillis
            date.add(Calendar.DAY_OF_MONTH, 3)
            val c = BillingCycle("dst", a, date.timeInMillis, 300)
            assertEquals(3, Finance.days(c.start, c.end))
            date.timeInMillis = a; date.add(Calendar.DAY_OF_MONTH, 1)
            val result = Finance.report(emptyList(), listOf(c), emptyList(), emptyList(), date.timeInMillis)
            assertEquals(150L, result.day(date.timeInMillis).billTarget)
        } finally { TimeZone.setDefault(previous) }
    }
}
