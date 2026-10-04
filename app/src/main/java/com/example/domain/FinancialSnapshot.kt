package com.example.domain

import com.example.db.*
import java.util.TimeZone

/** Immutable read model. All amounts come from the existing accounting rules. */
data class FinancialData(
    val sessions: List<Session>, val sales: List<ManualSale>, val corrections: List<RevenueCorrection>,
    val config: BusinessSettings, val cycles: List<BillingCycle>,
    val debts: List<Debt>, val payments: List<DebtPayment>, val balanceUpdates: List<BalanceUpdate> = emptyList(),
    val invoicePayments: List<InvoicePayment> = emptyList(),
)
data class FinancialSnapshot(
    val data: FinancialData, val day: Long, val ledger: List<LedgerEntry>,
    val revenue: RevenueReport, val budget: BudgetReport, val balance: BalanceState? = null,
    val billProgress: BillProgress? = null,
) {
    val remainingForBill: Long? get() = revenue.cost?.let { cost ->
        balance?.funds?.equivalent(data.config.premiumBps)?.let { (cost - it).coerceAtLeast(0) } ?: revenue.remainingCost
    }
    val coveredForBill: Long get() = revenue.cost?.let { it - (remainingForBill ?: it) } ?: 0
    val availableBalanceSurplus: Long? get() = revenue.cost?.let { cost -> balance?.funds?.equivalent(data.config.premiumBps)?.let { (it - cost).coerceAtLeast(0) } }
    val today: DailyIncome = revenue.days.firstOrNull { it.day == day }
        ?: DailyIncome(day, 0, 0, 0, null, 0)
}

/**
 * §9: bill-payment progress for the current cycle — ADDITIVE display only.
 * The existing cost/profit accounting is untouched (owner's rule 2026-10-04:
 * additions, no accounting changes).
 * - [boughtUsdCents] / [remainingUsdCents]: USD progress against the bill
 *   ([BusinessSettings.usdCents]); each payment reduces the remaining USD.
 * - [remainingSdg]: remaining USD converted at the FIXED accounting rate
 *   ([BusinessSettings.bankRate], e.g. 8500) — never at a purchase rate.
 * - [totalPaidSdg]: actual SDG outlay so far (Σ [com.example.db.InvoicePayment.sdgPaid]);
 *   this is what realized profit subtracts.
 */
data class BillProgress(
    val billUsdCents: Long,
    val boughtUsdCents: Long,
    val remainingUsdCents: Long,
    val remainingSdg: Long,
    val totalPaidSdg: Long,
    val fixedRate: Long,
) {
    val isFullyPaid: Boolean get() = billUsdCents > 0 && remainingUsdCents == 0L
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
        data.sessions.forEach {
            if (!it.home && it.recognized in 1..now) cutoff = maxOf(cutoff, it.recognized)
            if (!it.home && it.started in 1..now) cutoff = maxOf(cutoff, it.started)
        }
        data.balanceUpdates.forEach { if (it.at in 1..now) cutoff = maxOf(cutoff, it.at) }
        data.sales.forEach { if (it.at in 1..now) cutoff = maxOf(cutoff, it.at) }
        data.payments.forEach { if (it.at in 1..now) cutoff = maxOf(cutoff, it.at) }
        data.invoicePayments.forEach { if (it.at in 1..now) cutoff = maxOf(cutoff, it.at) }
        if (data == previous && day == previousDay && zone == previousZone && cutoff == previousCutoff) return snapshot!!
        val config = data.config
        val configured = config.cycleStart > 0 && config.cycleEnd > config.cycleStart && config.usdCents > 0 && config.bankRate > 0
        val cost = if (configured) Money.bankToCash(Money.bill(config.usdCents, config.bankRate), config.premiumBps) + config.expenses else null
        val cycles = if (data.cycles.isEmpty() && cost != null) listOf(BillingCycle("current", config.cycleStart, config.cycleEnd, cost)) else data.cycles
        val ledger = Finance.ledger(data.sessions, data.sales, data.corrections)
        val receipts = if (data.balanceUpdates.isEmpty()) emptyList() else BalanceBook.receipts(data.sessions, data.sales)
        // §9: actual bill payments recorded against the configured cycle.
        // invoicePaid is the ACTUAL SDG outlay (owner's rule 2026-10-04) —
        // realized profit subtracts what he really paid, not the accounting value.
        val cyclePayments = data.invoicePayments.filter { it.cycleId == config.cycleId }
        val invoicePaid = cyclePayments.sumOf { it.sdgPaid }
        val boughtUsd = cyclePayments.sumOf { it.usdCents }
        val remainingUsd = (config.usdCents - boughtUsd).coerceAtLeast(0)
        val billProgress = if (config.usdCents > 0 && config.bankRate > 0)
            BillProgress(config.usdCents, boughtUsd, remainingUsd,
                Money.bill(remainingUsd, config.bankRate), invoicePaid, config.bankRate)
        else null
        val result = FinancialSnapshot(data, day, ledger,
            Revenue.report(ledger.filterNot { it.voided }.map { it.income() }, config.cycleStart, config.cycleEnd, cost, now, invoicePaid = invoicePaid),
            Finance.report(ledger, cycles, data.debts, data.payments, now, data.balanceUpdates, receipts, config.premiumBps),
            BalanceBook.state(data.balanceUpdates, receipts, now), billProgress)
        previous = data; previousDay = day; previousZone = zone; previousCutoff = cutoff; snapshot = result
        return result
    }
}
