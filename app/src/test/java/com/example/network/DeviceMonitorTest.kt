package com.example.network

import com.example.data.SubscriptionRepository
import com.example.db.BusinessSettings
import com.example.db.DeviceList
import com.example.db.DeviceListDao
import com.example.db.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceMonitorTest {

    private fun client(id: Long?, ip: String, name: String = "phone-$ip") =
        StarlinkProtocol.Client(name, ip, "aa:bb:cc:dd:ee:ff", null, id, role = 1)

    private class FakeDeviceListDao(
        householdIps: Set<String> = emptySet(),
        watchlistIps: Set<String> = emptySet(),
    ) : DeviceListDao {
        private val entries = mutableListOf<DeviceList>()
        init {
            householdIps.forEach { entries.add(DeviceList(ip = it, listType = "HOUSEHOLD", addedAt = 0)) }
            watchlistIps.forEach { entries.add(DeviceList(ip = it, listType = "WATCHLIST", addedAt = 0)) }
        }
        override suspend fun byType(type: String): List<DeviceList> = entries.filter { it.listType == type }
        override fun observeByType(type: String): Flow<List<DeviceList>> = flowOf(entries.filter { it.listType == type })
        override suspend fun add(entry: DeviceList): Long { entries.add(entry); return entries.size.toLong() }
        override suspend fun remove(ip: String, type: String) { entries.removeAll { it.ip == ip && it.listType == type } }
        override suspend fun contains(ip: String, type: String): Boolean = entries.any { it.ip == ip && it.listType == type }
    }

    private class FakeSessionControl(
        private val sessions: MutableList<Session>,
        private val now: Long = 0L,
    ) : SessionControl {
        private val stateChanges = mutableListOf<Pair<String, String>>()
        val changes: List<Pair<String, String>> get() = stateChanges

        override suspend fun reconcile(now: Long): List<Session> = sessions

        override suspend fun changeState(id: String, action: String) {
            stateChanges.add(id to action)
            val idx = sessions.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val s = sessions[idx]
                sessions[idx] = when (action) {
                    "PAUSE" -> s.copy(state = "PAUSED", served = s.served + 1000)
                    "RESUME" -> s.copy(state = "ACTIVE", resumed = now)
                    else -> s
                }
            }
        }
    }

    private fun makeSession(
        id: String = "session-1",
        deviceClientId: String? = null,
        state: String = "ACTIVE",
        home: Boolean = false,
    ) = Session(
        id = id, client = "مشترك 1", plan = "ساعة", started = 0, resumed = 0,
        duration = 3600000, served = 0, state = state, amount = 50000,
        cashEquivalent = 50000, payment = "CASH", premiumBps = 2500,
        home = home, grace = 1800000, source = "manual", reference = "1",
        deviceClientId = deviceClientId,
    )

    @Test fun `classification assigns household watchlist and unknown correctly`() {
        val clients = listOf(
            client(1L, "192.168.1.10"),
            client(2L, "192.168.1.11"),
            client(3L, "192.168.1.12"),
        )
        val result = DeviceClassifier.classify(
            clients,
            householdIps = setOf("192.168.1.10"),
            watchlistIps = setOf("192.168.1.11"),
        )
        assertEquals(DeviceCategory.HOUSEHOLD, result[0].category)
        assertEquals(DeviceCategory.WATCHLIST, result[1].category)
        assertEquals(DeviceCategory.UNKNOWN, result[2].category)
    }

    @Test fun `household devices are excluded from connected ids`() {
        val clients = listOf(
            client(1L, "192.168.1.10"), // household
            client(2L, "192.168.1.11"), // unknown
        )
        val dao = FakeDeviceListDao(householdIps = setOf("192.168.1.10"))
        val sessions = mutableListOf<Session>(makeSession(deviceClientId = "1"))
        val repo = FakeSessionControl(sessions)
        val monitor = DeviceMonitor(repo, dao, { 0L })

        runBlocking { monitor.scan(clients) }

        // Session linked to household device (clientId 1) should NOT be paused even though
        // the device appears — household devices are excluded from monitoring.
        assertEquals(0, repo.changes.size)
    }

    @Test fun `active session with absent device is paused`() {
        val clients = listOf(client(2L, "192.168.1.11")) // only device 2 is connected
        val dao = FakeDeviceListDao()
        val sessions = mutableListOf(makeSession(id = "s1", deviceClientId = "1", state = "ACTIVE"))
        val repo = FakeSessionControl(sessions)
        val monitor = DeviceMonitor(repo, dao, { 0L })

        runBlocking { monitor.scan(clients) }

        assertEquals(1, repo.changes.size)
        assertEquals("PAUSE", repo.changes[0].second)
    }

    @Test fun `paused session with returned device is resumed`() {
        val clients = listOf(client(1L, "192.168.1.10")) // device 1 is back
        val dao = FakeDeviceListDao()
        val sessions = mutableListOf(makeSession(id = "s1", deviceClientId = "1", state = "PAUSED"))
        val repo = FakeSessionControl(sessions)
        val monitor = DeviceMonitor(repo, dao, { 0L })

        runBlocking { monitor.scan(clients) }

        assertEquals(1, repo.changes.size)
        assertEquals("RESUME", repo.changes[0].second)
    }

    @Test fun `active session with present device is not modified`() {
        val clients = listOf(client(1L, "192.168.1.10")) // device 1 is present
        val dao = FakeDeviceListDao()
        val sessions = mutableListOf(makeSession(id = "s1", deviceClientId = "1", state = "ACTIVE"))
        val repo = FakeSessionControl(sessions)
        val monitor = DeviceMonitor(repo, dao, { 0L })

        runBlocking { monitor.scan(clients) }

        assertEquals(0, repo.changes.size)
    }

    @Test fun `paused session with absent device stays paused`() {
        val clients = listOf(client(2L, "192.168.1.11")) // device 1 is NOT present
        val dao = FakeDeviceListDao()
        val sessions = mutableListOf(makeSession(id = "s1", deviceClientId = "1", state = "PAUSED"))
        val repo = FakeSessionControl(sessions)
        val monitor = DeviceMonitor(repo, dao, { 0L })

        runBlocking { monitor.scan(clients) }

        assertEquals(0, repo.changes.size)
    }

    @Test fun `home sessions are never touched by monitoring`() {
        val clients = emptyList<StarlinkProtocol.Client>() // nobody connected
        val dao = FakeDeviceListDao()
        val sessions = mutableListOf(makeSession(id = "s1", deviceClientId = "1", state = "ACTIVE", home = true))
        val repo = FakeSessionControl(sessions)
        val monitor = DeviceMonitor(repo, dao, { 0L })

        runBlocking { monitor.scan(clients) }

        assertEquals(0, repo.changes.size)
    }

    @Test fun `sessions without deviceClientId are never touched`() {
        val clients = emptyList<StarlinkProtocol.Client>()
        val dao = FakeDeviceListDao()
        val sessions = mutableListOf(makeSession(id = "s1", deviceClientId = null, state = "ACTIVE"))
        val repo = FakeSessionControl(sessions)
        val monitor = DeviceMonitor(repo, dao, { 0L })

        runBlocking { monitor.scan(clients) }

        assertEquals(0, repo.changes.size)
    }

    @Test fun `multiple sessions with different devices are handled independently`() {
        val clients = listOf(client(1L, "192.168.1.10")) // only device 1 is connected
        val dao = FakeDeviceListDao()
        val sessions = mutableListOf(
            makeSession(id = "s1", deviceClientId = "1", state = "ACTIVE"), // present, active → no change
            makeSession(id = "s2", deviceClientId = "2", state = "ACTIVE"), // absent, active → PAUSE
            makeSession(id = "s3", deviceClientId = "1", state = "PAUSED"), // present, paused → RESUME
            makeSession(id = "s4", deviceClientId = "3", state = "PAUSED"), // absent, paused → no change
        )
        val repo = FakeSessionControl(sessions)
        val monitor = DeviceMonitor(repo, dao, { 0L })

        val result = runBlocking { monitor.scan(clients) }

        assertEquals(1, repo.changes.filter { it.second == "PAUSE" }.size)
        assertEquals(1, repo.changes.filter { it.second == "RESUME" }.size)
        assertTrue(repo.changes.any { it.first == "s2" && it.second == "PAUSE" })
        assertTrue(repo.changes.any { it.first == "s3" && it.second == "RESUME" })
        assertEquals(4, result.totalSeen)
        assertEquals(0, result.household)
    }

    @Test fun `scan result summary contains key metrics`() {
        val clients = listOf(
            client(1L, "192.168.1.10"),
            client(2L, "192.168.1.11"),
            client(null, "192.168.1.12"), // no clientId
        )
        val dao = FakeDeviceListDao(householdIps = setOf("192.168.1.10"), watchlistIps = setOf("192.168.1.11"))
        val sessions = mutableListOf<Session>()
        val repo = FakeSessionControl(sessions)
        val monitor = DeviceMonitor(repo, dao, { 0L })

        val result = runBlocking { monitor.scan(clients) }

        assertEquals(3, result.totalSeen)
        assertEquals(1, result.household)
        assertEquals(1, result.watchlist)
        assertEquals(1, result.unknown)
        assertEquals(2, result.withClientId)
        assertTrue(result.summary.contains("أجهزة: 3"))
    }
}
