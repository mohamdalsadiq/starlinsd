package com.example.data

import com.example.domain.Rules
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Pure-rules tests for Phase 3 device alerts (spec 27/28/29). No sleeps: the
 * clock is just a Long parameter advanced by hand.
 */
class DeviceAlertsTest {
    private val dayStart = DeviceAlerts.dayStart(1_800_000_000_000L)
    private fun at(minute: Long) = dayStart + minute * 60_000L

    private fun device(id: Long, ip: String = "192.168.1.5", category: IpLists.Category = IpLists.Category.WATCH, mac: String = "aa:bb:cc:00:00:0$id") =
        TrackedDevice(id, "d$id", ip, mac, category)

    private fun pending(id: Long, firstSeen: Long, deadline: Long, category: IpLists.Category = IpLists.Category.WATCH) =
        DeviceAlerts.PendingAlert(id, firstSeen, deadline, category)

    private fun unknownDevice(id: Long, ip: String = "192.168.1.5") =
        TrackedDevice(id, "d$id", ip, "aa:bb:cc:00:00:0$id", IpLists.Category.UNKNOWN)

    private fun input(
        now: Long,
        pending: List<DeviceAlerts.PendingAlert> = emptyList(),
        live: List<TrackedDevice>? = emptyList(),
        bindings: Map<Long, String> = emptyMap(),
        notified: Set<Long> = emptySet(),
        delay: Int = 3,
    ) = DeviceAlerts.EvalInput(now, delay, pending, live, bindings, notified)

    // ---- Test 1: no alert before the delay, alert at/after it (WATCH only) ----
    @Test fun alertFiresOnlyAfterTheDelayElapses() {
        val firstSeen = at(600) // 10:00
        val p = listOf(pending(123, firstSeen, firstSeen + 3 * Rules.MINUTE))
        val live = listOf(device(123))
        val before = DeviceAlerts.evaluate(input(at(602), pending = p, live = live))
        assertTrue(before.due.isEmpty()); assertEquals(listOf(123L), before.pending.map { it.clientId })
        val atDeadline = DeviceAlerts.evaluate(input(firstSeen + 3 * Rules.MINUTE, pending = p, live = live))
        assertEquals(listOf(123L), atDeadline.due.map { it.clientId }); assertTrue(atDeadline.pending.isEmpty())
    }

    // ---- Test 2: one alert per clientId per day, even after reconnect (spec 9/10) ----
    @Test fun noSecondAlertSameDayAfterReturn() {
        val firstSeen = at(600)
        val p = listOf(pending(123, firstSeen, firstSeen + 3 * Rules.MINUTE))
        // Notified before the deadline, then device left and returned: no re-alert.
        val result = DeviceAlerts.evaluate(input(at(605), pending = p, live = listOf(device(123)), notified = setOf(123)))
        assertTrue(result.due.isEmpty()); assertTrue(result.pending.isEmpty())
        // A fresh cycle is only opened when the device is live, unnotified, unbound.
        val stillNotified = DeviceAlerts.evaluate(input(at(606), live = listOf(device(123)), notified = setOf(123)))
        assertTrue(stillNotified.pending.isEmpty())
    }

    // ---- Test 3: next day allows a new alert (spec 10) ----
    @Test fun alertAllowedAgainNextDay() {
        val nextDay = at(600) + 24 * 60 * Rules.MINUTE
        val live = listOf(device(123))
        val result = DeviceAlerts.evaluate(input(nextDay, live = live, notified = emptySet()))
        assertEquals(listOf(123L), result.pending.map { it.clientId })
        assertTrue(result.due.isEmpty()) // fresh cycle: deadline not reached yet
    }

    // ---- Test 4: device becomes HOME before the deadline (spec 14/33) ----
    @Test fun noAlertWhenDeviceBecameHome() {
        val firstSeen = at(600)
        val p = listOf(pending(123, firstSeen, firstSeen + 3 * Rules.MINUTE))
        val result = DeviceAlerts.evaluate(input(firstSeen + 3 * Rules.MINUTE, pending = p, live = listOf(device(123, category = IpLists.Category.HOME))))
        assertTrue(result.due.isEmpty()); assertTrue(result.pending.isEmpty())
    }

