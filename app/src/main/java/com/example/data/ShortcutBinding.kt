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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Deterministic auto-binding for subscription shortcuts.
 *
 * The shortcut IS the subscription workflow: when it fires, the operator's text
 * is expanded to include the subscriber number as a stamp ("[N]", appended by
 * [com.example.domain.TextRules.withReference]) and the operator saves that text
 * as the router device name (e.g. "🌹٠٢:٢٨م [2]🌹 M05"). That stamp is the ONLY
 * signal used here — no heuristics, no guessing:
 *
 * - The device whose live router name carries "[N]" for today's subscriber N is
 *   the device the session was sold to. Exactly one such device must exist.
 * - The stamp must be FRESH: first sighted at/after the session was created.
 *   Reference numbers reset daily, so yesterday's "[2]" can never match today's
 *   subscriber 2 (sightings are keyed per day in DeviceAlertsCoordinator).
 * - Discovery failure (null snapshot) → never bind. No stamp → the session stays
 *   unbound and the owner binds it manually from the devices screen. Binding must
 *   never break or block a sale.
 *
 * Why not a single immediate snapshot: the operator saves the router rename
 * AFTER the text expansion, so the stamp is usually not visible yet when the
 * shortcut fires. Binding therefore retries on a bounded schedule
 * ([bindShortcutWithRetry]) and a safety-net sweep rides the single existing
 * scheduler ([sweepUnbound], called from SubscriptionAlarms.refresh()).
 */
object ShortcutBinding {

    /** Tolerance for the stamp-freshness check (operator save delay vs our clock). */
    const val STAMP_TOLERANCE_MS = 60_000L

    /**
     * Pure choice of which live device a shortcut session belongs to, or null.
     *
     * @param snapshot live reconciled devices, or null on discovery failure.
     * @param reference the session's subscriber number (e.g. "2" matches "[2]").
     * @param sessionStartedAt epoch ms when the session was created.
     * @param stampFirstSeen "$dayKey:$clientId:$reference" -> first stamp sighting.
     * @param now current epoch ms (lookup key day + freshness fallback).
     */
    fun matchStamp(
        snapshot: List<TrackedDevice>?,
        reference: String,
        sessionStartedAt: Long,
        stampFirstSeen: Map<String, Long>,
        now: Long,
        boundClientIds: Set<Long>,
        homeClientIds: Set<Long>,
        homeMacs: Set<String>,
        legacyHomeIps: Set<String>,
    ): TrackedDevice? {
        if (snapshot == null || reference.isBlank()) return null
        val stamp = "[$reference]"
        val candidates = snapshot.filter { device ->
            device.clientId in 1..4294967295L &&
                device.clientId !in boundClientIds &&
                device.clientId !in homeClientIds &&
                (device.mac.isBlank() || device.mac !in homeMacs) &&
                device.ip !in legacyHomeIps &&
                stamp in device.name
        }
        if (candidates.isEmpty()) return null
        // Freshness: the stamp must have appeared at/after the session started.
        // A sighting recorded under either the session's day or today is accepted
        // (midnight-boundary sales); anything older is a stale stamp from a
        // previous day and must never match. No sighting at all means the stamp
        // predates our observation — also stale, never a match. There is no
        // fallback: an unobserved stamp is not a fresh stamp.
        val fresh = candidates.filter { device ->
            val keys = listOf(
                "${DeviceAlerts.dayKey(sessionStartedAt)}:${device.clientId}:$reference",
                "${DeviceAlerts.dayKey(now)}:${device.clientId}:$reference",
            )
            val firstSeen = keys.firstNotNullOfOrNull { stampFirstSeen[it] } ?: return@filter false
            firstSeen >= sessionStartedAt - STAMP_TOLERANCE_MS
        }
        return fresh.singleOrNull()
    }

