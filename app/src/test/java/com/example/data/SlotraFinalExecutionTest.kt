package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.AppDatabase
import com.example.db.DeviceIdentity
import com.example.db.Session
import com.example.domain.Rules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * SLOTRA FINAL EXECUTION — the 21 required behaviour tests. Real in-memory Room and
 * the production engines; no sleeps, no network. Identity is clientId first, a real
 * MAC second, IP is never an identity; discovery failure never acts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SlotraFinalExecutionTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SubscriptionRepository
    private lateinit var lists: IpListStore
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1759000000000L

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = SubscriptionRepository(context, db) { now }
        lists = IpListStore(context, db)
        repo.initialize()
    }
    @After fun close() { db.close() }

    private fun device(clientId: Long, ip: String, mac: String = "60:74:f4:aa:bb:01", name: String = "جهاز $clientId") =
        TrackedDevice(clientId, name, ip, mac, IpLists.Category.UNKNOWN, null)

    private fun client(clientId: Long, ip: String) =
        com.example.network.StarlinkProtocol.Client("جهاز $clientId", ip, "60:74:f4:aa:bb:01", true, clientId, false, null)

    private fun session(clientId: Long, state: String = "ACTIVE", served: Long = 0) = Session(
        id = "s-$clientId", client = "مشترك $clientId", plan = "ساعة", started = now, resumed = now,
        duration = 60 * Rules.MINUTE, served = served, state = state, amount = 50000, cashEquivalent = 50000,
        payment = "CASH", premiumBps = 2500, home = false, grace = Rules.RECOGNITION_MINUTES * Rules.MINUTE,
        deviceClientId = clientId, deviceIp = "192.168.1.50", deviceMac = "60:74:f4:aa:bb:01")

    private suspend fun boundSession(clientId: Long, ip: String, minutes: Int = 60): String {
        val plan = repo.dao.plans().first { it.minutes == minutes && !it.home }
        val s = repo.prepare("مشترك $clientId", plan.id, "CASH", "test")
        repo.insert(s)
        repo.bindDevice(s.id, device(clientId, ip))
        return s.id
    }

    private fun dayDevice(clientId: Long, firstSeen: Long, lastSeen: Long, category: IpLists.Category = IpLists.Category.UNKNOWN) =
        DeviceAlerts.DayDevice(clientId, "جهاز $clientId", "192.168.1.20", "60:74:f4:aa:bb:01", category, firstSeen, lastSeen)

    // 1. clientId remains stable when the IP changes.
    @Test fun t01_clientIdStableWhenIpChanges() = runBlocking {
        val id = boundSession(42, "192.168.1.88")
        val tracker = ClientTracker(context, repo, lists) { listOf(client(42, "192.168.1.120")) }
        tracker.poll()
        val s = repo.dao.session(id)!!
        assertEquals("ACTIVE", s.state)
        assertEquals(42L, s.deviceClientId)
        assertEquals(1, repo.dao.sessions().size)
    }

    // 2. MAC fallback: a stored MAC recognizes the device under a churned clientId.
    @Test fun t02_macFallback() {
        val stored = DeviceIdentity(42, "HOME", "60:74:f4:aa:bb:01", "Galaxy", "192.168.1.9", 0, 0)
        val resolved = DeviceIdentityEngine.reconcile(
            listOf(device(99, "192.168.1.44")), listOf(stored), emptySet(), emptySet())
        assertEquals(IpLists.Category.HOME, resolved.single().category)
        assertEquals(42L, resolved.single().rekeyFrom)
        // Masked MACs never identify anyone.
        assertNull(DeviceAlerts.usableMac("60:74:f4:XX:XX:XX"))
    }

    // 3. HOME identity: stored clientId wins even when the IP is unknown.
    @Test fun t03_homeIdentityByClientId() {
        val stored = DeviceIdentity(42, "HOME", "", "Galaxy", "192.168.1.9", 0, 0)
        val resolved = DeviceIdentityEngine.reconcile(
            listOf(device(42, "192.168.1.200")), listOf(stored), emptySet(), emptySet())
        assertEquals(IpLists.Category.HOME, resolved.single().category)
        assertNull(resolved.single().rekeyFrom)
    }

    // 4. Successful CLIENTS snapshot + device absent → PAUSE.
    @Test fun t04_successfulSnapshotDeviceAbsentPauses() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        now += 10 * Rules.MINUTE
        val tracker = ClientTracker(context, repo, lists) { listOf(client(99, "192.168.1.99")) }
        tracker.poll()
        assertEquals("PAUSED", repo.dao.session(id)!!.state)
    }

    // 5. Discovery failure is never a disappearance.
    @Test fun t05_discoveryFailureNeverPauses() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        now += 10 * Rules.MINUTE
        val tracker = ClientTracker(context, repo, lists) { null }
        assertNull(tracker.poll())
        assertEquals("ACTIVE", repo.dao.session(id)!!.state)
        assertNull(tracker.lastSnapshot)
    }

    // 6. Device returns with the same clientId → the same subscription resumes.
    @Test fun t06_sameClientIdReturnsResumes() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        now += 10 * Rules.MINUTE
        ClientTracker(context, repo, lists) { listOf(client(99, "192.168.1.99")) }.poll()
        assertEquals("PAUSED", repo.dao.session(id)!!.state)
        now += 5 * Rules.MINUTE
        ClientTracker(context, repo, lists) { listOf(client(42, "192.168.1.115")) }.poll()
        val s = repo.dao.session(id)!!
        assertEquals("ACTIVE", s.state)
        assertEquals(50 * Rules.MINUTE, Rules.remaining(s.clock(), now))
        assertEquals(1, repo.dao.sessions().size)
    }

    // 7. Pause preserves the remaining time (offline minutes are never consumed).
    @Test fun t07_pausePreservesRemainingTime() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        now += 10 * Rules.MINUTE
        ClientTracker(context, repo, lists) { emptyList() }.poll()
        assertEquals("PAUSED", repo.dao.session(id)!!.state)
        assertEquals(10 * Rules.MINUTE, repo.dao.session(id)!!.served)
        now += 40 * Rules.MINUTE // long offline window
        assertEquals(50 * Rules.MINUTE, Rules.remaining(repo.dao.session(id)!!.clock(), now))
    }

    // 8. Resume creates no new revenue.
    @Test fun t08_resumeCreatesNoRevenue() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        ClientTracker(context, repo, lists) { emptyList() }.poll()
        ClientTracker(context, repo, lists) { listOf(client(42, "192.168.1.50")) }.poll()
        assertEquals("ACTIVE", repo.dao.session(id)!!.state)
        assertEquals(50000L, repo.dao.session(id)!!.amount)
        assertEquals(1, repo.dao.sessions().size)
        assertTrue(db.businessDao().manualSales().isEmpty())
    }

    // 9. Shortcut 500 binding.
    @Test fun t09_shortcut500Binding() = runBlocking {
        val id = boundSession(42, "192.168.1.50", minutes = 60)
        val s = repo.dao.session(id)!!
        assertEquals(50000L, s.amount)
        assertEquals(42L, s.deviceClientId)
        assertEquals(1, repo.dao.sessions().size)
    }

    // 10. Shortcut 1000 binding.
    @Test fun t10_shortcut1000Binding() = runBlocking {
        val id = boundSession(42, "192.168.1.50", minutes = 180)
        assertEquals(100000L, repo.dao.session(id)!!.amount)
    }

    // 11. Registered amounts stay locked to the shortcut: the ledger only takes unregistered money.
    @Test fun t11_registeredAmountNotEditable() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        val s = repo.dao.session(id)!!
        val groups = DailyReconciliation.confirmationGroups(
            listOf(dayDevice(42, now, now)), repo.dao.sessions(), DeviceAlerts.dayKey(now))
        assertEquals(50000L, groups.subscribed.single().second.amount)
        // A session-linked ("registered") confirmation row contributes ZERO ledger money.
        val registeredRow = com.example.db.DailyDeviceConfirmation(
            DeviceAlerts.dayKey(now), 42L, id, registered = 99999L, confirmed = 99999L, payment = "CASH", premiumBps = 2500, updated = now)
        assertNull(DailyReconciliation.ledgerRow(DeviceAlerts.dayKey(now), listOf(registeredRow), now, 2500, "CASH"))
    }

    // 12. A shortcut during the grace window cancels the unregistered state.
    @Test fun t12_shortcutDuringGraceCancelsUnregistered() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        val history = listOf(dayDevice(42, now - 3 * Rules.MINUTE, now))
        val groups = DailyReconciliation.confirmationGroups(history, repo.dao.sessions(), DeviceAlerts.dayKey(now))
        assertEquals(1, groups.subscribed.size)
        assertTrue(groups.unregistered.isEmpty())
        assertTrue(DailyReconciliation.qualifiedUnregistered(history, repo.dao.sessions(), DeviceAlerts.dayKey(now), 5, now, 0).isEmpty())
        assertEquals(50000L, repo.dao.session(id)!!.amount)
    }

    // 13. A device that disconnects before the grace period leaves no record.
    @Test fun t13_disconnectBeforeGraceLeavesNoRecord() {
        val history = listOf(dayDevice(77, now, now + 2 * Rules.MINUTE))
        val qualified = DailyReconciliation.qualifiedUnregistered(history, emptyList(), DeviceAlerts.dayKey(now), 5, now + 10 * Rules.MINUTE, 0)
        assertTrue(qualified.isEmpty())
    }

    // 14. Still connected beyond the grace period → unregistered.
    @Test fun t14_beyondGraceBecomesUnregistered() {
        val history = listOf(dayDevice(77, now, now + 30 * Rules.MINUTE))
        val qualified = DailyReconciliation.qualifiedUnregistered(history, emptyList(), DeviceAlerts.dayKey(now), 5, now + 30 * Rules.MINUTE, 0)
        assertEquals(listOf(77L), qualified.map { it.clientId })
    }

    // 15. The same clientId counts once, across sightings.
    @Test fun t15_sameClientIdCountedOnce() {
        val first = DeviceAlerts.mergeSnapshot(now, listOf(device(77, "192.168.1.20")), emptyList())
        val later = now + 30 * Rules.MINUTE
        val second = DeviceAlerts.mergeSnapshot(later, listOf(device(77, "192.168.1.21")), first)
        assertEquals(1, second.size)
        val qualified = DailyReconciliation.qualifiedUnregistered(second, emptyList(), DeviceAlerts.dayKey(now), 5, later, 0)
        assertEquals(1, qualified.size)
    }

    // 16. HOME is excluded from financial confirmation.
    @Test fun t16_homeExcludedFromFinancialConfirmation() {
        val history = listOf(dayDevice(701, now, now, IpLists.Category.HOME), dayDevice(88, now, now))
        val groups = DailyReconciliation.confirmationGroups(history, emptyList(), DeviceAlerts.dayKey(now))
        assertTrue(groups.home.any { it.clientId == 701L })
        assertTrue(groups.unregistered.none { it.clientId == 701L })
        assertTrue(groups.subscribed.none { it.first.clientId == 701L })
    }

    // 17. WATCH behavior is preserved (own section, alerts only, never revenue).
    @Test fun t17_watchBehaviorPreserved() {
        val watch = device(31, "192.168.1.31").copy(category = IpLists.Category.WATCH)
        val groups = DailyReconciliation.confirmationGroups(
            listOf(dayDevice(31, now, now, IpLists.Category.WATCH)), emptyList(), DeviceAlerts.dayKey(now))
        assertEquals(listOf(31L), groups.watch.map { it.clientId })
        assertTrue(groups.unregistered.isEmpty() && groups.subscribed.isEmpty())
        val eval = DeviceAlerts.evaluate(DeviceAlerts.EvalInput(now, 5,
            listOf(DeviceAlerts.PendingAlert(31, now - 6 * Rules.MINUTE, now - Rules.MINUTE, IpLists.Category.WATCH)),
            listOf(watch), emptyMap(), emptySet()))
        assertEquals(listOf(31L), eval.due.map { it.clientId })
    }

    // 18. Recovery relinks without a new subscription or revenue.
    @Test fun t18_recoveryCreatesNoSubscriptionOrRevenue() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        now += 10 * Rules.MINUTE
        repo.changeState(id, "PAUSE")
        now += 20 * Rules.MINUTE
        val remainingBefore = Rules.remaining(repo.dao.session(id)!!.clock(), now)
        repo.relinkDevice(id, device(42, "192.168.1.99"), deviceIsLive = true)
        val s = repo.dao.session(id)!!
        assertEquals("ACTIVE", s.state)
        assertEquals(remainingBefore, Rules.remaining(s.clock(), now))
        assertEquals(1, repo.dao.sessions().size)
        assertTrue(db.businessDao().manualSales().isEmpty())
    }

    // 19. Registered revenue is never written again as manual_sales.
    @Test fun t19_registeredRevenueNotDuplicated() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        val s = repo.dao.session(id)!!
        // The subscription's revenue is recognized on the session ledger…
        repo.dao.updateSession(s.copy(recognized = now))
        repo.confirmDailyDevices(DeviceAlerts.dayKey(now),
            listOf(SubscriptionRepository.DailyDeviceAmount(42, id, s.amount)), "CASH")
        // …and the daily confirmation stores it as an audit row only: no manual_sales copy.
        assertEquals(50000L, db.businessDao().dayConfirmations(DeviceAlerts.dayKey(now)).single().registered)
        assertTrue(db.businessDao().manualSales().isEmpty())
    }

    // 20. Daily Confirmation shows END TIME (label + exact end math).
    @Test fun t20_dailyConfirmationShowsEndTime() {
        val sourceFile = listOf(File("src/main/java/com/example/ui/ManagerApp.kt"), File("app/src/main/java/com/example/ui/ManagerApp.kt"))
            .firstOrNull { it.isFile }
        assertNotNull("ManagerApp.kt must be readable", sourceFile)
        assertTrue(sourceFile!!.readText().contains("ينتهي"))
        val start = 1759000000000L
        val s = session(42).copy(started = start, resumed = start, duration = 60 * Rules.MINUTE, served = 0)
        val end = s.resumed + s.duration - s.served
        assertEquals(60 * Rules.MINUTE, end - start)
        assertEquals(0L, Rules.remaining(s.clock(), end))
    }

    // 21. Failed discovery does not pause the timer (pure rule + tracker).
    @Test fun t21_failedDiscoveryDoesNotPauseTimer() = runBlocking {
        assertEquals(DeviceTracker.Plan(), DeviceTracker.compare(listOf(session(42)), null))
        val id = boundSession(42, "192.168.1.50")
        ClientTracker(context, repo, lists) { null }.poll()
        assertEquals("ACTIVE", repo.dao.session(id)!!.state)
    }
}
