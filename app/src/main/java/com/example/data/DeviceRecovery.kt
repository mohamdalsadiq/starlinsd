package com.example.data

import com.example.db.Session
import com.example.domain.Rules

/**
 * Phase 4 password-change recovery (§40 report point J). Pure rules only:
 * binding and resuming live in SubscriptionRepository.bindDevice/changeState,
 * and the screen lives in ManagerApp.
 *
 * Scenario: after a Starlink Wi-Fi password change a subscribed device may come
 * back with a NEW router clientId, so its old binding no longer matches a live
 * device and the tracker can neither pause nor resume it. This engine lists the
 * affected subscriptions and the live candidate devices; the operator pairs them
 * by eye. Identity stays clientId internally (bindDevice rule) — the UI shows
 * only name + last IP octets + end time. Recovery never creates a subscription,
 * never adds time, never books revenue, never changes the amount: it re-binds
 * the existing session and lets the existing PAUSE/RESUME machinery continue it.
 */
object DeviceRecovery {
    /** One subscription whose bound device is not in the last successful snapshot. */
    data class Candidate(
        val sessionId: String,
        val name: String,
        val reference: String,
        val state: String,
        val remaining: Long,
        /** Actual end instant: for ACTIVE the running clock's end; for PAUSED the projected end if resumed now. */
        val endAt: Long,
        /** Display-only match helpers (never the primary identity). */
        val ip: String,
        val deviceName: String,
    )

    /** One live device available for re-linking. */
    data class Option(val clientId: Long, val name: String, val ip: String, val mac: String)

    /** One recovery pass: everything the screen renders in a single read. */
    data class Board(val snapshotOk: Boolean, val candidates: List<Candidate>, val options: List<Option>)

    /** Last octets of an IP for the visual "…145" match (display only). */
    fun ipTail(ip: String): String = ip.substringAfterLast('.', ip)

    /**
     * Subscriptions needing recovery: bound, non-home, still ACTIVE or PAUSED,
     * whose device is absent from the last successful snapshot. A failed snapshot
     * is represented by an empty [liveClientIds] ONLY when the caller knows the
     * read failed — callers must not pass empty for a successful "nothing live"
     * answer; they pass null live ids? No: pass the actual set and let
     * [snapshotOk] distinguish. With [snapshotOk] = false nothing is proposed,
     * so a router read failure can never manufacture recovery candidates.
     */
    fun candidates(sessions: List<Session>, now: Long, liveClientIds: Set<Long>, snapshotOk: Boolean): List<Candidate> {
        if (!snapshotOk) return emptyList()
        return sessions.filter { s ->
            !s.home && s.state in listOf("ACTIVE", "PAUSED") && s.deviceClientId != null && s.deviceClientId !in liveClientIds
        }.map { s ->
            val remaining = Rules.remaining(s.clock(), now)
            Candidate(
                sessionId = s.id, name = s.client, reference = s.reference.ifBlank { s.id.take(8) },
                state = s.state, remaining = remaining,
                endAt = if (s.state == "ACTIVE") s.resumed + s.duration - s.served else now + remaining,
                ip = s.deviceIp, deviceName = s.deviceName,
            )
        }.sortedBy { it.endAt }
    }

    /**
     * Live devices the operator may link to: non-HOME, not currently bound to
     * another active/paused session. HOME exclusion is identity-based (clientId,
     * then MAC before promotion) with the legacy IP rows only as a pre-promotion
     * fallback. A null snapshot (failed read) yields no options; the screen
     * explains the failure instead of guessing.
     */
    fun options(snapshot: List<TrackedDevice>?, homeClientIds: Set<Long>, legacyHomeIps: Set<String>,
        boundClientIds: Set<Long>, homeMacs: Set<String> = emptySet()): List<Option> {
        if (snapshot == null) return emptyList()
        return snapshot.filter { device ->
            device.clientId !in homeClientIds &&
                (device.mac.isBlank() || device.mac !in homeMacs) &&
                device.ip !in legacyHomeIps &&
                device.clientId !in boundClientIds
        }.map { Option(it.clientId, it.name, it.ip, it.mac) }
    }

    /**
     * After re-binding, a still-PAUSED session whose new device is live must be
     * resumed immediately so tracking continues from the remaining time — the
     * existing changeState(id, "RESUME") applies it, nothing here mutates.
     */
    fun shouldResumeAfterRelink(session: Session?, deviceIsLive: Boolean): Boolean =
        session != null && !session.home && session.state == "PAUSED" && deviceIsLive
}
