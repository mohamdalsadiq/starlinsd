package com.example.domain

import com.example.db.*
import java.util.Calendar

data class LedgerEntry(val id: String, val at: Long, val label: String, val amount: Long, val value: Long,
    val bank: Boolean, val premiumBps: Int, val count: Int, val voided: Boolean = false, val corrected: Boolean = false) {
    fun income() = Income(at, value, amount, bank, count)
}
data class BudgetDay(val day: Long, val revenue: Long, val billTarget: Long?, val billReserved: Long,
    val shortfall: Long, val surplus: Long?, val debtReserved: Long, val available: Long?)
data class DebtBalance(val debt: Debt, val allocated: Long, val paid: Long) {
    val remaining get() = (debt.total - paid).coerceAtLeast(0)
    val reserved get() = (allocated - paid).coerceAtLeast(0)
    val fundingGap get() = (paid - allocated).coerceAtLeast(0)
}
data class BudgetReport(val days: Map<Long, BudgetDay>, val debts: List<DebtBalance>)

object Finance {
    fun ledger(sessions: List<Session>, sales: List<ManualSale>, corrections: List<RevenueCorrection>): List<LedgerEntry> {
        val changes = corrections.sortedBy { it.id }.associateBy { it.source }
        val raw = sessions.filter { !it.home && it.recognized > 0 }.map {
            LedgerEntry("session:${it.id}", it.recognized, "[${it.reference.ifBlank { it.id.take(8) }}] ${it.client}", it.amount, it.cashEquivalent, it.payment == "BANK", it.premiumBps, 1)
        } + sales.map { LedgerEntry("manual:${it.id}", it.at, "إدخال يدوي", it.amount, it.cashEquivalent, it.payment == "BANK", it.premiumBps, it.count) }
        return raw.map { row -> changes[row.id]?.let { row.copy(amount = it.amount, value = it.cashEquivalent, count = it.count, voided = it.voided, corrected = true) } ?: row }.sortedByDescending { it.at }
    }
    fun days(start: Long, end: Long): Int {
        val a = Calendar.getInstance().apply { timeInMillis = Revenue.day(start) }
        var count = 0
        while (a.timeInMillis < end && count < 36600) { count++; a.add(Calendar.DAY_OF_MONTH, 1) }
        return count.coerceAtLeast(1)
    }
    fun dailyTarget(cycle: BillingCycle): Long = (cycle.cost + days(cycle.start, cycle.end) - 1) / days(cycle.start, cycle.end)

    /** The bill target depends on the saved cycle, never on today's changing receipts. */
    fun report(ledger: List<LedgerEntry>, cycles: List<BillingCycle>, debts: List<Debt>, payments: List<DebtPayment>, now: Long): BudgetReport {
        val today = Revenue.day(now)
        val income = ledger.filter { !it.voided && it.at <= now }.groupBy { Revenue.day(it.at) }.mapValues { (_, rows) -> rows.sumOf { it.value } }
        val allocations = debts.associate { it.id to 0L }.toMutableMap()
        val order = debts.sortedWith(compareBy<Debt> { it.due }.thenBy { it.id })
        val paid = debts.associate { debt -> debt.id to payments.filter { it.debtId == debt.id && it.at <= now }.sumOf { it.amount } }
        val results = linkedMapOf<Long, BudgetDay>()
        (income.keys + today).sorted().forEach { day ->
            val revenue = income[day] ?: 0L
            val cycle = cycles.firstOrNull { day >= Revenue.day(it.start) && day < it.end }
            val target = cycle?.let(::dailyTarget)
            val surplus = target?.let { (revenue - it).coerceAtLeast(0) }
            var free = surplus ?: 0L
            // Actual payments consume the available surplus first, even after a
            // correction or priority edit; never reserve the same money twice.
            order.forEach { debt ->
                val required = (paid.getValue(debt.id) - allocations.getValue(debt.id)).coerceAtLeast(0)
                val funded = minOf(free, required)
                allocations[debt.id] = allocations.getValue(debt.id) + funded
                free -= funded
            }
            order.filter { Revenue.day(it.start) <= day }.forEach { debt ->
                val left = (debt.total - allocations.getValue(debt.id)).coerceAtLeast(0)
                val reserved = minOf(free, left)
                allocations[debt.id] = allocations.getValue(debt.id) + reserved
                free -= reserved
            }
            results[day] = BudgetDay(day, revenue, target, minOf(revenue, target ?: 0), ((target ?: 0) - revenue).coerceAtLeast(0), surplus,
                (surplus ?: 0) - free, if (surplus == null) null else free)
        }
        return BudgetReport(results, order.map { debt -> DebtBalance(debt, allocations.getValue(debt.id), paid.getValue(debt.id)) })
    }
}
