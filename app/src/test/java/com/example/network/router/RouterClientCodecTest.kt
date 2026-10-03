package com.example.network.router

import com.example.network.StarlinkProtocol
import com.example.network.StarlinkProtocol.field
import com.example.network.StarlinkProtocol.numberField
import org.junit.Assert.*
import org.junit.Test

/**
 * Prototype coverage 1-6 and 10:
 * 1 response parsing, 2 MAC normalization, 3 IP normalization, 4 stable-vs-current separation,
 * 5 duplicate client handling, 6 malformed/missing fields, 10 unsupported RPC handling.
 */
class RouterClientCodecTest {
    private fun str(n: Int, value: String) = field(n, value.toByteArray())
    private fun clients(body: ByteArray) = field(3002, body)
    private fun client(
        name: String = "هاتف",
        mac: String = "AA:BB:CC:DD:EE:01",
        ip: String = "192.168.1.101",
        extra: ByteArray = byteArrayOf(),
    ) = str(1, name) + str(2, mac) + str(3, ip) + extra

    private fun rejected(block: () -> Unit) {
        try {
            block(); fail("Malformed input accepted")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        } catch (_: StarlinkProtocol.RpcFailure) {
        }
    }

    // 1 — response parsing keeps every useful field, not only the ones the Slotra model uses.
    @Test
    fun `every useful field is retained not just the ones Slotra uses`() {
        val body = client(extra = str(31, "سامسونج") + str(15, "dev-777") + str(53, "cap-9") + str(13, "aa:00:11:22:33:44") +
            str(41, "fe80::1") + str(26, "wlan0") + str(37, "rev3") + str(38, "2026.1") +
            numberField(43, 42) + numberField(14, 1) + numberField(9, 2) + numberField(7, 900) + numberField(45, 3) +
            numberField(39, 5) + numberField(54, 10) + numberField(55, 20) + numberField(56, 2) + numberField(57, 2) +
            numberField(58, 1) + numberField(42, 0) + numberField(49, 1) + numberField(46, 1) + numberField(47, 0))
        val c = RouterClientCodec.decodeClients(clients(field(1, body)))!!.single()
        assertEquals("سامسونج", c.hostname)
        assertEquals("aa:bb:cc:dd:ee:01", c.mac)
        assertEquals("192.168.1.101", c.ip)
        assertEquals("dev-777", c.deviceId)
        assertEquals("cap-9", c.captiveClientId)
        assertEquals("aa:00:11:22:33:44", c.upstreamMac)
        assertEquals(listOf("fe80::1"), c.ipv6)
        assertEquals("wlan0", c.interfaceName)
        assertEquals("rev3", c.hardwareVersion)
        assertEquals("2026.1", c.softwareVersion)
        assertEquals(42L, c.clientId)
        assertEquals(1L, c.role)
        assertEquals(2L, c.interfaceType)
        assertEquals(900L, c.associatedSeconds)
        assertEquals(3L, c.idleSeconds)
        assertEquals(5L, c.apiVersion)
        assertEquals(10L, c.uploadMb)
        assertEquals(20L, c.downloadMb)
        assertEquals(2L, c.captiveState)
        assertEquals(2L, c.sandboxState)
        assertEquals(true, c.active)
        assertEquals(false, c.blocked)
        assertEquals(true, c.dhcpLeaseFound)
        assertEquals(true, c.dhcpLeaseActive)
        assertEquals(false, c.dhcpLeaseRenewed)
    }

    @Test
    fun `given name wins over name and absent fields stay absent`() {
        val c = RouterClientCodec.decodeClients(clients(field(1, client(extra = str(31, "الاسم المعطى")))))!!.single()
        assertEquals("الاسم المعطى", c.hostname)
        assertEquals("", c.deviceId)
        assertEquals("", c.upstreamMac)
        assertNull(c.clientId)
        assertNull(c.active)
        assertNull(c.blocked)
        assertNull(c.role)
    }

    @Test
    fun `router status client layout and explicit empty list behave`() {
        assertEquals(1, RouterClientCodec.decodeClients(field(3004, field(3000, client())))!!.size)
        assertEquals(1, RouterClientCodec.decodeClients(field(3004, field(2, client())))!!.size)
        assertEquals(emptyList<RouterClient>(), RouterClientCodec.decodeClients(clients(byteArrayOf())))
        assertNull(RouterClientCodec.decodeClients(field(3004, byteArrayOf())))
        assertNull(RouterClientCodec.decodeClients(field(2004, byteArrayOf())))
    }

