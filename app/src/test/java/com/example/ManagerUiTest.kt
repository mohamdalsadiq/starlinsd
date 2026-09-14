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
        compose.onNodeWithTag("cycle-profit").assertTextEquals("500 ج.س").assertIsDisplayed()
        compose.onNodeWithTag("cycle-profit-explanation").assertTextEquals("هذا ربحك الفعلي بعد كامل فاتورة الدورة. الفائض اليومي في الأسفل رقم توزيع مؤقت فقط وليس ربحًا.")
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
            DebtPaymentIndicator(DebtBalance(debt.copy(id = "waiting", name = "انتظار"), 0, 0))
        } } } }
        compose.onNodeWithText("مسدد بالكامل · لا يلزم سداد").assertIsDisplayed()
        compose.onNodeWithText("سداد جاهز اليوم: 400 ج.س").assertIsDisplayed()
        compose.onNodeWithText("لم يتوفر مخصص للسداد اليوم").assertIsDisplayed()
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
}
