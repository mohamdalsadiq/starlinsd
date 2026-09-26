package com.example.domain

import com.example.db.*
import java.util.Calendar

data class CashBank(val cash: Long = 0, val bank: Long = 0) {
    fun equivalent(premiumBps: Int): Long = Math.addExact(cash, Money.bankToCash(bank, premiumBps))
}
data class CashReceipt(val at: Long, val cash: Long, val bank: Long)
data class BalanceState(val update: BalanceUpdate, val funds: CashBank)
data class BalancePlan(val target: Long, val shortfall: Long, val surplus: Long,
    val remaining: Long, val funds: CashBank, val availableSurplus: Long)

/** Confirmed cash receipts are separate from recognition and revenue corrections. */
object BalanceBook {
    fun receipts(sessions: List<Session>, sales: List<ManualSale>): List<CashReceipt> =
        sessions.filterNot { it.home }.map { receipt(it.started, it.amount, it.payment) } +
            sales.map { receipt(it.at, it.amount, it.payment) }

    private fun receipt(at: Long, amount: Long, payment: String) =
        CashReceipt(at, if (payment == "CASH") amount else 0, if (payment == "BANK") amount else 0)

    fun totals(receipts: List<CashReceipt>, at: Long): CashBank {
        val eligible = receipts.filter { it.at in 1..at }
        return CashBank(eligible.sumOf { it.cash }, eligible.sumOf { it.bank })
    }

    fun expected(updates: List<BalanceUpdate>, receipts: List<CashReceipt>, at: Long, cycleStart: Long): CashBank {
        state(updates, receipts, at)?.let { return it.funds }
        val start = if (cycleStart > 0) cycleStart else Calendar.getInstance().apply {
            timeInMillis = Revenue.day(at); set(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        return totals(receipts.filter { it.at >= start }, at)
    }

    fun state(updates: List<BalanceUpdate>, receipts: List<CashReceipt>, at: Long): BalanceState? {
        val update = updates.filter { it.at <= at }.maxWithOrNull(compareBy<BalanceUpdate> { it.at }.thenBy { it.id }) ?: return null
        val total = totals(receipts, at)
        return BalanceState(update, CashBank(
            Math.addExact(update.cash, (total.cash - update.cashReceived).coerceAtLeast(0)),
            Math.addExact(update.bank, (total.bank - update.bankReceived).coerceAtLeast(0))))
    }

    fun plan(updates: List<BalanceUpdate>, receipts: List<CashReceipt>, cycle: BillingCycle, at: Long, premiumBps: Int): BalancePlan? {
        val day = Revenue.day(at)
        if (day < Revenue.day(cycle.start) || day >= cycle.end) return null
        val current = state(updates, receipts, at) ?: return null
        // Reconstruct the day's opening basis: the absolute balance already includes today's receipts.
        // Subtract them only from this planning basis, never from actual funds or the remaining bill.
        val openingValue = if (current.update.at >= day) {
            val includedToday = totals(receipts.filter { it.at >= day }, current.update.at).equivalent(premiumBps)
            CashBank(current.update.cash, current.update.bank).equivalent(premiumBps) - includedToday
        } else state(updates, receipts, day - 1)?.funds?.equivalent(premiumBps) ?: return null
        val currentValue = current.funds.equivalent(premiumBps)
        val remainingAtOpening = (cycle.cost - openingValue).coerceAtLeast(0)
        val days = Finance.days(maxOf(day, cycle.start), cycle.end)
        val target = remainingAtOpening / days + if (remainingAtOpening % days > 0) 1 else 0
        val collected = (currentValue - openingValue).coerceAtLeast(0)
        return BalancePlan(target, (target - collected).coerceAtLeast(0), (collected - target).coerceAtLeast(0),
            (cycle.cost - currentValue).coerceAtLeast(0), current.funds, (currentValue - cycle.cost).coerceAtLeast(0))
    }

    fun endOfDay(day: Long): Long = Calendar.getInstance().apply {
        timeInMillis = Revenue.day(day); add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis - 1
}
