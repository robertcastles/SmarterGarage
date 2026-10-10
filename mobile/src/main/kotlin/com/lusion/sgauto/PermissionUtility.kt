package com.lusion.sgauto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

object PermissionUtility {
    private const val TAG = "PermissionUtility"

    // Set to true after we call requestPermissions for background location.
    // Prevents calling requestPermissions again from onResume (which would loop).
    // Reset when the user navigates away and comes back (new activity session).
    private var backgroundLocationRequested = false

    /** Set to true after we show the battery optimization dialog this session. */
    private var batteryOptimizationRequested = false

    fun permissionGranted(context: Context, permissionName: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED
    }

    fun isFineLocationGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun isBackgroundLocationGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun checkLocationPermissions(context: Context): Boolean {
        return isFineLocationGranted(context) && isBackgroundLocationGranted(context)
    }

    // ---- Battery optimization (Doze / Samsung Freecess / MARs) ----

    /**
     * Check if the app is exempt from battery optimizations.
     * When not exempt, Samsung's Freecess freezes the process and MARs blocks
     * all network traffic — even when a foreground service is running.
     */
    fun isBatteryOptimizationExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Prompt the user to exempt the app from battery optimizations.
     * Uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS for a direct system dialog.
     *
     * On Samsung devices, even this exemption may not fully prevent Freecess/MARs
     * throttling, so we also guide users to Samsung's "Unrestricted" setting when
     * the standard exemption isn't enough.
     *
     * Returns true if a prompt was shown, false if already exempt.
     */
    @SuppressLint("BatteryLife") // We have a legitimate IoT monitoring use case
    fun requestBatteryOptimizationExemption(activity: AppCompatActivity): Boolean {
        if (isBatteryOptimizationExempt(activity)) {
            Log.d(TAG, "Already exempt from battery optimizations")
            batteryOptimizationRequested = false
            return false
        }

        if (batteryOptimizationRequested) {
            Log.d(TAG, "Battery optimization already requested this session")
            return false
        }

        batteryOptimizationRequested = true
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        val message = buildString {
            append("Smarter Garage needs to run in the background to monitor your garage status ")
            append("and respond to Android Auto notifications.\n\n")
            if (isSamsung) {
                append("Your Samsung device aggressively restricts background apps, which can ")
                append("block network access and prevent garage status updates.\n\n")
                append("Please allow \"Unrestricted\" battery usage on the next screen.")
            } else {
                append("Please allow unrestricted battery usage so the app can maintain ")
                append("its network connection for garage status updates.")
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("Battery Optimization")
            .setMessage(message)
            .setPositiveButton("Allow") { _, _ ->
                try {
                    // First try the direct system dialog
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${activity.packageName}".toUri()
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Direct battery optimization request failed, opening settings: ${e.message}")
                    // Fallback: open battery optimization settings list
                    openBatteryOptimizationSettings(activity)
                }
            }
            .setNegativeButton("Not Now") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .create()
            .show()

        return true
    }

    /**
     * On Samsung devices, guide the user to the additional "Unrestricted" setting
     * in Device Care → Battery → Background usage limits.
     * This prevents Freecess process freezing and MARs network traffic blocking.
     */
    fun promptSamsungUnrestrictedIfNeeded(activity: AppCompatActivity): Boolean {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return false
        if (!isBatteryOptimizationExempt(activity)) return false // Handle standard exemption first

        // Check if we've already prompted for Samsung unrestricted this install
        val alreadyPrompted = PreferenceUtility.getPreference(
            PREF_SAMSUNG_UNRESTRICTED_PROMPTED, Boolean::class.java, activity
        ) as? Boolean ?: false
        if (alreadyPrompted) return false

        PreferenceUtility.setPreference(PREF_SAMSUNG_UNRESTRICTED_PROMPTED, true, activity)

        AlertDialog.Builder(activity)
            .setTitle("Samsung Battery Settings")
            .setMessage(
                "For the best experience on Samsung devices, also add Smarter Garage to the " +
                "\"Never sleeping apps\" list.\n\n" +
                "Go to: Settings → Battery → Background usage limits → Never sleeping apps → " +
                "tap \"+\" and add Smarter Garage.\n\n" +
                "This prevents Samsung from freezing the app and blocking network access."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openSamsungBatterySettings(activity)
            }
            .setNegativeButton("Skip") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .create()
            .show()

        return true
    }

    private fun openBatteryOptimizationSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Could not open battery optimization settings: ${e.message}")
        }
    }

    private fun openSamsungBatterySettings(context: Context) {
        // Try Samsung-specific intents, falling back to general battery settings
        val intents = listOf(
            // Samsung Device Care → Battery → Background usage limits
            Intent("com.samsung.android.sm.ACTION_BATTERY_USAGE_LIMIT").apply {
                setPackage("com.samsung.android.lool")
            },
            // Samsung Battery settings
            Intent().apply {
                setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
            },
            // Generic battery saver settings
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            // General settings fallback
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                continue
            }
        }
    }

    private const val PREF_SAMSUNG_UNRESTRICTED_PROMPTED = "samsung_unrestricted_prompted"

    /**
     * Request the next missing location permission. Call from SettingsActivity only.
     * Returns true if a request was initiated, false if all granted or nothing more to do.
     */
    fun requestNextLocationPermission(activity: AppCompatActivity): Boolean {
        if (!isFineLocationGranted(activity)) {
            Log.d(TAG, "Requesting ACCESS_FINE_LOCATION")
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                Constants.ACCESS_FINE_LOCATION_REQUEST
            )
            return true
        }
        if (!isBackgroundLocationGranted(activity)) {
            if (backgroundLocationRequested) {
                // Already tried once this session — don't loop.
                Log.d(TAG, "Background location already requested this session, not re-requesting")
                return false
            }
            Log.d(TAG, "Showing background location rationale")
            backgroundLocationRequested = true
            AlertDialog.Builder(activity)
                .setTitle("Background Location")
                .setMessage(
                    "Smarter Garage uses background location to support geofence features " +
                    "and automated notification display.\n\n" +
                    "On the next screen, please select \"Allow all the time\"."
                )
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    Log.d(TAG, "Requesting ACCESS_BACKGROUND_LOCATION via system API")
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                        Constants.ACCESS_BACKGROUND_LOCATION_REQUEST
                    )
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .create()
                .show()
            return true
        }
        // All granted
        backgroundLocationRequested = false
        return false
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun requestNotificationPermissionIfNeeded(activity: AppCompatActivity): Boolean {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Requesting POST_NOTIFICATIONS")
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                Constants.POST_NOTIFICATIONS_REQUEST
            )
            return true
        }
        return false
    }

    // ---- Legacy methods kept for other callers ----

    fun requestPermission(activity: AppCompatActivity, permissionName: String, requestCode: Int) {
        Log.d(TAG, "requestPermission: $permissionName $requestCode")
        ActivityCompat.requestPermissions(activity, arrayOf(permissionName), requestCode)
    }

    fun clearRequestingFlag() {
        // No-op — kept for callers
    }
}
