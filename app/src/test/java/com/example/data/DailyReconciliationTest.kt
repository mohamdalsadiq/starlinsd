package com.example.data

import com.example.db.ManualSale
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure math for the Phase 4 daily device confirmation (§40 report point H):
 * grouped amounts, count validation, deterministic upsert ids, event-day rows.
 * No Android runtime, no sleeps (spec 33).
 */
class DailyReconciliationTest {
    @Test
    fun groupedAmountsSumCorrectly() {
        // 500×1 + 1000×2 = 2500 (spec 12 example, minor units ×100)
        val groups = listOf(DailyReconciliation.Group(1, 50000), DailyReconciliation.Group(2, 100000))
        assertEquals(250000L, groups.sumOf { it.total })
    }

    @Test
    fun upsertIdsAreDeterministicPerDayAndIndex() {
        assertEquals("rev-2026-09-28:0", DailyReconciliation.upsertId("2026-09-28", 0))
        assertEquals("rev-2026-09-28:1", DailyReconciliation.upsertId("2026-09-28", 1))
        assertEquals("rev-2026-09-29:0", DailyReconciliation.upsertId("2026-09-29", 0))
        // Same inputs, same id: rewriting a confirmation cannot fork a second row.
        assertEquals(DailyReconciliation.upsertId("2026-09-28", 0), DailyReconciliation.upsertId("2026-09-28", 0))
    }

    @Test
    fun dayKeyOfExtractsEventDayFromRowId() {
        assertEquals("2026-09-28", DailyReconciliation.dayKeyOf("rev-2026-09-28:2"))
        assertEquals("2026-01-02", DailyReconciliation.dayKeyOf("rev-2026-01-02:0"))
        assertNull(DailyReconciliation.dayKeyOf("3f1c9e2a-1111-2222-3333-444455556666:0"))
        assertNull(DailyReconciliation.dayKeyOf("session:x"))
    }

    @Test
    fun rowsForPicksOnlyThisDaysReviewRows() {
        val rows = listOf(
            ManualSale("rev-2026-09-28:0", 100L, 1, 50000, 50000, 50000, "CASH", 2500),
            ManualSale("rev-2026-09-28:1", 100L, 2, 100000, 200000, 200000, "CASH", 2500),
            ManualSale("rev-2026-09-29:0", 200L, 1, 70000, 70000, 70000, "CASH", 2500),
            ManualSale("3f1c9e2a-1111-2222-3333-444455556666:0", 300L, 5, 1000, 5000, 5000, "CASH", 2500)
        )
        val day = DailyReconciliation.rowsFor(rows, "2026-09-28")
        assertEquals(listOf("rev-2026-09-28:0", "rev-2026-09-28:1"), day.map { it.id })
        assertEquals(250000L, DailyReconciliation.confirmedTotal(rows, "2026-09-28"))
        assertEquals(3, DailyReconciliation.confirmedCount(rows, "2026-09-28"))
    }

    @Test
    fun countCannotExceedUnregisteredDevices() {
        // 3 unregistered devices, groups try to allocate 5 → rejected (spec 21).
        try {
            DailyReconciliation.validate(listOf(DailyReconciliation.Group(1, 50000), DailyReconciliation.Group(4, 100000)), 3, "CASH")
            fail("allocating 5 devices against 3 must fail")
        } catch (expected: IllegalArgumentException) { }
        // Exactly 3 is allowed.
        DailyReconciliation.validate(listOf(DailyReconciliation.Group(1, 50000), DailyReconciliation.Group(2, 100000)), 3, "CASH")
    }

