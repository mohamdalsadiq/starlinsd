package com.example.data

import android.content.Context
import com.example.db.Session
import com.example.network.StarlinkProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Monitoring bridge: turns a live CLIENTS snapshot into idempotent pause/resume calls,
 * reusing SubscriptionRepository.changeState() exactly as-is.
 *
 * A poll cycle:
 *  1. Reads one CLIENTS snapshot (StarlinkProbe.clients()). Exceptions become null - never
 *     a pause trigger (spec 26): discovery failure never pauses anything and never
 *     reconciles identities (identity reconciliation only runs on SUCCESS).
 *  2. Excludes the management phone's own current IPs (never assume a static phone IP,
 *     spec 15) from the snapshot before comparison.
 *  3. Reconciles device identity (clientId primary, MAC secondary, one-shot legacy IP
 *     promotion) and classifies each device by identity — NOT by IP alone.
 *  4. Compares against bound ACTIVE/PAUSED sessions via the pure DeviceTracker.compare().
 *  5. Applies the plan with the existing changeState() which is already idempotent and
 *     re-advances state under the repository's own clock.
 *
 * Never touches Starlink Cloud, never mutates router state, never scans the subnet.
 */
internal class ClientTracker(context: Context,
    private val repo: SubscriptionRepository,
    private val lists: IpListStore,
    private val probe: suspend (android.net.Network?) -> List<StarlinkProtocol.Client>?,
) {
    private val connectivity = context.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)

    /** Latest successful snapshot in tracking form; null until a poll succeeds. */
    var lastSnapshot: List<TrackedDevice>? = null
        private set

    /** Current live devices for the binding flow, without touching session state. */
    suspend fun snapshotBlocking(): List<TrackedDevice>? = withContext(Dispatchers.IO) {
        val network = wifiNetwork() ?: return@withContext null
        val raw = probe(network)
        if (raw == null) null else track(raw).also { lastSnapshot = it }
    }

    private suspend fun track(snapshot: List<StarlinkProtocol.Client>): List<TrackedDevice> {
        val phone = phoneIps()
        val base = snapshot
            .filter { c -> c.id != null && c.ip !in phone }
            .map { c -> TrackedDevice(c.id!!, c.name, c.ip, c.mac, IpLists.Category.UNKNOWN, c.blocked) }
        return lists.reconcile(base)
    }

    private fun wifiNetwork(): android.net.Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
                !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        } == true
    }

    private fun phoneIps(): Set<String> = connectivity.allNetworks.flatMap { network ->
        connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { it.address.hostAddress }
    }.toSet()

    /**
     * One monitoring cycle. Returns the applied plan so callers (UI/log) can show what
     * happened; null means the snapshot failed and nothing was touched.
     */
    suspend fun poll(): DeviceTracker.Plan? = withContext(Dispatchers.IO) {
        val network = wifiNetwork() ?: return@withContext null
        val raw = try { probe(network) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return@withContext null
        val tracked = track(raw)
        lastSnapshot = tracked
        val lists = lists0()
        applyPlan(repo.reconcile(), tracked, lists.homeClientIds, lists.legacyHomeIps)
    }

    private suspend fun lists0() = lists.identitySnapshot()

    /** Pure-application path, also the unit-test entry point (no network involved). */
    suspend fun applyPlan(sessions: List<Session>, snapshot: List<TrackedDevice>?,
        homeClientIds: Set<Long> = emptySet(), legacyHomeIps: Set<String> = emptySet()): DeviceTracker.Plan? {
        val plan = DeviceTracker.compare(sessions, snapshot, homeClientIds, legacyHomeIps)
        // The plan carries clientIds; changeState() needs session ids, so map them back.
        val pauseIds = plan.pause.toSet()
        val resumeIds = plan.resume.toSet()
        sessions.filter { it.deviceClientId in pauseIds }.forEach { repo.changeState(it.id, "PAUSE") }
        sessions.filter { it.deviceClientId in resumeIds }.forEach { repo.changeState(it.id, "RESUME") }
        return plan
    }
}
