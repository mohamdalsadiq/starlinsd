package com.example.domain

import com.example.db.*
import java.util.TimeZone

/** Immutable read model. All amounts come from the existing accounting rules. */
data class FinancialData(
    val sessions: List<Session>, val sales: List<ManualSale>, val corrections: List<RevenueCorrection>,
    val config: BusinessSettings, val cycles: List<BillingCycle>,
    val debts: List<Debt>, val payments: List<DebtPayment>,
)
data class FinancialSnapshot(
    val data: FinancialData, val day: Long, val ledger: List<LedgerEntry>,
    val revenue: RevenueReport, val budget: BudgetReport,
) {
    val today: DailyIncome = revenue.days.firstOrNull { it.day == day }
        ?: DailyIncome(day, 0, 0, 0, null, 0)
}

/** Single snapshot, never an unbounded history cache. Use on a worker dispatcher. */
class FinancialReportCache {
    private var previous: FinancialData? = null
    private var previousDay = Long.MIN_VALUE
    private var previousZone = ""
    private var previousCutoff = Long.MIN_VALUE
    private var snapshot: FinancialSnapshot? = null

    @Synchronized
    fun get(data: FinancialData, now: Long): FinancialSnapshot {
        val day = Revenue.day(now)
        val zone = TimeZone.getDefault().id
        // Restored future-dated records become eligible even without another Room emission.
        var cutoff = 0L
        data.sessions.forEach { if (!it.home && it.recognized in 1..now) cutoff = maxOf(cutoff, it.recognized) }
        data.sales.forEach { if (it.at in 1..now) cutoff = maxOf(cutoff, it.at) }
        data.payments.forEach { if (it.at in 1..now) cutoff = maxOf(cutoff, it.at) }
        if (data == previous && day == previousDay && zone == previousZone && cutoff == previousCutoff) return snapshot!!
        val config = data.config
        val configured = config.cycleStart > 0 && config.cycleEnd > config.cycleStart && config.usdCents > 0 && config.bankRate > 0
        val cost = if (configured) Money.bankToCash(Money.bill(config.usdCents, config.bankRate), config.premiumBps) + config.expenses else null
        val cycles = if (data.cycles.isEmpty() && cost != null) listOf(BillingCycle("current", config.cycleStart, config.cycleEnd, cost)) else data.cycles
        val ledger = Finance.ledger(data.sessions, data.sales, data.corrections)
        val result = FinancialSnapshot(data, day, ledger,
            Revenue.report(ledger.filterNot { it.voided }.map { it.income() }, config.cycleStart, config.cycleEnd, cost, now),
            Finance.report(ledger, cycles, data.debts, data.payments, now))
        previous = data; previousDay = day; previousZone = zone; previousCutoff = cutoff; snapshot = result
        return result
    }
}
