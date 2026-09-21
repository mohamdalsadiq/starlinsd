package com.example

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.network.*
import com.example.network.StarlinkProtocol.field
import com.example.network.StarlinkProtocol.numberField
import com.example.ui.StarlinkControlPanel
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StarlinkControlUiTest {
    @get:Rule val compose = createComposeRule()
    private fun str(n: Int, s: String) = field(n, s.toByteArray())
    @Test fun `control requires selection preflight and explicit acknowledgement before dispatch`() {
        val device = StarlinkProtocol.Client("realme-C55", "192.168.1.103", "8a:bb:a2:XX:XX:XX", null, 977656928, role = 1)
        var pending: PendingPause? = null
        var writes = 0
        val journal = object : PauseJournal {
            override fun read() = pending
            override fun write(pendingPause: PendingPause) { pending = pendingPause }
            override fun clear() { pending = null }
        }
        val link = object : RouterControlLink {
            override val localIps = setOf("192.168.1.20")
            override suspend fun exchange(payload: ByteArray): ByteArray = when (StarlinkProtocol.fields(payload).single().number) {
                1004 -> field(3004, field(3, str(1, "router-fixture")))
                3009 -> field(3009, field(1, numberField(43, 5)))
                3002 -> field(3002, field(1, str(1, device.name) + str(2, device.mac) + str(3, device.ip) + numberField(43, device.id!!) + numberField(14, 1)))
                3017 -> { writes++; field(2, numberField(1, 7)) }
                else -> error("unexpected_request")
            }
        }
        val controller = RouterControl({ link }, journal, { 0 }, {})
        compose.setContent { ManagerTheme { Surface(Modifier.fillMaxSize()) {
            StarlinkControlPanel(listOf(device), false, {}, controller)
        } } }
        compose.runOnIdle { assertEquals(0, writes) }
        compose.onNodeWithTag("starlink-select").performClick()
        compose.onNodeWithText("realme-C55\nID: 977656928 · 192.168.1.103").performClick()
        compose.onNodeWithTag("starlink-prepare").performClick()
        compose.onNodeWithTag("starlink-control-confirm").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, writes) }
        compose.onNodeWithTag("starlink-control-ack").performClick()
        compose.onNode(isDialog()).captureRoboImage("build/reports/ui/starlink-control-confirm.png")
        compose.onNodeWithTag("starlink-control-confirm").performClick()
        compose.waitUntil(timeoutMillis = 5000) { writes == 1 }
        compose.onNodeWithTag("starlink-control-result").assertTextContains("الراوتر رفض صلاحية التحكم", substring = true)
        compose.onNodeWithTag("starlink-restore").assertExists()
    }
    @Test fun `pending recovery journal survives reopening and excludes backup storage`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val journal = FilePauseJournal(context)
        val saved = PendingPause("router-fixture", StarlinkProtocol.Client("phone", "192.168.1.50", "aa:bb:cc:XX:XX:XX", null, 4294967295L),
            "slotra-11111111-2222-3333-4444-555555555555")
        try {
            journal.write(saved)
            val reopened = FilePauseJournal(context).read()!!
            assertEquals(saved.marker, reopened.marker)
            assertEquals(saved.device.id, reopened.device.id)
            assertTrue(java.io.File(context.noBackupFilesDir, "starlink-pause-v1.json").isFile)
            journal.clear(); assertNull(FilePauseJournal(context).read())
        } finally { journal.clear() }
    }
}