    // 2 — MAC normalization: full MACs are kept, masked/blank/zero MACs are unknown (null).
    @Test
    fun `mac normalization accepts only full hardware addresses`() {
        assertEquals("aa:bb:cc:dd:ee:ff", RouterClientIdentity.normalizeMac("AA:BB:CC:DD:EE:FF"))
        assertEquals("aa:bb:cc:dd:ee:ff", RouterClientIdentity.normalizeMac("aa-bb-cc-dd-ee-ff"))
        assertEquals("aa:bb:cc:dd:ee:ff", RouterClientIdentity.normalizeMac("  aa:bb:cc:dd:ee:ff  "))
        assertNull(RouterClientIdentity.normalizeMac("60:74:f4:XX:XX:XX"))
        assertNull(RouterClientIdentity.normalizeMac("00:00:00:00:00:00"))
        assertNull(RouterClientIdentity.normalizeMac("ff:ff:ff:ff:ff:ff"))
        assertNull(RouterClientIdentity.normalizeMac(""))
        assertNull(RouterClientIdentity.normalizeMac("aa:bb:cc:dd:ee"))
        assertNull(RouterClientIdentity.normalizeMac("zz:bb:cc:dd:ee:ff"))
        assertNull(RouterClientIdentity.normalizeMac("aa:bb:cc:dd:ee:ff:11"))
    }

    @Test
    fun `masked mac is reported as masked and cannot become an identity`() {
        val c = RouterClientCodec.decodeClients(clients(field(1, client(mac = "60:74:f4:XX:XX:XX", extra = numberField(43, 9)))))!!.single()
        assertNull(c.mac)
        assertEquals("60:74:f4:XX:XX:XX", c.rawMac)
        assertTrue(c.macMasked)
        assertEquals(StableIdentity.ClientId("9"), RouterClientIdentity.stableIdentity(c))
    }

    // 3 — IP normalization: dotted quads only, never used as identity.
    @Test
    fun `ip normalization accepts only canonical dotted quads`() {
        assertEquals("192.168.1.10", RouterClientIdentity.normalizeIp("192.168.1.10"))
        assertEquals("192.168.1.10", RouterClientIdentity.normalizeIp(" 192.168.1.10 "))
        assertNull(RouterClientIdentity.normalizeIp("192.168.1"))
        assertNull(RouterClientIdentity.normalizeIp("192.168.1.256"))
        assertNull(RouterClientIdentity.normalizeIp("192.168.01.10"))
        assertNull(RouterClientIdentity.normalizeIp("fe80::1"))
        assertNull(RouterClientIdentity.normalizeIp(""))
        assertNull(RouterClientIdentity.normalizeIp("0.0.0.0"))
    }

    @Test
    fun `non canonical ip is kept raw and never normalized`() {
        val c = RouterClientCodec.decodeClients(clients(field(1, client(ip = "192.168.01.10"))))!!.single()
        assertNull(c.ip)
        assertEquals("192.168.01.10", c.rawIp)
    }

    // 4 — stable identity is explicitly separated from the current IP.
    @Test
    fun `stable identity prefers full mac then device id then client id`() {
        val byMac = RouterClientCodec.decodeClients(clients(field(1, client(extra = str(15, "dev-1") + numberField(43, 7)))))!!.single()
        assertEquals(StableIdentity.Mac("aa:bb:cc:dd:ee:01"), RouterClientIdentity.stableIdentity(byMac))
        val byDevice = RouterClientCodec.decodeClients(clients(field(1, client(mac = "60:74:f4:XX:XX:XX", extra = str(15, "dev-1") + numberField(43, 7)))))!!.single()
        assertEquals(StableIdentity.DeviceId("dev-1"), RouterClientIdentity.stableIdentity(byDevice))
        val byClient = RouterClientCodec.decodeClients(clients(field(1, client(mac = "", extra = numberField(43, 7)))))!!.single()
        assertEquals(StableIdentity.ClientId("7"), RouterClientIdentity.stableIdentity(byClient))
        val none = RouterClientCodec.decodeClients(clients(field(1, client(mac = ""))))!!.single()
        assertEquals(StableIdentity.None, RouterClientIdentity.stableIdentity(none))
    }

    @Test
    fun `changing ip does not change the selected stable identity`() {
        val before = RouterClientCodec.decodeClients(clients(field(1, client(ip = "192.168.1.50"))))!!.single()
        val after = RouterClientCodec.decodeClients(clients(field(1, client(ip = "192.168.1.77"))))!!.single()
        assertEquals(RouterClientIdentity.stableIdentity(before), RouterClientIdentity.stableIdentity(after))
        assertNotEquals(before.ip, after.ip)
    }

    @Test
    fun `zero client id is absent not identity zero`() {
        val c = RouterClientCodec.decodeClients(clients(field(1, client(mac = "", extra = numberField(43, 0)))))!!.single()
        assertNull(c.clientId)
        assertEquals(StableIdentity.None, RouterClientIdentity.stableIdentity(c))
    }

