package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full pause/resume cycle against a real repository and an in-memory database, with a
 * fake probe: no real network, no real Starlink (spec 22).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClientTrackerTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SubscriptionRepository
    private lateinit var lists: IpListStore
    private var now = 1700000000000L

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = SubscriptionRepository(context, db) { now }
        lists = IpListStore(context, db)
        repo.initialize()
    }
    @After fun close() { db.close() }

    private suspend fun boundSession(clientId: Long, state: String): String {
        val p = repo.dao.plans().first { it.minutes == 180 && !it.home }
        val s = repo.prepare("مشترك", p.id, "CASH", "test").let { prepared ->
            repo.insert(prepared)
            if (state == "PAUSED") repo.changeState(prepared.id, "PAUSE")
            prepared.id
        }
        repo.bindDevice(s, TrackedDevice(clientId, "جهاز", "192.168.1.50", "aa:bb:cc:dd:ee:ff", IpLists.Category.UNKNOWN))
        return s
    }

    private fun fakeProbe(vararg ids: Long): suspend (android.net.Network?) -> List<com.example.network.StarlinkProtocol.Client>? =
        { ids.map { com.example.network.StarlinkProtocol.Client("d$it", "192.168.1.1$it", "aa:bb:cc:00:00:00", true, it, false, null) } }

    @Test fun disappearancePausesAndReturnResumesThroughExistingChangeState() = runBlocking {
        val id = boundSession(102, "ACTIVE")
        val tracker = ClientTracker(ApplicationProvider.getApplicationContext(), repo, lists, fakeProbe(101, 102, 103))
        tracker.poll()
        assertEquals("ACTIVE", repo.dao.session(id)!!.state)
        // The device disappears from a SUCCESSFUL snapshot.
        ClientTracker(ApplicationProvider.getApplicationContext(), repo, lists, fakeProbe(101, 103)).poll()
        assertEquals("PAUSED", repo.dao.session(id)!!.state)
        // Repeated cycle with the same snapshot: no duplicate mutation, still PAUSED.
        ClientTracker(ApplicationProvider.getApplicationContext(), repo, lists, fakeProbe(101, 103)).poll()
        assertEquals("PAUSED", repo.dao.session(id)!!.state)
        // The same clientId returns (IP and MAC unchanged by the router).
        ClientTracker(ApplicationProvider.getApplicationContext(), repo, lists, fakeProbe(101, 102, 103)).poll()
        assertEquals("ACTIVE", repo.dao.session(id)!!.state)
    }

    @Test fun failedSnapshotNeverPauses() = runBlocking {
        val id = boundSession(102, "ACTIVE")
        ClientTracker(ApplicationProvider.getApplicationContext(), repo, lists, { null }).poll()
        assertEquals("ACTIVE", repo.dao.session(id)!!.state)
    }

    @Test fun changedIpOrMacDoesNotDetachTheSession() = runBlocking {
        val id = boundSession(102, "ACTIVE")
        val moved = ClientTracker(ApplicationProvider.getApplicationContext(), repo, lists,
            { listOf(com.example.network.StarlinkProtocol.Client("d102", "192.168.1.200", "11:22:33:44:55:66", true, 102, false, null)) })
        moved.poll()
        assertEquals("ACTIVE", repo.dao.session(id)!!.state)
        val row = repo.dao.session(id)!!
        assertEquals(102L, row.deviceClientId)
    }

    @Test fun homeDevicesAreExcludedFromTrackingAndSuggestions() = runBlocking {
        // HOME by IDENTITY: device 102's clientId is stored as HOME, so the session is
        // never tracked even though the device is absent from the snapshot.
        lists.setHomeIdentity(102, "تلفاز", "aa:bb:cc:dd:ee:ff", "192.168.1.10")
        val id = boundSession(102, "ACTIVE")
        ClientTracker(ApplicationProvider.getApplicationContext(), repo, lists, fakeProbe(101)).poll()
        assertEquals("ACTIVE", repo.dao.session(id)!!.state)
        // A HOME identity device (103) is not offered to the binding flow.
        lists.setHomeIdentity(103, "جهاز", "aa:bb:cc:00:00:03", "192.168.1.11")
        val tracker = ClientTracker(ApplicationProvider.getApplicationContext(), repo, lists,
            { listOf(com.example.network.StarlinkProtocol.Client("d103", "192.168.1.11", "aa:bb:cc:00:00:03", true, 103, false, null)) })
        val choices = DeviceSelection.choose(tracker.snapshotBlocking().orEmpty(), emptySet())
        assertTrue(choices.unbound)
    }

    @Test fun pausedSessionResumesWhenDeviceReturns() = runBlocking {
        val id = boundSession(102, "PAUSED")
        ClientTracker(ApplicationProvider.getApplicationContext(), repo, lists, fakeProbe(101, 102)).poll()
        assertEquals("ACTIVE", repo.dao.session(id)!!.state)
    }
}
