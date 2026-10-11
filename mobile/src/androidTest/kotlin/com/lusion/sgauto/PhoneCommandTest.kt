package com.lusion.sgauto

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

/** Run only through tools/phone_command_test.py, which owns fixture reset and count assertions. */
@RunWith(AndroidJUnit4::class)
class PhoneCommandTest {
    @Test fun openThenCloseWithFreshConfirmation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Command tests require explicit fixture opt-in", args.getString("allowFakeCommands") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("Use an emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val prefs = PreferenceUtility.getSharedPreferences(context)
        assertTrue("Debug routing must stay enabled", prefs.getBoolean("debug_switch", false))
        val endpoint = URI(prefs.getString("debug_server", "").orEmpty())
        assertTrue("Fixture origin mismatch", endpoint.toString() == args.getString("expectedEndpoint") &&
            endpoint.scheme == "https" && endpoint.host in setOf("10.0.2.2", "127.0.0.1", "localhost") && endpoint.port == 3000)
        assertTrue("Fake device mismatch", prefs.getString("garage_device_id", "") == args.getString("expectedDevice"))

        fun awaitFresh(status: GarageUtility.GarageStatus, after: Long, description: String) {
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (GarageUtility.gLastSuccessfulStatusTime > after && GarageUtility.getLastGarageStatus(context) == status &&
                    device.findObjects(By.pkg(context.packageName).desc(description)).size == 1) return
                SystemClock.sleep(250)
            }
            throw AssertionError("No fresh $status and unique matching phone control within 90 seconds")
        }

        fun tapOnce(description: String): Long {
            // Verify routing immediately before each tap; never retry a command tap.
            assertTrue("Routing changed during test", prefs.getBoolean("debug_switch", false) &&
                prefs.getString("debug_server", "") == args.getString("expectedEndpoint"))
            val controls = device.findObjects(By.pkg(context.packageName).desc(description))
            assertTrue("Expected exactly one garage control", controls.size == 1)
            val before = System.currentTimeMillis()
            controls.single().click()
            return before
        }

        val baseline = GarageUtility.gLastSuccessfulStatusTime
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitFresh(GarageUtility.GarageStatus.CLOSED, baseline, "Closed")
            val openedAfter = tapOnce("Closed")
            awaitFresh(GarageUtility.GarageStatus.OPEN, openedAfter, "Open")
            // Allow the host to observe the intermediate OPEN checkpoint.
            SystemClock.sleep(1_000)
            val closedAfter = tapOnce("Open")
            awaitFresh(GarageUtility.GarageStatus.CLOSED, closedAfter, "Closed")
        } finally {
            device.pressHome()
        }
    }
}
