package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.network.StarlinkProbe
import com.example.network.StarlinkProtocol
import com.example.notifications.DeviceAlertsCoordinator
import com.example.notifications.DeviceTrackerBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Auto-binding for subscription shortcuts (spec sections 1/15/22).
 *
 * A shortcut-created session previously stayed unbound (`deviceClientId == null`),
 * so the just-sold device was classified as *unregistered* in daily reconciliation
 * and the owner could even receive an "unknown device" alert for a paying customer.
 * The shortcut IS the subscription workflow, so firing a shortcut must bind the new
 * session to the device being sold to — without a second manual registration step.
 *
 * Binding rules (pure, testable, in [choose]):
 * - Discovery failure (null snapshot) → never bind. No guessing.
 * - Only currently live, non-HOME (identity-based), currently unbound devices are
 *   candidates. Name/IP are display data, never identity.
 * - Preference goes to devices under unregistered-delay monitoring (the pending
 *   unregistered candidates): binding one of them also cancels its pending alert
 *   via [DeviceAlerts.evaluate], which drops pending candidates once bound.
 *   Among them the most recently arrived device wins — the customer who just
 *   walked in is the one the owner is selling to right now.
 * - With no pending candidate, exactly one free candidate is unambiguous and binds.
 * - Several free candidates with no monitoring signal is ambiguous: leave the
 *   session unbound and let the owner bind from the devices screen. Session
 *   creation is never blocked or failed by binding.
 */
object ShortcutBinding {

    /** A device currently under unregistered-delay monitoring (pending alert). */
    data class PendingCandidate(val clientId: Long, val firstSeen: Long)

    /**
     * Pure choice of which live device a just-fired shortcut belongs to, or null
     * when there is no safe unambiguous choice.
     */
    fun choose(
        snapshot: List<TrackedDevice>?,
        boundClientIds: Set<Long>,
        pending: List<PendingCandidate>,
        homeClientIds: Set<Long>,
        homeMacs: Set<String>,
        legacyHomeIps: Set<String>,
    ): TrackedDevice? {
        if (snapshot == null) return null
        val eligible = snapshot.filter { device ->
            device.clientId in 1..4294967295L &&
                device.clientId !in boundClientIds &&
                device.clientId !in homeClientIds &&
                (device.mac.isBlank() || device.mac !in homeMacs) &&
                device.ip !in legacyHomeIps
        }
        if (eligible.isEmpty()) return null
        val pendingById = pending.associateBy { it.clientId }
        val monitored = eligible.filter { it.clientId in pendingById }
        if (monitored.isNotEmpty()) {
            return monitored.maxByOrNull { pendingById.getValue(it.clientId).firstSeen }
        }
        return eligible.singleOrNull()
    }
}

/**
 * Best-effort orchestration: take one bounded CLIENTS snapshot and bind [sessionId]
 * to the chosen device. Returns the bound device, or null when nothing was bound.
 *
 * Never throws: binding must never break or block a shortcut subscription. On any
 * failure (no Wi-Fi, discovery failure, timeout) the session simply stays unbound,
 * exactly as before this change.
 */
suspend fun bindShortcutSession(
    context: Context,
    sessionId: String,
    timeoutMs: Long = 8000L,
): TrackedDevice? = withContext(Dispatchers.IO) {
    try {
        val app = context.applicationContext
        val probe = StarlinkProbe(app)
        val lists = IpListStore(app)
        val tracked = withTimeout(timeoutMs) {
            val network = wifiNetwork(app) ?: return@withTimeout null
            val raw: List<StarlinkProtocol.Client> = probe.clients(network) ?: return@withTimeout null
            lists.reconcile(raw.map {
                TrackedDevice(it.clientId, it.name, it.ip, it.mac, IpLists.Category.UNKNOWN)
            })
        } ?: return@withContext null
        val now = System.currentTimeMillis()
        // Keep the same pipeline as the periodic poll: refresh the in-memory
        // snapshot used by alerts and record today's sightings for reconciliation.
        DeviceTrackerBridge.updateSnapshot(tracked)
        DeviceAlertsCoordinator.recordSnapshot(app, now, tracked)
        val repo = SubscriptionRepository(app)
        val bound = repo.dao.sessions()
            .filter { !it.home && it.state in listOf("ACTIVE", "PAUSED") && it.deviceClientId != null }
            .mapNotNull { it.deviceClientId }
            .toSet()
        val identity = lists.identitySnapshot()
        val pending = DeviceAlertsCoordinator.pending(app)
            .map { ShortcutBinding.PendingCandidate(it.clientId, it.firstSeen) }
        val choice = ShortcutBinding.choose(
            snapshot = tracked,
            boundClientIds = bound,
            pending = pending,
            homeClientIds = identity.homeClientIds,
            homeMacs = IpLists.homeMacs(identity.identities),
            legacyHomeIps = identity.legacyHomeIps,
        ) ?: return@withContext null
        repo.bindDevice(sessionId, choice)
        choice
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { null }
}

private fun wifiNetwork(context: Context): Network? {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return null
    return connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
    }
}
