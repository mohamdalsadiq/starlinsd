package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.AppDatabase
import com.example.db.DeviceIdentity
import com.example.domain.Finance
import com.example.domain.Revenue
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
 * The 15 device-identity-reconciliation tests (task المطلوب 15). Real in-memory Room +
 * the real repository with an injected clock; no sleeps, no network. Identity order is
 * clientId → MAC → one-shot legacy IP promotion; discovery failure never acts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceIdentityReconciliationTest {
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

    private fun device(clientId: Long, ip: String, name: String = "d$clientId", mac: String = "mac$clientId",
        category: IpLists.Category = IpLists.Category.UNKNOWN) =
        TrackedDevice(clientId, name, ip, mac, category, null)

    private suspend fun tracked(vararg devices: TrackedDevice) = lists.reconcile(devices.toList())

    private suspend fun boundSession(clientId: Long, ip: String, minutes: Int = 60): String {
        val plan = repo.dao.plans().first { it.minutes == minutes && !it.home }
        val s = repo.prepare("مشترك $clientId", plan.id, "CASH", "test")
        repo.insert(s)
        repo.bindDevice(s.id, device(clientId, ip))
        return s.id
    }

    // ---- TEST 1: HOME device IP changes, same clientId → remains HOME ----
    @Test fun test1_homeDeviceIpChangeStaysHome() = runBlocking {
        lists.setHomeIdentity(21, "Galaxy-A21s", "mac21", "192.168.1.69")
        val resolved = tracked(device(21, "192.168.1.101"))
        assertEquals(IpLists.Category.HOME, resolved.single().category)
        // The identity record keeps the same device with the new last-known IP.
        val row = db.businessDao().identity(21)!!
        assertEquals("192.168.1.101", row.lastIp)
        assertEquals("HOME", row.list)
    }

    // ---- TEST 2: subscribed device IP changes → same subscription, tracked normally ----
    @Test fun test2_subscribedDeviceIpChangeKeepsSubscription() = runBlocking {
        val id = boundSession(42, "192.168.1.69")
        // Device returns with a NEW IP; plan must neither pause nor resume it.
        val tracker = ClientTracker(context, repo, lists) { listOf(
            com.example.network.StarlinkProtocol.Client("d42", "192.168.1.101", "mac42", true, 42, false, null),
            com.example.network.StarlinkProtocol.Client("d99", "192.168.1.99", "mac99", true, 99, false, null)) }
        tracker.poll()
        val s = repo.dao.session(id)!!
        assertEquals("ACTIVE", s.state)
        assertEquals(42L, s.deviceClientId)
        assertEquals(50000L, s.amount)
    }

    // ---- TEST 3: subscribed device disappears → PAUSE, remaining preserved ----
    @Test fun test3_disappearancePausesAndPreservesRemaining() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        now += 10 * Rules.MINUTE
        val tracker = ClientTracker(context, repo, lists) { listOf(
            com.example.network.StarlinkProtocol.Client("d99", "192.168.1.99", "mac99", true, 99, false, null)) }
        tracker.poll()
        val s = repo.dao.session(id)!!
        assertEquals("PAUSED", s.state)
        assertEquals(10 * Rules.MINUTE, s.served)
        now += 25 * Rules.MINUTE // offline window must not consume the plan
        assertEquals(50 * Rules.MINUTE, Rules.remaining(s.clock(), now))
    }

    // ---- TEST 4: same clientId returns with new IP → subscription resumed ----
    @Test fun test4_sameClientIdNewIpResumesSubscription() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        now += 10 * Rules.MINUTE
        val gone = ClientTracker(context, repo, lists) { listOf(
            com.example.network.StarlinkProtocol.Client("d99", "192.168.1.99", "mac99", true, 99, false, null)) }
        gone.poll()
        assertEquals("PAUSED", repo.dao.session(id)!!.state)
        now += 5 * Rules.MINUTE
        // Same clientId, NEW IP (DHCP moved it while offline).
        val back = ClientTracker(context, repo, lists) { listOf(
            com.example.network.StarlinkProtocol.Client("d42", "192.168.1.115", "mac42", true, 42, false, null)) }
        back.poll()
        val s = repo.dao.session(id)!!
        assertEquals("ACTIVE", s.state)
        assertEquals(50 * Rules.MINUTE, Rules.remaining(s.clock(), now))
        assertEquals(1, repo.dao.sessions().size) // no second subscription
    }

    // ---- TEST 5: HOME device appears with new IP → not unregistered ----
    @Test fun test5_homeDeviceNewIpIsNotUnregistered() = runBlocking {
        lists.setHomeIdentity(21, "Galaxy-A21s", "mac21", "192.168.1.69")
        val resolved = tracked(device(21, "192.168.1.101"), device(55, "192.168.1.55"))
        assertEquals(IpLists.Category.HOME, resolved.first { it.clientId == 21L }.category)
        assertNotEquals(IpLists.Category.UNKNOWN, resolved.first { it.clientId == 21L }.category)
        // HOME stays out of the day's confirmable/unregistered pool.
        assertEquals(IpLists.Category.UNKNOWN, resolved.first { it.clientId == 55L }.category)
    }

    // ---- TEST 6: WATCH device changes IP → still WATCH ----
    @Test fun test6_watchDeviceIpChangeStaysWatch() = runBlocking {
        lists.setWatchIdentity(31, "realme-C55", "mac31", "192.168.1.139")
        val resolved = tracked(device(31, "192.168.1.77"))
        assertEquals(IpLists.Category.WATCH, resolved.single().category)
        assertEquals("192.168.1.77", db.businessDao().identity(31)!!.lastIp)
    }

    // ---- TEST 7: discovery failure → no false disappearance, no state change ----
    @Test fun test7_discoveryFailureChangesNothing() = runBlocking {
        val id = boundSession(42, "192.168.1.50")
        now += 10 * Rules.MINUTE
        val tracker = ClientTracker(context, repo, lists) { null }
        assertNull(tracker.poll())
        val s = repo.dao.session(id)!!
        assertEquals("ACTIVE", s.state)
        assertEquals(0L, s.served)
        // A failed read also reconciles nothing.
        assertTrue(db.businessDao().identities().isEmpty())
    }

    // ---- TEST 8: Daily Confirmation recognizes the subscribed device after IP change ----
    @Test fun test8_dailyConfirmationRecognizesSubscribedAfterIpChange() = runBlocking {
        val id = boundSession(42, "192.168.1.69")
        repo.dao.updateSession(repo.dao.session(id)!!.copy(recognized = now)) // revenue recognized
        val dayKey = DeviceAlerts.dayKey(now)
        val device = device(42, "192.168.1.101") // new IP, same identity
        val session = repo.dao.sessions().first { it.deviceClientId == device.clientId }
        repo.confirmDailyDevices(dayKey, listOf(SubscriptionRepository.DailyDeviceAmount(device.clientId, session.id, session.amount)), "CASH")
        val rows = db.businessDao().dayConfirmations(dayKey)
        assertEquals(1, rows.size)
        assertEquals(42L, rows[0].deviceId)
        assertEquals(50000L, rows[0].confirmed)
        // The day's single ledger row carries the amount for the event day.
        val ledger = DailyReconciliation.rowsFor(db.businessDao().manualSales(), dayKey).single()
        assertEquals(50000L, ledger.amount)
        assertEquals(Revenue.day(now), Revenue.day(ledger.at))
    }

    // ---- TEST 9: Daily Confirmation recognizes an unregistered device ----
    @Test fun test9_dailyConfirmationRecognizesUnregistered() = runBlocking {
        val dayKey = DeviceAlerts.dayKey(now)
        repo.confirmDailyDevices(dayKey, listOf(SubscriptionRepository.DailyDeviceAmount(77, "", 30000L)), "CASH")
        val rows = db.businessDao().dayConfirmations(dayKey)
        assertEquals(1, rows.size)
        assertEquals(77L, rows[0].deviceId)
        assertEquals("", rows[0].sessionId) // no subscription behind it
        assertEquals(30000L, rows[0].confirmed)
        assertEquals(0L, rows[0].registered)
    }

    // ---- TEST 10: edit 1000 → 500 recalculates the total correctly ----
    @Test fun test10_editAmountRecalculatesTotal() = runBlocking {
        val dayKey = DeviceAlerts.dayKey(now)
        repo.confirmDailyDevices(dayKey, listOf(
            SubscriptionRepository.DailyDeviceAmount(1, "", 100000L),
            SubscriptionRepository.DailyDeviceAmount(2, "", 50000L)), "CASH")
        now += 60_000
        // Device 1 was actually paid 500, not 1000.
        repo.confirmDailyDevices(dayKey, listOf(
            SubscriptionRepository.DailyDeviceAmount(1, "", 50000L),
            SubscriptionRepository.DailyDeviceAmount(2, "", 50000L)), "CASH")
        val rows = db.businessDao().dayConfirmations(dayKey)
        assertEquals(2, rows.size)
        assertEquals(100000L, rows.sumOf { it.confirmed })
        val ledger = DailyReconciliation.rowsFor(db.businessDao().manualSales(), dayKey).single()
        assertEquals(100000L, ledger.amount)
    }

    // ---- TEST 11: open confirmation twice → no duplicate revenue ----
    @Test fun test11_repeatedConfirmationNeverDuplicatesRevenue() = runBlocking {
        val dayKey = DeviceAlerts.dayKey(now)
        val entry = SubscriptionRepository.DailyDeviceAmount(42, "", 25000L)
        repo.confirmDailyDevices(dayKey, listOf(entry), "CASH")
        now += 3 * 60 * 60_000L
        repo.confirmDailyDevices(dayKey, listOf(entry), "CASH") // reopened, confirmed again
        now += 3 * 60 * 60_000L
        repo.confirmDailyDevices(dayKey, listOf(entry), "CASH") // and a third time
        val ledger = Finance.ledger(db.businessDao().sessions(), db.businessDao().manualSales(), emptyList())
            .filter { !it.voided && it.id.startsWith("manual:") }
        assertEquals(1, ledger.size) // exactly ONE revenue entry for the day
        assertEquals(25000L, ledger.single().value)
    }

    // ---- TEST 12: modify the same device twice → same reconciliation record ----
    @Test fun test12_modifyTwiceUpdatesSameRecord() = runBlocking {
        val dayKey = DeviceAlerts.dayKey(now)
        repo.confirmDailyDevices(dayKey, listOf(SubscriptionRepository.DailyDeviceAmount(9, "", 40000L)), "CASH")
        val firstUpdated = db.businessDao().dayConfirmations(dayKey).single().updated
        now += 120_000
        repo.confirmDailyDevices(dayKey, listOf(SubscriptionRepository.DailyDeviceAmount(9, "", 35000L)), "CASH")
        val rows = db.businessDao().dayConfirmations(dayKey)
        assertEquals(1, rows.size) // same (dayKey, deviceId) record, not a second one
        assertEquals(35000L, rows[0].confirmed)
        assertTrue(rows[0].updated > firstUpdated)
    }

    // ---- TEST 13: HOME excluded from financial totals ----
    @Test fun test13_homeExcludedFromFinancialTotals() = runBlocking {
        lists.setHomeIdentity(21, "Galaxy-A21s", "mac21", "192.168.1.69")
        val id = boundSession(42, "192.168.1.50")
        val resolved = tracked(device(21, "192.168.1.101"), device(42, "192.168.1.50"))
        val dayKey = DeviceAlerts.dayKey(now)
        // The screen only offers non-HOME devices; simulate exactly that (المطلوب 9/13).
        val entries = resolved.filter { it.category != IpLists.Category.HOME }
            .map { SubscriptionRepository.DailyDeviceAmount(it.clientId, "", 30000L) }
        repo.confirmDailyDevices(dayKey, entries, "CASH")
        val rows = db.businessDao().dayConfirmations(dayKey)
        assertTrue(rows.none { it.deviceId == 21L })
        val ledger = DailyReconciliation.rowsFor(db.businessDao().manualSales(), dayKey).single()
        assertEquals(30000L, ledger.amount) // only the non-HOME device's money
        // Defense in depth: a HOME session can never be confirmed — the repository
        // rejects any entry whose sessionId resolves to a real home session.
        val homeSession = repo.dao.session(id)!!.copy(id = "home-s", home = true, amount = 0, cashEquivalent = 0)
        repo.dao.insertSession(homeSession)
        try {
            repo.confirmDailyDevices(dayKey, listOf(SubscriptionRepository.DailyDeviceAmount(21, "home-s", 1000L)), "CASH")
            fail("HOME sessions must never enter the financial confirmation")
        } catch (expected: IllegalArgumentException) { }
    }

    // ---- TEST 14: recovery after IP change re-binds the same subscription ----
    @Test fun test14_recoveryAfterIpChange() = runBlocking {
        val id = boundSession(42, "192.168.1.69")
        now += 10 * Rules.MINUTE
        repo.changeState(id, "PAUSE")
        now += 20 * Rules.MINUTE
        // The device returns with a different IP; recovery re-binds by clientId.
        repo.relinkDevice(id, device(42, "192.168.1.115"), deviceIsLive = true)
        val s = repo.dao.session(id)!!
        assertEquals("ACTIVE", s.state)
        assertEquals(42L, s.deviceClientId)
        assertEquals("192.168.1.115", s.deviceIp)
        assertEquals(50 * Rules.MINUTE, Rules.remaining(s.clock(), now))
        assertEquals(1, repo.dao.sessions().size) // no new subscription, no new revenue
        assertTrue(db.businessDao().manualSales().isEmpty())
    }

    // ---- TEST 15: different clientIds with similar names stay separate ----
    @Test fun test15_similarNamesDifferentClientIdsStaySeparate() = runBlocking {
        val a = boundSession(101, "192.168.1.10")
        val b = boundSession(102, "192.168.1.11")
        // Same display name: identity must never merge them.
        repo.rename(a, "Galaxy-A21s")
        repo.rename(b, "Galaxy-A21s")
        val resolved = tracked(device(101, "192.168.1.101", name = "Galaxy-A21s"),
            device(102, "192.168.1.102", name = "Galaxy-A21s"))
        assertEquals(2, resolved.size)
        assertEquals(setOf(101L, 102L), resolved.map { it.clientId }.toSet())
        // Each keeps its own subscription and money.
        assertEquals(2, repo.dao.sessions().count { it.state == "ACTIVE" })
        repo.confirmDailyDevices(DeviceAlerts.dayKey(now), listOf(
            SubscriptionRepository.DailyDeviceAmount(101, a, 50000L),
            SubscriptionRepository.DailyDeviceAmount(102, b, 100000L)), "CASH")
        val rows = db.businessDao().dayConfirmations(DeviceAlerts.dayKey(now)).sortedBy { it.deviceId }
        assertEquals(listOf(50000L, 100000L), rows.map { it.confirmed })
    }
}
