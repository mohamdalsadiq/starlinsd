package com.example.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The fixed 18:00 → 18:00 business day (§2, §6).
 *
 * The day's subscriptions close automatically at exactly 18:00 (device/Sudan
 * time): ACTIVE/PAUSED sessions are force-ended at the cutoff instant — frozen,
 * never deleted — and the paid-subscription ledger for that day is sealed with
 * a stored [com.example.db.DailyCutoff] summary. Anything sold at or after
 * 18:00 belongs to the NEXT business day and starts it with a fresh ledger.
 *
 * A business day is labelled by the calendar date of its CLOSING cutoff, so
 * key(2026-10-05 19:00) == "2026-10-06": the post-cutoff sale belongs to the
 * next day, ends at the next day's 18:00, and is never swept into the closed
 * day's cutoff. Pure and unit-tested; the device timezone is used everywhere,
 * consistent with [Revenue.day].
 */
object BusinessDay {
    /** The fixed daily cutoff hour, 18:00. Not configurable: one rule for every day. */
    const val CUTOFF_HOUR = 18

    /** Shift that maps the 18:00 boundary onto midnight for key computation. */
    private const val SHIFT_MS = (24 - CUTOFF_HOUR) * 3_600_000L

    private fun keyFormat(zone: TimeZone) =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }

    /**
     * The "yyyy-MM-dd" business-day key for [at]: the date whose 18:00 cutoff
     * closes the day [at] belongs to. Sales before 18:00 keep the calendar
     * date; sales at/after 18:00 move to the next date.
     */
    fun key(at: Long, zone: TimeZone = TimeZone.getDefault()): String =
        keyFormat(zone).format(Date(at + SHIFT_MS))

    /** The exact 18:00 instant that closes business day [key]. */
    fun cutoffInstant(key: String, zone: TimeZone = TimeZone.getDefault()): Long {
        val midnight = keyFormat(zone).apply { isLenient = false }.parse(key)?.time
            ?: throw IllegalArgumentException("مفتاح يوم غير صالح: $key")
        return midnight + CUTOFF_HOUR * 3_600_000L
    }

    /**
     * The business-day key whose 18:00 cutoff most recently passed at [now]
     * (the day [applyDailyCutoff][com.example.data.SubscriptionRepository.applyDailyCutoff]
     * must close next).
     */
    fun lastCutoffKey(now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val hour = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val base = if (hour >= CUTOFF_HOUR) now else now - 24 * 3_600_000L
        return keyFormat(zone).format(Date(base))
    }

    /** The next 18:00 cutoff strictly after [now] — the single scheduler's alarm target. */
    fun nextCutoffInstant(now: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        cutoffInstant(lastCutoffKey(now, zone), zone) + 24 * 3_600_000L

    /** True once [now] has reached the closing 18:00 of business day [key]. */
    fun isPastCutoff(key: String, now: Long, zone: TimeZone = TimeZone.getDefault()): Boolean =
        now >= cutoffInstant(key, zone)

    /**
     * The calendar date whose evening belongs to business day [key]: the key
     * minus one day. Business day K runs (K−1) 18:00 → K 18:00, and the
     * operator's evening review window opens after 18:00 — so the device
     * history, unregistered aggregate, and dismissed keys for business day K
     * live under calendar day K−1 (which is also how every existing
     * unregistered summary row is keyed: no data migration, no accounting
     * change).
     */
    fun eveningCalendarKey(key: String, zone: TimeZone = TimeZone.getDefault()): String {
        val midnight = keyFormat(zone).apply { isLenient = false }.parse(key)?.time
            ?: throw IllegalArgumentException("مفتاح يوم غير صالح: $key")
        return keyFormat(zone).format(Date(midnight - 24 * 3_600_000L))
    }
}
