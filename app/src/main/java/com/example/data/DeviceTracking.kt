package com.example.data

/**
 * Identity rules for device tracking.
 *
 * - clientId (StarlinkProtocol.Client.id, field 43) is the ONLY identity that binds a
 *   subscription to a device. MAC can be spoofed and IP rotates with DHCP.
 * - IP is the management key for the home and watch lists (simple user-facing management),
 *   and for excluding the management phone from tracking.
 * - These are two different systems: subscriptions bind by clientId; list management
 *   and classification bind by IP. One IP classification, though, still decides whether
 *   the clientId currently sitting at that IP gets tracked.
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
