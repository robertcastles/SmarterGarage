package com.lusion.sgauto
//
//import android.Manifest
//import android.app.UiModeManager
//import android.bluetooth.BluetoothClass
//import android.bluetooth.BluetoothDevice
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.content.pm.PackageManager
//import android.os.Build
//import android.os.Handler
//import android.os.Looper
//import android.util.Log
//import androidx.annotation.RequiresApi
//import androidx.core.app.ActivityCompat
//
//class CarModeReceiver : BroadcastReceiver() {
//    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
//    override fun onReceive(context: Context, intent: Intent) {
//        Log.e(TAG, "CarModeReceiver")
//        val action = intent.action
//        if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
//            val device: BluetoothDevice? =
//                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
//
//            if (ActivityCompat.checkSelfPermission(
//                    context,
//                    Manifest.permission.BLUETOOTH_CONNECT
//                ) != PackageManager.PERMISSION_GRANTED
//            ) {
//                device?.let {
//                    val deviceClass = it.bluetoothClass?.deviceClass
//
//                    if (deviceClass == BluetoothClass.Device.Major.UNCATEGORIZED ||
//                        deviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
//                        deviceClass == BluetoothClass.Device.Major.PHONE
//                    ) {
//                        // The connected Bluetooth device is likely a car's infotainment system
//                        // You can start your Android Auto app or send a notification
//                        LusionUtility.ongoingGarageStatusThread(context, TAG)
//                    }
//                }
//            }
//        }
//        if (UiModeManager.ACTION_ENTER_CAR_MODE == action) {
//            Log.i(TAG, "Entered Car Mode")
//            val r = Runnable { LusionUtility.getGarageStatus(context) }
//            val looper = Looper.myLooper()
//            if (looper != null) Handler(Looper.myLooper()!!).postDelayed(r, 0)
//            Log.w(TAG, "looper is null, can't getGarageStatus")
//        } else if (UiModeManager.ACTION_EXIT_CAR_MODE == action) {
//            Log.i(TAG, "Exited Car Mode")
//        }
//    }
//
//    companion object {
//        private const val TAG = "CarModeReceiver"
//    }
//}