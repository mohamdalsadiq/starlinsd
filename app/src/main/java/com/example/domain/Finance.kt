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
data class BudgetReport(val days: Map<Long, BudgetDay>, val debts: List<DebtBalance>, val cycles: List<BillingCycle>,
    val cycleIncome: Map<String, Map<Long, Long>> = emptyMap()) {
    fun day(at: Long): BudgetDay {
        val date = Revenue.day(at)
        return days[date] ?: Finance.budgetDay(date, 0, cycles, cycleIncome)
    }
}

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
    fun dailyTarget(cycle: BillingCycle): Long = divideUp(cycle.cost, days(cycle.start, cycle.end))

    private fun divideUp(value: Long, divisor: Int): Long = value / divisor + if (value % divisor > 0) 1 else 0

    internal fun budgetDay(day: Long, revenue: Long, cycles: List<BillingCycle>,
        cycleIncome: Map<String, Map<Long, Long>>): BudgetDay {
        val cycle = cycles.firstOrNull { day >= Revenue.day(it.start) && day < it.end }
            ?: return BudgetDay(day, revenue, null, 0, 0, null, 0, null)
        val income = cycleIncome[cycle.id].orEmpty()
        // Only receipts before this calendar day affect its target. Today's receipts never move it.
        val before = income.filterKeys { it < day }.values.sum()
        val remaining = (cycle.cost - before).coerceAtLeast(0)
        val target = divideUp(remaining, days(maxOf(day, cycle.start), cycle.end))
        val surplus = (revenue - target).coerceAtLeast(0)
        return BudgetDay(day, revenue, target, minOf(income[day] ?: 0, remaining),
            (target - revenue).coerceAtLeast(0), surplus, 0, surplus)
    }

    /** Derived coverage is not a cash reservation or a recorded bill payment. */
    fun report(ledger: List<LedgerEntry>, cycles: List<BillingCycle>, debts: List<Debt>, payments: List<DebtPayment>, now: Long): BudgetReport {
        val today = Revenue.day(now)
        val valid = ledger.filter { !it.voided && it.at in 1..now }
        val income = valid.groupBy { Revenue.day(it.at) }.mapValues { (_, rows) -> rows.sumOf { it.value } }
        val cycleIncome = cycles.associate { cycle -> cycle.id to valid.filter { it.at >= cycle.start && it.at < cycle.end }
            .groupBy { Revenue.day(it.at) }.mapValues { (_, rows) -> rows.sumOf { it.value } } }
        val paid = payments.filter { it.at <= now }.groupBy { it.debtId }.mapValues { (_, rows) -> rows.sumOf { it.amount } }
        val balances = debts.sortedWith(compareBy<Debt> { it.due }.thenBy { it.id }).map {
            val amount = paid[it.id] ?: 0L
            DebtBalance(it, amount, amount)
        }
        val results = (income.keys + today).sorted().associateWith { day -> budgetDay(day, income[day] ?: 0, cycles, cycleIncome) }
        return BudgetReport(results, balances, cycles, cycleIncome)
    }
}
