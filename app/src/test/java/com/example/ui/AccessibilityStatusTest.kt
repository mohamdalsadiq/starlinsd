package com.example.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.service.TextExpanderService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec 28 (20): the activation state is never cached — it is re-read from Android
 * settings, which is exactly what the ON_RESUME re-check does when the operator
 * returns from the accessibility page (activate → recheck → «تم التفعيل»).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccessibilityStatusTest {

    @Test
    fun activationIsDetectedOnRecheckAndUndetectedWhenRemoved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = ComponentName(context, TextExpanderService::class.java).flattenToString()

        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        assertFalse(accessibilityEnabled(context))

        // The operator enables Slotra (among other services) then returns: re-check sees it.
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.other/com.other.Svc:$service",
        )
        assertTrue(accessibilityEnabled(context))

        // Disabled again: the very next re-check must not keep a stale "enabled".
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.other/com.other.Svc",
        )
        assertFalse(accessibilityEnabled(context))
    }
}
