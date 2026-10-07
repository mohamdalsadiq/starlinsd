package com.example.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * §2/§6: the fixed 18:00 → 18:00 business day. A sale before 18:00 keeps the
 * calendar date; a sale at/after 18:00 belongs to the NEXT day and ends at the
 * next day's 18:00 — never swept into the closed day.
 */
class BusinessDayTest {
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test fun saleBeforeCutoffKeepsCalendarDate() {
        assertEquals("2026-10-05", BusinessDay.key(at(5, 10)))
        assertEquals("2026-10-05", BusinessDay.key(at(5, 17, 59)))
    }

    @Test fun saleAtOrAfterCutoffBelongsToNextDay() {
        assertEquals("2026-10-06", BusinessDay.key(at(5, 18)))
        assertEquals("2026-10-06", BusinessDay.key(at(5, 23, 30)))
        assertEquals("2026-10-06", BusinessDay.key(at(6, 0, 30)))
        assertEquals("2026-10-06", BusinessDay.key(at(6, 17, 59)))
    }

    @Test fun cutoffInstantIs1800OfItsKey() {
        assertEquals(at(5, 18), BusinessDay.cutoffInstant("2026-10-05"))
    }

    @Test fun cutoffInstantBelongsToNextBusinessDay() {
        // The 18:00 instant itself is already the next day's business: a sale
        // exactly at cutoff is a next-day sale.
        assertEquals("2026-10-06", BusinessDay.key(BusinessDay.cutoffInstant("2026-10-05")))
    }

    @Test fun lastCutoffKeyIsTheMostRecentlyPassed1800() {
        assertEquals("2026-10-06", BusinessDay.lastCutoffKey(at(6, 19)))
        assertEquals("2026-10-06", BusinessDay.lastCutoffKey(at(6, 18)))
        assertEquals("2026-10-05", BusinessDay.lastCutoffKey(at(6, 10)))
        assertEquals("2026-10-05", BusinessDay.lastCutoffKey(at(6, 0, 5)))
    }

    @Test fun nextCutoffInstantIsTheUpcoming1800() {
        assertEquals(at(7, 18), BusinessDay.nextCutoffInstant(at(6, 19)))
        assertEquals(at(6, 18), BusinessDay.nextCutoffInstant(at(6, 10)))
        assertEquals(at(6, 18), BusinessDay.nextCutoffInstant(at(5, 18, 1)))
    }

    @Test fun isPastCutoff() {
        assertTrue(BusinessDay.isPastCutoff("2026-10-05", at(5, 18)))
        assertTrue(BusinessDay.isPastCutoff("2026-10-05", at(5, 20)))
        assertFalse(BusinessDay.isPastCutoff("2026-10-05", at(5, 17, 59)))
    }

    @Test fun cutoffHourIsFixedAt1800() {
        assertEquals(18, BusinessDay.CUTOFF_HOUR)
    }

    @Test fun eveningCalendarKeyIsBusinessDayMinusOne() {
        // Business day K runs (K−1) 18:00 → K 18:00: its evening belongs to
        // calendar day K−1, where the device history and unregistered rows live.
        assertEquals("2026-10-05", BusinessDay.eveningCalendarKey("2026-10-06"))
        assertEquals("2026-10-04", BusinessDay.eveningCalendarKey("2026-10-05"))
        // Round-trip: the evening's 21:00 sale maps back to the business day.
        assertEquals("2026-10-06", BusinessDay.key(at(5, 21)))
    }
}