    @Test
    fun validationRejectsEmptyGroupsBadPaymentAndNonPositivePrices() {
        try { DailyReconciliation.validate(emptyList(), 3, "CASH"); fail("empty groups must fail") } catch (expected: IllegalArgumentException) { }
        try { DailyReconciliation.validate(listOf(DailyReconciliation.Group(1, 50000)), 3, "GIFT"); fail("bad payment must fail") } catch (expected: IllegalArgumentException) { }
        try { DailyReconciliation.validate(listOf(DailyReconciliation.Group(1, 0)), 3, "CASH"); fail("zero price must fail") } catch (expected: IllegalArgumentException) { }
        try { DailyReconciliation.validate(listOf(DailyReconciliation.Group(0, 50000)), 3, "CASH"); fail("zero count must fail") } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun planProducesEventDayRowsWithDeterministicIds() {
        val at = 1759000000000L // confirm moment (any time on the confirm day)
        val rows = DailyReconciliation.plan("2026-09-28", at,
            listOf(DailyReconciliation.Group(1, 50000), DailyReconciliation.Group(2, 100000)), "CASH", 2500)
        assertEquals(listOf("rev-2026-09-28:0", "rev-2026-09-28:1"), rows.map { it.id })
        // Row `at` lands inside the event day, never at confirm time (spec 22).
        assertEquals(com.example.domain.Revenue.day(at) + 12 * 60 * 60_000L, rows[0].at)
        assertEquals(50000L, rows[0].amount)
        assertEquals(200000L, rows[1].amount)
        assertEquals(3, rows.sumOf { it.count })
    }

    @Test
    fun planConvertsBankPaymentsThroughPremium() {
        val rows = DailyReconciliation.plan("2026-09-28", 1759000000000L, listOf(DailyReconciliation.Group(1, 125000)), "BANK", 2500)
        // 125000 bank at 25% premium = 100000 cash equivalent (project rule).
        assertEquals(100000L, rows[0].cashEquivalent)
        assertEquals("BANK", rows[0].payment)
    }

    private fun confirmation(deviceId: Long, sessionId: String, confirmed: Long) =
        com.example.db.DailyDeviceConfirmation("2026-09-28", deviceId, sessionId, 0L, confirmed, "CASH", 2500, 0L)

    /** Subscribed amounts are audit-only: they never create ledger money (spec 14). */
    @Test
    fun ledgerRowExcludesRegisteredAmounts() {
        val row = DailyReconciliation.ledgerRow("2026-09-28", listOf(
            confirmation(1, "session-a", 50000L),   // subscribed: registered income already exists
            confirmation(2, "", 100000L),          // unregistered: creates reconciliation revenue
        ), 1759000000000L, 2500, "CASH")
        assertNotNull(row)
        assertEquals(100000L, row!!.amount)
        assertEquals(1, row.count)
    }

    /** A day with only subscribed devices writes no ledger row at all. */
    @Test
    fun ledgerRowIsNullWhenOnlySubscribedDevices() {
        assertNull(DailyReconciliation.ledgerRow("2026-09-28",
            listOf(confirmation(1, "session-a", 50000L)), 1759000000000L, 2500, "CASH"))
    }

    /** CASH keeps cashEquivalent == amount (no premium haircut on cash). */
    @Test
    fun ledgerRowCashKeepsFullValue() {
        val row = DailyReconciliation.ledgerRow("2026-09-28",
            listOf(confirmation(2, "", 100000L)), 1759000000000L, 2500, "CASH")
        assertEquals("CASH", row!!.payment)
        assertEquals(100000L, row.cashEquivalent)
    }

    /** BANK converts through the premium, like every other manual sale. */
    @Test
    fun ledgerRowBankConvertsThroughPremium() {
        val row = DailyReconciliation.ledgerRow("2026-09-28",
            listOf(confirmation(2, "", 125000L)), 1759000000000L, 2500, "BANK")
        assertEquals("BANK", row!!.payment)
        assertEquals(100000L, row.cashEquivalent)
    }

    // ---- confirmationGroups: HOME/WATCH never enter financial confirmation ----

    private fun dayDevice(id: Long, category: IpLists.Category) =
        DeviceAlerts.DayDevice(id, "d$id", "192.168.1.$id", "aa:bb:cc:dd:ee:$id", category, 1000L, 2000L)

    private fun session(id: String, clientId: Long?, home: Boolean = false, state: String = "ACTIVE") =
        com.example.db.Session(id = id, client = "c", plan = "p", started = 1000L, resumed = 1000L,
            duration = 3600000L, amount = 50000L, cashEquivalent = 50000L, payment = "CASH",
            premiumBps = 2500, home = home, grace = 300000L, state = state, deviceClientId = clientId)

    @Test
    fun confirmationGroupsSplitByIdentityAndCategory() {
        val devices = listOf(
            dayDevice(101, IpLists.Category.UNKNOWN), // bound below
            dayDevice(102, IpLists.Category.UNKNOWN), // free
            dayDevice(103, IpLists.Category.HOME),
            dayDevice(104, IpLists.Category.WATCH),
        )
        val sessions = listOf(session("s1", 101L))
        val groups = DailyReconciliation.confirmationGroups(devices, sessions)
        assertEquals(listOf(101L), groups.subscribed.map { it.first.clientId })
        assertEquals("s1", groups.subscribed.single().second.id)
        assertEquals(listOf(102L), groups.unregistered.map { it.clientId })
        assertEquals(listOf(103L), groups.home.map { it.clientId })
        assertEquals(listOf(104L), groups.watch.map { it.clientId })
    }

    @Test
    fun watchDevicesNeverEnterFinancialGroups() {
        // A WATCH device with no session stays in device management only: it is
        // neither subscribed nor an unregistered financial candidate.
        val groups = DailyReconciliation.confirmationGroups(
            listOf(dayDevice(104, IpLists.Category.WATCH)), emptyList())
        assertTrue(groups.subscribed.isEmpty())
        assertTrue(groups.unregistered.isEmpty())
        assertEquals(listOf(104L), groups.watch.map { it.clientId })
    }

    @Test
    fun homeDevicesNeverEnterFinancialGroups() {
        val groups = DailyReconciliation.confirmationGroups(
            listOf(dayDevice(103, IpLists.Category.HOME)), listOf(session("s1", 103L)))
        assertTrue(groups.subscribed.isEmpty())
        assertTrue(groups.unregistered.isEmpty())
        assertEquals(listOf(103L), groups.home.map { it.clientId })
    }

    @Test
    fun endedSessionStillCountsAsSubscribed() {
        // A session that ended today keeps its device in the subscribed
        // (audit-only) group — its money already exists as session revenue.
        val groups = DailyReconciliation.confirmationGroups(
            listOf(dayDevice(101, IpLists.Category.UNKNOWN)), listOf(session("s1", 101L, state = "ENDED")))
        assertEquals(listOf(101L), groups.subscribed.map { it.first.clientId })
        assertTrue(groups.unregistered.isEmpty())
    }

    // ---- Aggregate unregistered confirmation: dwell gate + tariff math ----

    private fun dwellDevice(id: Long, firstSeen: Long, lastSeen: Long) =
        DeviceAlerts.DayDevice(id, "d$id", "192.168.1.$id", "aa:bb:cc:dd:ee:$id",
            IpLists.Category.UNKNOWN, firstSeen, lastSeen)

    @Test
    fun qualifiedUnregisteredRequiresDwell() {
        val now = 10_000_000L
        val devices = listOf(
            dwellDevice(1, now - 6 * 60_000L, now), // 6 min: qualifies
            dwellDevice(2, now - 5 * 60_000L, now), // exactly 5 min: qualifies
            dwellDevice(3, now - 4 * 60_000L, now), // 4 min: passing phone, excluded
            dwellDevice(4, now - 30 * 60_000L, now), // bound below: subscribed, never unregistered
        )
        val sessions = listOf(session("s4", 4L))
        val qualified = DailyReconciliation.qualifiedUnregistered(devices, sessions, 5, now)
        assertEquals(listOf(1L, 2L), qualified.map { it.clientId })
    }

    @Test
    fun qualifiedUnregisteredFollowsTheConfiguredDelay() {
        val now = 10_000_000L
        val devices = listOf(dwellDevice(1, now - 4 * 60_000L, now))
        assertEquals(listOf(1L), DailyReconciliation.qualifiedUnregistered(devices, emptyList(), 3, now).map { it.clientId })
        assertTrue(DailyReconciliation.qualifiedUnregistered(devices, emptyList(), 5, now).isEmpty())
    }

    @Test
    fun unregisteredNetMath() {
        // 3 devices × 500 − 1 unpaid = 1000 (minor units ×100)
        assertEquals(100000L, DailyReconciliation.unregisteredNet(3, 50000L, 1))
        assertEquals(0L, DailyReconciliation.unregisteredNet(0, 50000L, 0))
        assertEquals(0L, DailyReconciliation.unregisteredNet(3, 50000L, 3))
        assertEquals(0L, DailyReconciliation.unregisteredNet(2, 50000L, 9)) // unpaid clamped
    }

    @Test
    fun validateUnregisteredSummaryRejectsBadInputs() {
        DailyReconciliation.validateUnregisteredSummary(3, 50000L, 1, "CASH") // ok
        DailyReconciliation.validateUnregisteredSummary(0, 50000L, 0, "BANK") // empty day ok
        try { DailyReconciliation.validateUnregisteredSummary(3, 50000L, 4, "CASH"); fail("unpaid > count") }
        catch (expected: IllegalArgumentException) { }
        try { DailyReconciliation.validateUnregisteredSummary(3, 0, 0, "CASH"); fail("zero tariff") }
        catch (expected: IllegalArgumentException) { }
        try { DailyReconciliation.validateUnregisteredSummary(3, 50000L, 0, "NOPE"); fail("bad payment") }
        catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun summaryLedgerRowUsesDeterministicIdAndNetTotal() {
        val summary = com.example.db.UnregisteredDaySummary("2026-09-28", 50000L, 3, 1, 100000L, "CASH", 999L)
        val row = DailyReconciliation.summaryLedgerRow("2026-09-28", summary, 1_800_000_000_000L, 2500)!!
        assertEquals(DailyReconciliation.dailyRowId("2026-09-28"), row.id)
        assertEquals(100000L, row.amount)
        assertEquals(2, row.count)
        assertEquals(50000L, row.unitPrice)
        assertEquals("CASH", row.payment)
        // Zero net → no ledger row at all.
        val zero = summary.copy(netTotal = 0L)
        assertNull(DailyReconciliation.summaryLedgerRow("2026-09-28", zero, 1_800_000_000_000L, 2500))
    }

    @Test
    fun summaryLedgerRowConvertsBankThroughPremium() {
        val summary = com.example.db.UnregisteredDaySummary("2026-09-28", 50000L, 2, 0, 100000L, "BANK", 999L)
        val row = DailyReconciliation.summaryLedgerRow("2026-09-28", summary, 1_800_000_000_000L, 2500)!!
        assertEquals("BANK", row.payment)
        assertEquals(80000L, row.cashEquivalent) // 1000 / 1.25
    }
}
