package com.example.data

import com.example.db.Session
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure state-transition decisions from CLIENTS snapshots (spec 22: cases 8-14,
 * spec 13/14/21/26/27). No network, no Android framework.
 */
class DeviceTrackerTest {
    private fun session(id: String, state: String, clientId: Long?, home: Boolean = false,
        deviceIp: String = "", now: Long = 0) = Session(
        id = id, client = "مشترك", plan = "ساعة", started = 0, resumed = now, duration = 3_600_000,
        served = 0, state = state, amount = 50000, cashEquivalent = 50000, payment = "CASH",
        premiumBps = 2500, home = home, grace = 300_000, deviceClientId = clientId, deviceIp = deviceIp)

    private fun live(vararg ids: Long) = ids.map { TrackedDevice(it, "d$it", "192.168.1.10", "aa:bb:cc:dd:ee:ff", IpLists.Category.UNKNOWN, null) }

    // Case 8: ACTIVE + no bound device => never paused by tracking.
    @Test fun activeSessionWithoutDeviceIsNeverPaused() {
        val plan = DeviceTracker.compare(listOf(session("a", "ACTIVE", null)), live(101))
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty())
    }

    // Case 9: device disappears => PAUSE.
    @Test fun activeSessionPausesWhenItsDeviceDisappears() {
        val plan = DeviceTracker.compare(listOf(session("a", "ACTIVE", 102)), live(101, 103))
        assertEquals(listOf(102L), plan.pause); assertTrue(plan.resume.isEmpty())
    }

    // Case 10: PAUSED + device returns => RESUME.
    @Test fun pausedSessionResumesWhenItsDeviceReturns() {
        val plan = DeviceTracker.compare(listOf(session("a", "PAUSED", 102)), live(101, 102, 103))
        assertTrue(plan.pause.isEmpty()); assertEquals(listOf(102L), plan.resume)
    }

    // Case 11: PAUSED + device still absent => no new mutation.
    @Test fun pausedSessionWithStillAbsentDeviceIsNotTouchedAgain() {
        val plan = DeviceTracker.compare(listOf(session("a", "PAUSED", 102)), live(101, 103))
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty())
    }

    // Case 12: ACTIVE + device present => no mutation.
    @Test fun activeSessionWithDevicePresentIsNotTouched() {
        val plan = DeviceTracker.compare(listOf(session("a", "ACTIVE", 102)), live(101, 102, 103))
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty())
    }

    // Case 13: HOME => no tracking at all.
    @Test fun homeSessionIsNeverTracked() {
        val plan = DeviceTracker.compare(listOf(session("a", "ACTIVE", 102, home = true)), emptyList())
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty())
    }

    @Test fun sessionWhoseBoundDeviceIsHomeByIdentityStopsBeingTracked() {
        val plan = DeviceTracker.compare(listOf(session("a", "ACTIVE", 102, deviceIp = "192.168.1.7")), emptyList(), homeClientIds = setOf(102L))
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty())
    }

    // Identity reconciliation: a bound session whose device's IP changed is still tracked
    // normally — the old IP-keyed exclusion could never pause/miss it.
    @Test fun subscribedDeviceIpChangeKeepsSubscriptionTracking() {
        val live = listOf(TrackedDevice(102, "a", "192.168.1.101", "mac", IpLists.Category.UNKNOWN, null))
        val plan = DeviceTracker.compare(listOf(session("a", "ACTIVE", 102, deviceIp = "192.168.1.69")), live, homeClientIds = setOf(999L))
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty()) // still live, same device
    }

    // Case 14: duplicate polling cycle => no repeated state mutation.
    @Test fun repeatedPollOfTheSameSnapshotYieldsNoNewMutations() {
        val sessions = listOf(session("a", "ACTIVE", 102), session("b", "PAUSED", 103))
        val first = DeviceTracker.compare(sessions, live(101))
        assertEquals(listOf(102L), first.pause)
        // After applying the plan, the new states must produce an empty plan for the same snapshot.
        val after = DeviceTracker.compare(
            listOf(session("a", "PAUSED", 102), session("b", "PAUSED", 103)), live(101))
        assertTrue(after.pause.isEmpty()); assertTrue(after.resume.isEmpty())
    }

    // ENDED/CANCELLED are never resumed (spec 21: daily close separation).
    @Test fun endedAndCancelledSessionsAreNeverResumed() {
        val plan = DeviceTracker.compare(listOf(
            session("e", "ENDED", 102), session("c", "CANCELLED", 103)), live(102, 103))
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty())
    }

    // Spec 26: a failed CLIENTS query never pauses anything.
    @Test fun failedSnapshotLeavesEverySessionUntouched() {
        val plan = DeviceTracker.compare(listOf(
            session("a", "ACTIVE", 102), session("b", "PAUSED", 103)), null)
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty())
    }

    // Spec 27: empty successful snapshot is router truth - bound ACTIVE devices are gone.
    // A PAUSED session whose device is still absent gets NO mutation (spec 22 case 11);
    // resume only fires when the device RETURNS (spec 13).
    @Test fun emptySuccessfulSnapshotPausesOnlyBoundActiveSessions() {
        val plan = DeviceTracker.compare(listOf(
            session("a", "ACTIVE", 102), session("b", "ACTIVE", null), session("c", "PAUSED", 103)), emptyList())
        assertEquals(listOf(102L), plan.pause); assertTrue(plan.resume.isEmpty())
    }

    // Spec 27: duplicate/null/zero clientIds and infrastructure roles must not crash or double-fire.
    @Test fun duplicateClientIdsInSnapshotAreCollapsed() {
        val duplicated = listOf(
            TrackedDevice(102, "أ", "192.168.1.10", "aa", IpLists.Category.UNKNOWN, null),
            TrackedDevice(102, "ب", "192.168.1.11", "bb", IpLists.Category.UNKNOWN, null))
        val plan = DeviceTracker.compare(listOf(session("a", "ACTIVE", 102)), duplicated)
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty())
    }
}
