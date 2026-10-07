package com.example.data

import com.example.db.Session

/**
 * Pure session-state transitions from a successful CLIENTS snapshot.
 *
 * Identity: [Session.deviceClientId] (the router client id) is the only link. IP and MAC
 * changes never detach a session or change behavior.
 *
 * Safety rules (task spec 14/26/27 + identity reconciliation):
 * - Only a SUCCESSFUL snapshot with no matching client counts as a disappearance.
 *   A failed/exception CLIENTS query never pauses anything: the caller passes null and
 *   sessions are left untouched.
 * - Tracking applies only to non-home sessions in ACTIVE or PAUSED with a bound
 *   deviceClientId. HOME exclusion is IDENTITY-based: a session whose bound clientId
 *   (or, before promotion, whose currently held IP) is HOME-classified is never
 *   tracked. A DHCP IP change alone can therefore never pause a subscription.
 * - Transitions are idempotent: no PAUSE for an already-PAUSED session, no RESUME for an
 *   already-ACTIVE one. Re-running the same snapshot yields no repeated mutations.
 * - ENDED/CANCELLED sessions (including daily-close victims) are never resumed.
 */
object DeviceTracker {
    /** Decisions a caller must apply in order, via SubscriptionRepository.changeState(). */
    data class Plan(val pause: List<Long> = emptyList(), val resume: List<Long> = emptyList())

    /**
     * [snapshot] is null when the CLIENTS query itself failed - never a pause trigger.
     * An empty list (successful snapshot with no clients) does count as all-gone.
     *
     * [homeClientIds] excludes sessions whose BOUND DEVICE is HOME by identity
     * (clientId, or MAC before promotion). [legacyHomeIps] is the limited fallback for
     * pre-promotion devices: only the IP the session's device HELD AT PAUSE TIME is
     * checked, never the current snapshot IP, so an IP change can never pause a
     * subscribed session.
     */
    fun compare(sessions: List<Session>, snapshot: List<TrackedDevice>?, homeClientIds: Set<Long> = emptySet(),
        legacyHomeIps: Set<String> = emptySet()): Plan {
        if (snapshot == null) return Plan()
        val live = snapshot.map { it.clientId }.toSet()
        val tracked = sessions.filter { !it.home &&
            (it.deviceClientId == null || it.deviceClientId !in homeClientIds) &&
            (it.deviceIp.isEmpty() || it.deviceIp !in legacyHomeIps) &&
            it.state in listOf("ACTIVE", "PAUSED") }
        val pause = tracked.mapNotNull { s -> s.deviceClientId?.takeIf { s.state == "ACTIVE" && it !in live } }
        val resume = tracked.mapNotNull { s -> s.deviceClientId?.takeIf { s.state == "PAUSED" && it in live } }
        return Plan(pause, resume)
    }
}
