package com.lusion.sgauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// This AutoUpdateRequestReceiver runs in auto app and acts as a public accessible proxy to receive intents from our mobile app which then must be relayed to the SGScreen by new intent.
class AutoUpdateRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == null) {
            Log.w(TAG, "Received intent with no action")
            return
        }

        // Security check: ensure the action is one we expect from manifest
        val allowedActions = listOf(
            "com.lusion.sgauto.AutoUpdateRequestReceiver",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.MY_PACKAGE_ADDED",
            "android.app.action.ENTER_CAR_MODE"
        )

        if (action !in allowedActions) {
            Log.w(TAG, "Received intent with unexpected action: $action")
            return
        }

        try {
            Log.i(TAG, "AutoUpdateRequestReceiver rebroadcasting internally with SGService")

            val relayIntent = Intent(ACTION_SG_SERVICE).apply {
                putExtras(intent)
                setPackage(context.packageName)
            }

            Log.i(TAG, "context.sendBroadcast(relayIntent)")
            context.sendBroadcast(relayIntent)

            // Break the infinite loop: Only trigger a new status fetch if this isn't already a status update relay
            if (action != "com.lusion.sgauto.AutoUpdateRequestReceiver") {
                Log.d(TAG, "Trigger action '$action' received, enqueuing GarageStatusWorker")
                GarageStatusWorker.runNow(context, TAG)
                GarageStatusWorker.scheduleGarageStatusWork(context, TAG)
            } else {
                Log.d(TAG, "Data relay action received, status update already in progress")
            }

            // ENTER_CAR_MODE: start full monitoring, not just a WorkManager job.
            // This is a protected system broadcast that CAN wake a dead process.
            if (action == "android.app.action.ENTER_CAR_MODE") {
                Log.i(TAG, "ENTER_CAR_MODE: starting full garage monitoring")
                LusionUtility.isInCarSession = true
                // Routed through the shared, debounced helper — CarConnectionReceiver /
                // SGAutoApp's LiveData observer may fire for this same underlying event and
                // would otherwise race this receiver, potentially leaving both a foreground
                // service AND a background poller running concurrently.
                GarageUtility.startCarConnectMonitoring(context, TAG)
            }
        } catch (e: Exception) {
            Log.w(TAG, e.message ?: "Unknown error")
        }
    }

    companion object {
        private const val TAG = "AutoUpdateReqReceiver"
        private const val ACTION_SG_SERVICE = "com.lusion.sgauto.SGService"
    }
}