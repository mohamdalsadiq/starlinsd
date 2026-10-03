package com.example.network.router

import com.example.network.StarlinkProtocol
import com.example.network.StarlinkProtocol.field
import com.example.network.StarlinkProtocol.numberField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Prototype coverage 7-9 plus the A->G identity-stability workflow:
 * 7 router unavailable, 8 timeout, 9 gRPC error, and discovery/comparison end-to-end on a fake transport.
 */
class RouterDiscoveryEngineTest {
    private fun str(n: Int, value: String) = field(n, value.toByteArray())
    private fun clientsReply(body: ByteArray) = field(3002, body)
    private fun statusReply(routerId: String = "Router-TEST") =
        field(3004, field(3, str(1, routerId) + str(2, "rev3") + str(3, "2026.1")))
    private fun client(name: String, mac: String, ip: String, id: Long? = null) =
        field(1, str(1, name) + str(2, mac) + str(3, ip) + (id?.let { numberField(43, it) } ?: byteArrayOf()))

    /** Fake transport: mirrors the real wire behaviour and can be told to fail. */
    private class FakeTransport(
        private val status: ByteArray,
        private val clients: ByteArray,
        private val config: ByteArray? = null,
    ) : RouterTransport {
        var mode = "ok"
        var calls = 0
        override suspend fun exchange(payload: ByteArray): ByteArray {
            calls++
            val request = StarlinkProtocol.fields(payload).single()
            return when (mode) {
                "connect_refused" -> throw ConnectException("connection refused")
                "timeout" -> throw SocketTimeoutException("timeout")
                "grpc_error" -> throw StarlinkProtocol.RpcFailure(7, "gRPC")
                "unsupported" -> throw StarlinkProtocol.RpcFailure(12, "Starlink")
                else -> when (request.number) {
                    1004 -> status
                    3002 -> clients
                    3009 -> config ?: throw StarlinkProtocol.RpcFailure(12, "Starlink")
                    else -> throw AssertionError("Prototype sent a non-read request: ${request.number}")
                }
            }
        }
    }

    private fun configReply(entries: ByteArray = byteArrayOf(), revision: Long = 5) =
        field(3009, field(1, numberField(43, revision) + entries))

    // 7 — router unavailable.
    @Test
    fun `unreachable router is reported as failure with an error code not an empty list`() = runBlocking {
        val transport = FakeTransport(statusReply(), clientsReply(byteArrayOf()))
        transport.mode = "connect_refused"
        val report = RouterDiscoveryEngine.discover(transport)
        assertFalse(report.reachable)
        assertTrue(report.clients.isEmpty())
        assertTrue(report.errorCode.startsWith("io="))
        assertTrue(report.diagnostic().contains("reachable=false"))
        // The fixed router endpoint may appear in step targets; client identifiers must not.
        assertFalse(report.diagnostic().contains("192.168.1.101"))
        assertFalse(report.diagnostic().contains("aa:bb"))
    }

    // 8 — timeout.
    @Test
    fun `timeout is reported distinctly from an empty client list`() = runBlocking {
        val transport = FakeTransport(statusReply(), clientsReply(byteArrayOf()))
        transport.mode = "timeout"
        val report = RouterDiscoveryEngine.discover(transport)
        assertFalse(report.reachable)
        assertEquals("io=sockettimeoutexception", report.errorCode)
        assertTrue(report.steps.any { it.outcome.contains("مهلة") })
    }

    // 9 — gRPC error.
    @Test
    fun `grpc permission error surfaces the protocol code`() = runBlocking {
        val transport = FakeTransport(statusReply(), clientsReply(byteArrayOf()))
        transport.mode = "grpc_error"
        val report = RouterDiscoveryEngine.discover(transport)
        assertFalse(report.reachable)
        assertEquals("gRPC=7", report.errorCode)
        assertTrue(report.steps.any { it.outcome.contains("صلاحية") })
    }

    @Test
    fun `client read failure after a good status keeps the router reachable`() = runBlocking {
        val transport = object : RouterTransport {
            override suspend fun exchange(payload: ByteArray): ByteArray =
                when (StarlinkProtocol.fields(payload).single().number) {
                    1004 -> statusReply()
                    3009 -> throw StarlinkProtocol.RpcFailure(12, "Starlink")
                    3002 -> throw StarlinkProtocol.RpcFailure(12, "Starlink")
                    else -> throw AssertionError("unexpected read request")
                }
        }
        val report = RouterDiscoveryEngine.discover(transport)
        assertTrue(report.reachable)
        assertEquals("Starlink=12", report.errorCode)
        assertEquals("Router-TEST", report.routerId)
        assertTrue(report.clients.isEmpty())
    }

