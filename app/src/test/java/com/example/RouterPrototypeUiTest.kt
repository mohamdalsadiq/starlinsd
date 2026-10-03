package com.example

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.network.router.RouterClient
import com.example.network.router.RouterDiscoveryReport
import com.example.network.router.RouterStep
import com.example.ui.RouterPrototypePanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
class RouterPrototypeUiTest {
    @get:Rule val compose = createComposeRule()

    private fun client(name: String, mac: String, ip: String, id: Long?) = RouterClient(
        name = name, givenName = "", mac = mac, rawMac = mac, ip = ip, rawIp = ip, ipv6 = emptyList(),
        clientId = id, deviceId = "", captiveClientId = "", upstreamMac = "", active = null, blocked = null,
        role = 1, interfaceType = null, interfaceName = "", associatedSeconds = null, idleSeconds = null,
        dhcpLeaseFound = null, dhcpLeaseActive = null, dhcpLeaseRenewed = null, captiveState = null,
        sandboxState = null, hardwareVersion = "", softwareVersion = "", apiVersion = null,
        uploadMb = null, downloadMb = null,
    )

    private fun report(clients: List<RouterClient>) =
        RouterDiscoveryReport(true, "rev3", "2026.1", "Router-TEST", clients, listOf(RouterStep("get_status", "ok", true)), "")

    @Test
    fun `snapshot one lists clients and never auto compares`() {
        var calls = 0
        val first = listOf(client("هاتف", "aa:bb:cc:dd:ee:01", "192.168.1.101", 42))
        compose.setContent {
            ManagerTheme { Surface(Modifier.fillMaxSize()) {
                RouterPrototypePanel(discover = { calls++; report(first) })
            } }
        }
        compose.onNodeWithTag("router-snapshot-2").assertIsNotEnabled()
        compose.onNodeWithTag("router-snapshot-1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("لقطة ١: 1 سجلًا · وصلنا للراوتر").assertIsDisplayed()
        compose.onNodeWithTag("router-snapshot-2").assertIsEnabled()
        assertEquals(1, calls)
    }

    @Test
    fun `second snapshot shows the identity verdicts`() {
        var call = 0
        val before = listOf(client("هاتف", "aa:bb:cc:dd:ee:01", "192.168.1.101", 42))
        val after = listOf(client("هاتف", "aa:bb:cc:dd:ee:01", "192.168.1.180", 91))
        compose.setContent {
            ManagerTheme { Surface(Modifier.fillMaxSize()) {
                RouterPrototypePanel(discover = { call++; if (call == 1) report(before) else report(after) })
            } }
        }
        compose.onNodeWithTag("router-snapshot-1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("router-snapshot-2").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("نتيجة المقارنة").assertIsDisplayed()
        compose.onNodeWithText("MAC: ثابت · IP: تغيّر · clientId: تغيّر").assertIsDisplayed()
    }

    @Test
    fun `masked mac is labelled masked in the client list`() {
        val masked = RouterClient(
            name = "جهاز", givenName = "", mac = null, rawMac = "60:74:f4:XX:XX:XX", ip = "192.168.1.101",
            rawIp = "192.168.1.101", ipv6 = emptyList(), clientId = 9, deviceId = "", captiveClientId = "",
            upstreamMac = "", active = null, blocked = null, role = null, interfaceType = null, interfaceName = "",
            associatedSeconds = null, idleSeconds = null, dhcpLeaseFound = null, dhcpLeaseActive = null,
            dhcpLeaseRenewed = null, captiveState = null, sandboxState = null, hardwareVersion = "",
            softwareVersion = "", apiVersion = null, uploadMb = null, downloadMb = null,
        )
        compose.setContent {
            ManagerTheme { Surface(Modifier.fillMaxSize()) {
                RouterPrototypePanel(discover = { report(listOf(masked)) })
            } }
        }
        compose.onNodeWithTag("router-snapshot-1").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("MAC: 60:74:f4:XX:XX:XX (مقنّع)").assertIsDisplayed()
        compose.onNodeWithText("الهوية المقترحة: CLIENT_ID · الحظر: غير موجود").assertIsDisplayed()
    }
}
