package com.example.data

import com.example.db.Session

/**
 * Pure session-state transitions from a successful CLIENTS snapshot.
 *
 * Identity: [Session.deviceClientId] (the router client id) is the only link. IP and MAC
 * changes never detach a session or change behavior.
 *
 * Safety rules (task spec 14/26/27):
 * - Only a SUCCESSFUL snapshot with no matching client counts as a disappearance.
 *   A failed/exception CLIENTS query never pauses anything: the caller passes null and
 *   sessions are left untouched.
 * - Tracking applies only to non-home sessions in ACTIVE or PAUSED with a bound
 *   deviceClientId. Sessions whose bound device classifies as HOME are never tracked
 *   (HOME precedence means they were never valid candidates in the first place).
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
     * [homeIps] excludes sessions whose stored device IP is now on the home list: HOME
     * precedence means a family device is never tracked, even when its IP was added to
     * the list after the session was bound.
     */
    fun compare(sessions: List<Session>, snapshot: List<TrackedDevice>?, homeIps: Set<String> = emptySet()): Plan {
        if (snapshot == null) return Plan()
        val live = snapshot.map { it.clientId }.toSet()
        val tracked = sessions.filter { !it.home && it.deviceIp !in homeIps && it.state in listOf("ACTIVE", "PAUSED") }
        val pause = tracked.mapNotNull { s -> s.deviceClientId?.takeIf { s.state == "ACTIVE" && it !in live } }
        val resume = tracked.mapNotNull { s -> s.deviceClientId?.takeIf { s.state == "PAUSED" && it in live } }
        return Plan(pause, resume)
    }
}
