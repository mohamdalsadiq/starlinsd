package com.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.network.ProbeReport
import com.example.network.ProbeStep
import com.example.network.StarlinkProtocol
import com.example.network.StarlinkProbe
import com.example.ui.StarlinkTestScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StarlinkUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `manual start shows actual returned fields with unknown connection state`() {
        var calls = 0
        compose.setContent { ManagerTheme { Surface(Modifier.fillMaxSize()) {
            StarlinkTestScreen { calls++; ProbeReport(listOf(ProbeStep("router", "قراءة ناجحة", true)),
                listOf(StarlinkProtocol.Client("هاتف للاختبار", "192.168.1.25", "02:11:22:33:44:55", null)), "نجحت قراءة تجريبية") }
        } } }
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithTag("starlink-start").performClick()
        compose.onNodeWithTag("starlink-screen").performScrollToNode(hasText("1. هاتف للاختبار"))
        compose.onNodeWithText("1. هاتف للاختبار").assertIsDisplayed()
        compose.onNodeWithTag("starlink-screen").performScrollToNode(hasText("حالة الاتصال: غير مؤكدة من الرد"))
        compose.onNodeWithText("حالة الاتصال: غير مؤكدة من الرد").assertIsDisplayed()
        compose.onNodeWithText("IP: 192.168.1.25").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/reports/ui/starlink-test.png")
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("starlink-screen").performScrollToNode(hasTestTag("starlink-start"))
        compose.onNodeWithTag("starlink-start").assertIsEnabled()
    }
    @Test fun `cancel stops the active probe and re-enables start`() {
        var cancelled = false
        compose.setContent { ManagerTheme { Surface(Modifier.fillMaxSize()) { StarlinkTestScreen { progress ->
            try { progress("انتظار الراوتر"); awaitCancellation() } finally { cancelled = true }
        } } } }
        compose.onNodeWithTag("starlink-start").performClick()
        compose.onNodeWithTag("starlink-start").assertIsNotEnabled()
        compose.onNodeWithTag("starlink-cancel").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
        compose.onNodeWithTag("starlink-start").assertIsEnabled()
    }
    @Test fun `no wifi produces actionable result without pretending zero devices`() = runBlocking {
        val result = StarlinkProbe(ApplicationProvider.getApplicationContext()).run {}
        assertNull(result.clients)
        assertTrue(result.summary.contains("Wi-Fi"))
        assertTrue(result.steps.isEmpty())
    }
    @Test fun `diagnostic omits client names and identifying addresses`() {
        val report = ProbeReport(listOf(ProbeStep("router", "success")),
            listOf(StarlinkProtocol.Client("PRIVATE_NAME", "192.168.1.25", "02:11:22:33:44:55", true)), "success")
        val diagnostic = report.diagnostic()
        assertFalse(diagnostic.contains("PRIVATE_NAME"))
        assertFalse(diagnostic.contains("192.168.1.25"))
        assertFalse(diagnostic.contains("02:11:22:33:44:55"))
        assertTrue(diagnostic.contains("عدد السجلات المستلمة: 1"))
    }
}
