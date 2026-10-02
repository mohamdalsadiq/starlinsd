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

    /**
     * Financial candidate split for the daily confirmation screen (pure, testable).
     *
     * Subscription chain: history device clientId → session deviceClientId, for
     * non-home sessions in ACTIVE/PAUSED/ENDED. Anything else splits by category:
     * - SUBSCRIBED: bound to a live (or today-ended) session → audit/reference
     *   only, never new ledger money.
     * - UNREGISTERED: UNKNOWN devices with no binding → the only group whose
     *   confirmed amounts may become ledger money.
     * - HOME: tracked for identity, never enters financial confirmation or totals.
     * - WATCH: stays in device management with its own section, never enters
     *   financial confirmation or totals.
     *
     * Identity is always the clientId; IP/MAC/name are display only here.
     */
    data class ConfirmationGroups(
        val subscribed: List<Pair<DeviceAlerts.DayDevice, com.example.db.Session>>,
        val unregistered: List<DeviceAlerts.DayDevice>,
        val home: List<DeviceAlerts.DayDevice>,
        val watch: List<DeviceAlerts.DayDevice>,
    )

    /**
     * Splits one day's device history into the financial confirmation groups.
     * [dayKey] is the history day being confirmed: the subscriber stamp "[N]"
     * is only unique within its day (references reset daily), so the stamp
     * fallback matches ONLY sessions started that day — yesterday's "[2]"
     * must never claim today's subscriber 2. clientId and real-MAC matches are
     * physical identity and need no day scoping.
     */
    fun confirmationGroups(
        devices: List<DeviceAlerts.DayDevice>,
        sessions: List<com.example.db.Session>,
        dayKey: String,
    ): ConfirmationGroups {
        // HOME devices never enter financial confirmation, even if a session
        // somehow references their clientId (mergeSnapshot already keeps them
        // out of history; this is defense in depth).
        val billable = devices.filter { it.category != IpLists.Category.HOME }
        // Session↔device matching. clientId is the primary key, but the router
        // reassigns it on reconnect/lease churn — so a subscribed device seen
        // under a NEW clientId must still match its session. Two churn-proof
        // fallbacks, both deterministic:
        //  1. a REAL (unmasked) MAC stored at bind time — masked/blank never match;
        //  2. the subscriber stamp "[N]": unique per day, embedded in the router
        //     device name by the sale itself ("🌹٠٢:٢٨م [2]🌹 M05"), matched only
        //     against sessions started on [dayKey].
        // Without this, a subscribed device leaked into the unregistered list
        // under its new id while its session pointed at the old one.
        // Index the matchable sessions once: clientId is the primary key, then a
        // real (unmasked) MAC, then the day-stamped reference — the same
        // precedence the per-device scan implemented, but usableMac/dayKey are
        // parsed once per session instead of once per device × session.
        val candidates = sessions.filter { !it.home && it.state in listOf("ACTIVE", "PAUSED", "ENDED") }
        val byClientId = HashMap<Long, com.example.db.Session>()
        val byMac = HashMap<String, com.example.db.Session>()
        val byStamp = HashMap<String, com.example.db.Session>()
        for (session in candidates) {
            session.deviceClientId?.let { if (it !in byClientId) byClientId[it] = session }
            DeviceAlerts.usableMac(session.deviceMac)?.let { if (it !in byMac) byMac[it] = session }
            if (session.reference.isNotBlank() && DeviceAlerts.dayKey(session.started) == dayKey && session.reference !in byStamp) {
                byStamp[session.reference] = session
            }
        }
        val subscribed = billable.mapNotNull { device ->
            val match = byClientId[device.clientId]
                ?: DeviceAlerts.usableMac(device.mac)?.let { byMac[it] }
                ?: DeviceAlerts.stampOf(device.name)?.let { byStamp[it] }
            match?.let { device to it }
        }
        val subscribedIds = subscribed.map { it.first.clientId }.toSet()
        val others = billable.filter { it.clientId !in subscribedIds }
        return ConfirmationGroups(
            subscribed = subscribed,
            unregistered = others.filter { it.category == IpLists.Category.UNKNOWN },
            home = devices.filter { it.category == IpLists.Category.HOME },
            watch = others.filter { it.category == IpLists.Category.WATCH },
        )
    }

    /** Validates per-device entries: known payment, no duplicate devices, positive amounts. */
    fun validateDeviceAmounts(payment: String, entries: List<Pair<Long, Long>>) {        require(payment == "CASH" || payment == "BANK") { "اختر طريقة الدفع" }
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
     *
     * Only CONFIRMED UNREGISTERED amounts create reconciliation revenue (spec 14):
     * registered (subscribed) income already exists as session revenue in the
     * finance ledger, and writing it here again would double-count it. The
     * per-device confirmation rows keep the subscribed amounts for audit, but
     * they contribute zero ledger money.
     */
    fun ledgerRow(dayKey: String, devices: List<com.example.db.DailyDeviceConfirmation>, at: Long, premiumBps: Int, payment: String): ManualSale? {
        val unregistered = devices.filter { it.sessionId.isBlank() }
        if (unregistered.isEmpty()) return null
        val total = unregistered.sumOf { it.confirmed }
        val method = if (payment == "BANK") "BANK" else "CASH"
        val rowTime = try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            format.isLenient = false
            format.parse(dayKey)!!.time + 12 * 60 * 60_000L
        } catch (_: Exception) { Revenue.day(at) + 12 * 60 * 60_000L }
        val count = unregistered.count { it.confirmed > 0 }
        return ManualSale(
            id = dailyRowId(dayKey), at = rowTime, count = count, unitPrice = total,
            amount = total, cashEquivalent = if (method == "BANK") Money.bankToCash(total, premiumBps) else total,
            payment = method, premiumBps = premiumBps)
    }

    /** Confirmed device total for [dayKey] (sum of per-device confirmed amounts). */
    fun confirmedDeviceTotal(devices: List<com.example.db.DailyDeviceConfirmation>): Long =
        devices.sumOf { it.confirmed }

    // ---- Aggregate unregistered confirmation (deterministic binding v2) ----

    /**
     * Qualified unregistered devices: the confirmationGroups unregistered split,
     * further gated by DWELL time — a device only counts after it has been seen
     * for at least [delayMinutes] (lastSeen - firstSeen). Passing phones that
     * briefly appear never reach the list; evening customers who stay do.
     * A device that later gets a subscription is already excluded upstream by
     * the binding match in [confirmationGroups].
     */
    /**
     * Devices that dwelled [delayMinutes] without a subscription AND inside the
     * owner's unregistered window. Dwell only accrues from [windowStartMinute]
     * (minutes since midnight, e.g. 1080 = 18:00): a device first seen before
     * the window starts counting at the window start; a device gone before the
     * window never qualifies. [dismissedKeys] are deviceKeys the owner removed
     * by hand (e.g. a free short connection that must not be billed).
     */
    fun qualifiedUnregistered(
        devices: List<DeviceAlerts.DayDevice>,
        sessions: List<com.example.db.Session>,
        dayKey: String,
        delayMinutes: Int,
        now: Long,
        windowStartMinute: Int = 0,
        dismissedKeys: Set<String> = emptySet(),
    ): List<DeviceAlerts.DayDevice> {
        val dwellMs = delayMinutes.coerceAtLeast(1) * 60_000L
        val windowStart = DeviceAlerts.dayStart(now) + windowStartMinute.coerceIn(0, 1439) * 60_000L
        return confirmationGroups(devices, sessions, dayKey).unregistered
            .filter { device -> DeviceAlerts.deviceKey(device.mac, device.clientId) !in dismissedKeys }
            .filter { device ->
                // Dwell is measured inside the window only, up to now: a device
                // present since the morning starts accruing at the window's
                // opening, not at its first sighting.
                val effectiveFirst = maxOf(device.firstSeen, windowStart)
                minOf(now, device.lastSeen) - effectiveFirst >= dwellMs
            }
    }

    /**
     * Net unregistered revenue: (deviceCount − unpaidCount) × tariff.
     * unpaidCount is clamped to [0, deviceCount]; all amounts in minor units.
     */
    fun unregisteredNet(deviceCount: Int, tariff: Long, unpaidCount: Int): Long {
        val paid = (deviceCount - unpaidCount.coerceIn(0, deviceCount)).coerceAtLeast(0)
        return Math.multiplyExact(paid.toLong(), tariff.coerceAtLeast(0))
    }

    /** Validates an aggregate unregistered confirmation before it is saved. */
    fun validateUnregisteredSummary(deviceCount: Int, tariff: Long, unpaidCount: Int, payment: String) {
        require(payment == "CASH" || payment == "BANK") { "اختر طريقة الدفع" }
        require(deviceCount in 0..100000) { "عدد الأجهزة غير صالح" }
        require(tariff in 1..99999999999) { "التعرفة غير صالحة" }
        require(unpaidCount in 0..deviceCount) { "عدد غير الدافعين يجب أن يكون بين صفر وعدد الأجهزة" }
    }

    /**
     * The day's single ledger row for the aggregate unregistered summary,
     * REBUILT on every save under the same deterministic id [dailyRowId] so
     * re-confirming rewrites it in place. `at` is pinned inside the event day.
     * Returns null when the net total is zero (no row should exist).
     */
    fun summaryLedgerRow(
        dayKey: String,
        summary: com.example.db.UnregisteredDaySummary,
        at: Long,
        premiumBps: Int,
    ): com.example.db.ManualSale? {
        if (summary.netTotal <= 0) return null
        val method = if (summary.payment == "BANK") "BANK" else "CASH"
        val rowTime = try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            format.isLenient = false
            format.parse(dayKey)!!.time + 12 * 60 * 60_000L
        } catch (_: Exception) { Revenue.day(at) + 12 * 60 * 60_000L }
        return com.example.db.ManualSale(
            id = dailyRowId(dayKey), at = rowTime, count = summary.deviceCount - summary.unpaidCount,
            unitPrice = summary.tariff, amount = summary.netTotal,
            cashEquivalent = if (method == "BANK") Money.bankToCash(summary.netTotal, premiumBps) else summary.netTotal,
            payment = method, premiumBps = premiumBps)
    }
}
