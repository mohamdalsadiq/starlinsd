package com.example.network.router

/**
 * Experimental, isolated read-only diagnostic record of one Wi-Fi client as returned by the
 * Starlink router on 192.168.1.1:9000. Every value is exactly what the firmware reported (after
 * trimming/sanitizing); nothing is inferred or fabricated. See docs/ROUTER-CLIENT-CONTROL.md.
 *
 * Identity rule: [ip] is CURRENT connection information and must never be used as a permanent
 * identity. [mac] / [deviceId] / [clientId] are the only identity candidates, and their real-world
 * stability is UNKNOWN until observed on hardware.
 */
internal data class RouterClient(
    val name: String,
    val givenName: String,
    val mac: String?,
    val rawMac: String,
    val ip: String?,
    val rawIp: String,
    val ipv6: List<String>,
    val clientId: Long?,
    val deviceId: String,
    val captiveClientId: String,
    val upstreamMac: String,
    val active: Boolean?,
    val blocked: Boolean?,
    val role: Long?,
    val interfaceType: Long?,
    val interfaceName: String,
    val associatedSeconds: Long?,
    val idleSeconds: Long?,
    val dhcpLeaseFound: Boolean?,
    val dhcpLeaseActive: Boolean?,
    val dhcpLeaseRenewed: Boolean?,
    val captiveState: Long?,
    val sandboxState: Long?,
    val hardwareVersion: String,
    val softwareVersion: String,
    val apiVersion: Long?,
    val uploadMb: Long?,
    val downloadMb: Long?,
) {
    /** Display name preference matching the existing app behaviour: given_name first, then name. */
    val hostname: String get() = givenName.ifBlank { name }

    /** True when the firmware returned a MAC but it is masked/blank and therefore unusable. */
    val macMasked: Boolean get() = mac == null && rawMac.isNotBlank()
}

/** A candidate permanent identity. IP is deliberately not part of this type. */
internal sealed interface StableIdentity {
    val kind: String
    val value: String

    /** Hardware address. Preferred candidate, only when the firmware returned a full MAC. */
    data class Mac(override val value: String) : StableIdentity {
        override val kind: String get() = "MAC"
    }

    /** Router-side string identifier (WifiClient.device_id = 15), when the firmware populates it. */
    data class DeviceId(override val value: String) : StableIdentity {
        override val kind: String get() = "DEVICE_ID"
    }

    /** Router-assigned uint32 (WifiClient.client_id = 43). Observed to churn for real devices. */
    data class ClientId(override val value: String) : StableIdentity {
        override val kind: String get() = "CLIENT_ID"
    }

    data object None : StableIdentity {
        override val kind: String get() = "NONE"
        override val value: String get() = ""
    }
}

internal data class DuplicateGroup(val kind: String, val value: String, val count: Int)

/**
 * Normalization and identity selection. Kept pure so it is fully unit-testable without a router.
 */
internal object RouterClientIdentity {
    private val fullMac = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    /**
     * Lowercase colon-separated MAC, or null when the value is absent, masked (`60:74:f4:XX:XX:XX`),
     * all-zero, or otherwise not a full hardware address. A null result must never be treated as
     * "same device" or "different device"; it is simply unknown.
     */
    fun normalizeMac(raw: String): String? {
        val cleaned = raw.trim().lowercase().replace('-', ':')
        if (!fullMac.matches(cleaned)) return null
        if (cleaned == "00:00:00:00:00:00" || cleaned == "ff:ff:ff:ff:ff:ff") return null
        return cleaned
    }

    /**
     * Canonical dotted-quad IPv4, or null when the value is absent, out of range, ambiguous
     * (leading zeros), or the unspecified address. This is current connection data only.
     */
    fun normalizeIp(raw: String): String? {
        val parts = raw.trim().split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            if (part.length > 1 && part.startsWith("0")) return null
            value
        }
        if (octets.all { it == 0 }) return null
        return octets.joinToString(".")
    }

    /**
     * Prototype hypothesis for the permanent identity, strongest available first:
     * full MAC -> router device_id string -> client_id. Real stability must be observed on
     * hardware before this order is trusted.
     */
    fun stableIdentity(client: RouterClient): StableIdentity =
        client.mac?.let(StableIdentity::Mac)
            ?: client.deviceId.trim().takeIf { it.isNotBlank() }?.let(StableIdentity::DeviceId)
            ?: client.clientId?.let { StableIdentity.ClientId(it.toString()) }
            ?: StableIdentity.None

    /** Identity values that appear more than once in one snapshot; these are never auto-matched. */
    fun duplicateGroups(clients: List<RouterClient>): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()
        for (kind in listOf("MAC", "DEVICE_ID", "CLIENT_ID")) {
            clients.mapNotNull { identityOf(it, kind) }.groupingBy { it }.eachCount()
                .filterValues { it > 1 }
                .forEach { (value, count) -> groups += DuplicateGroup(kind, value, count) }
        }
        return groups
    }

    fun identityOf(client: RouterClient, kind: String): String? = when (kind) {
        "MAC" -> client.mac
        "DEVICE_ID" -> client.deviceId.trim().takeIf { it.isNotBlank() }
        "CLIENT_ID" -> client.clientId?.toString()
        else -> null
    }
}
