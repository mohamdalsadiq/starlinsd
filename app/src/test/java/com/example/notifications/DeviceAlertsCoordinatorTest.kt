package com.example.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.DeviceAlerts
import com.example.data.IpLists
import com.example.data.TrackedDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coordinator tests (spec 26/28): fast Robolectric tests over real
 * SharedPreferences; persistence rounds trip and one-per-day keys are covered
 * without any sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceAlertsCoordinatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = System.currentTimeMillis()

    private fun device(id: Long, ip: String, category: IpLists.Category) =
        TrackedDevice(id, "d$id", ip, "mac$id", category)

    @Test fun settingsRoundTripAndValidation() {
        DeviceAlertsCoordinator.setDelayMinutes(context, 5)
        assertEquals(5, DeviceAlertsCoordinator.knownDelayMinutes(context))
        DeviceAlertsCoordinator.setSummaryMinute(context, 23 * 60 + 30)
        assertEquals(23 * 60 + 30, DeviceAlertsCoordinator.knownSummaryMinute(context))
        DeviceAlertsCoordinator.setSummaryMinute(context, null)
        assertEquals(DeviceAlertsCoordinator.DEFAULT_SUMMARY_MINUTE, DeviceAlertsCoordinator.knownSummaryMinute(context))
        try {
            DeviceAlertsCoordinator.setDelayMinutes(context, 2); fail("delay 2 must be rejected")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test fun seedingIsIdempotentAndStopsWhenUserChangesAValue() {
        DeviceAlertsCoordinator.seedDefaults(context)
        assertEquals(3, DeviceAlertsCoordinator.knownDelayMinutes(context))
        assertEquals(DeviceAlertsCoordinator.DEFAULT_SUMMARY_MINUTE, DeviceAlertsCoordinator.knownSummaryMinute(context))
        DeviceAlertsCoordinator.setDelayMinutes(context, 1)
        DeviceAlertsCoordinator.seedDefaults(context) // must not overwrite the user choice
        assertEquals(1, DeviceAlertsCoordinator.knownDelayMinutes(context))
    }

    @Test fun notifiedTodayPersistsPerClientPerDayAndCleansOldDays() {
        DeviceAlertsCoordinator.resetForTest(context)
        DeviceAlertsCoordinator.markNotified(context, now, 123)
        assertEquals(setOf(123L), DeviceAlertsCoordinator.notifiedToday(context, now))
        DeviceAlertsCoordinator.markNotified(context, now, 456)
        assertEquals(setOf(123L, 456L), DeviceAlertsCoordinator.notifiedToday(context, now))
        // A stale key from another day must not leak into today (spec 30 cleanup).
        val prefs = context.getSharedPreferences("device_alerts", 0)
        prefs.edit().putString("notified", """{"2020-01-01":[999]}""").apply()
        DeviceAlertsCoordinator.markNotified(context, now, 123)
        assertFalse(DeviceAlertsCoordinator.notifiedToday(context, now).contains(999))
    }

    @Test fun pendingAlertsRoundTripThroughJson() {
        DeviceAlertsCoordinator.resetForTest(context)
        val alerts = listOf(DeviceAlerts.PendingAlert(7, now, now + 60_000, IpLists.Category.WATCH))
        DeviceAlertsCoordinator.savePending(context, alerts)
        assertEquals(alerts, DeviceAlertsCoordinator.pending(context))
    }

    @Test fun recordSnapshotMergesHistoryAndDropsHome() {
        DeviceAlertsCoordinator.resetForTest(context)
        DeviceAlertsCoordinator.recordSnapshot(context, now,
            listOf(device(1, "192.168.1.10", IpLists.Category.UNKNOWN), device(2, "192.168.1.11", IpLists.Category.HOME)),
            homeIps = setOf("192.168.1.11"))
        val history = DeviceAlertsCoordinator.historyFor(context, now)
        assertEquals(listOf(1L), history.map { it.clientId })
        // A second successful snapshot with the device gone keeps it in history (spec 17).
        DeviceAlertsCoordinator.recordSnapshot(context, now + 60_000, emptyList(), homeIps = emptySet())
        assertEquals(listOf(1L), DeviceAlertsCoordinator.historyFor(context, now).map { it.clientId })
    }

    private fun writeHistoryDay(dayKey: String, clientId: Long) {
        context.getSharedPreferences("device_alerts", 0).edit()
            .putString("history_$dayKey",
                """[{"clientId":$clientId,"name":"d$clientId","ip":"192.168.1.9","mac":"mac$clientId","category":"UNKNOWN","firstSeen":1,"lastSeen":2}]""")
            .apply()
    }

    /** Phase 4 (spec 13): a later day must never wipe earlier days — they stay reviewable. */
    @Test fun laterSnapshotsKeepPastDaysReviewable() {
        DeviceAlertsCoordinator.resetForTest(context)
        val todayKey = DeviceAlerts.dayKey(now)
        DeviceAlertsCoordinator.recordSnapshot(context, now,
            listOf(device(1, "192.168.1.10", IpLists.Category.UNKNOWN)), homeIps = emptySet())
        val tomorrow = now + 24 * 60 * 60_000L
        DeviceAlertsCoordinator.recordSnapshot(context, tomorrow,
            listOf(device(2, "192.168.1.12", IpLists.Category.UNKNOWN)), homeIps = emptySet())
        // Today's record survived the next day's write and still reads back.
        assertEquals(listOf(1L), DeviceAlertsCoordinator.historyFor(context, todayKey).map { it.clientId })
        assertEquals(listOf(2L), DeviceAlertsCoordinator.historyFor(context, tomorrow).map { it.clientId })
    }

    /** Pruning is a bounded retention window: only days older than the window are removed. */
    @Test fun historyPruneKeepsRetentionWindowAndDropsOnlyOlderDays() {
        DeviceAlertsCoordinator.resetForTest(context)
        val day = 24 * 60 * 60_000L
        val keptKey = DeviceAlerts.dayKey(now - 10 * day)
        val staleKey = DeviceAlerts.dayKey(now - 100 * day)
        writeHistoryDay(keptKey, 7)
        writeHistoryDay(staleKey, 8)
        DeviceAlertsCoordinator.recordSnapshot(context, now,
            listOf(device(1, "192.168.1.10", IpLists.Category.UNKNOWN)), homeIps = emptySet())
        val prefs = context.getSharedPreferences("device_alerts", 0)
        assertTrue(prefs.contains("history_$keptKey"))
        assertTrue(prefs.contains("history_${DeviceAlerts.dayKey(now)}"))
        assertFalse(prefs.contains("history_$staleKey"))
        // Exactly the window survivors remain: the kept past day and today.
        assertEquals(2, prefs.all.keys.count { it.startsWith("history_") })
    }
}
