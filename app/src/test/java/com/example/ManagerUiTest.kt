package com.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.db.*
import com.example.domain.Revenue
import com.example.ui.Dashboard
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
    @Test fun dashboardSeparatesBankAmountFromCashEquivalent() {
        val now = System.currentTimeMillis()
        val paid = Session(id = "paid", client = "محمد أحمد", plan = "3 ساعات", started = now - 3600000,
            resumed = now - 3600000, duration = 10800000, amount = 125000, cashEquivalent = 100000,
            payment = "BANK", premiumBps = 2500, home = false, grace = 1800000, recognized = now)
        val home = paid.copy(id = "home", client = "أهل البيت", home = true, amount = 0, cashEquivalent = 0, recognized = 0)
        val cycleStart = Revenue.day(now)
        val settings = BusinessSettings(usdCents = 100, bankRate = 62500, cycleStart = cycleStart, cycleEnd = cycleStart + 30 * 86400000L)
        compose.setContent { ManagerTheme {
            Surface(Modifier.fillMaxSize()) { Dashboard(listOf(paid, home), settings, now) {} }
        } }
        compose.onNodeWithTag("today-revenue").assertTextEquals("1,000 ج.س").assertIsDisplayed()
        compose.onNodeWithTag("today-bank").assertTextEquals("1,250 ج.س").assertIsDisplayed()
        compose.onNodeWithTag("today-cash").assertTextEquals("0 ج.س").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/reports/ui/dashboard.png")
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("today-profit"))
        compose.onNodeWithTag("today-profit").assertTextEquals("983.33 ج.س")
        compose.onRoot().captureRoboImage("build/reports/ui/daily-budget.png")
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("cycle-profit"))
        compose.onNodeWithTag("cycle-profit").assertTextEquals("500 ج.س").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/reports/ui/cycle-profit.png")
        compose.onNodeWithTag("dashboard-list").performScrollToNode(hasTestTag("open-history"))
        compose.onRoot().captureRoboImage("build/reports/ui/recent-days.png")
        compose.onNodeWithTag("open-history").performClick()
        compose.onNodeWithTag("history-calendar").assertIsDisplayed()
        compose.onNodeWithTag("selected-day-revenue").assertTextEquals("1,000 ج.س")
        compose.onNodeWithTag("history-calendar").captureRoboImage("build/reports/ui/calendar.png")
    }
}
