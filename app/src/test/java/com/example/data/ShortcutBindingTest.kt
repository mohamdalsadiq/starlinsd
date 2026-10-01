package com.example.data

import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic stamp binding: a shortcut session binds to the ONE live device
 * whose router name carries its subscriber stamp "[N]" — never guessed.
 * No network, no database.
 */
class ShortcutBindingTest {
    private val now = 1_700_000_000_000L
    private val sessionStart = now - 60_000L

    private fun device(id: Long, name: String, ip: String = "192.168.1.10", mac: String = "aa:bb:cc:dd:ee:ff") =
        TrackedDevice(id, name, ip, mac, IpLists.Category.UNKNOWN)

    private fun key(at: Long, ref: String) = "${DeviceAlerts.dayKey(at)}:$ref"

    private fun match(
        snapshot: List<TrackedDevice>? = emptyList(),
        reference: String = "2",
        sessionStartedAt: Long = sessionStart,
        stampFirstSeen: Map<String, Long> = emptyMap(),
        now: Long = this.now,
        bound: Set<Long> = emptySet(),
        homeIds: Set<Long> = emptySet(),
        homeMacs: Set<String> = emptySet(),
        legacyHome: Set<String> = emptySet(),
    ) = ShortcutBinding.matchStamp(snapshot, reference, sessionStartedAt, stampFirstSeen, now,
        bound, homeIds, homeMacs, legacyHome)

    // Discovery failure → never bind. No guessing on missing data.
    @Test fun failedDiscoveryNeverBinds() {
        assertNull(match(snapshot = null))
    }

    // A blank reference can never match a stamp.
    @Test fun blankReferenceNeverBinds() {
        assertNull(match(snapshot = listOf(device(101, "🌹 [2]🌹 M05")), reference = ""))
    }

    // The happy path: the sold device carries today's "[2]" stamp, freshly seen.
    @Test fun freshStampMatchBinds() {
        val d = device(101, "🌹٠٢:٢٨م [2]🌹 M05")
        val sighted = mapOf(key(now, "2") to now - 30_000L)
        assertEquals(d, match(snapshot = listOf(d), stampFirstSeen = sighted))
    }

    // No stamp anywhere → no bind, even with a single free device. No guessing.
    @Test fun noStampNoBind() {
        val devices = listOf(
            device(101, "🌹 [3]🌹 A"),
            device(102, "plain phone"),
        )
        assertNull(match(snapshot = devices))
    }

    // Yesterday's "[2]" must never match today's subscriber 2 (numbers reset daily).
    @Test fun staleStampFromYesterdayNeverMatches() {
        val d = device(101, "🌹 [2]🌹 M05")
        val yesterday = now - 25 * 60 * 60_000L
        val sighted = mapOf(key(yesterday, "2") to yesterday)
        assertNull(match(snapshot = listOf(d), stampFirstSeen = sighted))
    }

    // Two devices carrying the same stamp is ambiguous: manual binding instead.
    @Test fun multipleStampedDevicesDoNotBind() {
        val devices = listOf(device(101, "[2] A"), device(102, "[2] B"))
        val sighted = mapOf(key(now, "2") to now - 10_000L)
        assertNull(match(snapshot = devices, stampFirstSeen = sighted))
    }

    // Already-bound devices are never re-bound to a new session.
    @Test fun boundDeviceIsExcluded() {
        val d = device(101, "[2] A")
        val sighted = mapOf(key(now, "2") to now - 10_000L)
        assertNull(match(snapshot = listOf(d), stampFirstSeen = sighted, bound = setOf(101L)))
    }

    // HOME by identity (clientId, MAC, or legacy IP) is never a binding target.
    @Test fun homeDevicesAreExcludedByIdentity() {
        val sighted = mapOf(key(now, "2") to now - 10_000L)
        assertNull(match(snapshot = listOf(device(101, "[2] A")), stampFirstSeen = sighted, homeIds = setOf(101L)))
        assertNull(match(snapshot = listOf(device(101, "[2] A")), stampFirstSeen = sighted, homeMacs = setOf("aa:bb:cc:dd:ee:ff")))
        assertNull(match(snapshot = listOf(device(101, "[2] A")), stampFirstSeen = sighted, legacyHome = setOf("192.168.1.10")))
    }

    // Brackets delimit the reference exactly: "[12]" is not "[2]".
    @Test fun bracketDelimitedReferenceIsExact() {
        val d = device(101, "[12] A")
        val sighted = mapOf(key(now, "12") to now - 10_000L)
        assertNull(match(snapshot = listOf(d), reference = "2", stampFirstSeen = sighted))
        assertEquals(d, match(snapshot = listOf(d), reference = "12", stampFirstSeen = sighted))
    }

    // Out-of-range clientIds are not valid identities.
    @Test fun invalidClientIdsAreExcluded() {
        val sighted = mapOf(key(now, "2") to now - 10_000L)
        assertNull(match(snapshot = listOf(device(0, "[2] A")), stampFirstSeen = sighted))
    }

    // A stamp sighted before the session started (minus tolerance) is stale.
    @Test fun stampSightedBeforeSessionIsStale() {
        val d = device(101, "[2] A")
        val sighted = mapOf(key(now, "2") to sessionStart - ShortcutBinding.STAMP_TOLERANCE_MS - 1)
        assertNull(match(snapshot = listOf(d), stampFirstSeen = sighted))
    }

    // A masked MAC can never prove "home": the stamped device stays bindable.
    @Test fun maskedMacNeverExcludesStampCandidate() {
        val d = device(101, "🌹٠٢:٢٨م [2]🌹 M05", mac = "60:74:f4:XX:XX:XX")
        val sighted = mapOf(key(now, "2") to now - 30_000L)
        assertEquals(d, match(snapshot = listOf(d), stampFirstSeen = sighted, homeMacs = setOf("60:74:f4:XX:XX:XX")))
    }

    // A real home MAC still excludes the stamped device from binding.
    @Test fun realHomeMacExcludesStampCandidate() {
        val d = device(101, "🌹٠٢:٢٨م [2]🌹 M05")
        val sighted = mapOf(key(now, "2") to now - 30_000L)
        assertNull(match(snapshot = listOf(d), stampFirstSeen = sighted, homeMacs = setOf("aa:bb:cc:dd:ee:ff")))
    }
}
