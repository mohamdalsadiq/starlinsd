package com.example.notifications

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.db.*
import com.example.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StatusPanelTest {
    private val now = 1789390800000L
    private val start = Revenue.day(now)
    private val config = BusinessSettings(usdCents = 100, bankRate = 2500000, cycleStart = start, cycleEnd = StatusPanel.nextMidnight(now), cycleId = "cycle")
    private fun snapshot(corrections: List<RevenueCorrection> = emptyList()): PanelSnapshot {
        val active = Session(id = "a", client = "1", plan = "ساعة", started = now, resumed = now, duration = 60 * Rules.MINUTE,
            amount = 50000, cashEquivalent = 50000, payment = "CASH", premiumBps = 2500, home = false, grace = 30 * Rules.MINUTE)
        return PanelSnapshot.calculate(listOf(active, active.copy(id = "soon", duration = 10 * Rules.MINUTE),
            active.copy(id = "paused", state = "PAUSED"), active.copy(id = "ended", state = "ENDED"), active.copy(id = "home", home = true)),
            listOf(ManualSale("sale", now, 30, 100000, 3050000, 3050000, "CASH", 2500)), corrections, config,
            listOf(BillingCycle("cycle", start, config.cycleEnd, 2000000)), listOf(Debt("debt", "دين", 600000, start, config.cycleEnd)), emptyList(), now)
    }
    @Test fun countsExcludeHouseholdAndMoneyMatchesCorrectedDebtBudget() {
        val value = snapshot()
        assertEquals(2, value.active); assertEquals(1, value.soon); assertEquals(1, value.ended); assertEquals(1, value.paused)
        assertEquals(3050000L, value.income); assertEquals(0L, value.remainingBill); assertEquals(450000L, value.available)
        assertEquals(1050000L, value.cycleProfit); assertEquals(100, value.coveredPercent)
        val corrected = snapshot(listOf(RevenueCorrection(1, "manual:sale", 1000000, 1000000, 10, false, now, "تصحيح")))
        assertEquals(1000000L, corrected.income); assertEquals(1000000L, corrected.remainingBill); assertEquals(0L, corrected.available)
    }
    @Test fun panelIsSilentOngoingPrivateAndSeparateFromDeadlineNotifications() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = StatusPanel.build(context, snapshot(), now)
        assertEquals(StatusPanel.CHANNEL, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertNull(notification.sound); assertNull(notification.vibrate)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertFalse(notification.publicVersion.extras.toString().contains("30,500"))
        assertEquals(2, notification.actions.size)
        assertEquals(6, notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.size)
        StatusPanel.setEnabled(context, false); assertFalse(StatusPanel.enabled(context))
        StatusPanel.setEnabled(context, true); assertTrue(StatusPanel.enabled(context))
    }
    @Test fun emptyUnconfiguredBudgetDoesNotInventProfitAndMidnightAdvances() {
        val value = PanelSnapshot.calculate(emptyList(), emptyList(), emptyList(), BusinessSettings(), emptyList(), emptyList(), emptyList(), now)
        assertEquals(0L, value.income); assertNull(value.remainingBill); assertNull(value.available); assertNull(value.cycleProfit)
        assertTrue(StatusPanel.nextMidnight(now) > now)
        assertEquals(StatusPanel.nextMidnight(now), Revenue.day(StatusPanel.nextMidnight(now)))
    }
    @Test @Config(sdk = [24])
    fun panelSupportsAndroidSevenWithoutNotificationChannels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(StatusPanel.allowed(context))
        val notification = StatusPanel.build(context, snapshot(), now)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(2, notification.actions.size)
    }

}
