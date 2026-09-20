package com.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.db.*
import com.example.domain.*
import com.example.ui.BulkSalesForm
import com.example.ui.PlanForm
import org.junit.Assert.*
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import com.example.ui.ManagerApp
import com.example.ui.Dashboard
import com.example.ui.DebtPaymentIndicator
import com.example.domain.DebtBalance
import androidx.compose.foundation.layout.Column
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.github.takahirom.roborazzi.captureRoboImage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManagerUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun dashboardShowsTrueProfitAndKeepsLegacyBankConversion() {
        val now = System.currentTimeMillis()
        val paid = Session(id = "paid", client = "محمد أحمد", plan = "3 ساعات", started = now - 3600000,
            resumed = now - 3600000, duration = 10800000, amount = 125000, cashEquivalent = 100000,
            payment = "BANK", premiumBps = 2500, home = false, grace = 1800000, recognized = now)
        val home = paid.copy(id = "home", client = "أهل البيت", home = true, amount = 0, cashEquivalent = 0, recognized = 0)
        val cycleStart = Revenue.day(now)
        val settings = BusinessSettings(usdCents = 100, bankRate = 62500, cycleStart = cycleStart, cycleEnd = cycleStart + 30 * 86400000L)
        val snapshot = FinancialReportCache().get(FinancialData(listOf(paid, home), emptyList(), emptyList(), settings, emptyList(), emptyList(), emptyList()), now)
        compose.setContent { ManagerTheme {
            Surface(Modifier.fillMaxSize()) { Dashboard(snapshot) {} }
        } }
        compose.onNodeWithTag("today-revenue").assertTextEquals("1,000 ج.س").assertIsDisplayed()
        compose.onNodeWithText("بنكك المسجّل").assertDoesNotExist()
        compose.onNodeWithTag("today-profit").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/reports/ui/daily-plan.png")
        compose.onNodeWithTag("daily-bill-target").assertTextEquals("16.67 ج.س")
        compose.onNodeWithTag("daily-bill-target-bank").assertTextEquals("بنكك: 20.84 ج.س")
        compose.onNodeWithTag("daily-shortfall").assertTextEquals("0 ج.س")
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("cycle-profit-explanation"))
        compose.onNodeWithTag("cycle-profit").assertTextEquals("500 ج.س").assertIsDisplayed()
        compose.onNodeWithTag("cycle-profit-explanation").assertTextEquals("الربح بعد تغطية الفاتورة والمصروفات كاملة. التغطية المحسوبة لا تعني سداد الفاتورة.")
        compose.onRoot().captureRoboImage("build/reports/ui/dashboard.png")
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("open-history"))
        compose.onRoot().captureRoboImage("build/reports/ui/recent-days.png")
        compose.onNodeWithTag("open-history").performClick()
        compose.onNodeWithTag("history-calendar").assertIsDisplayed()
        compose.onNodeWithTag("history-calendar").performScrollToNode(hasTestTag("selected-day-revenue"))
        compose.onNodeWithTag("selected-day-revenue").assertTextEquals("1,000 ج.س")
        compose.onNodeWithTag("history-calendar").captureRoboImage("build/reports/ui/calendar.png")
    }
    @Test fun debtIndicatorsDistinguishPaidReadyAndUnfunded() {
        val debt = Debt("base", "دين", 100000, 1, 2)
        compose.setContent { ManagerTheme { Surface(Modifier.fillMaxSize()) { Column {
            DebtPaymentIndicator(DebtBalance(debt.copy(id = "paid", name = "مسدد"), 100000, 100000))
            DebtPaymentIndicator(DebtBalance(debt.copy(id = "ready", name = "جاهز"), 50000, 10000))
            DebtPaymentIndicator(DebtBalance(debt.copy(id = "waiting", name = "انتظار", due = System.currentTimeMillis() + 86400000L), 0, 0))
        } } } }
        compose.onNodeWithText("مسدد بالكامل · لا يلزم سداد").assertIsDisplayed()
        compose.onNodeWithText("مطلوب السداد · حلّ الموعد").assertIsDisplayed()
        compose.onNodeWithText("دين قائم · لم يحل الموعد").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/reports/ui/debt-indicators.png")
    }


    @Test fun bulkIncomeAcceptsArabicCountsAndSavesOneCashTotal() {
        var saved: List<Pair<Int, Long>>? = null
        var payment: String? = null
        compose.setContent { ManagerTheme { BulkSalesForm({}) { _, lines, method -> saved = lines; payment = method } } }
        compose.onNodeWithText("العدد 1").performTextInput("١٠")
        compose.onNodeWithText("العدد 2").performTextInput("10")
        compose.onNodeWithTag("bulk-total").performScrollTo().assertTextEquals("15,000 ج.س")
        compose.onNodeWithText("بنكك").assertDoesNotExist()
        compose.onNodeWithText("حفظ").performClick()
        compose.runOnIdle { assertEquals(listOf(10 to 50000L, 10 to 100000L), saved); assertEquals("CASH", payment) }
    }
    @Test fun editingUnifiedPlanPricePreservesLegacyBankPrice() {
        var saved: Plan? = null
        val plan = Plan(1, "ساعة", 60, 50000, 62500)
        compose.setContent { ManagerTheme { PlanForm(plan, 2500, {}) { saved = it } } }
        compose.onNodeWithText("السعر بالجنيه السوداني").performTextReplacement("600")
        compose.onNodeWithText("حفظ").performClick()
        compose.runOnIdle { assertEquals(60000L, saved!!.cash); assertEquals(62500L, saved!!.bank) }
    }

    @Test fun navigationSeparatesDebtsAndPreservesSubscriberSearch() {
        val store = ViewModelStore()
        val vm = MainViewModel(ApplicationProvider.getApplicationContext())
        store.put("manager", vm)
        try {
            compose.setContent { ManagerTheme { ManagerApp(vm, null) } }
            compose.onNodeWithTag("nav-1").performClick()
            compose.onNodeWithText("ابحث بالاسم أو الرقم أو الباقة").performTextInput("١٢")
            compose.onNodeWithTag("nav-2").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("خطة السداد مستقلة عن ربح الدورة").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("خطة السداد مستقلة عن ربح الدورة").assertIsDisplayed()
            compose.onNodeWithTag("nav-1").performClick()
            compose.onNodeWithText("١٢").assertIsDisplayed()
            compose.onNodeWithTag("nav-4").performClick()
            compose.onNodeWithText("الباقات والأسعار").performClick()
            compose.onNodeWithText("باقاتك وأسعارك").assertIsDisplayed()
            compose.onNodeWithContentDescription("رجوع").performClick()
            compose.onNodeWithText("أدوات مشروعك وإعدادات التطبيق").assertIsDisplayed()
            compose.onRoot().captureRoboImage("build/reports/ui/navigation.png")
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun largeArabicTextKeepsRealProfitVisibleAndCoverageConsistent() {
        val now = System.currentTimeMillis()
        val day = Revenue.day(now)
        val config = BusinessSettings(usdCents = 100, bankRate = 58925000, cycleStart = day - 9 * 86400000L, cycleEnd = day + 22 * 86400000L)
        val sale = ManualSale("bulk", now, 161, 100000, 16100000, 16100000, "CASH", 2500)
        val snapshot = FinancialReportCache().get(FinancialData(emptyList(), listOf(sale), emptyList(), config, emptyList(), emptyList(), emptyList()), now)
        compose.setContent { ManagerTheme {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.3f)) {
                Surface(Modifier.fillMaxSize()) { Dashboard(snapshot) {} }
            }
        } }
        compose.onNodeWithTag("today-revenue").assertTextEquals("161,000 ج.س").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("cycle-profit"))
        compose.onNodeWithTag("cycle-profit").assertTextEquals("0 ج.س").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("cycle-remaining"))
        compose.onNodeWithTag("cycle-remaining").assertTextEquals("310,400 ج.س").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/reports/ui/large-arabic-text.png")
    }
    @Test @Config(sdk = [36], qualifiers = "w800dp-h360dp-land-night-mdpi")
    fun landscapeDarkDashboardPreservesTargetAndIncome() {
        val now = System.currentTimeMillis()
        val day = Revenue.day(now)
        val config = BusinessSettings(usdCents = 100, bankRate = 12500000, cycleStart = day, cycleEnd = day + 5 * 86400000L)
        val data = FinancialData(emptyList(), listOf(ManualSale("sample", now, 15, 100000, 1500000, 1500000, "CASH", 2500)), emptyList(), config, emptyList(), emptyList(), emptyList())
        compose.setContent { ManagerTheme { Surface(Modifier.fillMaxSize()) { Dashboard(FinancialReportCache().get(data, now)) {} } } }
        compose.onNodeWithTag("today-revenue").assertTextEquals("15,000 ج.س")
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("daily-shortfall"))
        compose.onNodeWithTag("daily-bill-target").assertTextEquals("20,000 ج.س").assertIsDisplayed()
        compose.onNodeWithTag("daily-shortfall").assertTextEquals("5,000 ج.س").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/reports/ui/landscape-dark.png")
    }

    @Test fun balanceFormAcceptsArabicBalancesAndKeepsBankSeparate() {
        var saved: Triple<Long, Long, String>? = null
        val now = System.currentTimeMillis()
        val snapshot = FinancialReportCache().get(FinancialData(emptyList(), emptyList(), emptyList(), BusinessSettings(), emptyList(), emptyList(), emptyList()), now)
        compose.setContent { ManagerTheme { com.example.ui.BalanceForm(snapshot, {}) { cash, bank, reason -> saved = Triple(cash, bank, reason) } } }
        compose.onNodeWithText("الكاش الموجود الآن · ج.س").performTextInput("١٠٠٠٠")
        compose.onNodeWithText("بنكك الموجود الآن · ج.س").performTextInput("١٢٥٠٠")
        compose.onNodeWithText("إجمالي الموجود بمكافئ الكاش: 20,000 ج.س").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/reports/ui/balance-form.png")
        compose.onNodeWithText("حفظ").performClick()
        compose.runOnIdle { assertEquals(1000000L, saved!!.first); assertEquals(1250000L, saved!!.second) }
    }

}
