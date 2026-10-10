package com.lusion.sgauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.TextUtils
import com.lusion.sgauto.PersistentLog as Log
import android.widget.Toast
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.lusion.sgauto.GarageUtility.GarageStatus
import com.lusion.sgauto.GarageUtility.getLastGarageStatus
import com.lusion.sgauto.GeofenceUtility.logGeofence
import com.lusion.sgauto.NotificationUtility.dismissNotification
import com.lusion.sgauto.NotificationUtility.triggerNotification
import com.lusion.sgauto.PreferenceUtility.setPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive")

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent?.hasError() == true) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            Log.i(TAG, "geo errorMessage $errorMessage")
            return
        }

        val geofenceTransition = geofencingEvent?.geofenceTransition ?: return
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
        val geofenceTransitionDetails = getGeofenceTransitionDetails(context, geofenceTransition, triggeringGeofences)

        Log.i(TAG, "onReceive: geofenceTransitionDetails $geofenceTransitionDetails")

        when (geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_DWELL -> handleTransition(context, "DWELLING", "DWELLING")
            Geofence.GEOFENCE_TRANSITION_ENTER -> handleTransition(context, "ENTERED", "ENTERED")
            Geofence.GEOFENCE_TRANSITION_EXIT -> handleExitTransition(context)
            else -> Log.e(TAG, "geo invalid transition $geofenceTransition")
        }

        if (geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) {
            // Routed through the shared, debounced helper in GarageUtility. Geofence
            // transitions can occur very close in time to a car-connect event (e.g.
            // arriving home while Android Auto is connected) — using the same
            // coordinated entry point as the car-connect triggers prevents one from
            // starting the foreground service while another falls back to the
            // background thread, which would leave two pollers running concurrently.
            GarageUtility.startCarConnectMonitoring(context, TAG)
        }
    }

    private fun handleTransition(context: Context, type: String, logMessage: String) {
        setPreference("geofenceMessage", type, context)
        logGeofence(context, logMessage)

        // ENTERED or DWELLING
        val lastStatus = getLastGarageStatus(context)
        triggerNotification(context, LusionUtility.UpdateType.GEOFENCE, lastStatus)
        monitorGarage(context)
    }

    /**
     * Handles GEOFENCE_TRANSITION_EXIT. Unlike ENTER/DWELL, this decision matters a lot:
     * getting it wrong means either the notification never dismisses (annoying) or it
     * dismisses when the door is actually still open (the whole point of the feature).
     *
     * The CACHED last-known garage status ([getLastGarageStatus]) can be stale by the
     * time an EXIT transition fires — Android's own geofence exit detection can lag the
     * real-world exit by a while, and if monitoring wasn't actively polling right up to
     * that moment the cache may not reflect reality. So: apply the cached status
     * immediately (keeps the UI responsive — matches previous behavior), then kick off a
     * SINGLE fresh status probe via [goAsync] (required since this is a network round
     * trip that would otherwise be cut short the instant onReceive() returns). That
     * probe's normal response handling (GarageUtility.updateGarageAppAreas) already
     * re-evaluates keep-vs-dismiss against the now-current "EXITED" geofence state, so it
     * will self-correct shortly after if the immediate cached-status decision was wrong.
     */
    private fun handleExitTransition(context: Context) {
        setPreference("geofenceMessage", "EXITED", context)
        logGeofence(context, "DISMISS")

        val cachedStatus = getLastGarageStatus(context)
        if (GarageUtility.shouldKeepNotificationOnGeofenceExit(cachedStatus)) {
            triggerNotification(context, LusionUtility.UpdateType.GEOFENCE, cachedStatus)
        } else {
            dismissNotification(context)
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                GarageUtility.makeStatusRequest(appContext, ignoreTimers = true, "$TAG-exitProbe")
            } catch (e: Exception) {
                Log.w(TAG, "handleExitTransition: fresh status probe failed: ${e.message}")
            } finally {
                monitorGarage(appContext)
                pendingResult.finish()
            }
        }
    }

    private fun monitorGarage(context: Context) {
        val garageWorkRequest: WorkRequest = OneTimeWorkRequest.Builder(GarageCommandWorker::class.java)
            .setInputData(Data.Builder().putString("ACTION", "MONITOR").build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(garageWorkRequest)
    }

    private fun getGeofenceTransitionDetails(context: Context, geofenceTransition: Int, triggeringGeofences: List<Geofence>): String {
        val geofenceTransitionString = getTransitionString(context, geofenceTransition)
        val triggeringGeofencesIdsList = triggeringGeofences.map { it.requestId }
        val triggeringGeofencesIdsString = TextUtils.join(", ", triggeringGeofencesIdsList)
        return "$geofenceTransitionString: $triggeringGeofencesIdsString"
    }

    private fun getTransitionString(context: Context, transitionType: Int): String {
        return when (transitionType) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "transition - enter"
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                toast(context, "Geofence exited")
                "transition - exit"
            }
            Geofence.GEOFENCE_TRANSITION_DWELL -> {
                toast(context, "Geofence Dwell")
                "transition - Dwell"
            }
            else -> "unknown transition"
        }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "GeofenceBroadcastReceiver"
    }
}