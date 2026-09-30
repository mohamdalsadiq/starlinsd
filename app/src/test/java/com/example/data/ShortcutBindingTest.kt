package com.example.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure rules for shortcut auto-binding (spec 1/15/22): which live device a
 * just-fired subscription shortcut belongs to. No network, no database.
 */
class ShortcutBindingTest {
    private fun device(id: Long, ip: String = "192.168.1.10", mac: String = "aa:bb:cc:dd:ee:ff") =
        TrackedDevice(id, "d$id", ip, mac, IpLists.Category.UNKNOWN)

    private fun choose(
        snapshot: List<TrackedDevice>? = emptyList(),
        bound: Set<Long> = emptySet(),
        pending: List<ShortcutBinding.PendingCandidate> = emptyList(),
        homeIds: Set<Long> = emptySet(),
        homeMacs: Set<String> = emptySet(),
        legacyHome: Set<String> = emptySet(),
    ) = ShortcutBinding.choose(snapshot, bound, pending, homeIds, homeMacs, legacyHome)

    // Discovery failure → never bind. No guessing on missing data.
    @Test fun failedDiscoveryNeverBinds() {
        assertNull(choose(snapshot = null))
    }

    // Exactly one free candidate is unambiguous and binds.
    @Test fun singleFreeCandidateBinds() {
        val d = device(101)
        assertEquals(d, choose(snapshot = listOf(d)))
    }

    // Several free candidates with no monitoring signal is ambiguous: no bind.
    @Test fun multipleFreeCandidatesWithoutSignalDoNotBind() {
        assertNull(choose(snapshot = listOf(device(101), device(102))))
    }

    // A device under unregistered-delay monitoring is the customer who just
    // arrived: binding it cancels its pending alert (evaluate drops bound ids).
    @Test fun monitoredCandidateWinsOverUnmonitored() {
        val monitored = device(101)
        val other = device(102)
        val picked = choose(
            snapshot = listOf(monitored, other),
            pending = listOf(ShortcutBinding.PendingCandidate(102, firstSeen = 1000L),
                ShortcutBinding.PendingCandidate(101, firstSeen = 2000L)),
        )
        assertEquals(monitored, picked)
    }

    // Among monitored candidates the most recent arrival wins.
    @Test fun mostRecentMonitoredCandidateWins() {
        val picked = choose(
            snapshot = listOf(device(101), device(102)),
            pending = listOf(ShortcutBinding.PendingCandidate(101, firstSeen = 1000L),
                ShortcutBinding.PendingCandidate(102, firstSeen = 9000L)),
        )
        assertEquals(102L, picked?.clientId)
    }

    // Already-bound devices are never re-bound to a new session.
    @Test fun boundDeviceIsExcluded() {
        assertNull(choose(snapshot = listOf(device(101)), bound = setOf(101L)))
        val free = device(102)
        assertEquals(free, choose(snapshot = listOf(device(101), free), bound = setOf(101L)))
    }

    // HOME by identity (clientId, MAC, or legacy IP) is never a subscription target.
    @Test fun homeDevicesAreExcludedByIdentity() {
        assertNull(choose(snapshot = listOf(device(101)), homeIds = setOf(101L)))
        assertNull(choose(snapshot = listOf(device(101)), homeMacs = setOf("aa:bb:cc:dd:ee:ff")))
        assertNull(choose(snapshot = listOf(device(101)), legacyHome = setOf("192.168.1.10")))
    }

    // Out-of-range clientIds are not valid identities.
    @Test fun invalidClientIdsAreExcluded() {
        assertNull(choose(snapshot = listOf(device(0), device(-5))))
    }

    // A monitored HOME device stays excluded even when it is the only candidate.
    @Test fun monitoredHomeDeviceIsStillExcluded() {
        assertNull(choose(
            snapshot = listOf(device(101)),
            pending = listOf(ShortcutBinding.PendingCandidate(101, firstSeen = 5000L)),
            homeIds = setOf(101L),
        ))
    }
}
