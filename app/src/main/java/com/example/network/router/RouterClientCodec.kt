package com.example.network.router

import com.example.network.StarlinkProtocol
import com.example.network.StarlinkProtocol.fields
import com.example.network.StarlinkProtocol.singleBytes
import com.example.network.StarlinkProtocol.singleNumber
import com.example.network.StarlinkProtocol.string
import com.example.network.StarlinkProtocol.boolean

/**
 * Reads WifiGetClients (3002) / WifiGetStatus (3004) replies and keeps EVERY useful field the
 * firmware returned, including fields the current Slotra model does not use.
 *
 * Field numbers are from the published community schema
 * (proto/spacex/api/device/wifi.proto, WifiClient) — see docs/ROUTER-CLIENT-CONTROL.md for the
 * full evidence table. This is an independent bounded decoder; no generated protobuf runtime.
 *
 * Read-only: this object can only build the two fixed read requests and decode replies. It exposes
 * no write/mutation encoder, by design.
 */
internal object RouterClientCodec {
    const val MAX_CLIENTS = 512

    fun clientsRequest(): ByteArray = StarlinkProtocol.request(StarlinkProtocol.Query.CLIENTS)

    fun statusRequest(): ByteArray = StarlinkProtocol.request(StarlinkProtocol.Query.STATUS)

    /**
     * Decodes the client list from a raw application payload (already unwrapped from gRPC framing).
     * Throws on malformed input; returns an empty list only for an explicit empty client list.
     * Returns null when the reply carries no client list at all (never a fabricated empty list).
     */
    fun decodeClients(payload: ByteArray): List<RouterClient>? {
        StarlinkProtocol.checkStatus(payload)
        val outer = fields(payload)
        // A dish reply carries no Wi-Fi client list: that is "no list", not an empty list.
        if (outer.any { it.number == 2004 }) return null
        val variants = outer.filter { it.number in setOf(3002, 3004) }
        require(variants.size == 1) { "unsupported_response" }
        val variant = variants.single()
        val inner = fields(variant.bytes ?: error("invalid_response"))
        val clientField = when (variant.number) {
            3002 -> 1
            3004 -> if (inner.any { it.number == 3000 }) 3000 else 2
            else -> null
        }
        val present = variant.number == 3002 || (clientField != null && inner.any { it.number == clientField })
        if (!present) return null
        val entries = inner.filter { it.number == clientField }
        require(entries.size <= MAX_CLIENTS) { "too_many_clients" }
        return entries.map { decodeClient(fields(it.bytes ?: error("invalid_client"))) }
    }

    private fun decodeClient(client: List<StarlinkProtocol.WireField>): RouterClient {
        val rawMac = client.string(2)
        val rawIp = client.string(3)
        return RouterClient(
            name = client.string(1),
            givenName = client.string(31),
            mac = RouterClientIdentity.normalizeMac(rawMac),
            rawMac = rawMac,
            ip = RouterClientIdentity.normalizeIp(rawIp),
            rawIp = rawIp,
            ipv6 = client.filter { it.number == 41 }.map { bytes -> bytes.bytes?.toString(Charsets.UTF_8).orEmpty() },
            clientId = client.singleNumber(43)?.also { require(it in 0..0xffffffffL) { "invalid_client_id" } }?.takeIf { it != 0L },
            deviceId = client.string(15),
            captiveClientId = client.string(53),
            upstreamMac = client.string(13),
            active = client.boolean(58),
            blocked = client.boolean(42),
            role = client.singleNumber(14)?.also { require(it in 0..0xffffffffL) { "invalid_role" } },
            interfaceType = client.singleNumber(9)?.also { require(it in 0..0xffffffffL) { "invalid_iface" } },
            interfaceName = client.string(26),
            associatedSeconds = client.singleNumber(7)?.also { require(it in 0..0xffffffffL) { "invalid_associated_time" } },
            idleSeconds = client.singleNumber(45)?.also { require(it in 0..0xffffffffL) { "invalid_idle_time" } },
            dhcpLeaseFound = client.boolean(49),
            dhcpLeaseActive = client.boolean(46),
            dhcpLeaseRenewed = client.boolean(47),
            captiveState = client.singleNumber(56)?.also { require(it in 0..0xffffffffL) { "invalid_captive_state" } },
            sandboxState = client.singleNumber(57)?.also { require(it in 0..0xffffffffL) { "invalid_sandbox_state" } },
            hardwareVersion = client.string(37),
            softwareVersion = client.string(38),
            apiVersion = client.singleNumber(39)?.also { require(it in 0..0xffffffffL) { "invalid_api_version" } },
            uploadMb = client.singleNumber(54)?.also { require(it in 0..0xffffffffL) { "invalid_upload_mb" } },
            downloadMb = client.singleNumber(55)?.also { require(it in 0..0xffffffffL) { "invalid_download_mb" } },
        )
    }
}
