package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.Session
import com.example.domain.Finance
import com.example.domain.Rules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 4 password-change recovery (§40 report point J) against a real in-memory
 * Room database, plus the pure rules. Deterministic: the repository clock is
 * injected and advanced by hand (no sleeps, spec 33).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceRecoveryTest {
    private lateinit var db: com.example.db.AppDatabase
    private lateinit var repo: SubscriptionRepository
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1759000000000L

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, com.example.db.AppDatabase::class.java).build()
        repo = SubscriptionRepository(context, db) { now }
        repo.initialize()
    }
    @After fun close() { db.close() }

    /** Creates a bound active session via prepare/insert/bindDevice (the real shortcut path). */
    private suspend fun startBound(minutes: Int = 60, clientId: Long = 42): Session {
        val plan = repo.dao.plans().first { it.minutes == minutes && !it.home }
        val s = repo.prepare("أحمد", plan.id, "CASH", "test")
        repo.insert(s)
        repo.bindDevice(s.id, TrackedDevice(clientId, "أحمد فون", "192.168.1.145", "aa:bb:cc:dd:ee:ff", IpLists.Category.UNKNOWN, null))
        return repo.dao.session(s.id)!!
    }

    private fun sessionWith(id: Long, state: String, clientId: Long?): Session =
        Session(id = "s$id", client = "جهاز $id", plan = "ساعة", started = 0, resumed = 0,
            duration = 60 * Rules.MINUTE, served = 0, state = state, amount = 50000, cashEquivalent = 50000,
            payment = "CASH", premiumBps = 2500, home = false, grace = 5 * Rules.MINUTE,
            deviceClientId = clientId, deviceIp = if (clientId == null) "" else "192.168.1.$clientId",
            deviceName = "d$id", deviceMac = "mac$id")

    // ---- Pure rules ----

    @Test fun candidatesListOnlyBoundActiveSessionsMissingFromSnapshot() {
        val sessions = listOf(
            sessionWith(1L, "ACTIVE", 10L), sessionWith(2L, "PAUSED", 20L),
            sessionWith(3L, "ENDED", 30L), sessionWith(4L, "ACTIVE", null),
            sessionWith(5L, "ACTIVE", 99L), // still live → not a candidate
        )
        val result = DeviceRecovery.candidates(sessions, 1000L, liveClientIds = setOf(99L), snapshotOk = true)
        assertEquals(listOf("s1", "s2"), result.map { it.sessionId })
    }

    @Test fun failedSnapshotProposesNothing() {
        val sessions = listOf(sessionWith(1L, "ACTIVE", 10L))
        assertTrue(DeviceRecovery.candidates(sessions, 1000L, emptySet(), snapshotOk = false).isEmpty())
    }

    @Test fun optionsExcludeHomeDevicesAndAlreadyBoundClientIds() {
        val snapshot = listOf(
            TrackedDevice(1, "a", "192.168.1.2", "m1", IpLists.Category.UNKNOWN, null),
            TrackedDevice(2, "b", "192.168.1.3", "m2", IpLists.Category.HOME, null),
            TrackedDevice(3, "c", "192.168.1.4", "m3", IpLists.Category.WATCH, null),
        )
        val options = DeviceRecovery.options(snapshot, homeIps = setOf("192.168.1.3"), boundClientIds = setOf(3L))
        assertEquals(listOf(1L), options.map { it.clientId })
    }

    @Test fun ipTailShowsLastOctetsOnly() {
        assertEquals("145", DeviceRecovery.ipTail("192.168.1.145"))
        assertEquals("7", DeviceRecovery.ipTail("10.0.0.7"))
        assertEquals("", DeviceRecovery.ipTail(""))
    }

    @Test fun resumeDecisionRequiresPausedSessionAndLiveDevice() {
        val paused = sessionWith(1L, "PAUSED", 10L)
        val active = sessionWith(2L, "ACTIVE", 20L)
        assertTrue(DeviceRecovery.shouldResumeAfterRelink(paused, deviceIsLive = true))
        assertFalse(DeviceRecovery.shouldResumeAfterRelink(paused, deviceIsLive = false))
        assertFalse(DeviceRecovery.shouldResumeAfterRelink(active, deviceIsLive = true))
        assertFalse(DeviceRecovery.shouldResumeAfterRelink(null, deviceIsLive = true))
    }

    // ---- Integration: relink preserves the subscription ----

    @Test fun relinkKeepsExistingSubscriptionAndRemainingTime() = runBlocking {
        val s = startBound(clientId = 42)
        now += 10 * Rules.MINUTE // 10 minutes served
        repo.changeState(s.id, "PAUSE") // device disappears after password change
        assertEquals(10 * Rules.MINUTE, repo.dao.session(s.id)!!.served)
        now += 25 * Rules.MINUTE // offline window must not be counted
        // The device returns with a NEW router clientId (password change).
        repo.relinkDevice(s.id, TrackedDevice(777, "أحمد فون", "192.168.1.145", "aa:bb:cc:dd:ee:ff", IpLists.Category.UNKNOWN, null), deviceIsLive = true)
        val relinked = repo.dao.session(s.id)!!
        // Same subscription, not a new one; same amount; remaining preserved.
        assertEquals(s.id, relinked.id)
        assertEquals(50000L, relinked.amount)
        assertEquals("ACTIVE", relinked.state)
        assertEquals(777L, relinked.deviceClientId)
        assertEquals(50 * Rules.MINUTE, Rules.remaining(relinked.clock(), now))
        assertEquals(1, repo.dao.sessions().size)
    }

    @Test fun subscriptionEndsAtRealEndAfterRecovery() = runBlocking {
        val s = startBound(minutes = 60, clientId = 42)
        now += 20 * Rules.MINUTE
        repo.changeState(s.id, "PAUSE")
        now += 30 * Rules.MINUTE
        repo.relinkDevice(s.id, TrackedDevice(555, "x", "192.168.1.9", "m", IpLists.Category.UNKNOWN, null), deviceIsLive = true)
        // 20 served, 40 remaining: the pause did not eat the plan, the end shifted by the pause only.
        val active = repo.dao.session(s.id)!!
        assertEquals(40 * Rules.MINUTE, Rules.remaining(active.clock(), now))
        now += 40 * Rules.MINUTE
        assertEquals(0L, Rules.remaining(active.clock(), now))
        val ended = repo.reconcile(now).first { it.id == s.id }
        assertEquals("ENDED", ended.state)
    }

    @Test fun relinkBooksNoNewRevenueAndChangesNoAmount() = runBlocking {
        val s = startBound(clientId = 42)
        now += 10 * Rules.MINUTE
        repo.changeState(s.id, "PAUSE")
        now += 15 * Rules.MINUTE
        repo.relinkDevice(s.id, TrackedDevice(888, "x", "192.168.1.9", "m", IpLists.Category.UNKNOWN, null), deviceIsLive = true)
        val row = repo.dao.session(s.id)!!
        // Amount untouched; only the ORIGINAL session ledger entry exists (no new revenue row).
        assertEquals(50000L, row.amount)
        assertEquals(1, Finance.ledger(repo.dao.sessions(), repo.dao.manualSales(), emptyList()).count { it.id.startsWith("session:") })
        assertTrue(repo.dao.manualSales().isEmpty())
    }

    @Test fun relinkRejectsEndedSessions() = runBlocking {
        val s = startBound(clientId = 42)
        repo.dao.updateSession(repo.dao.session(s.id)!!.copy(state = "ENDED"))
        try {
            repo.relinkDevice(s.id, TrackedDevice(9, "x", "192.168.1.9", "m", IpLists.Category.UNKNOWN, null), deviceIsLive = false)
            fail("ended sessions must not be relinked")
        } catch (expected: IllegalArgumentException) { }
    }

    /** A subscription shortcut must never shadow the recovery keyword (bidirectional guard). */
    @Test fun subscriptionShortcutCannotShadowRecoveryKeyword() = runBlocking {
        val plan = repo.dao.plans().first { it.minutes == 60 && !it.home }
        val count = db.shortcutDao().list().size
        try {
            repo.saveShortcut(com.example.db.Shortcut(keyword = com.example.MainViewModel.DEFAULT_RECOVERY_KEYWORD, phrase = "x", planId = plan.id))
            fail("recovery keyword must be reserved")
        } catch (expected: IllegalArgumentException) { }
        assertEquals(count, db.shortcutDao().list().size)
    }
}
