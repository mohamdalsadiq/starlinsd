package com.example.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.example.network.StarlinkProtocol.Client
import com.example.network.StarlinkProtocol.RpcFailure
import com.example.network.StarlinkProtocol.field
import com.example.network.StarlinkProtocol.numberField
import com.example.network.StarlinkProtocol.fields
import com.example.network.StarlinkProtocol.singleBytes
import com.example.network.StarlinkProtocol.singleNumber

class CloudRouterControlTest {
    private val device = Client("Phone", "192.168.1.103", "ab:cd:ef:XX:XX:XX", null, 42, role = 1)
    private fun str(n: Int, s: String) = field(n, s.toByteArray())
    private class Journal : PauseJournal {
        var value: PendingPause? = null
        override fun read() = value
        override fun write(pending: PendingPause) { value = pending }
        override fun clear() { value = null }
    }
    private inner class Link(val journal: Journal) : RouterControlLink {
        override val cloud = true
        override val localIps = setOf("192.168.1.20")
        var entries = listOf(numberField(1, 42) + str(2, device.mac) + str(3, "Phone"),
            numberField(1, 99) + field(91, byteArrayOf(4, 5)) + field(5, str(2, "family-schedule")))
        var writes = 0
        var denied = false
        var ignore = false
        var lost = false
        override suspend fun exchange(payload: ByteArray): ByteArray {
            val request = fields(payload).single()
            return when (request.number) {
                1004 -> field(3004, field(3, str(1, "Router-fixture")))
                3009 -> field(3009, field(1, numberField(43, 7) + entries.fold(byteArrayOf()) { a, b -> a + field(74, b) } + str(1, "WIFI_SECRET_NEVER_SEND")))
                3002 -> field(3002, field(1, str(1, device.name) + str(2, device.mac) + str(3, device.ip) + numberField(43, 42) + numberField(14, 1) + numberField(42, if (RouterControlProtocol.hasMarker(entries[0], "_permanent")) 1 else 0)))
                3001 -> {
                    assertNotNull(journal.value); assertTrue(journal.value!!.cloud); writes++
                    if (denied) throw RpcFailure(7, "gRPC")
                    val config = fields(fields(request.bytes!!).singleBytes(1)!!)
                    assertEquals(1L, config.singleNumber(1089))
                    assertTrue(config.all { it.number in setOf(74, 1089) })
                    assertFalse(payload.toString(Charsets.UTF_8).contains("WIFI_SECRET_NEVER_SEND"))
                    if (!ignore) entries = config.filter { it.number == 74 }.map { it.bytes!! }
                    if (lost) error("cloud_network_failed")
                    byteArrayOf()
                }
                else -> error("unexpected_request")
            }
        }
    }
    @Test fun `cloud pause and restore preserve every other client unknown field and schedule`() = runBlocking {
        val journal = Journal(); val link = Link(journal); val before = link.entries.map { it.copyOf() }
        val controller = RouterControl({ link }, journal, { 0 }, {})
        assertTrue(controller.apply(controller.prepare(device)).verified)
        assertTrue(RouterControlProtocol.permanentIsFullWeek(link.entries[0]))
        assertArrayEquals(before[1], link.entries[1])
        assertTrue(controller.apply(controller.prepareRestore()).verified)
        assertTrue(RouterControlProtocol.sameEntries(before, link.entries))
        assertEquals(2, link.writes); assertNull(journal.value)
    }
    @Test fun `another clients concurrent change invalidates confirmation even with same revision`() = runBlocking {
        val journal = Journal(); val link = Link(journal); val control = RouterControl({ link }, journal, { 0 }, {})
        val preview = control.prepare(device)
        link.entries = listOf(link.entries[0], link.entries[1] + str(92, "new"))
        assertTrue(control.apply(preview).diagnostic.contains("config_changed")); assertEquals(0, link.writes)
    }
    @Test fun `denial has no write retry and restore with absent schedule sends nothing`() = runBlocking {
        val journal = Journal(); val link = Link(journal); link.denied = true
        val control = RouterControl({ link }, journal, { 0 }, {})
        assertFalse(control.apply(control.prepare(device)).verified)
        assertEquals(1, link.writes)
        assertTrue(control.apply(control.prepareRestore()).diagnostic.contains("owned_schedule_absent"))
        assertEquals(1, link.writes); assertNull(journal.value)
    }
    @Test fun `ignored cloud write is never success`() = runBlocking {
        val journal = Journal(); val link = Link(journal); link.ignore = true
        val control = RouterControl({ link }, journal, { 0 }, {})
        assertFalse(control.apply(control.prepare(device)).verified); assertEquals(1, link.writes)
    }
    @Test fun `lost cloud reply retains recovery and restores from fresh collection`() = runBlocking {
        val journal = Journal(); val link = Link(journal); link.lost = true
        val control = RouterControl({ link }, journal, { 0 }, {})
        assertFalse(control.apply(control.prepare(device)).verified); assertNotNull(journal.value)
        link.lost = false
        val changedOther = link.entries[1] + str(93, "new setting")
        link.entries = listOf(link.entries[0], changedOther)
        val recreated = RouterControl({ link }, journal, { 0 }, {})
        assertTrue(recreated.apply(recreated.prepareRestore()).verified)
        assertArrayEquals(changedOther, link.entries[1]); assertNull(journal.value)
    }
    @Test fun `changed permanent schedule cannot be removed by recovery`() = runBlocking {
        val journal = Journal(); val link = Link(journal); val control = RouterControl({ link }, journal, { 0 }, {})
        control.apply(control.prepare(device))
        val changed = RouterControlProtocol.withoutMarker(link.entries[0], "_permanent") + field(5, str(2, "_permanent") + field(1, numberField(2, 60)))
        link.entries = listOf(changed, link.entries[1])
        assertTrue(control.apply(control.prepareRestore()).diagnostic.contains("recovery_schedule_changed"))
        assertEquals(1, link.writes); assertNotNull(journal.value)
    }
    @Test fun `invalid collection identity prevents replacing list`() {
        val config = RouterControlProtocol.Config(1, listOf(numberField(1, 42), numberField(1, 42)), "")
        assertThrows(IllegalArgumentException::class.java) { RouterControlProtocol.cloudEntries(config, device, numberField(1, 42)) }
    }
}
