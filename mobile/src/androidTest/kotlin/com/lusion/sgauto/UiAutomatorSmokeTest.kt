package com.lusion.sgauto

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

/** Never taps a command, credential, location, or routing control. Use a provisioned fake-only AVD. */
@RunWith(AndroidJUnit4::class)
class UiAutomatorSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before fun requireLocalFakeServer() {
        assertTrue("Use a disposable emulator", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val prefs = PreferenceUtility.getSharedPreferences(context)
        assertTrue("Enable fake-server debug routing before testing", prefs.getBoolean("debug_switch", false))
        val endpoint = URI(prefs.getString("debug_server", "").orEmpty())
        assertTrue("Only emulator-local HTTPS fake servers are allowed",
            endpoint.scheme == "https" && endpoint.host in setOf("10.0.2.2", "localhost", "127.0.0.1"))
        assertTrue("Select a fake garage first", prefs.getString("garage_device_id", "") !in setOf(null, "", "NONE"))
    }

    @Test fun mainScreenSurvivesBackgroundAndResume() {
        fun launchAndCheck() {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Main screen/settings control missing or setup prompt blocks it",
                device.wait(Until.hasObject(By.pkg(context.packageName).desc("Settings")), 10_000))
            assertTrue("Geofence indicator missing",
                device.wait(Until.hasObject(By.pkg(context.packageName).desc("Geofence indicator")), 5_000))
        }
        try {
            repeat(3) {
                launchAndCheck()
                device.pressHome()
                device.waitForIdle()
            }
            launchAndCheck()
        } finally {
            device.pressHome()
        }
    }

    @Test fun inspectAndroidAutoAccessibilityOnAllDisplays() {
        assumeTrue("All-display accessibility requires API 30+", Build.VERSION.SDK_INT >= 30)
        val automation = instrumentation.uiAutomation
        val previous = automation.serviceInfo
        val originalFlags = previous.flags
        val enhanced = automation.serviceInfo
        enhanced.flags = enhanced.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = enhanced
        try {
            val displays = context.getSystemService(DisplayManager::class.java).displays
            val projection = displays.filter { it.displayId != 0 &&
                (it.name.contains("GhFacetBar") || it.name.contains("Dashboard") || it.name.contains("GhostActivityDisplay")) }
            val windows = automation.windowsOnAllDisplays
            var hostRoots = 0
            var interactiveNodes = 0
            for (index in 0 until windows.size()) {
                val displayId = windows.keyAt(index)
                windows.valueAt(index).forEach { window ->
                    val root = window.root
                    val packageName = root?.packageName?.toString()
                    Log.i(TAG, "display=$displayId window=${window.id} type=${window.type} root=${root != null} package=$packageName")
                    if (displayId != 0 && packageName == "com.google.android.projection.gearhead") {
                        hostRoots++
                        fun count(node: android.view.accessibility.AccessibilityNodeInfo): Int {
                            var total = if (node.isClickable) 1 else 0
                            for (childIndex in 0 until node.childCount) node.getChild(childIndex)?.let { total += count(it) }
                            return total
                        }
                        interactiveNodes += count(root)
                    }
                }
            }
            Log.i(TAG, "projectionDisplays=${projection.size} hostRoots=$hostRoots clickableHostNodes=$interactiveNodes")
            val requireHost = InstrumentationRegistry.getArguments().getString("requireAutoHost") == "true"
            if (requireHost) {
                assertTrue("DHU projection display is not connected", projection.isNotEmpty())
                assertTrue("Projection exists but host controls are not accessible; use DHU console/OCR", hostRoots > 0 && interactiveNodes > 0)
            }
        } finally {
            previous.flags = originalFlags
            automation.serviceInfo = previous
        }
    }

    companion object { private const val TAG = "SGUiAutomator" }
}