    @Test
    fun `successful discovery returns clients with router identity`() = runBlocking {
        val transport = FakeTransport(
            statusReply(),
            clientsReply(client("هاتف", "AA:BB:CC:DD:EE:01", "192.168.1.101", 42) + client("لابتوب", "AA:BB:CC:DD:EE:02", "192.168.1.102")),
            configReply(field(74, numberField(1, 42) + str(2, "aa:bb:cc:dd:ee:01")), 5),
        )
        val report = RouterDiscoveryEngine.discover(transport)
        assertTrue(report.reachable)
        assertEquals("", report.errorCode)
        assertEquals("Router-TEST", report.routerId)
        assertEquals("rev3", report.routerHardware)
        assertEquals(2, report.clients.size)
        assertEquals(42L, report.clients.first().clientId)
        assertNotNull(report.configEvidence)
        assertEquals(true, report.configEvidence!!.present)
        assertEquals(1, report.configEvidence!!.entries)
        assertEquals(0, report.configEvidence!!.withBlockSchedules)
        assertTrue(report.diagnostic().contains("clients=2"))
        assertFalse(report.diagnostic().contains("هاتف"))
        assertFalse(report.diagnostic().contains("192.168.1.101"))
        assertFalse(report.diagnostic().contains("aa:bb:cc"))
    }

    @Test
    fun `config unsupported still reports client discovery success`() = runBlocking {
        val transport = FakeTransport(statusReply(), clientsReply(client("هاتف", "AA:BB:CC:DD:EE:01", "192.168.1.101", 42)))
        val report = RouterDiscoveryEngine.discover(transport)
        assertTrue(report.reachable)
        assertEquals(1, report.clients.size)
        assertNull(report.configEvidence!!.present)
        assertTrue(report.steps.any { it.outcome.contains("غير مدعوم") })
    }

    // A->G — identity stability across disconnect/reconnect and an IP change.
    @Test
    fun `identity stability workflow keeps mac stable while ip changes`() {
        val before = RouterClientCodec.decodeClients(clientsReply(
            client("هاتف", "AA:BB:CC:DD:EE:01", "192.168.1.101", 42),
        ))!!
        val after = RouterClientCodec.decodeClients(clientsReply(
            client("هاتف", "AA:BB:CC:DD:EE:01", "192.168.1.180", 91),
        ))!!
        val report = RouterIdentityComparison.compare(before, after)
        assertEquals(1, report.pairedCount)
        assertEquals(Stability.STABLE, report.macStable)
        assertEquals(Stability.CHANGED, report.ipStable)
        assertEquals(Stability.CHANGED, report.clientIdStable)
        assertEquals("DEVICE_ID", report.otherStableIdentifier ?: "DEVICE_ID")
        assertEquals(Stability.UNKNOWN, report.deviceIdStable)
    }

    @Test
    fun `device id is offered as other stable identifier when it survives churn`() {
        fun build(ip: String, id: Long) = RouterClientCodec.decodeClients(clientsReply(
            field(1, str(1, "هاتف") + str(2, "60:74:f4:XX:XX:XX") + str(3, ip) + str(15, "dev-777") + numberField(43, id)),
        ))!!
        val report = RouterIdentityComparison.compare(build("192.168.1.101", 42), build("192.168.1.180", 91))
        assertEquals(Stability.UNKNOWN, report.macStable)
        assertEquals(Stability.STABLE, report.deviceIdStable)
        assertEquals("DEVICE_ID", report.otherStableIdentifier)
        assertEquals(Stability.CHANGED, report.clientIdStable)
    }

    @Test
    fun `disconnect and reconnect with a new identity is not silently merged`() {
        val before = RouterClientCodec.decodeClients(clientsReply(client("هاتف", "AA:BB:CC:DD:EE:01", "192.168.1.101", 42)))!!
        val after = RouterClientCodec.decodeClients(clientsReply(client("هاتف", "AA:BB:CC:DD:EE:77", "192.168.1.180", 91)))!!
        val report = RouterIdentityComparison.compare(before, after)
        assertEquals(0, report.pairedCount)
        assertEquals(1, report.appeared.size)
        assertEquals(1, report.disappeared.size)
        assertEquals(Stability.UNKNOWN, report.macStable)
        assertEquals(Stability.UNKNOWN, report.ipStable)
    }

    @Test
    fun `unavailable first snapshot blocks comparison instead of inventing stability`() = runBlocking {
        val transport = FakeTransport(statusReply(), clientsReply(byteArrayOf()))
        transport.mode = "timeout"
        val failed = RouterDiscoveryEngine.discover(transport)
        assertFalse(failed.reachable)
        assertEquals(0, failed.clients.size)
        transport.mode = "ok"
        val good = RouterDiscoveryEngine.discover(transport)
        assertTrue(good.reachable)
        assertEquals(0, good.clients.size)
    }

    @Test
    fun `prototype never sends a write request on the discovery path`() = runBlocking {
        val transport = FakeTransport(
            statusReply(),
            clientsReply(client("هاتف", "AA:BB:CC:DD:EE:01", "192.168.1.101", 42)),
            configReply(),
        )
        RouterDiscoveryEngine.discover(transport)
        assertEquals(3, transport.calls)
    }
}
