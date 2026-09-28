package com.example.notifications

import android.content.Context
import androidx.work.*
import com.example.data.SubscriptionRepository
import com.example.db.DeviceIdentity
import com.example.db.DevicePresence
import com.example.network.RouterControlLink
import com.example.network.StarlinkProtocol
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Local-only periodic router monitoring: one bounded CLIENTS read per cycle, no cloud calls,
 * no subnet scanning. Presence transitions call the existing [SubscriptionRepository.changeState]
 * so pause/resume keeps a single tested implementation.
 *
 * Router reads are wrapped the same way RouterControl wraps them: a fresh [RouterControlLink]
 * per cycle that validates we are still on the same Starlink Wi-Fi. Any failure propagates to
 * the worker as Result.retry() and never writes to the database - a lost or blocked read must
 * not pause or resume anyone.
 */
internal class DeviceMonitor internal constructor(
    private val openLink: () -> RouterControlLink,
    private val repo: SubscriptionRepository,
    private val misses: MutableMap<String, Int>,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    data class Result(val paused: Int, val resumed: Int, val tracked: Int)

    suspend fun runOnce(): Result {
        val linked = repo.dao.linkedSessions()
        val family = repo.devices.familyIps()
        val watch = repo.devices.watchIps()
        // Nothing to track and nothing to keep fresh: skip the radio entirely.
        if (linked.isEmpty() && family.isEmpty() && watch.isEmpty()) return Result(0, 0, 0)

        val link = openLink()
        val status = StarlinkProtocol.decode(link.exchange(StarlinkProtocol.request(StarlinkProtocol.Query.STATUS)))
        check(status.kind == "ROUTER") { "not_router" }
        val reply = StarlinkProtocol.decode(link.exchange(StarlinkProtocol.request(StarlinkProtocol.Query.CLIENTS)))
        check(reply.kind == "CLIENTS") { "missing_clients" }
        val clients = requireNotNull(reply.clients) { "missing_clients" }
        require(clients.size <= DevicePresence.MAX_CLIENTS) { "too_many_clients" }

        val stamp = now()
        val seen = clients.asSequence().filter { it.id != null && clientIp(it.ip) != null }
            .associate { it.id!!.toString() to DevicePresence.Snapshot.Seen(it.name, it.ip, it.active) }
        val snapshot = DevicePresence.Snapshot(seen, stamp, family.toSet(), watch.toSet())

        val (transitions, nextMisses) = DevicePresence.evaluate(linked, snapshot, misses)
        var paused = 0
        var resumed = 0
        transitions.forEach { transition ->
            when (transition) {
                is DevicePresence.Transition.Paused -> { repo.changeState(transition.session.id, "PAUSE"); paused++ }
                is DevicePresence.Transition.Resumed -> { repo.changeState(transition.session.id, "RESUME"); resumed++ }
            }
        }

        // Persist identities for every identifiable client so the devices screen and the future
        // watchlist alerts have data even when nothing changed. Unidentified entries (no router
        // id yet) are skipped: they cannot be tracked reliably and would churn the table.
        clients.forEach { client ->
            val id = client.id ?: return@forEach
            val ip = clientIp(client.ip) ?: return@forEach
            val existing = repo.devices.byClient(id.toString())
            repo.devices.upsert(DeviceIdentity(
                clientId = id.toString(),
                name = client.name.trim().ifBlank { "جهاز بدون اسم" }.take(80),
                ip = ip, lastSeenAt = stamp, lastSeenIp = ip,
                list = existing?.list ?: DeviceIdentity.LIST_NONE))
        }
        // Clients the router no longer reports cannot be classified anymore; drop their streaks
        // so a later return starts fresh instead of inheriting stale misses.
        val retained = nextMisses.filterKeys { it in seen }
        misses.clear()
        misses.putAll(retained)

        return Result(paused, resumed, seen.size)
    }

    /** Same validation as RouterControl.checkTarget: only trackable LAN clients count. */
    private fun clientIp(ip: String): String? =
        ip.takeIf { it.matches(Regex("192\\.168\\.1\\.[0-9]{1,3}")) && it.substringAfterLast('.').toIntOrNull() in 2..254 }
}

/**
 * Periodic (15 min) background poll of the local router. Chosen over a foreground service on
 * purpose: there is no system event for "device left the network", the shortest reliable
 * periodic cadence on modern Android is 15 minutes anyway, and the app's promise is a light
 * battery profile with no permanent notification.
 */
class DeviceMonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        monitor(applicationContext).runOnce(); Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Network/router failures are retryable by design; nothing was written on failure.
        Result.retry()
    }

    companion object {
        private const val UNIQUE = "slotra-device-monitor"
        @Volatile private var shared: DeviceMonitor? = null
        @Volatile private var misses: MutableMap<String, Int> = mutableMapOf()

        internal fun monitor(context: Context): DeviceMonitor {
            shared?.let { return it }
            synchronized(this) {
                shared ?: DeviceMonitor(
                    openLink = { com.example.network.AndroidRouterLink(context.applicationContext) },
                    repo = SubscriptionRepository(context.applicationContext),
                    misses = misses,
                ).also { shared = it }
            }.let { return it }
        }

        /** Schedules (or keeps) the unique periodic work. Safe to call from anywhere. */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<DeviceMonitorWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Immediate one-shot scan (manual refresh button); reuses the same monitor state. */
        suspend fun runOnceNow(context: Context) {
            monitor(context).runOnce()
            SubscriptionAlarms.refresh(context.applicationContext)
        }
    }
}
