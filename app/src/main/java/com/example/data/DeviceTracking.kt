package com.example.data

/**
 * Identity rules for device tracking.
 *
 * - clientId (StarlinkProtocol.Client.id, field 43) is the ONLY identity that binds a
 *   subscription to a device. MAC can be spoofed and IP rotates with DHCP.
 * - HOME/WATCH classification is IDENTITY-based (schema v8 device_identities):
 *   clientId first, MAC second, and only then a LIMITED legacy fallback to the
 *   owner's manually saved IP rows — promoted one-shot to a clientId record by
 *   DeviceIdentityEngine so the IP is never needed again for that device.
 * - These are two different systems: subscriptions bind by clientId; identity records
 *   (clientId/MAC) classify; IP is display/last-known only. An IP can never move a
 *   device between lists by itself once its clientId is stored.
 */
object IpLists {
    /** Priority: HOME before WATCH, so a HOME device is never tracked even if both lists match. */
    enum class Category { HOME, WATCH, UNKNOWN }

    private val ipPattern = Regex("""^192\.168\.1\.([0-9]{1,3})$""")

    fun valid(ip: String): Boolean {
        if (!ipPattern.matches(ip)) return false
        val last = ip.substringAfterLast('.').toInt()
        return last in 2..254
    }

    fun classify(ip: String, homeIps: Collection<String>, watchIps: Collection<String>): Category = when {
        ip in homeIps -> Category.HOME
        ip in watchIps -> Category.WATCH
        else -> Category.UNKNOWN
    }

    /** Home-identity clientIds from the stored identity records. */
    fun homeClientIds(identities: Collection<com.example.db.DeviceIdentity>): Set<Long> =
        identities.filter { it.list == "HOME" }.map { it.deviceId }.toSet()

    /** Watch-identity clientIds from the stored identity records. */
    fun watchClientIds(identities: Collection<com.example.db.DeviceIdentity>): Set<Long> =
        identities.filter { it.list == "WATCH" }.map { it.deviceId }.toSet()

    /**
     * MACs bound to HOME identity records (secondary identity). Only REAL
     * (unmasked) MACs are returned — masked values ("60:74:f4:XX:XX:XX") would
     * match every same-vendor device and must never identify anyone.
     */
    fun homeMacs(identities: Collection<com.example.db.DeviceIdentity>): Set<String> =
        identities.filter { it.list == "HOME" }.mapNotNull { DeviceAlerts.usableMac(it.mac) }.toSet()

    /** MACs bound to WATCH identity records (secondary identity); usable only. */
    fun watchMacs(identities: Collection<com.example.db.DeviceIdentity>): Set<String> =
        identities.filter { it.list == "WATCH" }.mapNotNull { DeviceAlerts.usableMac(it.mac) }.toSet()

    /**
     * Identity-based classification: stored clientId record wins, then a stored MAC
     * record, then the LIMITED legacy IP fallback (the owner's manual rows, which
     * DeviceIdentityEngine promotes to clientId records on first sight).
 */
    fun classifyByIdentity(
        clientId: Long,
        mac: String,
        ip: String,
        identities: Collection<com.example.db.DeviceIdentity>,
        legacyHome: Collection<String>,
        legacyWatch: Collection<String>,
    ): Category = when {
        clientId in homeClientIds(identities) -> Category.HOME
        clientId in watchClientIds(identities) -> Category.WATCH
        mac.isNotBlank() && mac in homeMacs(identities) -> Category.HOME
        mac.isNotBlank() && mac in watchMacs(identities) -> Category.WATCH
        ip in legacyHome -> Category.HOME
        ip in legacyWatch -> Category.WATCH
        else -> Category.UNKNOWN
    }
}

/** One snapshot of a router client, normalized for tracking and suggestion logic. */
data class TrackedDevice(
    val clientId: Long,
    val name: String,
    val ip: String,
    val mac: String,
    val category: IpLists.Category,
    val blocked: Boolean? = null,
)

/**
 * Immutable classification inputs at one instant: identity records plus the owner's
 * legacy manual IP rows. Classification uses one consistent set (replaces the old
 * pair of raw IP sets).
 */
data class IdentityLists(
    val identities: List<com.example.db.DeviceIdentity> = emptyList(),
    val legacyHome: Set<String> = emptySet(),
    val legacyWatch: Set<String> = emptySet(),
) {
    fun classify(device: TrackedDevice): IpLists.Category =
        IpLists.classifyByIdentity(device.clientId, device.mac, device.ip, identities, legacyHome, legacyWatch)

    /** clientIds classified HOME right now (tracking exclusion, recovery options). */
    val homeClientIds: Set<Long> get() = IpLists.homeClientIds(identities)

    /** Raw legacy HOME IPs, for the pre-clientId promotion path only. */
    val legacyHomeIps: Set<String> get() = legacyHome
}

/**
 * Pure device-selection rules for the new-session binding flow. Never picks a device
 * automatically: exactly one free candidate is a suggestion for the user to confirm,
 * several free candidates are a list for the user to choose from, and none means the
 * session is created unbound (creation is never blocked).
 */
object DeviceSelection {
    data class Result(val suggestion: TrackedDevice?, val options: List<TrackedDevice>) {
        val unbound: Boolean get() = options.isEmpty()
    }

    /** Candidates eligible for subscription binding: non-HOME with a valid id, not already bound. */
    fun choose(candidates: List<TrackedDevice>, takenClientIds: Collection<Long>): Result {
        val free = candidates.filter { it.category != IpLists.Category.HOME && it.clientId !in takenClientIds }
        return Result(free.singleOrNull(), free)
    }
}
