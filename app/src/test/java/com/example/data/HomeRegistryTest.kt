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

/**
 * Permanent HOME registry (task: SLOTRA FINAL EXECUTION). Real in-memory Room + the
 * pure engines; identity is clientId FIRST, a real MAC second, IP is display-only.
 * The 16 required HOME tests: persistence, idempotency, IP immunity, classification
 * BEFORE unregistered/grace/session/alerts/revenue, disappearance, discovery failure,
 * and removal returning the device to the normal pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeRegistryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SubscriptionRepository
    private lateinit var lists: IpListStore
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1759000000000L

    private val homeMac = "60:74:f4:aa:bb:01"

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = SubscriptionRepository(context, db) { now }
        lists = IpListStore(context, db)
        repo.initialize()
    }
    @After fun close() { db.close() }

    private fun device(clientId: Long, ip: String, mac: String = "60:74:f4:11:22:%02x".format(clientId % 256), name: String = "جهاز $clientId") =
        TrackedDevice(clientId, name, ip, mac, IpLists.Category.UNKNOWN, null)

    private fun session(clientId: Long, state: String = "ACTIVE") = Session(
        id = "s-$clientId", client = "مشترك $clientId", plan = "ساعة", started = now, resumed = now,
        duration = 60 * Rules.MINUTE, served = 0, state = state, amount = 50000, cashEquivalent = 50000,
        payment = "CASH", premiumBps = 2500, home = false, grace = Rules.RECOGNITION_MINUTES * Rules.MINUTE,
        deviceClientId = clientId, deviceIp = "192.168.1.20", deviceMac = homeMac)

    private fun dayDevice(clientId: Long, firstSeen: Long, lastSeen: Long, category: IpLists.Category = IpLists.Category.UNKNOWN) =
        DeviceAlerts.DayDevice(clientId, "جهاز $clientId", "192.168.1.20", homeMac, category, firstSeen, lastSeen)

    private suspend fun homeRows(): List<DeviceIdentity> = db.businessDao().identities().filter { it.list == "HOME" }

    // 1. Add device to HOME.
    @Test fun test1_addDeviceToHome() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val rows = homeRows()
        assertEquals(1, rows.size)
        assertEquals(701L, rows.single().deviceId)
        assertEquals(device(701, "192.168.1.30").mac, rows.single().mac)
        assertEquals("192.168.1.30", rows.single().lastIp)
    }

    // 2. HOME membership persists (a fresh store over the same database still sees it).
    @Test fun test2_homeMembershipPersists() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val reopened = IpListStore(context, db)
        assertTrue(reopened.identitySnapshot().identities.any { it.deviceId == 701L && it.list == "HOME" })
        assertEquals(IpLists.Category.HOME, reopened.identitySnapshot().classify(device(701, "192.168.1.30")))
    }

    // 3. Same clientId + different IP = same HOME device.
    @Test fun test3_sameClientIdDifferentIpStaysHome() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val stored = homeRows().single()
        val pure = DeviceIdentityEngine.reconcile(listOf(device(701, "192.168.1.51")), listOf(stored), emptySet(), emptySet())
        assertEquals(IpLists.Category.HOME, pure.single().category)
        assertNull(pure.single().rekeyFrom)
        assertEquals(IpLists.Category.HOME, lists.reconcile(listOf(device(701, "192.168.1.51"))).single().category)
        assertEquals(1, homeRows().size)
        assertEquals("192.168.1.51", homeRows().single().lastIp)
    }

    // 4. Same MAC + changed IP = same HOME device when the clientId churned.
    @Test fun test4_sameMacChangedIpStaysHome() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30", mac = homeMac))
        val stored = homeRows().single()
        val pure = DeviceIdentityEngine.reconcile(listOf(device(909, "192.168.1.77", mac = homeMac)), listOf(stored), emptySet(), emptySet())
        assertEquals(IpLists.Category.HOME, pure.single().category)
        assertEquals(701L, pure.single().rekeyFrom) // recognized by MAC, re-keyed
        assertEquals(IpLists.Category.HOME, lists.reconcile(listOf(device(909, "192.168.1.77", mac = homeMac))).single().category)
        assertEquals(1, homeRows().size) // ONE entry, never a duplicate
        assertEquals(909L, homeRows().single().deviceId)
    }

    // 5. Known HOME is classified BEFORE unregistered detection.
    @Test fun test5_homeClassifiedBeforeUnregistered() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val homeCategory = lists.reconcile(listOf(device(701, "192.168.1.30"))).single().category
        assertEquals(IpLists.Category.HOME, homeCategory)
        val history = listOf(dayDevice(701, now, now, homeCategory), dayDevice(88, now, now))
        val groups = DailyReconciliation.confirmationGroups(history, emptyList(), DeviceAlerts.dayKey(now))
        assertTrue(groups.home.any { it.clientId == 701L })
        assertTrue(groups.unregistered.none { it.clientId == 701L })
        assertTrue(groups.unregistered.any { it.clientId == 88L })
    }

    // 6. Known HOME never enters the 5-minute grace (dwell does not qualify it).
    @Test fun test6_homeNeverEntersGrace() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val history = listOf(dayDevice(701, now - 30 * Rules.MINUTE, now, IpLists.Category.HOME))
        val qualified = DailyReconciliation.qualifiedUnregistered(history, emptyList(), DeviceAlerts.dayKey(now), 5, now, 0)
        assertTrue(qualified.isEmpty())
        // An unknown device in the exact same position DOES qualify — grace stays intact.
        val unknown = DailyReconciliation.qualifiedUnregistered(
            listOf(dayDevice(88, now - 30 * Rules.MINUTE, now)), emptyList(), DeviceAlerts.dayKey(now), 5, now, 0)
        assertEquals(1, unknown.size)
    }

    // 7. Known HOME never creates a Session (never offered for binding).
    @Test fun test7_homeNeverCreatesSession() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val resolved = lists.reconcile(listOf(device(701, "192.168.1.30"), device(88, "192.168.1.44")))
        val choice = DeviceSelection.choose(resolved, emptySet())
        assertTrue(choice.options.none { it.clientId == 701L })
        assertEquals(88L, choice.suggestion?.clientId)
        assertEquals(0, repo.dao.sessions().size)
    }

    // 8. Known HOME never enters DeviceTracker customer tracking.
    @Test fun test8_homeNeverTracked() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val plan = DeviceTracker.compare(listOf(session(701)), listOf(device(701, "192.168.1.30")), setOf(701L))
        assertEquals(DeviceTracker.Plan(), plan)
        // And a churned clientId matched by MAC is excluded too — nothing is tracked.
        val churned = lists.reconcile(listOf(device(909, "192.168.1.30", mac = homeMac)))
        val plan2 = DeviceTracker.compare(listOf(session(909)), churned, IpLists.homeClientIds(lists.identitySnapshot().identities))
        assertEquals(DeviceTracker.Plan(), plan2)
    }

    // 9. Known HOME never enters Daily Confirmation history.
    @Test fun test9_homeNeverEntersConfirmation() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val merged = DeviceAlerts.mergeSnapshot(now, listOf(device(701, "192.168.1.30").copy(category = IpLists.Category.HOME)),
            emptyList(), homeClientIds = setOf(701L), homeMacs = setOf(homeMac))
        assertTrue(merged.isEmpty())
        val groups = DailyReconciliation.confirmationGroups(merged, repo.dao.sessions(), DeviceAlerts.dayKey(now))
        assertTrue(groups.subscribed.isEmpty() && groups.unregistered.isEmpty())
    }

    // 10. Known HOME creates no revenue.
    @Test fun test10_homeCreatesNoRevenue() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val history = listOf(dayDevice(701, now, now, IpLists.Category.HOME))
        val qualified = DailyReconciliation.qualifiedUnregistered(history, emptyList(), DeviceAlerts.dayKey(now), 5, now, 0)
        assertTrue(qualified.isEmpty())
        repo.confirmUnregisteredSummary(DeviceAlerts.dayKey(now), qualified.size, 50000L, 0, "CASH")
        assertTrue(db.businessDao().manualSales().isEmpty())
        assertNull(DailyReconciliation.ledgerRow(DeviceAlerts.dayKey(now), emptyList(), now, 2500, "CASH"))
    }

    // 11. Known HOME creates no customer alert.
    @Test fun test11_homeCreatesNoAlert() {
        val home = device(701, "192.168.1.30").copy(category = IpLists.Category.HOME)
        val pending = listOf(DeviceAlerts.PendingAlert(701, now - 30 * Rules.MINUTE, now - Rules.MINUTE, IpLists.Category.WATCH))
        val result = DeviceAlerts.evaluate(DeviceAlerts.EvalInput(now, 5, pending, listOf(home), emptyMap(), emptySet()))
        assertTrue(result.due.isEmpty())
        assertTrue(result.pending.isEmpty())
    }

    // 12. Known HOME disappearance does not pause anything.
    @Test fun test12_homeDisappearancePausesNothing() {
        // A successful snapshot with no clients: the unknown-bound session pauses,
        // the HOME-bound one (excluded by identity) does not.
        val plan = DeviceTracker.compare(listOf(session(701), session(88)), emptyList(), setOf(701L))
        assertEquals(listOf(88L), plan.pause)
        assertTrue(plan.resume.isEmpty())
        // HOME devices have no session in the real flow: empty snapshot → empty plan.
        assertEquals(DeviceTracker.Plan(), DeviceTracker.compare(emptyList(), emptyList(), setOf(701L)))
    }

    // 13. Discovery failure does not change HOME classification.
    @Test fun test13_discoveryFailureKeepsHome() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val before = homeRows()
        val tracker = ClientTracker(context, repo, lists) { null }
        assertNull(tracker.poll())
        assertEquals(before, homeRows())
        assertTrue(tracker.lastSnapshot == null)
    }

    // 14. Returning HOME device is automatically recognized (original added moment preserved).
    @Test fun test14_returningHomeRecognized() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        val added = homeRows().single().added
        now += 2 * 24 * 60 * 60_000L // two days later
        val resolved = lists.reconcile(listOf(device(701, "192.168.1.17")))
        assertEquals(IpLists.Category.HOME, resolved.single().category)
        assertEquals(1, homeRows().size)
        assertEquals(added, homeRows().single().added)
    }

    // 15. Adding the same HOME device twice is idempotent (clientId AND MAC paths).
    @Test fun test15_addIsIdempotent() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30", mac = homeMac))
        lists.addHomeDevice(device(701, "192.168.1.31", mac = homeMac)) // same clientId again
        assertEquals(1, homeRows().size)
        lists.addHomeDevice(device(909, "192.168.1.40", mac = homeMac)) // churned clientId, same MAC
        assertEquals(1, homeRows().size)
        assertEquals(909L, homeRows().single().deviceId)
        lists.addHomeDevice(device(909, "192.168.1.41", mac = homeMac)) // and again
        assertEquals(1, homeRows().size)
    }

    // 16. Removing HOME makes the device eligible for the normal pipeline again.
    @Test fun test16_removalReturnsDeviceToNormalPipeline() = runBlocking {
        lists.addHomeDevice(device(701, "192.168.1.30"))
        lists.removeIdentity(701L)
        val resolved = lists.reconcile(listOf(device(701, "192.168.1.30")))
        assertEquals(IpLists.Category.UNKNOWN, resolved.single().category)
        val history = listOf(dayDevice(701, now - 30 * Rules.MINUTE, now))
        val qualified = DailyReconciliation.qualifiedUnregistered(history, emptyList(), DeviceAlerts.dayKey(now), 5, now, 0)
        assertEquals(1, qualified.size)
    }
}
