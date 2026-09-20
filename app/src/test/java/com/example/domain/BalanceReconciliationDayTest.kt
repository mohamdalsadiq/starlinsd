package com.example.domain

import com.example.db.BalanceUpdate
import com.example.db.BillingCycle
import org.junit.Assert.*
import org.junit.Test

class BalanceReconciliationDayTest {
    private val day = Revenue.day(1789909200000L)
    private val now = day + 12 * 60 * Rules.MINUTE
    private val cycle = BillingCycle("screenshot", day, day + 16 * 86400000L, 50000000)
    private val receipts = listOf(CashReceipt(now - 1000, 1700000, 0))
    private val update = BalanceUpdate(1, now, 5000000, 15930000, 1700000, 0, 3000, "الرصيد يشمل دخل اليوم")

    @Test fun screenshotSeventeenThousandAlreadyInBalanceStillCountsTowardToday() {
        val plan = BalanceBook.plan(listOf(update), receipts, cycle, now, 3000)!!
        assertEquals(32746154L, plan.remaining)
        assertEquals(CashBank(5000000, 15930000), plan.funds)
        assertEquals(2152885L, plan.target)
        assertEquals(452885L, plan.shortfall)
        assertEquals(0L, plan.surplus)
    }
}
