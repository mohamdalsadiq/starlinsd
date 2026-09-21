package com.example.network

import com.example.network.StarlinkProtocol.fields
import com.example.network.StarlinkProtocol.field
import com.example.network.StarlinkProtocol.numberField
import com.example.network.StarlinkProtocol.singleBytes
import com.example.network.StarlinkProtocol.singleNumber
import com.example.network.StarlinkProtocol.string
import java.io.ByteArrayOutputStream

/** Independent implementation from the published protobuf schema; no collection writes. */
internal object RouterControlProtocol {
    const val MAX_CONFIG = 262144
    data class Config(val revision: Long, val entries: List<ByteArray>, val routerMac: String)
    fun getConfigRequest(): ByteArray = field(3009, byteArrayOf())
    fun decodeConfig(payload: ByteArray): Config {
        StarlinkProtocol.checkStatus(payload)
        val outer = fields(payload)
        require(outer.none { it.number >= 1000 && it.number != 3009 }) { "unexpected_config_response" }
        val body = fields(outer.singleBytes(3009) ?: error("missing_config_response"))
        val raw = body.singleBytes(1) ?: error("missing_wifi_config")
        require(raw.size <= MAX_CONFIG) { "config_too_large" }
        val config = fields(raw)
        val entries = config.filter { it.number == 74 }.map { it.bytes ?: error("invalid_client_config") }
        require(entries.size <= 512 && entries.all { it.size <= 16384 }) { "client_config_too_large" }
        entries.forEach { fields(it) }
        // Keep only the client collection, revision and router identity; discard network credentials.
        return Config(config.singleNumber(43) ?: 0, entries, config.string(13))
    }
    fun entry(config: Config, device: StarlinkProtocol.Client): ByteArray? {
        val id = device.id ?: error("missing_client_id")
        val byId = config.entries.filter { fields(it).singleNumber(1) == id }
        require(byId.size <= 1) { "ambiguous_client_id" }
        val hasFullMac = fullMac(device.mac)
        val byMac = if (hasFullMac) config.entries.filter { fields(it).string(2).equals(device.mac, true) } else emptyList()
        require(byMac.size <= 1) { "ambiguous_client_mac" }
        val chosen = byId.singleOrNull() ?: byMac.singleOrNull()
        if (chosen != null) {
            val data = fields(chosen)
            val configId = data.singleNumber(1)
            require(configId == null || configId == 0L || configId == id) { "client_identity_conflict" }
            val mac = data.string(2)
            require(!hasFullMac || !fullMac(mac) || mac.equals(device.mac, true)) { "client_identity_conflict" }
            require(byMac.isEmpty() || byMac.single().contentEquals(chosen)) { "client_identity_conflict" }
        }
        return chosen
    }
    fun fullMac(value: String): Boolean = value.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))
    fun hasMarker(entry: ByteArray?, marker: String): Boolean = entry != null && fields(entry)
        .filter { it.number == 5 }.any { fields(it.bytes ?: error("invalid_schedule")).string(2) == marker }
    fun hasSchedules(entry: ByteArray?): Boolean = entry != null && fields(entry).any { it.number == 5 }
    fun withoutMarker(entry: ByteArray, marker: String): ByteArray = join(fields(entry).filterNot {
        it.number == 5 && fields(it.bytes ?: error("invalid_schedule")).string(2) == marker
    }.map { it.raw })
    fun updatedEntry(old: ByteArray?, device: StarlinkProtocol.Client, marker: String, pause: Boolean): ByteArray {
        require(marker.matches(Regex("slotra-[a-f0-9-]{36}"))) { "invalid_marker" }
        val id = device.id ?: error("missing_client_id")
        require(id in 1..0xffffffffL) { "invalid_client_id" }
        var base = old ?: (numberField(1, id) +
            (if (fullMac(device.mac)) field(2, device.mac.toByteArray()) else byteArrayOf()) +
            field(3, device.name.toByteArray()))
        base = withoutMarker(base, marker)
        if (old != null && fields(base).singleNumber(1).let { it == null || it == 0L }) {
            base = join(fields(base).filterNot { it.number == 1 }.map { it.raw }) + numberField(1, id)
        }
        if (!pause) return base
        // Experimental all-week schedule. Router-side interpretation must be verified on hardware.
        val range = numberField(1, 0) + numberField(2, 10080)
        val schedule = field(1, range) + field(2, marker.toByteArray())
        return base + field(5, schedule)
    }
    fun setClientRequest(entry: ByteArray): ByteArray {
        require(entry.size <= 16384)
        return field(3017, field(2, entry))
    }
    fun sameBytes(a: ByteArray?, b: ByteArray?): Boolean = if (a == null || b == null) a == null && b == null else a.contentEquals(b)
    private fun join(parts: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
}
