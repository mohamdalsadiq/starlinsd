package com.example.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class RevenueTest {
    private val zone = TimeZone.getTimeZone("UTC")
    private val start = 1700006400000L // 2023-11-15 UTC
    private val day = 86400000L
    @Test fun onlyTheAmountAboveCumulativeCycleCostIsProfit() {
        val entries = listOf(Income(start + 1000, 800000, 800000, false), Income(start + day + 1000, 400000, 500000, true))
        val report = Revenue.report(entries, start, start + 30 * day, 1000000, start + 2 * day, zone)
        assertEquals(1200000L, report.cycleRevenue)
        assertEquals(200000L, report.cycleProfit)
        assertEquals(0L, report.remainingCost)
        assertEquals(200000L, report.days[0].profit)
        assertEquals(400000L, report.days[0].revenue)
        assertEquals(500000L, report.days[0].bank)
        assertEquals(0L, report.days[1].profit)
    }
    @Test fun priorCycleAndFutureSalesDoNotCoverCurrentCost() {
        val entries = listOf(Income(start - 1, 10000000, 10000000, false), Income(start + 1, 200000, 200000, false), Income(start + 10 * day, 900000, 900000, false))
        val report = Revenue.report(entries, start, start + 30 * day, 300000, start + day, zone)
        assertEquals(200000L, report.cycleRevenue)
        assertEquals(100000L, report.remainingCost)
        assertEquals(0L, report.cycleProfit)
        assertNull(report.days.last().profit)
    }
    @Test fun missingCostsNeverInventProfitAndUnsortedInputIsStable() {
        val entries = listOf(Income(start + day, 200000, 200000, false), Income(start, 200000, 200000, false))
        assertNull(Revenue.report(entries, start, start + 30 * day, null, start + 2 * day, zone).cycleProfit)
        val a = Revenue.report(entries, start, start + 30 * day, 300000, start + 2 * day, zone)
        val b = Revenue.report(entries.reversed(), start, start + 30 * day, 300000, start + 2 * day, zone)
        assertEquals(a, b); assertEquals(100000L, a.days.first().profit)
    }
}
