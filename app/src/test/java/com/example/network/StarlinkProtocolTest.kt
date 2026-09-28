package com.example.network

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class StarlinkProtocolTest {
    private fun field(n: Int, b: ByteArray) = StarlinkProtocol.field(n, b)
    private fun string(n: Int, value: String) = field(n, value.toByteArray())
    private fun client(name: String = "هاتف محمد") = string(1, name) + string(2, "02:11:22:33:44:55") + string(3, "192.168.1.25")
    private fun clients(b: ByteArray) = field(3002, b)
    private fun frame(b: ByteArray) = StarlinkProtocol.frame(b)
    private fun rejected(block: () -> Unit) { try { block(); fail("Malformed input accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun `only two read requests have fixed known wire bytes`() {
        assertArrayEquals(byteArrayOf(0xd2.toByte(), 0xbb.toByte(), 1, 0), StarlinkProtocol.request(StarlinkProtocol.Query.CLIENTS))
        assertArrayEquals(byteArrayOf(0xe2.toByte(), 0x3e, 0), StarlinkProtocol.request(StarlinkProtocol.Query.STATUS))
        assertEquals(2, StarlinkProtocol.Query.entries.size)
    }
    @Test fun `router clients preserve names and addresses without assuming online`() {
        val result = StarlinkProtocol.decode(clients(field(1, client())))
        assertEquals("CLIENTS", result.kind)
        assertEquals("هاتف محمد", result.clients!!.single().name)
        assertEquals("192.168.1.25", result.clients.single().ip)
        assertNull(result.clients.single().active)
    }
    @Test fun `new router names and explicit active field are respected`() {
        val result = StarlinkProtocol.decode(clients(field(1, client() + string(31, "سامسونج") + byteArrayOf(0xd0.toByte(), 3, 1))))
        assertEquals("سامسونج", result.clients!!.single().name)
        assertEquals(true, result.clients.single().active)
    }
    @Test fun `empty explicit clients result differs from absent list in status`() {
        assertEquals(emptyList<StarlinkProtocol.Client>(), StarlinkProtocol.decode(clients(byteArrayOf())).clients)
        assertNull(StarlinkProtocol.decode(field(3004, byteArrayOf())).clients)
        assertNull(StarlinkProtocol.decode(field(2004, byteArrayOf())).clients)
    }
    @Test fun `both published router status client field layouts are parsed`() {
        for (n in listOf(2, 3000)) {
            val result = StarlinkProtocol.decode(field(3004, field(n, client())))
            assertEquals("ROUTER", result.kind)
            assertEquals(1, result.clients!!.size)
        }
    }
    @Test fun `dish status is never a router client list`() {
        val info = string(2, "rev3") + string(3, "firmware")
        val result = StarlinkProtocol.decode(field(2004, field(1, info)))
        assertEquals("DISH", result.kind)
        assertEquals("rev3", result.hardware)
        assertNull(result.clients)
    }
    @Test fun `application permission error is not zero devices`() {
        try {
            StarlinkProtocol.decode(field(2, byteArrayOf(8, 7)) + clients(byteArrayOf()))
            fail("Denied request accepted")
        } catch (e: StarlinkProtocol.RpcFailure) { assertEquals(7, e.code); assertEquals("Starlink", e.layer) }
    }
    @Test fun `grpc requires successful final status even with valid message`() {
        val b = frame(clients(byteArrayOf()))
        assertArrayEquals(clients(byteArrayOf()), StarlinkProtocol.unwrap(b, null, "0", false))
        rejected { StarlinkProtocol.unwrap(b, null, null, false) }
        try { StarlinkProtocol.unwrap(b, null, "16", false); fail("Denied") }
        catch (e: StarlinkProtocol.RpcFailure) { assertEquals(16, e.code) }
    }
    @Test fun `grpc web trailers are consumed and their error overrides payload`() {
        fun trailer(status: Int): ByteArray {
            val text = "grpc-status: $status\r\n".toByteArray()
            return ByteBuffer.allocate(5 + text.size).put(128.toByte()).putInt(text.size).put(text).array()
        }
        val b = clients(field(1, client()))
        assertArrayEquals(b, StarlinkProtocol.unwrap(frame(b) + trailer(0), null, null, true))
        try { StarlinkProtocol.unwrap(frame(b) + trailer(7), null, null, true); fail("Denied") }
        catch (e: StarlinkProtocol.RpcFailure) { assertEquals(7, e.code) }
        rejected { StarlinkProtocol.unwrap(trailer(0) + frame(b), null, null, true) }
        rejected { StarlinkProtocol.unwrap(frame(b) + trailer(0), null, null, false) }
    }
    @Test fun `truncated oversized compressed and duplicate frames fail closed`() {
        rejected { StarlinkProtocol.unwrap(byteArrayOf(0, 0), null, "0", false) }
        rejected { StarlinkProtocol.unwrap(byteArrayOf(0, 127, -1, -1, -1), null, "0", false) }
        rejected { StarlinkProtocol.unwrap(byteArrayOf(1, 0, 0, 0, 0), null, "0", false) }
        rejected { StarlinkProtocol.unwrap(frame(byteArrayOf()) + frame(byteArrayOf()), null, "0", false) }
        rejected { StarlinkProtocol.unwrap(ByteArray(StarlinkProtocol.MAX_BYTES + 1), null, "0", false) }
    }
    @Test fun `malformed protobuf cannot manufacture an empty client list`() {
        rejected { StarlinkProtocol.decode(byteArrayOf(0)) }
        rejected { StarlinkProtocol.decode(byteArrayOf(0xe2.toByte(), 0xbb.toByte(), 1, 100, 0)) }
        rejected { StarlinkProtocol.decode(clients(byteArrayOf()) + clients(byteArrayOf())) }
        rejected { StarlinkProtocol.decode(clients(byteArrayOf(8, 1))) }
        rejected { StarlinkProtocol.decode(clients(field(1, string(1, "one") + string(1, "two")))) }
        rejected { StarlinkProtocol.decode(clients(field(1, byteArrayOf(0xff.toByte(), 0xff.toByte())))) }
    }
    @Test fun `unknown fields are skipped and display controls are stripped`() {
        val result = StarlinkProtocol.decode(clients(field(1, client("phone\n\u202eevil") + field(99, byteArrayOf(1, 2)))))
        assertEquals("phoneevil", result.clients!!.single().name)
    }
}
