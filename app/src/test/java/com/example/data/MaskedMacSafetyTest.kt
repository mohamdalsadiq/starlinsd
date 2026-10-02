package com.example.data

import com.example.db.DeviceIdentity
import org.junit.Assert.*
import org.junit.Test

/**
 * Masked-MAC safety (Dishylink LOCAL-API, measured 2026-08-15): the firmware
 * hides the low three MAC octets ("60:74:f4:XX:XX:XX"), and several devices of
 * the same vendor share the visible prefix. A masked MAC must therefore NEVER
 * identify a device — only a full six-hex-octet MAC may match across snapshots.
 * No network, no database.
 */
class MaskedMacSafetyTest {
    private val masked = "60:74:f4:XX:XX:XX"
    private val real = "60:74:f4:11:22:33"

    private fun identity(deviceId: Long, list: String, mac: String) =
        DeviceIdentity(deviceId, list, mac, "phone", "192.168.1.50", 0L, 0L)

    private fun tracked(id: Long, mac: String) =
        TrackedDevice(id, "phone", "192.168.1.$id", mac, IpLists.Category.UNKNOWN)

    // A masked MAC shared by every same-vendor device must not classify as HOME.
    @Test fun maskedMacNeverClassifiesHomeInEngine() {
        val identities = listOf(identity(7, "HOME", masked))
        val resolved = DeviceIdentityEngine.reconcile(listOf(tracked(9, masked)), identities, emptySet(), emptySet())
        assertEquals(IpLists.Category.UNKNOWN, resolved.single().category)
    }

    // A real MAC still classifies as HOME across a clientId churn.
    @Test fun realMacStillClassifiesHomeInEngine() {
        val identities = listOf(identity(7, "HOME", real))
        val resolved = DeviceIdentityEngine.reconcile(listOf(tracked(9, real)), identities, emptySet(), emptySet())
        assertEquals(IpLists.Category.HOME, resolved.single().category)
    }

    // homeMacs/watchMacs drop masked and blank MACs; real ones survive.
    @Test fun homeMacsDropsMaskedAndBlank() {
        val identities = listOf(
            identity(7, "HOME", masked),
            identity(8, "HOME", ""),
            identity(9, "HOME", real),
            identity(10, "WATCH", real),
        )
        assertEquals(setOf(real), IpLists.homeMacs(identities))
        assertEquals(setOf(real), IpLists.watchMacs(identities))
    }

    // Home re-link candidates: only HOME identities absent from the snapshot.
    @Test fun homeCandidatesAbsentOnly() {
        val identities = listOf(identity(7, "HOME", real), identity(8, "WATCH", real), identity(9, "HOME", ""))
        val found = DeviceRecovery.homeCandidates(identities, liveClientIds = setOf(9L), snapshotOk = true)
        assertEquals(listOf(7L), found.map { it.deviceId })
    }

    // No re-link suggestions before a successful router read.
    @Test fun homeCandidatesEmptyOnFailedDiscovery() {
        val identities = listOf(identity(7, "HOME", real))
        assertTrue(DeviceRecovery.homeCandidates(identities, liveClientIds = emptySet(), snapshotOk = false).isEmpty())
    }

    // A masked-MAC live device is never excluded as "home" from recovery options.
    @Test fun recoveryOptionsNeverExcludeMaskedMac() {
        val live = listOf(tracked(9, masked))
        val options = DeviceRecovery.options(live, homeClientIds = emptySet(), legacyHomeIps = emptySet(),
            boundClientIds = emptySet(), homeMacs = setOf(masked))
        assertEquals(listOf(9L), options.map { it.clientId })
    }

    // A real home MAC still excludes the device from recovery options.
    @Test fun recoveryOptionsExcludeRealHomeMac() {
        val live = listOf(tracked(9, real))
        val options = DeviceRecovery.options(live, homeClientIds = emptySet(), legacyHomeIps = emptySet(),
            boundClientIds = emptySet(), homeMacs = setOf(real))
        assertTrue(options.isEmpty())
    }
}
