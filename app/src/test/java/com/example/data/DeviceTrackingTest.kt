package com.example.data

import org.junit.Assert.*
import org.junit.Test

/** Pure classification and selection rules; no Android, no network. */
class DeviceTrackingTest {
    private fun device(clientId: Long, ip: String, name: String = "جهاز", mac: String = "aa:bb:cc:dd:ee:ff", category: IpLists.Category) =
        TrackedDevice(clientId, name, ip, mac, category, null)

    // --- Classification (spec 22: cases 1-4) ---

    @Test fun homeIpClassifiesAsHome() {
        assertEquals(IpLists.Category.HOME, IpLists.classify("192.168.1.5", listOf("192.168.1.5"), emptyList()))
    }

    @Test fun watchIpClassifiesAsWatch() {
        assertEquals(IpLists.Category.WATCH, IpLists.classify("192.168.1.9", emptyList(), listOf("192.168.1.9")))
    }

    @Test fun unlistedIpClassifiesAsUnknown() {
        assertEquals(IpLists.Category.UNKNOWN, IpLists.classify("192.168.1.77", emptyList(), emptyList()))
    }

    @Test fun homePrecedenceOverWatch() {
        // Even when both lists match by mistake, HOME wins and the device is never tracked.
        assertEquals(IpLists.Category.HOME, IpLists.classify("192.168.1.5", listOf("192.168.1.5"), listOf("192.168.1.5")))
    }

    @Test fun invalidIpsAreRejected() {
        listOf("", "10.0.0.4", "192.168.1.1", "192.168.1.255", "192.168.1.999", "192.168.1", "text").forEach {
            assertFalse("$it should be rejected", IpLists.valid(it))
        }
        assertTrue(IpLists.valid("192.168.1.55"))
    }

    // --- Selection (spec 22: cases 5-7, spec 11) ---

    @Test fun singleFreeCandidateIsASuggestion() {
        val result = DeviceSelection.choose(listOf(device(101, "192.168.1.10", category = IpLists.Category.UNKNOWN)), emptySet())
        assertEquals(101L, result.suggestion?.clientId)
        assertTrue(!result.unbound)
    }

    @Test fun multipleFreeCandidatesAreAListNotAnAutoPick() {
        val result = DeviceSelection.choose(
            listOf(device(101, "192.168.1.10", category = IpLists.Category.UNKNOWN),
                device(102, "192.168.1.11", category = IpLists.Category.WATCH)), emptySet())
        assertNull(result.suggestion)
        assertEquals(listOf(101L, 102L), result.options.map { it.clientId })
    }

    @Test fun noFreeCandidateMeansUnboundCreation() {
        val result = DeviceSelection.choose(emptyList(), emptySet())
        assertTrue(result.unbound)
        assertNull(result.suggestion)
    }

    @Test fun boundDevicesAreExcludedFromSuggestions() {
        val result = DeviceSelection.choose(
            listOf(device(101, "192.168.1.10", category = IpLists.Category.UNKNOWN),
                device(102, "192.168.1.11", category = IpLists.Category.UNKNOWN)), setOf(101L))
        assertEquals(102L, result.suggestion?.clientId)
    }

    @Test fun homeDevicesAreNeverCandidates() {
        val result = DeviceSelection.choose(
            listOf(device(101, "192.168.1.10", category = IpLists.Category.HOME),
                device(102, "192.168.1.11", category = IpLists.Category.UNKNOWN)), emptySet())
        assertEquals(listOf(102L), result.options.map { it.clientId })
    }

    @Test fun allCandidatesTakenMeansUnbound() {
        val result = DeviceSelection.choose(listOf(device(101, "192.168.1.10", category = IpLists.Category.WATCH)), listOf(101L))
        assertTrue(result.unbound)
    }

    // --- Identity (spec 6/12/22: cases 15-16) ---

    @Test fun changedIpWithSameClientIdIsTheSameDevice() {
        // Tracking never keys on IP: the snapshot entry for clientId 102 is matched by id only.
        val live = listOf(device(102, "192.168.1.200", category = IpLists.Category.UNKNOWN))
        val bound = com.example.db.Session(
            id = "s1", client = "محمد", plan = "ساعة", started = 0, resumed = 0, duration = 3_600_000,
            amount = 50000, cashEquivalent = 50000, payment = "CASH", premiumBps = 2500, home = false,
            grace = 300_000, deviceClientId = 102, deviceIp = "192.168.1.50")
        val plan = DeviceTracker.compare(listOf(bound), live)
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty())
    }

    @Test fun changedMacWithSameClientIdIsNotADifferentDevice() {
        val live = listOf(device(102, "192.168.1.50", mac = "11:22:33:44:55:66", category = IpLists.Category.UNKNOWN))
        val bound = com.example.db.Session(
            id = "s1", client = "محمد", plan = "ساعة", started = 0, resumed = 0, duration = 3_600_000,
            amount = 50000, cashEquivalent = 50000, payment = "CASH", premiumBps = 2500, home = false,
            grace = 300_000, deviceClientId = 102, deviceMac = "aa:bb:cc:dd:ee:ff")
        val plan = DeviceTracker.compare(listOf(bound), live)
        assertTrue(plan.pause.isEmpty()); assertTrue(plan.resume.isEmpty())
    }
}
