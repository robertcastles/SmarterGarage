package com.lusion.sgauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.app.UiModeManager
import com.lusion.sgauto.PersistentLog as Log
import androidx.car.app.connection.CarConnection

class CarConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED") {
            val carConnection = CarConnection(context)
            val connectionType = carConnection.type.value

            Log.i(TAG, "Car connection updated callback. ConnectionType from LiveData: $connectionType")

            // In a BroadcastReceiver, carConnection.type.value might return null because the LiveData
            // hasn't had time to fetch the value from the content provider yet.
            // We use UiModeManager as a fallback to detect if we're currently in car mode.
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val isCarMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR

            Log.i(TAG, "System Car Mode check: $isCarMode")

            val isConnected = connectionType == CarConnection.CONNECTION_TYPE_PROJECTION ||
                             connectionType == CarConnection.CONNECTION_TYPE_NATIVE ||
                             (connectionType == null && isCarMode)

            if (isConnected) {
                Log.i(TAG, "Connected to car (Android Auto/Automotive). Refreshing garage status...")

                // Routed through the shared, debounced helper — SGAutoApp's in-process
                // CarConnection LiveData observer reacts to this SAME real-world event and
                // would otherwise race this receiver, potentially leaving both a foreground
                // service AND a background poller running concurrently.
                GarageUtility.startCarConnectMonitoring(context, TAG)
            } else {
                Log.i(TAG, "Disconnected or not in car mode. connectionType=$connectionType, isCarMode=$isCarMode")
            }
        }
    }

    companion object {
        private const val TAG = "CarConnectionReceiver"
    }
}