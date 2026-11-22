package com.lusion.sgauto

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.car.app.connection.CarConnection
import com.lusion.sgauto.PersistentLog as Log

class CarConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CAR_CONNECTION_UPDATED -> handleCarConnectionUpdated(context)
            BluetoothDevice.ACTION_ACL_CONNECTED -> handleBluetoothConnected(context, intent, "BT_ACL")
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                    handleBluetoothConnected(context, intent, "BT_HFP")
                }
            }
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                    handleBluetoothConnected(context, intent, "BT_A2DP")
                }
            }
        }
    }

    private fun handleCarConnectionUpdated(context: Context) {
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
            LusionUtility.isInCarSession = true

            // Routed through the shared, debounced helper — SGAutoApp's in-process
            // CarConnection LiveData observer reacts to this SAME real-world event and
            // would otherwise race this receiver, potentially leaving both a foreground
            // service AND a background poller running concurrently.
            GarageUtility.startCarConnectMonitoring(context, TAG)
        } else {
            Log.i(TAG, "Disconnected or not in car mode. connectionType=$connectionType, isCarMode=$isCarMode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothConnected(context: Context, intent: Intent, source: String) {
        val device = extractBluetoothDevice(intent)
        val majorClass = if (device != null && PermissionUtility.isBluetoothConnectGranted(context)) {
            try {
                device.bluetoothClass?.majorDeviceClass
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not inspect Bluetooth device class: ${e.message}")
                null
            }
        } else {
            null
        }

        val shouldWatch = CarBluetoothWakePolicy.shouldWatchForCarConnection(
            majorDeviceClass = majorClass,
            alreadyInCarSession = LusionUtility.isInCarSession
        )
        Log.i(TAG, "Bluetooth connected ($source, majorClass=$majorClass, inCar=${LusionUtility.isInCarSession}) -> watch=$shouldWatch")
        if (shouldWatch) {
            CarConnectionCheckWorker.enqueue(context, source)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractBluetoothDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    companion object {
        private const val TAG = "CarConnectionReceiver"
        private const val ACTION_CAR_CONNECTION_UPDATED =
            "androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"
    }
}