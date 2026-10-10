package com.lusion.sgauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.WorkerThread
import com.lusion.sgauto.PreferenceUtility.getPreference

class BootReceiver : BroadcastReceiver() {

    @WorkerThread
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.e(TAG, "BootReceiver received intent: ${intent.action}")
//            testPostRequest(context, "BootReceiver ${intent.action}")

            if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                intent.action == Intent.ACTION_POWER_CONNECTED) {

                // Preserve the last confirmed state across an update. AA may start the
                // foreground service before its first network poll; clearing this status
                // would make that mandatory notification show UNKNOWN.
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    Log.i(TAG, "App updated, retaining last confirmed garage status until the immediate check completes")
                }

                val garageAccessKey = getPreference(KEY_GARAGE_DEVICE_ID, String::class.java, context) as String
                if (garageAccessKey.isNotEmpty()) {
                    // Android 15 prohibits BOOT_COMPLETED receivers from starting
                    // foreground services. Keep all work caused by boot at background
                    // priority; ordinary post-boot triggers may still elevate normally.
                    Log.i(TAG, "BootReceiver: scheduling garage status monitoring and running background-only immediate check")
                    GarageStatusWorker.scheduleGarageStatusWork(
                        context,
                        TAG,
                        allowForegroundEscalation = false
                    )
                    GarageStatusWorker.runNow(context, TAG, allowForegroundEscalation = false)
                }

                // Geofences do NOT survive a reboot — the OS wipes GeofencingClient's
                // registered state, so without this the app has zero geofence
                // monitoring until the user happens to manually open the app again.
                Log.i(TAG, "BootReceiver: re-registering home geofence without an initial transition")
                GeofenceUtility.ensureGeofencesRegistered(context, triggerInitialState = false)
            }
        }
        catch (e: Exception) {
            Log.w(TAG, "Error processing intent", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val KEY_GARAGE_DEVICE_ID = "garage_device_id"
    }
}