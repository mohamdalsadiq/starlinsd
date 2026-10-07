package com.example.data

import com.example.db.Session
import com.example.domain.BusinessDay
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * §1: the Daily Confirmation's subscribed section is built from the PAID
 * subscription list — never from device history. A paid subscription appears
 * even when its device was never seen, disconnected, or churned identity;
 * device history only supplies an optional display label.
 */
class SubscriptionConfirmationTest {
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun session(
        id: String,
        started: Long,
        state: String = "ACTIVE",
        recognized: Long = started + 300_000L,
        home: Boolean = false,
        amount: Long = 50000L,
        reference: String = "2",
        deviceClientId: Long? = 7L,
    ) = Session(id = id, client = "مشترك $id", plan = "ساعة", started = started,
        resumed = started, duration = 3_600_000L, amount = amount, cashEquivalent = amount,
        payment = "CASH", premiumBps = 2500, home = home, grace = 300_000L,
        recognized = recognized, reference = reference, deviceClientId = deviceClientId,
        state = state)

    private fun device(clientId: Long, name: String = "", mac: String = "", firstSeen: Long = 0L) =
        DeviceAlerts.DayDevice(clientId, name, "192.168.1.50", mac,
            IpLists.Category.UNKNOWN, firstSeen, firstSeen + 600_000L)

    @Test fun paidSessionAppearsWithNoDeviceHistoryAtAll() {
        val s = session("s1", at(5, 10))
        val list = DailyReconciliation.subscriptionConfirmations(listOf(s), emptyList(), "2026-10-05")
        assertEquals(1, list.size)
        assertEquals("s1", list.single().session.id)
        assertEquals(50000L, list.single().session.amount) // locked to the shortcut price
        assertNull(list.single().deviceLabel)
    }

    @Test fun includesPausedDisconnectedAndEnded() {
        val active = session("a", at(5, 10))
        val paused = session("p", at(5, 11), state = "PAUSED")
        val ended = session("e", at(5, 9), state = "ENDED")
        val list = DailyReconciliation.subscriptionConfirmations(
            listOf(active, paused, ended), emptyList(), "2026-10-05")
        assertEquals(setOf("a", "p", "e"), list.map { it.session.id }.toSet())
    }

    @Test fun excludesHomeUnrecognizedCancelledAndOtherDays() {
        val home = session("h", at(5, 10), home = true)
        val fresh = session("f", at(5, 10), recognized = 0L) // under five minutes: not confirmed money yet
        val cancelled = session("c", at(5, 10), state = "CANCELLED", recognized = at(5, 10))
        val yesterday = session("y", at(4, 10))
        val list = DailyReconciliation.subscriptionConfirmations(
            listOf(home, fresh, cancelled, yesterday), emptyList(), "2026-10-05")
        assertTrue(list.isEmpty())
    }

    @Test fun postCutoffSaleBelongsToNextBusinessDay() {
        val evening = session("n", at(5, 19))
        assertTrue(DailyReconciliation.subscriptionConfirmations(
            listOf(evening), emptyList(), "2026-10-05").isEmpty())
        val next = DailyReconciliation.subscriptionConfirmations(
            listOf(evening), emptyList(), "2026-10-06")
        assertEquals(listOf("n"), next.map { it.session.id })
    }

    @Test fun deviceLabelComesFromClientIdMatch() {
        val s = session("s1", at(5, 10), deviceClientId = 42L)
        val d = device(42L, name = "realme-C55", firstSeen = at(5, 10))
        val list = DailyReconciliation.subscriptionConfirmations(listOf(s), listOf(d), "2026-10-05")
        assertEquals("realme-C55", list.single().deviceLabel)
    }

    @Test fun eachSessionAppearsExactlyOnce() {
        val s = session("s1", at(5, 10), deviceClientId = 42L)
        // The same session "seen" as two device records (misbinding aftermath)
        // still yields ONE confirmation: the session is the money identity.
        val devices = listOf(device(42L, "a", firstSeen = at(5, 10)), device(43L, "b", firstSeen = at(5, 10)))
        val list = DailyReconciliation.subscriptionConfirmations(listOf(s, s.copy(id = "s2")), devices, "2026-10-05")
        assertEquals(listOf("s1", "s2"), list.map { it.session.id })
    }

    @Test fun sortedBySaleTime() {
        val late = session("late", at(5, 12))
        val early = session("early", at(5, 10))
        val list = DailyReconciliation.subscriptionConfirmations(listOf(late, early), emptyList(), "2026-10-05")
        assertEquals(listOf("early", "late"), list.map { it.session.id })
    }

    @Test fun businessDayKeyMatchesCutoffRule() {
        // Sanity: the confirmation groups by the same key the cutoff closes.
        val s = session("s1", at(5, 10))
        assertEquals("2026-10-05", BusinessDay.key(s.started))
    }
}
