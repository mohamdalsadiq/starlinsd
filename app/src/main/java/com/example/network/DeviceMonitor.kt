package com.example.network

import com.example.data.SubscriptionRepository
import com.example.db.DeviceListDao
import com.example.db.Session

/** Interface for repository operations the monitor needs — testable without a real database. */
internal interface SessionControl {
    suspend fun reconcile(now: Long): List<Session>
    suspend fun changeState(id: String, action: String)
}

/** Extension to bridge SubscriptionRepository into SessionControl. */
internal suspend fun SubscriptionRepository.asSessionControl(): SessionControl = object : SessionControl {
    override suspend fun reconcile(now: Long): List<Session> = this@asSessionControl.reconcile(now)
    override suspend fun changeState(id: String, action: String) = this@asSessionControl.changeState(id, action)
}

/** Classifies a discovered router client into one of three categories. */
internal enum class DeviceCategory { HOUSEHOLD, WATCHLIST, UNKNOWN }

/** Result of classifying a single router client. */
internal data class ClassifiedDevice(
    val client: StarlinkProtocol.Client,
    val category: DeviceCategory,
)

/**
 * Pure classification logic: maps each router client to HOUSEHOLD, WATCHLIST, or UNKNOWN
 * based on IP membership in user-managed lists. No network access, no side effects.
 *
 * Identity for session linking is clientId (StarlinkProtocol.Client.id), not MAC,
 * because MAC addresses can be spoofed. Household/watchlist lists use IP as the key,
 * per the owner's explicit request for simpler management.
 */
object DeviceClassifier {
    const val HOUSEHOLD = "HOUSEHOLD"
    const val WATCHLIST = "WATCHLIST"

    internal fun classify(
        clients: List<StarlinkProtocol.Client>,
        householdIps: Set<String>,
        watchlistIps: Set<String>,
    ): List<ClassifiedDevice> = clients.map { client ->
        val category = when {
            client.ip in householdIps -> DeviceCategory.HOUSEHOLD
            client.ip in watchlistIps -> DeviceCategory.WATCHLIST
            else -> DeviceCategory.UNKNOWN
        }
        ClassifiedDevice(client, category)
    }
}

/**
 * Monitors router clients and triggers PAUSE/RESUME on linked subscriptions.
 *
 * Design:
 * - Reads the current device list from the router (local gRPC, no cloud).
 * - Classifies each device (household/watchlist/unknown).
 * - For each ACTIVE session with a linked deviceClientId:
 *   - If the device is NOT in the current client list → call changeState(id, "PAUSE")
 * - For each PAUSED session with a linked deviceClientId:
 *   - If the device IS in the current client list → call changeState(id, "RESUME")
 *
 * Household devices are excluded from all monitoring (they're never linked to sessions).
 * Watchlist devices are classified but not automatically paused — that's a future feature.
 *
 * This class is testable with fake implementations of the dependencies.
 */
internal class DeviceMonitor(
    private val repo: SessionControl,
    private val deviceListDao: DeviceListDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Runs one monitoring cycle. Returns a summary of actions taken.
     * Called periodically by DeviceMonitorWorker.
     */
    internal suspend fun scan(clients: List<StarlinkProtocol.Client>): ScanResult {
        val householdIps = deviceListDao.byType(DeviceClassifier.HOUSEHOLD).map { it.ip }.toSet()
        val watchlistIps = deviceListDao.byType(DeviceClassifier.WATCHLIST).map { it.ip }.toSet()
        val classified = DeviceClassifier.classify(clients, householdIps, watchlistIps)

        // Build a set of currently connected clientIds (non-null, non-household)
        val connectedIds = classified
            .filter { it.category != DeviceCategory.HOUSEHOLD }
            .mapNotNull { it.client.id }
            .toSet()

        // Build a set of household clientIds — sessions linked to these are never monitored
        val householdIds = classified
            .filter { it.category == DeviceCategory.HOUSEHOLD }
            .mapNotNull { it.client.id }
            .toSet()

        val sessions = repo.reconcile(now())
        var paused = 0
        var resumed = 0

        sessions.filter { !it.home && it.deviceClientId != null }.forEach { session ->
            val linkedId = session.deviceClientId!!.toLongOrNull() ?: return@forEach
            // Skip sessions linked to household devices — they are never monitored
            if (linkedId in householdIds) return@forEach
            val isPresent = linkedId in connectedIds

            when {
                isPresent && session.state == "PAUSED" -> {
                    repo.changeState(session.id, "RESUME")
                    resumed++
                }
                !isPresent && session.state == "ACTIVE" -> {
                    repo.changeState(session.id, "PAUSE")
                    paused++
                }
            }
        }

        return ScanResult(
            totalSeen = clients.size,
            household = classified.count { it.category == DeviceCategory.HOUSEHOLD },
            watchlist = classified.count { it.category == DeviceCategory.WATCHLIST },
            unknown = classified.count { it.category == DeviceCategory.UNKNOWN },
            withClientId = clients.count { it.id != null },
            pausedSessions = paused,
            resumedSessions = resumed,
        )
    }
}

internal data class ScanResult(
    val totalSeen: Int,
    val household: Int,
    val watchlist: Int,
    val unknown: Int,
    val withClientId: Int,
    val pausedSessions: Int,
    val resumedSessions: Int,
) {
    val summary: String get() = "أجهزة: $totalSeen (${withClientId} بمعرّف) · أهل بيت: $household · مراقبة: $watchlist · غير معروف: $unknown · إيقاف: $pausedSessions · استئناف: $resumedSessions"
}
