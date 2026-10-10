package com.lusion.sgauto

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.car.app.CarAppPermission
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.OnClickListener
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.lusion.sgauto.GarageUtility.GarageCommand
import com.lusion.sgauto.GarageUtility.GarageStatus
import com.lusion.sgauto.GarageUtility.requestGarageData
import com.lusion.sgauto.GarageUtility.sendGarageCommand
import com.lusion.sgauto.LusionUtility.getBitmapFromVectorDrawable
import com.lusion.sgauto.PreferenceUtility.getPreference
import java.util.Date
import java.util.Timer
import java.util.TimerTask

/**
 * Smarter Garage default Auto launch screen
 *
 *
 * See [SGService] for the app's entry point to the car host.
 */

class SGScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private var isBroadcastReceiverRegistered = false

    override fun onResume(owner: LifecycleOwner) {
        Log.i(TAG, "onResume")
        registerBroadcastReceiver()
    }

    override fun onGetTemplate(): Template {
        Log.i(TAG, "onGetTemplate")
        registerBroadcastReceiver()

        val homeLoc = getPreference("home", Location::class.java, carContext) as Location?
        val apiKey = getPreference("garage_device_id", String::class.java, carContext) as String?

        val homeSet = homeLoc != null
        val keySet = !apiKey.isNullOrEmpty() && apiKey != "NONE"

//        registerBroadcastReceiver()

        val currentTime = Date().time
        if (currentTime - lastRunTime > 10000) {
            lastRunTime = currentTime
            requestGarageData(carContext)
        }

        val resources = carContext.resources
        val itemList = ItemList.Builder()

        val garageStatusBitmap = when (GarageUtility.getLastGarageStatus(carContext)) {
            GarageStatus.CLOSING -> getBitmapFromVectorDrawable(carContext, R.drawable.garage_door_closing)
            GarageStatus.CLOSED -> getBitmapFromVectorDrawable(carContext, R.drawable.smarter_garage_svg)
            GarageStatus.OPENING -> getBitmapFromVectorDrawable(carContext, R.drawable.garage_door_opening)
            GarageStatus.OPEN -> getBitmapFromVectorDrawable(carContext, R.drawable.garage_door_open_red_vector)
            else -> getBitmapFromVectorDrawable(carContext, R.drawable.garage_door_unknown)
        }

        garageStatusBitmap?.let {
            val title = when (GarageUtility.getLastGarageStatus(carContext)) {
                GarageStatus.CLOSING -> resources.getString(R.string.open_close)
                GarageStatus.CLOSED -> resources.getString(R.string.open)
                GarageStatus.OPENING -> resources.getString(R.string.wait)
                GarageStatus.OPEN -> resources.getString(R.string.close)
                else -> resources.getString(R.string.wait)
            }

            val command = when (GarageUtility.getLastGarageStatus(carContext)) {
                GarageStatus.CLOSING, GarageStatus.CLOSED -> GarageCommand.OPEN
                GarageStatus.OPENING, GarageStatus.OPEN -> GarageCommand.CLOSE
                else -> null
            }

            val onClickListener = if (command != null) {
                OnClickListener { sendGarageCommand(carContext, command) }
            } else {
                OnClickListener {
                    if (keySet) {
                        Timer().schedule(object : TimerTask() {
                            override fun run() {
                                invalidate()
                            }
                        }, 2000)
                    } else {
                        CarToast.makeText(carContext, R.string.auto_setup_warning, CarToast.LENGTH_LONG).show()
                    }
                }
            }

            itemList.addItem(
                Row.Builder()
                    .setTitle(title)
                    .setImage(CarIcon.Builder(IconCompat.createWithBitmap(it)).build(), Row.IMAGE_TYPE_LARGE)
                    .setOnClickListener(onClickListener)
                    .build()
            )
        }

        val garagesListBitmap = getBitmapFromVectorDrawable(carContext, R.drawable.geofence_in)
        garagesListBitmap?.let {
            val homeClickListener = if (homeSet) {
                OnClickListener { screenManager.push(GarageParkingMapperScreen.create(carContext)) }
            } else {
                OnClickListener { CarToast.makeText(carContext, R.string.auto_setup_warning, CarToast.LENGTH_LONG).show() }
            }

            try {
                CarAppPermission.checkHasLibraryPermission(carContext, CarAppPermission.NAVIGATION_TEMPLATES)
                itemList.addItem(
                    Row.Builder()
                        .setTitle(resources.getString(R.string.show_garages))
                        .setImage(CarIcon.Builder(IconCompat.createWithBitmap(it)).build(), Row.IMAGE_TYPE_LARGE)
                        .setOnClickListener(homeClickListener)
                        .build()
                )
            } catch (e: Exception) {
                Log.d(TAG, "onGetTemplate: ${e.message}")
            }
        }

        return ListTemplate.Builder()
            .setSingleList(itemList.build())
            .build()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.i(TAG, "onDestroy")
        stopMonitoring()
    }

    override fun onPause(owner: LifecycleOwner) {
        Log.i(TAG, "onPause")
        stopMonitoring()
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.i(TAG, "onStop")
        stopMonitoring()
    }

    private fun stopMonitoring() {
        unregisterBroadcastReceiver()
    }

    private fun unregisterBroadcastReceiver() {
        Log.i(TAG, "unregisterBroadcastReceiver")
        if (isBroadcastReceiverRegistered) {
            try {
                carContext.unregisterReceiver(mBroadcastReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "mBroadcastReceiver not registered: ${e.message}")
            }
            isBroadcastReceiverRegistered = false
        }
    }

    private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            invalidate()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBroadcastReceiver() {
        if (isBroadcastReceiverRegistered) return

        Log.i(TAG, "registerBroadcastReceiver")
        val filter = IntentFilter("com.lusion.sgauto.SGScreen")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            carContext.registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            carContext.registerReceiver(mBroadcastReceiver, filter)
        }
        isBroadcastReceiverRegistered = true
    }

    companion object {
        private const val TAG = "SGScreen"
        private var lastRunTime: Long = 0
    }
}