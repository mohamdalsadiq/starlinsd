package com.example.data

import com.example.db.ManualSale
import com.example.domain.Money
import com.example.domain.Revenue

/**
 * Phase 4 daily device confirmation math (§40 report point H). Pure rules only:
 * storage, Room, and UI live in DeviceAlertsCoordinator, SubscriptionRepository,
 * and ManagerApp.
 *
 * Model: the operator reviews one EVENT DAY's unregistered devices and records
 * grouped amounts (e.g. 500×1 + 1000×2). Each group becomes exactly one row in
 * the existing [ManualSale] table, keyed by a deterministic upsert id
 * [upsertId] = "manual:rev-<yyyy-MM-dd>-<index>" so:
 *  - reopening the review and confirming again rewrites the SAME rows (no
 *    double-counting),
 *  - editing 2500→3000 replaces the stored amount instead of adding a second one,
 *  - the event day owns the money because the row's `at` (and hence
 *    Finance/Revenue day bucketing) is set to that day, never to confirm time.
 * Registered (shortcut) devices never get rows: their revenue already exists as
 * `session:*` ledger entries, and HOME devices never reach this screen.
 */
object DailyReconciliation {
    /** Prefix of the deterministic per-day review row ids inside manual_sales. */
    const val SOURCE_PREFIX = "rev-"
    const val MAX_GROUPS = 20

    /** Fixed manual-sales id for [index]-th group of [dayKey]; stable across sessions. */
    fun upsertId(dayKey: String, index: Int): String = "$SOURCE_PREFIX$dayKey:$index"

    /** The event day a stored review row belongs to (null when not a review row). */
    fun dayKeyOf(saleId: String): String? =
        if (saleId.startsWith(SOURCE_PREFIX)) saleId.removePrefix(SOURCE_PREFIX).substringBeforeLast(':') else null

    /** One grouped line: [count] devices at [unitPrice] (minor units). */
    data class Group(val count: Int, val unitPrice: Long) {
        val total: Long get() = Math.multiplyExact(count.toLong(), unitPrice)
    }

    /** Max devices one day's review may allocate across all groups. */
    fun maxCount(unregistered: Int): Int = unregistered.coerceAtLeast(1)

    /**
     * Validates a full day's groups against the day's unregistered devices:
     * at least one group, known payment, positive prices, and total device count
     * within [maxCount] (spec 21: cannot confirm more devices than were seen).
     * Throws IllegalArgumentException with an operator-facing Arabic message.
     */
    fun validate(groups: List<Group>, unregisteredCount: Int, payment: String) {
        require(groups.isNotEmpty()) { "أضف مبلغًا واحدًا على الأقل" }
        require(groups.size <= MAX_GROUPS) { "الحد الأقصى $MAX_GROUPS مجموعات لليوم الواحد" }
        require(payment == "CASH" || payment == "BANK") { "اختر طريقة الدفع" }
        groups.forEach { group ->
            require(group.count in 1..100000 && group.unitPrice in 1..99999999999) { "أدخل عددًا وسعرًا موجبين" }
        }
        val allocated = groups.sumOf { it.count }
        require(allocated <= maxCount(unregisteredCount)) {
            "عدد الأجهزة المخصصة ($allocated) يتجاوز الأجهزة غير المسجلة ($unregisteredCount)"
        }
    }

    /**
     * The stored review rows for [dayKey], in id order, as the single source for
     * the screen's "already confirmed" view and for upsert rewriting.
     */
    fun rowsFor(sales: List<ManualSale>, dayKey: String): List<ManualSale> =
        sales.filter { dayKeyOf(it.id) == dayKey }.sortedBy { it.id }

    /** Total additional revenue already confirmed for [dayKey]. */
    fun confirmedTotal(sales: List<ManualSale>, dayKey: String): Long =
        rowsFor(sales, dayKey).sumOf { it.amount }

    /** Devices already allocated to confirmed groups for [dayKey]. */
    fun confirmedCount(sales: List<ManualSale>, dayKey: String): Int =
        rowsFor(sales, dayKey).sumOf { it.count }

    /**
     * The rows to write for [dayKey]: one row per group with a fixed [at] inside
     * the event day (day start + 12h keeps the money inside the day in every
     * zone offset the phone uses). Superseded rows (when the new group list is
     * shorter) are deleted by the storage layer via [rowsFor]. [payment] follows
     * the existing addSales semantics (bank premium applied by the repository).
     */
    fun plan(dayKey: String, at: Long, groups: List<Group>, payment: String, premiumBps: Int): List<ManualSale> {
        val rowTime = Revenue.day(at) + 12 * 60 * 60_000L
        return groups.mapIndexed { index, group ->
            val amount = group.total
            ManualSale(
                id = upsertId(dayKey, index), at = rowTime, count = group.count, unitPrice = group.unitPrice,
                amount = amount, cashEquivalent = if (payment == "BANK") Money.bankToCash(amount, premiumBps) else amount,
                payment = payment, premiumBps = premiumBps
            )
        }
    }

    // ---- Per-device confirmation (device-identity-reconciliation-v1) ----

    /** The ONE deterministic manual-sales id for [dayKey]'s device-level ledger row. */
    fun dailyRowId(dayKey: String): String = "${SOURCE_PREFIX}$dayKey:devices"

    /** Validates per-device entries: known payment, no duplicate devices, positive amounts. */
    fun validateDeviceAmounts(payment: String, entries: List<Pair<Long, Long>>) {
        require(payment == "CASH" || payment == "BANK") { "اختر طريقة الدفع" }
        require(entries.size <= MAX_GROUPS * 100) { "عدد الأجهزة كبير جدًا لليوم الواحد" }
        require(entries.map { it.first }.distinct().size == entries.size) { "جهاز مكرر في نفس اليوم" }
        entries.forEach { (_, confirmed) ->
            require(confirmed in 0..99999999999) { "أدخل مبلغًا صحيحًا لكل جهاز" }
        }
    }

    /**
     * The day's single ledger row, REBUILT from the stored confirmation rows on
     * every save: editing a device amount rewrites this row in place (same id),
     * so manual_sales keeps exactly one revenue entry per confirmed day. `at` is
     * pinned INSIDE THE EVENT DAY (dayKey parsed as the device's local calendar
     * day, then +12h) so the money always lands on the reviewed day — never on
     * the day the operator happens to confirm. count = devices with money.
     * Returns null when nothing is confirmed for the day (row should not exist).
     */
    fun ledgerRow(dayKey: String, devices: List<com.example.db.DailyDeviceConfirmation>, at: Long, premiumBps: Int): ManualSale? {
        if (devices.isEmpty()) return null
        val total = devices.sumOf { it.confirmed }
        val rowTime = try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            format.isLenient = false
            format.parse(dayKey)!!.time + 12 * 60 * 60_000L
        } catch (_: Exception) { Revenue.day(at) + 12 * 60 * 60_000L }
        val count = devices.count { it.confirmed > 0 }
        return ManualSale(
            id = dailyRowId(dayKey), at = rowTime, count = count, unitPrice = total,
            amount = total, cashEquivalent = total, payment = "CASH", premiumBps = premiumBps)
    }

    /** Confirmed device total for [dayKey] (sum of per-device confirmed amounts). */
    fun confirmedDeviceTotal(devices: List<com.example.db.DailyDeviceConfirmation>): Long =
        devices.sumOf { it.confirmed }
}