    // ---- Test 5: device gets an active subscription before the deadline (spec 14) ----
    @Test fun noAlertWhenDeviceGotBound() {
        val firstSeen = at(600)
        val p = listOf(pending(123, firstSeen, firstSeen + 3 * Rules.MINUTE))
        val result = DeviceAlerts.evaluate(input(firstSeen + 3 * Rules.MINUTE, pending = p, live = listOf(device(123)), bindings = mapOf(123L to "s1")))
        assertTrue(result.due.isEmpty()); assertTrue(result.pending.isEmpty())
    }

    // ---- Only WATCH devices ever notify; UNKNOWN stays silent (it qualifies for
    // the unregistered list after the dwell delay instead) ----
    @Test fun onlyWatchDevicesNotifyUnknownStaysSilent() {
        val firstSeen = at(600)
        val p = listOf(
            pending(1, firstSeen, firstSeen + 3 * Rules.MINUTE, IpLists.Category.WATCH),
            pending(2, firstSeen, firstSeen + 3 * Rules.MINUTE, IpLists.Category.UNKNOWN),
        )
        val live = listOf(device(1, category = IpLists.Category.WATCH), unknownDevice(2))
        val result = DeviceAlerts.evaluate(input(firstSeen + 3 * Rules.MINUTE, pending = p, live = live))
        assertEquals(listOf(1L), result.due.map { it.clientId })
        assertEquals(IpLists.Category.WATCH, result.due.single().category)
        assertTrue(result.pending.isEmpty()) // the UNKNOWN pending is dropped, never re-armed as an alert
    }

    @Test fun unknownDeviceNeverOpensAnAlertCycle() {
        val result = DeviceAlerts.evaluate(input(at(600), live = listOf(unknownDevice(123))))
        assertTrue(result.due.isEmpty()); assertTrue(result.pending.isEmpty())
    }

    // ---- Spec 32: failed discovery freezes pending and opens nothing ----
    @Test fun failedDiscoveryFreezesPendingAlerts() {
        val firstSeen = at(600)
        val p = listOf(pending(123, firstSeen, firstSeen + 3 * Rules.MINUTE))
        val frozen = DeviceAlerts.evaluate(input(firstSeen + 10 * Rules.MINUTE, pending = p, live = null))
        assertEquals(listOf(123L), frozen.pending.map { it.clientId }); assertTrue(frozen.due.isEmpty())
        val none = DeviceAlerts.evaluate(input(firstSeen + 10 * Rules.MINUTE, live = null))
        assertTrue(none.due.isEmpty()); assertTrue(none.pending.isEmpty())
    }

    // ---- Spec 45: a changed delay recalculates deadlines from firstSeen ----
    @Test fun changedDelayRecalculatesPendingDeadlines() {
        val firstSeen = at(600)
        val p = listOf(pending(123, firstSeen, firstSeen + 5 * Rules.MINUTE))
        // 30 seconds in: a 1-minute delay still waits; the deadline follows the new delay.
        val result = DeviceAlerts.evaluate(input(firstSeen + 30_000L, pending = p, live = listOf(device(123)), delay = 1))
        assertEquals(firstSeen + Rules.MINUTE, result.pending.single().deadline)
    }

    // ---- A vanished WATCH device loses its window; it re-arms on return ----
    @Test fun vanishedWatchDeviceLosesItsWindowThenReArmsOnReturn() {
        val firstSeen = at(600)
        val p = listOf(pending(123, firstSeen, firstSeen + 3 * Rules.MINUTE))
        val gone = DeviceAlerts.evaluate(input(firstSeen + 4 * Rules.MINUTE, pending = p, live = emptyList()))
        assertTrue(gone.due.isEmpty()); assertTrue(gone.pending.isEmpty())
        val back = DeviceAlerts.evaluate(input(firstSeen + 5 * Rules.MINUTE, pending = emptyList(), live = listOf(device(123))))
        assertEquals(listOf(123L), back.pending.map { it.clientId })
    }

