package com.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.db.*
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
        compose.setContent { ManagerTheme {
            Surface(Modifier.fillMaxSize()) { Dashboard(listOf(paid, home), BusinessSettings(), now) {} }
        } }
        compose.onNodeWithText("1000 ج.س").assertIsDisplayed()
        compose.onNodeWithText("بنكك: 1250 ج.س").assertIsDisplayed()
        compose.onNodeWithText("كاش: 0 ج.س").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/reports/ui/dashboard.png")
    }
}
