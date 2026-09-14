package com.example.domain

import com.example.db.Session

/** Presentation-only filtering; never mutates a session or its clock. */
object SessionSearch {
    fun filter(sessions: List<Session>, query: String, filter: String, requested: String?, now: Long): List<Session> {
        val text = Money.normalize(query.trim()).removePrefix("#").removeSurrounding("[", "]")
        return sessions.asSequence().filterNot { it.home }.filter { session ->
            (filter == "ALL" || session.state == filter || filter == "SOON" && session.state == "ACTIVE" && Rules.remaining(session.clock(), now) in 1..(10 * Rules.MINUTE)) &&
                (Money.normalize(session.client).contains(text, true) || Money.normalize(session.plan).contains(text, true) || Money.normalize(session.reference).contains(text, true))
        }.sortedWith(compareByDescending<Session> { it.id == requested }.thenBy { if (it.state == "ACTIVE") Rules.remaining(it.clock(), now) else Long.MAX_VALUE }.thenByDescending { it.started }).toList()
    }
}
