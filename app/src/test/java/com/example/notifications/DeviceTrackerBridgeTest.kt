package com.example.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.IpLists
import com.example.data.TrackedDevice
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Forensic audit coverage for the background monitoring bridge:
 *
 * - A stale snapshot must never drive evaluation (treated as discovery failure).
 * - Discovery failure must drop the snapshot, never re-stamp it as fresh.
 * - New-device discovery must stay alive while the app is closed: no
 *   subscription + no pending candidate must still allow periodic observation,
 *   on the single alarm schedule (never a second scheduler).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceTrackerBridgeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun device(id: Long) = TrackedDevice(id, "d$id", "192.168.1.$id", "aa:bb:cc:dd:ee:$id", IpLists.Category.UNKNOWN)

    @Before fun reset() { DeviceTrackerBridge.resetForTest() }

    // ---- Snapshot freshness (audit: stale snapshot) ----

    @Test fun freshSnapshotReturnsListWhenFresh() {
        val live = listOf(device(101))
        DeviceTrackerBridge.updateSnapshot(live)
        assertEquals(live, DeviceTrackerBridge.freshSnapshot())
    }

    @Test fun freshSnapshotReturnsNullWhenStale() {
        DeviceTrackerBridge.updateSnapshot(listOf(device(101)))
        // The age gate: older than maxAgeMs is treated as discovery failure.
        assertNull(DeviceTrackerBridge.freshSnapshot(maxAgeMs = -1L))
    }

    @Test fun failedSnapshotYieldsNullSnapshot() {
        DeviceTrackerBridge.updateSnapshot(listOf(device(101)))
        // Discovery failure drops the snapshot; it must never be re-stamped fresh.
        DeviceTrackerBridge.updateSnapshot(null)
        assertNull(DeviceTrackerBridge.freshSnapshot())
    }

    // ---- Background discovery cadence (audit: new device discovery) ----

    @Test fun monitoringWakeupIsNullWhenTickIsDue() {
        // Fresh state: last attempt is ancient, so the tick is due now and the
        // poll runs on this refresh instead of scheduling a future wakeup.
        assertNull(DeviceTrackerBridge.monitoringWakeup())
    }

    @Test fun monitoringWakeupScheduledAfterObservation() {
        val before = System.currentTimeMillis()
        DeviceTrackerBridge.updateSnapshot(listOf(device(101)))
        val after = System.currentTimeMillis()
        val wakeup = DeviceTrackerBridge.monitoringWakeup(after)
        assertNotNull(wakeup)
        assertTrue(wakeup!! in (before + DeviceTrackerBridge.MONITOR_INTERVAL_MS)..(after + DeviceTrackerBridge.MONITOR_INTERVAL_MS))
        // Far in the future the tick is already due → no future wakeup.
        assertNull(DeviceTrackerBridge.monitoringWakeup(after + DeviceTrackerBridge.MONITOR_INTERVAL_MS + 1))
    }

    @Test fun needsPollWhenIdleButDiscoveryOverdue() {
        // No subscriptions, no pending candidates — yet the periodic discovery
        // sweep is due, so a poll must still happen. "No subscription + no
        // pending candidate" must never mean "no observation".
        assertTrue(DeviceTrackerBridge.needsPoll(context))
    }

    @Test fun noPollWhenRecentlyObservedAndNothingTracked() {
        DeviceTrackerBridge.updateSnapshot(listOf(device(101)))
        assertFalse(DeviceTrackerBridge.needsPoll(context))
    }
}