    // 5 — duplicates inside one snapshot are detected, never silently matched.
    @Test
    fun `duplicate mac device id and client id are grouped`() {
        val mac = "AA:BB:CC:DD:EE:99"
        val snapshot = RouterClientCodec.decodeClients(clients(
            field(1, client(mac = mac, extra = str(15, "same") + numberField(43, 5))) +
                field(1, client(name = "آخر", mac = mac, extra = str(15, "same") + numberField(43, 5))) +
                field(1, client(name = "ثالث", mac = "AA:BB:CC:DD:EE:11", extra = numberField(43, 5))),
        ))!!
        val groups = RouterClientIdentity.duplicateGroups(snapshot)
        assertTrue(groups.any { it.kind == "MAC" && it.value == "aa:bb:cc:dd:ee:99" && it.count == 2 })
        assertTrue(groups.any { it.kind == "DEVICE_ID" && it.value == "same" && it.count == 2 })
        assertTrue(groups.any { it.kind == "CLIENT_ID" && it.value == "5" && it.count == 3 })
    }

    @Test
    fun `distinct clients produce no duplicate groups`() {
        val snapshot = RouterClientCodec.decodeClients(clients(
            field(1, client(mac = "AA:BB:CC:DD:EE:01", extra = numberField(43, 1))) +
                field(1, client(mac = "AA:BB:CC:DD:EE:02", extra = numberField(43, 2))),
        ))!!
        assertTrue(RouterClientIdentity.duplicateGroups(snapshot).isEmpty())
    }

    // 6 — malformed or missing fields fail closed instead of fabricating data.
    @Test
    fun `malformed payloads cannot manufacture clients`() {
        rejected { RouterClientCodec.decodeClients(byteArrayOf(0)) }
        rejected { RouterClientCodec.decodeClients(clients(byteArrayOf(8, 1))) }
        rejected { RouterClientCodec.decodeClients(clients(field(1, byteArrayOf(0xff.toByte(), 0xff.toByte())))) }
        rejected { RouterClientCodec.decodeClients(clients(byteArrayOf()) + clients(byteArrayOf())) }
        rejected { RouterClientCodec.decodeClients(clients(field(1, str(1, "one") + str(1, "two")))) }
        rejected { RouterClientCodec.decodeClients(field(2, byteArrayOf(8, 7)) + clients(byteArrayOf())) }
    }

    @Test
    fun `oversized client counts and invalid booleans are rejected`() {
        val many = (1..RouterClientCodec.MAX_CLIENTS + 1).fold(byteArrayOf()) { acc, i ->
            acc + field(1, client(mac = "AA:BB:CC:DD:EE:01", extra = numberField(43, i.toLong())))
        }
        rejected { RouterClientCodec.decodeClients(clients(many)) }
        rejected { RouterClientCodec.decodeClients(clients(field(1, client(extra = numberField(58, 7))))) }
    }

    @Test
    fun `application permission error is a failure not an empty client list`() {
        try {
            RouterClientCodec.decodeClients(field(2, byteArrayOf(8, 7)) + clients(byteArrayOf()))
            fail("Denied read accepted")
        } catch (e: StarlinkProtocol.RpcFailure) {
            assertEquals(7, e.code)
            assertEquals("Starlink", e.layer)
        }
    }

    // 10 — unsupported RPC handling: only the two published read requests are ever built.
    @Test
    fun `only two fixed read requests exist and no write encoder is exposed`() {
        assertArrayEquals(byteArrayOf(0xd2.toByte(), 0xbb.toByte(), 1, 0), RouterClientCodec.clientsRequest())
        assertArrayEquals(byteArrayOf(0xe2.toByte(), 0x3e, 0), RouterClientCodec.statusRequest())
        val methods = RouterClientCodec::class.java.methods.map { it.name }
        assertFalse(methods.any { it.contains("set", true) && !it.startsWith("set") })
        assertEquals(2, listOf("clientsRequest", "statusRequest").count { methods.contains(it) })
    }

    @Test
    fun `unsupported rpc code 12 is reported as unsupported`() {
        try {
            RouterClientCodec.decodeClients(field(2, byteArrayOf(8, 12)) + clients(byteArrayOf()))
            fail("Unsupported RPC accepted")
        } catch (e: StarlinkProtocol.RpcFailure) {
            assertEquals(12, e.code)
        }
    }

    @Test
    fun `unknown response variant is rejected instead of guessed`() {
        rejected { RouterClientCodec.decodeClients(field(1234, byteArrayOf())) }
        rejected { RouterClientCodec.decodeClients(byteArrayOf()) }
    }
}
