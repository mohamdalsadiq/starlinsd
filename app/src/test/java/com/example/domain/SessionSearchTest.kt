package com.example.domain

import com.example.db.Session
import org.junit.Assert.*
import org.junit.Test

class SessionSearchTest {
    private val now = 1789390800000L
    private fun session(id: String, minutes: Int, reference: String = id) = Session(id, "مشترك $id", "ساعة",
        now, now, minutes * Rules.MINUTE, amount = 50000, cashEquivalent = 50000,
        payment = "CASH", premiumBps = 2500, home = false, grace = 30 * Rules.MINUTE, reference = reference)
    @Test fun arabicDigitsAndReferenceBracketsFindSameSubscriber() {
        val rows = listOf(session("a", 30, "12"), session("b", 20, "2"))
        listOf("١٢", "[12]", "#۱۲").forEach { query ->
            assertEquals(listOf(rows.first()), SessionSearch.filter(rows, query, "ALL", null, now))
        }
    }
    @Test fun nearDeadlineExcludesPausedExpiredAndHouseholdAndOrdersUrgentFirst() {
        val rows = listOf(session("late", 40), session("soon", 8), session("first", 2),
            session("home", 1).copy(home = true), session("paused", 1).copy(state = "PAUSED"), session("expired", 0))
        assertEquals(listOf("first", "soon"), SessionSearch.filter(rows, "", "SOON", null, now).map { it.id })
        assertEquals("late", SessionSearch.filter(rows, "", "ALL", "late", now).first().id)
        assertEquals(6, rows.size)
        assertEquals("ACTIVE", rows.first().state)
    }
}