    /**
     * Safety-net sweep: bind every still-unbound, non-home shortcut session from
     * today against an already-fresh snapshot. Performs no discovery of its own;
     * called from SubscriptionAlarms.refresh() after the poll. Returns the number
     * of sessions bound. Never throws.
     */
    suspend fun sweepUnbound(context: Context, tracked: List<TrackedDevice>): Int = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val repo = SubscriptionRepository(app)
            val now = System.currentTimeMillis()
            val dayStart = DeviceAlerts.dayStart(now)
            val sessions = repo.dao.sessions()
            val unbound = sessions.filter { s ->
                !s.home && s.deviceClientId == null && s.reference.isNotBlank() &&
                    s.state in listOf("ACTIVE", "PAUSED", "ENDED") && s.started >= dayStart
            }
            if (unbound.isEmpty()) return@withContext 0
            val lists = IpListStore(app)
            val identity = lists.identitySnapshot()
            val homeMacs = IpLists.homeMacs(identity.identities)
            val stamps = DeviceAlertsCoordinator.stampSightings(app, now)
            val bound = sessions
                .filter { !it.home && it.state in listOf("ACTIVE", "PAUSED") && it.deviceClientId != null }
                .mapNotNull { it.deviceClientId }
                .toMutableSet()
            var count = 0
            for (session in unbound) {
                val choice = matchStamp(
                    snapshot = tracked,
                    reference = session.reference,
                    sessionStartedAt = session.started,
                    stampFirstSeen = stamps,
                    now = now,
                    boundClientIds = bound,
                    homeClientIds = identity.homeClientIds,
                    homeMacs = homeMacs,
                    legacyHomeIps = identity.legacyHomeIps,
                ) ?: continue
                repo.bindDevice(session.id, choice)
                bound.add(choice.clientId)
                count++
            }
            count
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { 0 }
    }
}

/**
 * One bounded CLIENTS snapshot followed by a stamp-only bind attempt for
 * [sessionId]. Returns the bound device, or null when nothing was bound.
 * Never throws.
 */
suspend fun bindShortcutSessionByStamp(
    context: Context,
    sessionId: String,
    reference: String,
    sessionStartedAt: Long,
    timeoutMs: Long = 8000L,
): TrackedDevice? = withContext(Dispatchers.IO) {
    try {
        val app = context.applicationContext
        val probe = StarlinkProbe(app)
        val lists = IpListStore(app)
        val tracked = withTimeout(timeoutMs) {
            val network = wifiNetwork(app) ?: return@withTimeout null
            val raw: List<StarlinkProtocol.Client> = probe.clients(network) ?: return@withTimeout null
            lists.reconcile(raw
                .filter { it.id != null }
                .map { TrackedDevice(it.id!!, it.name, it.ip, it.mac, IpLists.Category.UNKNOWN, it.blocked) }
            )
        } ?: return@withContext null
        val now = System.currentTimeMillis()
        // Keep the same pipeline as the periodic poll: refresh the in-memory
        // snapshot used by alerts and record today's sightings for reconciliation
        // (recordSnapshot also records subscriber-stamp sightings).
        DeviceTrackerBridge.updateSnapshot(tracked)
        DeviceAlertsCoordinator.recordSnapshot(app, now, tracked)
        val repo = SubscriptionRepository(app)
        val bound = repo.dao.sessions()
            .filter { !it.home && it.state in listOf("ACTIVE", "PAUSED") && it.deviceClientId != null }
            .mapNotNull { it.deviceClientId }
            .toSet()
        val identity = lists.identitySnapshot()
        val stamps = DeviceAlertsCoordinator.stampSightings(app, now)
        val choice = ShortcutBinding.matchStamp(
            snapshot = tracked,
            reference = reference,
            sessionStartedAt = sessionStartedAt,
            stampFirstSeen = stamps,
            now = now,
            boundClientIds = bound,
            homeClientIds = identity.homeClientIds,
            homeMacs = IpLists.homeMacs(identity.identities),
            legacyHomeIps = identity.legacyHomeIps,
        ) ?: return@withContext null
        repo.bindDevice(sessionId, choice)
        choice
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { null }
}

/**
 * Bounded deferred binding: try now, then retry every [intervalMs] up to
 * [attempts] times. The operator saves the router rename after the expansion,
 * so the first attempt usually misses the stamp; retries catch it without any
 * persistent scheduler. Returns the bound device or null. Never throws.
 */
suspend fun bindShortcutWithRetry(
    context: Context,
    sessionId: String,
    reference: String,
    sessionStartedAt: Long,
    attempts: Int = 8,
    intervalMs: Long = 15_000L,
): TrackedDevice? {
    repeat(attempts) { i ->
        if (i > 0) {
            try { delay(intervalMs) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        }
        val bound = bindShortcutSessionByStamp(context, sessionId, reference, sessionStartedAt)
        if (bound != null) return bound
    }
    return null
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