    // ---- History merging (spec 17/18/32/33) ----
    @Test fun historyMergesByClientIdKeepsGoneAndDropsHome() {
        val now = at(600)
        val existing = listOf(
            DeviceAlerts.DayDevice(7, "old", "192.168.1.9", "mac7", IpLists.Category.UNKNOWN, at(500), at(500)),
            DeviceAlerts.DayDevice(10, "gone", "192.168.1.30", "mac10", IpLists.Category.UNKNOWN, at(510), at(515)))
        // 7 still live with a new IP (same device), 8 is new, 9 is HOME by identity, 10 left.
        val seen = listOf(
            device(7, ip = "192.168.1.20"),
            device(8, ip = "192.168.1.21"),
            device(9, ip = "192.168.1.22", category = IpLists.Category.HOME),
        )
        val merged = DeviceAlerts.mergeSnapshot(now, seen, existing,
            homeClientIds = setOf(9L), homeMacs = emptySet(), legacyHomeIps = emptySet())
        val byId = merged.associateBy { it.clientId }
        assertEquals("192.168.1.20", byId[7L]!!.ip); assertEquals(at(500), byId[7L]!!.firstSeen)
        assertEquals(now, byId[7L]!!.lastSeen)
        assertEquals(now, byId[8L]!!.firstSeen)
        assertNull(byId[9L])
        assertEquals(at(515), byId[10L]!!.lastSeen) // left the network but stays in today's history
    }

    // Identity reconciliation: a HOME device that appears with a NEW IP is still excluded
    // (clientId match), and a legacy-IP device is excluded only while it holds that IP.
    @Test fun homeExclusionFollowsIdentityNotIp() {
        val now = at(600)
        val seen = listOf(
            device(7, ip = "192.168.1.101", mac = "mac7"), // HOME clientId, IP changed overnight
            device(8, ip = "192.168.1.69", mac = "mac8"), // legacy IP match, pre-promotion
            device(9, ip = "192.168.1.70", mac = "mac9"), // legacy HOME device now at a NEW IP: tracked
        )
        val merged = DeviceAlerts.mergeSnapshot(now, seen, emptyList(),
            homeClientIds = setOf(7L), homeMacs = emptySet(), legacyHomeIps = setOf("192.168.1.69"))
        val ids = merged.map { it.clientId }
        assertTrue(7L !in ids)  // HOME by clientId survives any IP change
        assertTrue(8L !in ids)  // still holding the legacy listed IP
        assertTrue(9L in ids)   // legacy IP no longer matches after the move: normal device
    }

    @Test fun dayKeyUsesLocalDateAndRollsAtMidnight() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 28, 23, 59, 0); set(Calendar.MILLISECOND, 0) }
        val before = cal.timeInMillis
        val sameDay = DeviceAlerts.dayStart(before)
        cal.add(Calendar.MINUTE, 1)
        val after = cal.timeInMillis
        assertEquals(DeviceAlerts.dayKey(before), DeviceAlerts.dayKey(sameDay))
        assertNotEquals(DeviceAlerts.dayKey(before), DeviceAlerts.dayKey(after)) // spec 29: 23:59 vs 00:00
        assertEquals(after, DeviceAlerts.nextDaily(before, 0)) // next occurrence of 00:00 is tomorrow 00:00
    }

    // ---- Daily summary (spec 16/19/20) ----
    @Test fun summarySplitsSubscribedAndUnconfirmedAndExcludesHomeUpstream() {
        val history = listOf(
            DeviceAlerts.DayDevice(1, "A", "192.168.1.10", "", IpLists.Category.UNKNOWN, 0, 0),
            DeviceAlerts.DayDevice(2, "B", "192.168.1.11", "", IpLists.Category.UNKNOWN, 0, 0),
            DeviceAlerts.DayDevice(3, "C", "192.168.1.12", "", IpLists.Category.UNKNOWN, 0, 0),
        )
        val bindings = mapOf(1L to "s1", 2L to "s2") // 3 unbound
        val summary = DeviceAlerts.summary(history, bindings)
        assertEquals(listOf(1L, 2L), summary.subscribed.map { it.clientId })
        assertEquals(listOf(3L), summary.unconfirmed.map { it.clientId })
        // A binding whose session ended is not a live subscription (spec 19): only
        // ACTIVE/PAUSED sessions reach `bindings` by construction.
        val none = DeviceAlerts.summary(history, emptyMap())
        assertTrue(none.subscribed.isEmpty()); assertEquals(3, none.unconfirmed.size)
    }

    @Test fun nextWakeupPicksNearestPendingOrSummary() {
        val now = at(600)
        val wakeups = listOf(
            DeviceAlerts.nextWakeup(now, listOf(pending(1, at(590), at(620))), null),
            DeviceAlerts.nextWakeup(now, emptyList(), 22 * 60),
            DeviceAlerts.nextWakeup(now, emptyList(), null))
        assertEquals(at(620), wakeups[0])
        assertEquals(DeviceAlerts.nextDaily(now, 22 * 60), wakeups[1])
        assertNull(wakeups[2])
    }
}
