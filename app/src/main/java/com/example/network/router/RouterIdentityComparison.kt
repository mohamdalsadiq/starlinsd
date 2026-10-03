package com.example.network.router

/** Verdict for one identity field across two observations of the same device. */
internal enum class Stability { STABLE, CHANGED, UNKNOWN }

internal data class DevicePair(val before: RouterClient, val after: RouterClient, val pairedBy: String)

/** STABLE only when both observations carry the value and they match; UNKNOWN when either is absent. */
private fun <T> verdict(first: T?, second: T?): Stability = when {
    first == null || second == null -> Stability.UNKNOWN
    first == second -> Stability.STABLE
    else -> Stability.CHANGED
}

internal data class DeviceComparison(val pair: DevicePair) {
    val hostname: String get() = pair.after.hostname.ifBlank { pair.before.hostname }
    val pairedBy: String get() = pair.pairedBy
    val mac: Pair<String?, String?> get() = pair.before.mac to pair.after.mac
    val ip: Pair<String?, String?> get() = pair.before.ip to pair.after.ip
    val clientId: Pair<Long?, Long?> get() = pair.before.clientId to pair.after.clientId
    val deviceId: Pair<String, String> get() = pair.before.deviceId to pair.after.deviceId
    val upstreamMac: Pair<String, String> get() = pair.before.upstreamMac to pair.after.upstreamMac
    val captiveClientId: Pair<String, String> get() = pair.before.captiveClientId to pair.after.captiveClientId

    val macStability: Stability get() = verdict(mac.first, mac.second)
    val ipStability: Stability get() = verdict(ip.first, ip.second)
    val clientIdStability: Stability get() = verdict(clientId.first, clientId.second)
    val deviceIdStability: Stability get() = verdict(deviceId.first.trim().ifBlank { null }, deviceId.second.trim().ifBlank { null })
    val upstreamMacStability: Stability get() = verdict(upstreamMac.first.trim().ifBlank { null }, upstreamMac.second.trim().ifBlank { null })

    /** True when any identifier the prototype tracks changed while the device stayed the same. */
    val anyIdentifierChanged: Boolean get() = listOf(macStability, clientIdStability, deviceIdStability)
        .any { it == Stability.CHANGED }
}

/**
 * Pure identity-stability engine for the A->G real-device procedure:
 * connect, record, disconnect, reconnect, record, observe IP change, compare.
 *
 * Nothing here talks to a router, so the same logic used on hardware is exercised by unit tests.
 */
internal object RouterIdentityComparison {
    fun compare(before: List<RouterClient>, after: List<RouterClient>): IdentityStabilityReport {
        val pairing = pair(before, after)
        val comparisons = pairing.pairs.map(::DeviceComparison)
        return IdentityStabilityReport(
            comparisons = comparisons,
            appeared = pairing.appeared,
            disappeared = pairing.disappeared,
            macStable = aggregate(comparisons.map { it.macStability }),
            ipStable = aggregate(comparisons.map { it.ipStability }),
            clientIdStable = aggregate(comparisons.map { it.clientIdStability }),
            deviceIdStable = aggregate(comparisons.map { it.deviceIdStability }),
            otherStableIdentifier = otherStableIdentifier(comparisons),
        )
    }

    internal data class Pairing(
        val pairs: List<DevicePair>,
        val appeared: List<RouterClient>,
        val disappeared: List<RouterClient>,
    )

    /** Deterministic greedy pairing: unique MAC, then unique device_id, then unique client_id, then unique hostname. */
    internal fun pair(before: List<RouterClient>, after: List<RouterClient>): Pairing {
        val pairs = mutableListOf<DevicePair>()
        val beforeLeft = before.toMutableList()
        val afterLeft = after.toMutableList()
        // Names are display-only, matching Slotra's identity rule: never pair by name.
        for (kind in listOf("MAC", "DEVICE_ID", "CLIENT_ID")) {
            val beforeGroups = group(beforeLeft, kind)
            val afterGroups = group(afterLeft, kind)
            for ((value, groupBefore) in beforeGroups) {
                val groupAfter = afterGroups[value] ?: continue
                if (groupBefore.size != 1 || groupAfter.size != 1) continue
                val first = groupBefore.single()
                val second = groupAfter.single()
                if (first !in beforeLeft || second !in afterLeft) continue
                beforeLeft.remove(first)
                afterLeft.remove(second)
                pairs += DevicePair(first, second, kind)
            }
        }
        return Pairing(pairs, afterLeft, beforeLeft)
    }

    private fun group(clients: List<RouterClient>, kind: String): Map<String, List<RouterClient>> =
        clients.mapNotNull { client -> RouterClientIdentity.identityOf(client, kind)?.let { it to client } }
            .groupBy({ it.first }, { it.second })

    /** STABLE only when every evaluable observation matched; CHANGED when any evaluable one changed. */
    private fun aggregate(verdicts: List<Stability>): Stability = when {
        verdicts.isEmpty() -> Stability.UNKNOWN
        verdicts.any { it == Stability.CHANGED } -> Stability.CHANGED
        verdicts.all { it == Stability.UNKNOWN } -> Stability.UNKNOWN
        else -> Stability.STABLE
    }

    private fun otherStableIdentifier(comparisons: List<DeviceComparison>): String? {
        val candidates = listOf(
            "DEVICE_ID" to comparisons.map { it.deviceIdStability },
            "UPSTREAM_MAC" to comparisons.map { it.upstreamMacStability },
        )
        return candidates.firstOrNull { (_, verdicts) ->
            verdicts.any { it == Stability.STABLE } && verdicts.none { it == Stability.CHANGED }
        }?.first
    }

}

internal data class IdentityStabilityReport(
    val comparisons: List<DeviceComparison>,
    val appeared: List<RouterClient>,
    val disappeared: List<RouterClient>,
    val macStable: Stability,
    val ipStable: Stability,
    val clientIdStable: Stability,
    val deviceIdStable: Stability,
    val otherStableIdentifier: String?,
) {
    val pairedCount: Int get() = comparisons.size

    /** Sanitized diagnostic: counts and verdicts only, never names, MACs, IPs or client IDs. */
    fun diagnostic(): String = buildString {
        appendLine("ROUTER IDENTITY COMPARISON")
        appendLine("paired=${comparisons.size} appeared=${appeared.size} disappeared=${disappeared.size}")
        appendLine("MAC_STABILITY=$macStable")
        appendLine("IP_STABILITY=$ipStable")
        appendLine("CLIENT_ID_STABILITY=$clientIdStable")
        appendLine("DEVICE_ID_STABILITY=$deviceIdStable")
        appendLine("OTHER_STABLE_ID=${otherStableIdentifier ?: "none"}")
        comparisons.forEachIndexed { index, comparison ->
            appendLine("device#$index pairedBy=${comparison.pairedBy} mac=${comparison.macStability} ip=${comparison.ipStability} " +
                "clientId=${comparison.clientIdStability} deviceId=${comparison.deviceIdStability}")
        }
    }
}
