package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.db.*
import com.example.domain.Rules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SubscriptionRepository
    private var now = 1700000000000L
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = SubscriptionRepository(context, db) { now }
        repo.initialize()
    }
    @After fun close() { db.close() }
    private suspend fun start(minutes: Int = 180, home: Boolean = false, payment: String = "CASH"): Session {
        val p = repo.dao.plans().first { it.minutes == minutes && it.home == home }
        return repo.prepare("محمد", p.id, payment, "test").also { repo.insert(it) }
    }
    @Test fun cancellationBeforeThresholdDoesNotBookRevenue() = runBlocking {
        val s = start(); now += 29 * Rules.MINUTE
        repo.changeState(s.id, "CANCEL"); now += 60 * Rules.MINUTE; repo.reconcile()
        assertEquals(0L, repo.dao.session(s.id)!!.recognized)
        assertEquals("CANCELLED", repo.dao.session(s.id)!!.state)
    }
    @Test fun exactlyThirtyMinutesBooksFullPackageOnceEvenAfterCancellation() = runBlocking {
        val s = start(); now += 30 * Rules.MINUTE
        repo.changeState(s.id, "CANCEL")
        repeat(3) { now += Rules.MINUTE; repo.reconcile(); repo.insert(s) }
        val row = repo.dao.session(s.id)!!
        assertEquals(100000L, row.amount)
        assertEquals(s.started + 30 * Rules.MINUTE, row.recognized)
        assertEquals(1, repo.dao.sessions().size)
    }
    @Test fun pauseExcludesTimeAndSettingsChangesPreserveSnapshots() = runBlocking {
        val s = start(60, payment = "BANK"); now += 20 * Rules.MINUTE
        repo.changeState(s.id, "PAUSE"); now += 120 * Rules.MINUTE
        repo.reconcile(); assertEquals(0L, repo.dao.session(s.id)!!.recognized)
        repo.saveSettings(BusinessSettings(premiumBps = 0, graceMinutes = 0))
        val plan = repo.dao.plans().first { it.minutes == 60 }
        repo.savePlan(plan.copy(cash = 900000, bank = 900000))
        repo.changeState(s.id, "RESUME"); now += 10 * Rules.MINUTE
        repo.reconcile(); val row = repo.dao.session(s.id)!!
        assertEquals(62500L, row.amount); assertEquals(50000L, row.cashEquivalent)
        assertEquals(2500, row.premiumBps); assertEquals(now, row.recognized)
    }
    @Test fun overdueAndHomeSessionsReconcileAfterProcessAbsence() = runBlocking {
        val paid = start()
        val homePlan = repo.dao.plans().first { it.home }
        assertTrue(runCatching { repo.prepare("", homePlan.id, "CASH", "بيت") }.isFailure)
        val legacy = paid.copy(id = "legacy-home", home = true, amount = 0, cashEquivalent = 0, reference = "2")
        repo.dao.insertSession(legacy)
        now += 24 * 60 * Rules.MINUTE
        val rows = repo.reconcile()
        assertEquals("ENDED", rows.first { it.id == paid.id }.state)
        assertEquals(paid.started + 30 * Rules.MINUTE, rows.first { it.id == paid.id }.recognized)
        val home = rows.first { it.id == legacy.id }
        assertEquals("CANCELLED", home.state); assertEquals("", home.reference)
        assertTrue(home.notified); assertTrue(home.warned); assertEquals(0L, home.recognized)
        assertEquals(0L, home.amount)
    }

    @Test fun duplicateKeywordsAreRejectedWithoutLosingOriginal() = runBlocking {
        val original = db.shortcutDao().list().first()
        try { repo.saveShortcut(Shortcut(keyword = original.keyword, phrase = "replace")); fail("duplicate accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(original, db.shortcutDao().list().first { it.id == original.id })
    }
    @Test fun bareShortcutCreatesUniqueAnonymousSubscriberAndCanBeRenamed() = runBlocking {
        val p = repo.dao.plans().first { it.minutes == 180 && !it.home }
        val match = com.example.domain.TextRules.match("mm ", 3, 3, setOf("mm"))!!
        val first = repo.prepare(match.client, p.id, "CASH", "mm")
        repo.insert(first)
        val second = repo.prepare("", p.id, "CASH", "mm")
        repo.insert(second)
        assertEquals("مشترك 1", first.client); assertEquals("2", second.reference)
        assertEquals("مشترك 1 1", com.example.domain.TextRules.render("%client% %code%", now, first.client, code = first.reference))
        repo.rename(first.id, "هاتف محمد")
        val renamed = repo.dao.session(first.id)!!
        assertEquals("هاتف محمد", renamed.client); assertEquals("1", renamed.reference)
        assertEquals(first.amount, renamed.amount); assertEquals(first.started, renamed.started)
        repo.saveSettings(BusinessSettings())
        assertEquals("3", repo.prepare("", p.id, "CASH", "mm").reference)
    }

    @Test fun subscriberPoolReservesAndRejectsWhenFullWithoutReusingSameDayNumber() = runBlocking {
        repo.saveSettings(BusinessSettings(maxSubscribers = 2))
        val p = repo.dao.plans().first { it.minutes == 180 && !it.home }
        val first = repo.prepare("", p.id, "CASH", "mm")
        val second = repo.prepare("", p.id, "CASH", "mm")
        assertEquals("1", first.reference); assertEquals("2", second.reference)
        assertTrue(runCatching { repo.prepare("", p.id, "CASH", "mm") }.isFailure)
        repo.release(first.id)
        repo.insert(second)
        repo.changeState(second.id, "PAUSE")
        assertTrue(runCatching { repo.saveSettings(BusinessSettings(maxSubscribers = 1)) }.isFailure)
        assertEquals("🌹04:00🌹[1]", com.example.domain.TextRules.withReference("🌹04:00🌹", "1"))
        assertEquals("🌹04:00🌹[1]", com.example.domain.TextRules.withReference("🌹04:00🌹[1] ", "1"))
    }
    @Test fun expiredReservationReleasesNumberButCompletedSessionHoldsDailyNumber() = runBlocking {
        repo.saveSettings(BusinessSettings(maxSubscribers = 2))
        val p = repo.dao.plans().first { it.minutes == 60 && !it.home }
        val lost = repo.prepare("", p.id, "CASH", "mm")
        now += 60001
        val next = repo.prepare("", p.id, "CASH", "mm")
        assertEquals("1", next.reference)
        assertTrue(runCatching { repo.insert(lost) }.isFailure)
        repo.insert(next); now += 61 * Rules.MINUTE; repo.reconcile(now)
        val endedNext = repo.dao.session(next.id)!!
        assertEquals("ENDED", endedNext.state)
        val third = repo.prepare("", p.id, "CASH", "mm")
        assertEquals("2", third.reference)
    }
    @Test fun manualRevenueAddsImmediatelyAndDuplicateConfirmIsIdempotent() = runBlocking {
        val id = java.util.UUID.randomUUID().toString()
        repo.addSales(id, listOf(10 to 50000L, 10 to 100000L), "CASH")
        repo.addSales(id, listOf(10 to 50000L, 10 to 100000L), "CASH")
        val rows = repo.dao.manualSales()
        assertEquals(2, rows.size); assertEquals(1500000L, rows.sumOf { it.amount })
        assertEquals(20, rows.sumOf { it.count }); assertTrue(repo.dao.sessions().isEmpty())
        repo.addSales(java.util.UUID.randomUUID().toString(), listOf(1 to 125000L), "BANK")
        assertEquals(1600000L, repo.dao.manualSales().sumOf { it.cashEquivalent })
    }
    @Test fun backupRestoresAllDataAndRejectsCorruptionWithoutChangingLedger() = runBlocking {
        val s = start(); now += 31 * Rules.MINUTE; repo.reconcile()
        repo.addSales(java.util.UUID.randomUUID().toString(), listOf(10 to 50000L), "BANK")
        repo.saveSettings(BusinessSettings(maxSubscribers = 75))
        val backup = repo.exportJson()
        val parsed = BackupData.parse(backup)
        assertEquals(1, parsed.rows.getValue("manual_sales").size)
        repo.rename(s.id, "changed")
        repo.restoreJson(backup)
        assertEquals("محمد", repo.dao.session(s.id)!!.client)
        assertEquals(75, repo.dao.settings()!!.maxSubscribers)
        assertEquals(400000L, repo.dao.manualSales().single().cashEquivalent)
        val bad = org.json.JSONObject(backup)
        bad.getJSONArray("manual_sales").getJSONObject(0).put("amount", -1)
        assertTrue(runCatching { repo.restoreJson(bad.toString()) }.isFailure)
        assertEquals(500000L, repo.dao.manualSales().single().amount)
        assertEquals(1, repo.dao.sessions().size)
        bad.getJSONArray("manual_sales").getJSONObject(0).put("amount", 500000).put("sql", "DROP TABLE sessions")
        assertTrue(runCatching { repo.restoreJson(bad.toString()) }.isFailure)
    }

    @Test fun correctionsChangeOriginalDayAndDebtPaymentsAreNotCountedTwice() = runBlocking {
        val day = com.example.domain.Revenue.day(now)
        repo.saveSettings(BusinessSettings(usdCents = 100, bankRate = 75000000, cycleStart = day, cycleEnd = day + 30 * 86400000L))
        repo.addSales(java.util.UUID.randomUUID().toString(), listOf(1 to 3050000L), "CASH")
        val sale = repo.dao.manualSales().single()
        val debt = Debt("debt-test", "دين", 600000, day, day + 10 * 86400000L)
        repo.saveDebt(debt)
        suspend fun report() = com.example.domain.Finance.report(com.example.domain.Finance.ledger(repo.dao.sessions(), repo.dao.manualSales(), repo.dao.corrections()), repo.dao.cycles(), repo.dao.debts(), repo.dao.debtPayments(), now)
        assertEquals(2000000L, report().days.getValue(day).billTarget)
        assertEquals(1050000L, report().days.getValue(day).surplus)
        assertEquals(1050000L, report().days.getValue(day).available)
        repo.payDebt("payment-test", debt.id, 100000)
        repo.payDebt("payment-test", debt.id, 100000)
        assertEquals(1050000L, report().days.getValue(day).available)
        assertEquals(100000L, report().debts.single().paid)
        repo.correctRevenue("manual:${sale.id}", 100000, 1, false, "تصحيح المبلغ")
        assertEquals(sale, repo.dao.manualSales().single())
        repo.correctRevenue("manual:${sale.id}", 100000, 1, true, "حذف خاطئ")
        assertEquals(0L, report().days.getValue(day).revenue)
        val backup = repo.exportJson(); repo.restoreJson(backup)
        assertEquals(2, repo.dao.corrections().size); assertEquals(1, repo.dao.debtPayments().size)
    }
    @Test fun changingMonthKeepsOldCycleAndFixedTarget() = runBlocking {
        val day = com.example.domain.Revenue.day(now)
        repo.saveSettings(BusinessSettings(usdCents = 100, bankRate = 75000000, cycleStart = day, cycleEnd = day + 30 * 86400000L))
        val first = repo.dao.settings()!!
        repo.saveSettings(first.copy(cycleStart = first.cycleEnd, cycleEnd = first.cycleEnd + 30 * 86400000L))
        assertEquals(2, repo.dao.cycles().size)
        assertTrue(repo.dao.settings()!!.cycleId != first.cycleId)
    }

}
