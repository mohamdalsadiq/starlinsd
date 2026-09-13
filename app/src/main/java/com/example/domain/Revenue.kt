package com.example.domain

import java.util.Calendar
import java.util.TimeZone

/** Values are minor SDG units; bank amounts are already normalized at sale time. */
data class Income(val at: Long, val value: Long, val amount: Long, val bank: Boolean)
data class DailyIncome(val day: Long, val revenue: Long, val cash: Long, val bank: Long, val profit: Long?, val sales: Int)
data class RevenueReport(val days: List<DailyIncome>, val cycleRevenue: Long, val covered: Long,
    val remainingCost: Long?, val cycleProfit: Long?, val cost: Long?)

object Revenue {
    fun day(at: Long, zone: TimeZone = TimeZone.getDefault()): Long = Calendar.getInstance(zone).apply {
        timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun report(entries: List<Income>, start: Long, end: Long, cost: Long?, now: Long,
        zone: TimeZone = TimeZone.getDefault()): RevenueReport {
        require(cost == null || cost >= 0)
        val knownCycle = start > 0 && end > start && cost != null
        val valid = entries.filter { it.at > 0 && it.at <= now && it.value >= 0 && it.amount >= 0 }
        val cycle = valid.filter { it.at >= start && it.at < end }.sortedBy { it.at }
        var collected = 0L
        val profits = mutableMapOf<Long, Long>()
        cycle.forEach { sale ->
            val before = if (knownCycle) (collected - cost!!).coerceAtLeast(0) else 0
            collected += sale.value
            if (knownCycle) {
                val earned = (collected - cost!!).coerceAtLeast(0) - before
                val d = day(sale.at, zone)
                profits[d] = profits.getOrDefault(d, 0L) + earned
            }
        }
        val days = valid.groupBy { day(it.at, zone) }.map { (d, sales) ->
            val inCycle = knownCycle && d >= day(start, zone) && d <= day(end - 1, zone)
            DailyIncome(d, sales.sumOf { it.value }, sales.filter { !it.bank }.sumOf { it.amount },
                sales.filter { it.bank }.sumOf { it.amount }, if (inCycle) profits[d] ?: 0 else null, sales.size)
        }.sortedByDescending { it.day }
        return RevenueReport(days, collected, if (knownCycle) minOf(collected, cost!!) else 0,
            if (knownCycle) (cost!! - collected).coerceAtLeast(0) else null,
            if (knownCycle) (collected - cost!!).coerceAtLeast(0) else null, if (knownCycle) cost else null)
    }
}
