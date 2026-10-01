package com.example.data

import com.example.domain.Rules
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Wall-clock seam so alert timing is unit-testable without sleeps (spec 23/24).
 * Production uses [DeviceAlertsClock.SYSTEM]; tests advance a fake clock instead.
 */
fun interface DeviceAlertsClock { fun now(): Long
    companion object { val SYSTEM = DeviceAlertsClock { System.currentTimeMillis() } }
}

/**
 * Pure rules for Phase 3 device alerts: the unknown-device alert and the daily
 * device confirmation summary. All state enters as plain values; storage and
 * notifications live in DeviceAlertsCoordinator.
 *
 * Identity stays clientId (Phase 2 rule); IP/MAC are display-only here.
 * HOME precedence (spec 33): a HOME-classified device is excluded from
 * everything - history, alerts, and the summary.
 * Discovery-failure protection (spec 32): a null [DeviceAlerts.EvalInput.live]
 * freezes pending alerts and never fabricates sightings, disappearances, or
 * alerts - only a successful CLIENTS snapshot changes anything.
 */
object DeviceAlerts {
    /** Per-day storage key suffix, in device local time (spec 29). */
    fun dayKey(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))

    /** Local midnight of the day containing [at]. */
    fun dayStart(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Today's occurrence of [minute], possibly already past; null when disabled. */
    fun todayAt(now: Long, minute: Int?): Long? {
        if (minute == null || minute !in 0..1439) return null
        return dayStart(now) + minute * Rules.MINUTE
    }

    /** Next future occurrence of a daily minute-of-day: today if ahead, else tomorrow. */
    fun nextDaily(now: Long, minute: Int?): Long? = todayAt(now, minute)?.let { if (it > now) it else it + 24 * 60 * Rules.MINUTE }

    /** One device as seen during the day, merged across sightings by clientId. */
    data class DayDevice(
        val clientId: Long,
        val name: String,
        val ip: String,
        val mac: String,
        val category: IpLists.Category,
        val firstSeen: Long,
        val lastSeen: Long,
    )

    /**
     * Merges one successful snapshot into the day's history and returns the full
     * updated list. Callers pass null snapshot results straight through as no call
     * at all: only successful answers touch history (spec 32). HOME exclusion is
     * IDENTITY-based: a device whose clientId (or MAC before promotion) is
     * HOME-classified never enters history, and a HISTORY entry whose device is
     * HOME by identity disappears entirely — the legacy homeIps set is only a
     * pre-promotion fallback keyed to the IP the device held when seen.
     */
    fun mergeSnapshot(now: Long, seen: List<TrackedDevice>, existing: List<DayDevice>,
        homeClientIds: Set<Long> = emptySet(), homeMacs: Set<String> = emptySet(),
        legacyHomeIps: Set<String> = emptySet()): List<DayDevice> {
        val byId = existing.associateBy { it.clientId }
        val updated = seen.filter { device ->
            device.clientId !in homeClientIds && (device.mac.isBlank() || device.mac !in homeMacs) &&
                device.ip !in legacyHomeIps
        }.map { device ->
            val previous = byId[device.clientId]
            if (previous == null) DayDevice(device.clientId, device.name, device.ip, device.mac, device.category, now, now)
            else previous.copy(name = device.name.ifBlank { previous.name }, ip = device.ip, mac = device.mac,
                category = device.category, lastSeen = now)
        }
        val liveIds = updated.map { it.clientId }.toSet()
        // Devices sighted earlier today but absent now stay in history (their alert
        // window dies instead); a device whose identity became HOME disappears entirely.
        return updated + existing.filter { entry ->
            entry.clientId !in liveIds && entry.clientId !in homeClientIds &&
                (entry.mac.isBlank() || entry.mac !in homeMacs) && entry.ip !in legacyHomeIps
        }
    }

    /** An unknown-device alert waiting out the user-configured delay. */
    data class PendingAlert(val clientId: Long, val firstSeen: Long, val deadline: Long, val category: IpLists.Category)

    /** Everything needed to decide alerts for one refresh. */
    data class EvalInput(
        val now: Long,
        val delayMinutes: Int,
        /** Persisted pending alerts from the previous refresh. */
        val pending: List<PendingAlert>,
        /** Live non-HOME devices right now; null when discovery failed (spec 32). */
        val live: List<TrackedDevice>?,
        /** clientId -> session id for sessions currently ACTIVE/PAUSED and bound. */
        val activeBindings: Map<Long, String>,
        /** clientIds already notified today (spec 10: one alert per clientId per day). */
        val notifiedToday: Set<Long>,
    )

    data class EvalResult(val due: List<PendingAlert>, val pending: List<PendingAlert>)

    /**
     * One evaluation pass. Pending deadlines are recalculated from firstSeen + the
     * current delay, so a changed setting applies to waiting devices too.
     * A device that vanished before its deadline loses its window; when it returns
     * the same day it starts a fresh connection cycle because it is no longer
     * pending. At the deadline everything is re-checked: still connected, not
     * HOME (upstream), not bound to a live session, not notified.
     *
     * Only WATCH devices ever notify. UNKNOWN devices stay silent: after the
     * dwell delay they quietly qualify for the unregistered-devices list
     * (see DailyReconciliation.qualifiedUnregistered) without a notification.
     */
    fun evaluate(input: EvalInput): EvalResult {
        val live = input.live ?: return EvalResult(emptyList(), input.pending) // discovery failed: freeze (spec 32)
        val liveById = live.associateBy { it.clientId }
        val delay = input.delayMinutes.coerceAtLeast(1) * Rules.MINUTE
        val due = mutableListOf<PendingAlert>()
        val pending = mutableListOf<PendingAlert>()
        input.pending.forEach { alert ->
            val device = liveById[alert.clientId]
            val eligible = alert.category == IpLists.Category.WATCH && device != null &&
                device.category != IpLists.Category.HOME &&
                !input.activeBindings.containsKey(alert.clientId) && !input.notifiedToday.contains(alert.clientId)
            when {
                !eligible -> Unit // vanished, became HOME, got bound, already notified, or legacy UNKNOWN: no alert
                alert.firstSeen + delay <= input.now -> due += alert
                else -> pending += alert.copy(deadline = alert.firstSeen + delay)
            }
        }
        // New connection cycles: live WATCH devices, unbound, unnotified, not already pending.
        val carried = (pending + due).map { it.clientId }.toSet()
        pending += live.filter { device ->
            device.category == IpLists.Category.WATCH &&
                !input.activeBindings.containsKey(device.clientId) && !input.notifiedToday.contains(device.clientId) &&
                device.clientId !in carried
        }.map { PendingAlert(it.clientId, input.now, input.now + delay, it.category) }
        return EvalResult(due, pending)
    }

    /** Nearest future instant the coordinator must be woken for, or null. */
    fun nextWakeup(now: Long, pending: List<PendingAlert>, summaryMinute: Int?): Long? =
        (pending.map { it.deadline } + listOfNotNull(nextDaily(now, summaryMinute))).filter { it > now }.minOrNull()

    /** Daily summary split (spec 16/20). HOME devices never reach here. */
    data class Summary(val subscribed: List<DayDevice>, val unconfirmed: List<DayDevice>) {
        val empty: Boolean get() = subscribed.isEmpty() && unconfirmed.isEmpty()
    }

    /**
     * SUBSCRIBED = bound (deviceClientId) to a session whose state is ACTIVE or
     * PAUSED - existing project states are reused, never redefined (spec 19).
     * Anything else needs manual confirmation, not blame (spec 20).
     */
    fun summary(history: List<DayDevice>, activeBindings: Map<Long, String>): Summary =
        Summary(history.filter { activeBindings.containsKey(it.clientId) }, history.filterNot { activeBindings.containsKey(it.clientId) })
}
