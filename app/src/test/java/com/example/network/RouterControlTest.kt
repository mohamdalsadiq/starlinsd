package com.example.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.example.network.StarlinkProtocol.field
import com.example.network.StarlinkProtocol.numberField
import com.example.network.StarlinkProtocol.fields
import com.example.network.StarlinkProtocol.singleBytes
import com.example.network.StarlinkProtocol.singleNumber

class RouterControlTest {
    private val device = StarlinkProtocol.Client("Test phone", "192.168.1.103", "8a:bb:a2:XX:XX:XX", null, 977656928, role = 1)
    private fun str(n: Int, s: String) = field(n, s.toByteArray())
    private class Journal : PauseJournal {
        var pending: PendingPause? = null
        var failWrite = false
        override fun read() = pending
        override fun write(pending: PendingPause) { check(!failWrite) { "disk_full" }; this.pending = pending }
        override fun clear() { pending = null }
    }
    private inner class Link(private val journal: Journal) : RouterControlLink {
        override var localIps = setOf("192.168.1.20")
        var entry = numberField(1, device.id!!) + str(2, device.mac) + str(3, device.name) + str(6, "original-group") + field(90, byteArrayOf(9, 8, 7))
        var blocked = false
        var revision = 20L
        var router = "router-test"
        var online = true
        var acceptWrites = true
        var deny = false
        var failAfterWrite = false
        var includeBlocked = true
        var writes = 0
        var reads = 0
        var duplicate = false
        override suspend fun exchange(payload: ByteArray): ByteArray {
            if (failAfterWrite && writes > 0) error("lost_reply")
            val request = fields(payload).single()
            if (request.number != 3017) reads++
            return when (request.number) {
                1004 -> field(3004, field(3, str(1, router)))
                3009 -> field(3009, field(1, numberField(43, revision) + field(74, entry) + str(999, "NEVER_COPY_NETWORK_SECRET")))
                3002 -> {
                    val c = str(1, device.name) + str(2, device.mac) + str(3, device.ip) + numberField(43, device.id!!) + numberField(14, 1) +
                        (if (includeBlocked) numberField(42, if (blocked) 1 else 0) else byteArrayOf())
                    field(3002, if (online) field(1, c) + (if (duplicate) field(1, c) else byteArrayOf()) else byteArrayOf())
                }
                3017 -> {
                    assertNotNull("recovery must be durable before the network write", journal.pending)
                    writes++
                    assertFalse(payload.toString(Charsets.UTF_8).contains("NEVER_COPY_NETWORK_SECRET"))
                    if (deny) return field(2, numberField(1, 7))
                    val body = fields(request.bytes!!)
                    assertEquals(listOf(2), body.map { it.number })
                    if (acceptWrites) {
                        entry = body.singleBytes(2)!!
                        blocked = RouterControlProtocol.hasMarker(entry, journal.pending!!.marker)
                        revision++
                    }
                    byteArrayOf()
                }
                else -> error("Unexpected RPC ${request.number}")
            }
        }
    }
    private fun rejected(block: suspend () -> Unit) = runBlocking {
        try { block(); fail("Unsafe operation accepted") } catch (_: IllegalStateException) {} catch (_: IllegalArgumentException) {} catch (_: StarlinkProtocol.RpcFailure) {}
    }
    @Test fun `pause and restore target client by unsigned id and preserve unknown data`() = runBlocking {
        val journal = Journal(); val link = Link(journal); val control = RouterControl({ link }, journal, { 0 }, {})
        val original = link.entry.copyOf()
        val preview = control.prepare(device)
        assertEquals(0, link.writes)
        assertTrue(control.apply(preview).verified)
        assertTrue(link.blocked)
        assertArrayEquals(original, RouterControlProtocol.withoutMarker(link.entry, journal.pending!!.marker))
        val result = control.apply(control.prepareRestore())
        assertTrue(result.verified)
        assertFalse(link.blocked)
        assertArrayEquals(original, link.entry)
        assertNull(journal.pending)
        assertEquals(2, link.writes)
    }
    @Test fun `management phone and infrastructure never reach mutation`() {
        val journal = Journal(); val link = Link(journal); val control = RouterControl({ link }, journal, { 0 }, {})
        rejected { control.prepare(device.copy(ip = "192.168.1.20")) }
        rejected { control.prepare(device.copy(role = 3)) }
        rejected { control.prepare(device.copy(id = null)) }
        assertEquals(0, link.reads); assertEquals(0, link.writes)
    }
    @Test fun `duplicate client ids prevent confirmation`() {
        val journal = Journal(); val link = Link(journal); link.duplicate = true
        rejected { RouterControl({ link }, journal, { 0 }, {}).prepare(device) }
        assertEquals(0, link.writes)
    }
    @Test fun `permission denial is reported and never retried`() = runBlocking {
        val journal = Journal(); val link = Link(journal); val control = RouterControl({ link }, journal, { 0 }, {})
        val preview = control.prepare(device); link.deny = true
        val result = control.apply(preview)
        assertFalse(result.verified); assertTrue(result.diagnostic.contains("Starlink=7")); assertEquals(1, link.writes)
        assertNotNull(journal.pending)
    }
    @Test fun `accepted but ignored mutation never reports success`() = runBlocking {
        val journal = Journal(); val link = Link(journal); link.acceptWrites = false
        val control = RouterControl({ link }, journal, { 0 }, {})
        val result = control.apply(control.prepare(device))
        assertFalse(result.verified); assertTrue(result.diagnostic.contains("config_confirmed=false")); assertEquals(1, link.writes)
    }
    @Test fun `missing bool is unknown despite confirmed config`() = runBlocking {
        val journal = Journal(); val link = Link(journal); link.includeBlocked = false
        val control = RouterControl({ link }, journal, { 0 }, {})
        val result = control.apply(control.prepare(device))
        assertFalse(result.verified); assertTrue(result.diagnostic.contains("config_confirmed=true"))
        assertTrue(result.diagnostic.contains("blocked_field=absent"))
    }
    @Test fun `recovery survives controller recreation and a lost response`() = runBlocking {
        val journal = Journal(); val link = Link(journal); val control = RouterControl({ link }, journal, { 0 }, {})
        val preview = control.prepare(device); link.failAfterWrite = true
        assertFalse(control.apply(preview).verified)
        assertNotNull(journal.pending); assertTrue(link.blocked)
        link.failAfterWrite = false; link.online = false
        val recreated = RouterControl({ link }, journal, { 0 }, {})
        val result = recreated.apply(recreated.prepareRestore())
        assertFalse(result.verified) // No live client to claim Internet connectivity.
        assertFalse(link.blocked); assertNull(journal.pending)
    }
    @Test fun `journal failure prevents dispatch`() = runBlocking {
        val journal = Journal(); journal.failWrite = true; val link = Link(journal)
        val control = RouterControl({ link }, journal, { 0 }, {})
        assertFalse(control.apply(control.prepare(device)).verified)
        assertEquals(0, link.writes)
    }
    @Test fun `changed config prevents stale overwrite`() = runBlocking {
        val journal = Journal(); val link = Link(journal); val control = RouterControl({ link }, journal, { 0 }, {})
        val preview = control.prepare(device); link.revision++
        assertTrue(control.apply(preview).diagnostic.contains("config_changed")); assertEquals(0, link.writes)
    }
    @Test fun `different router prevents restore and preserves recovery`() = runBlocking {
        val journal = Journal(); val link = Link(journal); val control = RouterControl({ link }, journal, { 0 }, {})
        control.apply(control.prepare(device)); link.router = "other-router"
        rejected { control.prepareRestore() }
        assertNotNull(journal.pending); assertEquals(1, link.writes)
    }
    @Test fun `expired and reused confirmations cannot dispatch`() = runBlocking {
        var now = 0L; val journal = Journal(); val link = Link(journal)
        val control = RouterControl({ link }, journal, { now }, {})
        val expired = control.prepare(device); now = 60001
        rejected { control.apply(expired) }; assertEquals(0, link.writes)
        val preview = control.prepare(device); control.apply(preview)
        rejected { control.apply(preview) }; assertEquals(1, link.writes)
    }
    @Test fun `existing schedules prevent pause and other schedules survive restore`() = runBlocking {
        val journal = Journal(); val link = Link(journal); val control = RouterControl({ link }, journal, { 0 }, {})
        val other = field(5, str(2, "family-owned"))
        link.entry += other
        rejected { control.prepare(device) }
        link.entry = link.entry.copyOfRange(0, link.entry.size - other.size)
        control.apply(control.prepare(device))
        link.entry += other
        control.apply(control.prepareRestore())
        assertTrue(link.entry.takeLast(other.size).toByteArray().contentEquals(other))
    }
    @Test fun `client metadata uses uint32 and does not turn absent blocked into false`() {
        val c = numberField(43, 0xffffffffL) + numberField(14, 1)
        val decoded = StarlinkProtocol.decode(field(3002, field(1, c))).clients!!.single()
        assertEquals(0xffffffffL, decoded.id); assertNull(decoded.blocked); assertEquals(1L, decoded.role)
    }
    @Test fun `config response errors and identity conflicts are rejected`() {
        rejected { RouterControlProtocol.decodeConfig(field(2, numberField(1, 7)) + field(3009, byteArrayOf())) }
        rejected { RouterControlProtocol.decodeConfig(field(3002, byteArrayOf())) }
        val wrong = numberField(1, 5) + str(2, "8a:bb:a2:11:22:33")
        rejected { RouterControlProtocol.entry(RouterControlProtocol.Config(1, listOf(wrong), ""), device.copy(mac = "8a:bb:a2:11:22:33")) }
    }
}
