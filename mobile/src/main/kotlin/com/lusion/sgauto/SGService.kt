package com.lusion.sgauto

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.validation.HostValidator
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.lusion.sgauto.GarageUtility.GarageStatus
import com.lusion.sgauto.GarageUtility.garageUpdateRunning
//import com.lusion.sgauto.GarageUtility.testPostRequest
import com.lusion.sgauto.LusionUtility.UpdateType

/**
 * Entry point for the hello world app.
 *
 *
 * [CarAppService] is the main interface between the app and the car host. For more
 * details, see the [Android for
 * Cars Library developer guide](https://developer.android.com/training/cars/navigation).
 */
class SGService : CarAppService() {
    private var garageUpdateThread: Thread? = null
    private var isUiMessageBroadcastReceiverRegistered = false
    private var isBroadcastReceiverRegistered = false

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        Log.d(TAG, "onCreate")
        super.onCreate()
    }

    override fun onCreateSession(): Session {
        Log.i(TAG, "onCreateSession")
        // The car host has already attached at this point. Do not wait for the separate
        // CarConnection LiveData observer before enabling car-specific notification behavior.
        LusionUtility.isInCarSession = true
        initNotifications(this)
        if (!isUiMessageBroadcastReceiverRegistered) {
            try {
                val filter = IntentFilter(getString(R.string.local_main_activity_listener))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(uiMessageBroadcastReceiver, filter, RECEIVER_EXPORTED)
                } else {
                    registerReceiver(uiMessageBroadcastReceiver, filter)
                }
                isUiMessageBroadcastReceiverRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register uiMessageBroadcastReceiver: ${e.message}")
            }
        }
        registerBroadcastReceiver()
        NotificationUtility.refreshForCarSession(applicationContext)
        // Starting Android Auto while already parked within the arrival radius produces no
        // geofence ENTER transition, so pop the arrival heads-up (OPEN/CLOSE controls) a few
        // seconds after the car attaches if we're actually near home.
        NotificationUtility.scheduleArrivalHeadsUpAfterCarStart(applicationContext)
        // Routed through the shared, debounced helper — the Auto car-screen being
        // created is effectively the same "car connected" event that CarConnectionReceiver
        // and SGAutoApp's LiveData observer also react to independently. Without
        // coordination, this could unconditionally start the background poller at the
        // same moment one of those other triggers starts the foreground service.
        GarageUtility.startCarConnectMonitoring(applicationContext, "SGService")
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                return SGScreen(carContext)
            }
        }
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    override fun createHostValidator(): HostValidator {
        Log.i(TAG, "createHostValidator")
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d(TAG, "Debug build: allowing all hosts")
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }

        return try {
            val builder = HostValidator.Builder(applicationContext)
            val hosts = resources.getStringArray(R.array.hosts_allowlist)
            val hostPattern = java.util.regex.Pattern.compile("^[a-zA-Z0-9._-]+:[a-fA-F0-9]{64}$")

            for (host in hosts) {
                val trimmedHost = host.trim()
                if (trimmedHost.isNotEmpty()) {
                    if (hostPattern.matcher(trimmedHost).matches()) {
                        val parts = trimmedHost.split(":")
                        if (parts.size == 2) {
                            Log.d(TAG, "Adding allowed host: ${parts[0]}")
                            builder.addAllowedHost(parts[0], parts[1])
                        }
                    } else {
                        Log.e(TAG, "Skipping invalid host entry format: '$trimmedHost'")
                    }
                }
            }
            builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "Error building HostValidator, falling back to ALLOW_ALL for recovery", e)
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        }
    }

    private fun stopMonitoring() {
        Log.i(TAG, "stopMonitoring")

        if (isUiMessageBroadcastReceiverRegistered) {
            try {
                unregisterReceiver(uiMessageBroadcastReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "uiMessageBroadcastReceiver not registered: ${e.message}")
            }
            isUiMessageBroadcastReceiverRegistered = false
        }
        unregisterBroadcastReceiver()

        try {
            val status = GarageUtility.getLastGarageStatus(applicationContext)
            if (status != GarageStatus.OPENING && status != GarageStatus.CLOSING) {
                garageUpdateRunning.set(false)
            }
            garageUpdateThread?.takeIf { it.isAlive }?.interrupt()
        } catch (e: Exception) {
            Log.e(TAG, "Error during stopMonitoring tasks: ${e.message}")
        }
    }

    private fun unregisterBroadcastReceiver() {
        Log.i(TAG, "unregisterBroadcastReceiver")
        if(isBroadcastReceiverRegistered) {
            try {
                applicationContext.unregisterReceiver(broadcastReceiver)
            } catch (e: Exception) {
                Log.d(TAG, "BroadcastReceiver not registered: ${e.message}")
            }
            isBroadcastReceiverRegistered = false
        }
    }

    private val uiMessageBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "uiMessageBroadcastReceiver")
            val myNumber = intent.getIntExtra("MYNUMBER", 0)
            val message = intent.getStringExtra("Message")
            val myMessage = intent.getStringExtra("MYMESSAGE")
            val action = intent.action
            Log.i(TAG, "CarAppService: received intent action $action and MYNUMBER $myNumber MYMESSAGE $myMessage")
            when (action) {
                getString(R.string.geofence_transition_exited) -> Log.i(TAG, "geofence_transition_exited")
                getString(R.string.geofence_transition_entered) -> Log.i(TAG, "geofence_transition_entered")
                getString(R.string.geofence_transition_dwelled) -> Log.i(TAG, "geofence_transition_dwelled")
                GARAGE_CLOSE -> Log.i(TAG, "GARAGE_CLOSE")
                GARAGE_OPEN -> Log.i(TAG, "GARAGE_OPEN")
            }
            if (!TextUtils.isEmpty(message)) Log.i(TAG, "NO MESSAGE")
        }
    }

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val responseType: UpdateType? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra("TYPE", UpdateType::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra("TYPE")?.let { UpdateType.valueOf(it.toString()) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing UpdateType from intent: ${e.message}")
                null
            }
            Log.i(TAG, "SGService BroadcastReceiver $responseType")
            responseType?.let {
                if (it == UpdateType.GARAGESTATUS) {
                    val garageStatus: GarageStatus? = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val newGarageStatus = intent.getSerializableExtra(
                                it.toString(),
                                GarageStatus::class.java
                            )
//                            testPostRequest(context, "Sender broadcastReceiver, newGarageStatus ${newGarageStatus.toString()}, autoGarageStatus ${autoGarageStatus.toString()}" )
                            newGarageStatus
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getSerializableExtra(it.toString()) as GarageStatus?
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SGScreen broadcastReceiver received unknown garage status ${e.message}")
//                        testPostRequest(context, "Sender broadcastReceiver, exception unknown status")
                        GarageStatus.UNKNOWN
                    }
                    if (garageStatus != GarageUtility.getLastGarageStatus(context)) {
//                        testPostRequest(context, "Sender broadcastReceiver, updating autoGarageStatus, current state garageStatus ${garageStatus.toString()}, autoGarageStatus ${autoGarageStatus.toString()}" )
                        Log.i(TAG, "triggerNotification $garageStatus")
//                        autoGarageStatus = garageStatus
                    }
                    val relayIntent = Intent("com.lusion.sgauto.SGScreen")
                    relayIntent.setPackage(context.packageName)
                    relayIntent.putExtras(intent)
                    Log.i(TAG, "SGService send intent com.lusion.sgauto.SGScreen")
                    context.sendBroadcast(relayIntent)
                }
            }
        }
    }

    // TODO bug this does not seem to get any intents from AutoUpdateRequestReceiver when garage events are happening
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerBroadcastReceiver() {
        if (isBroadcastReceiverRegistered) {
            Log.i(TAG, "registerBroadcastReceiver: already registered")
            return
        }
        Log.i(TAG, "registerBroadcastReceiver")
        val filter = IntentFilter("com.lusion.sgauto.SGService")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(broadcastReceiver, filter, RECEIVER_EXPORTED)
        } else {
            applicationContext.registerReceiver(broadcastReceiver, filter)
        }
        isBroadcastReceiverRegistered = true
    }

    companion object {
        private val TAG = SGService::class.qualifiedName!!
        private const val GARAGE_CLOSE = "GARAGE_CLOSE"
        private const val GARAGE_OPEN = "GARAGE_OPEN"
//        private var autoGarageStatus: GarageStatus? = gMyGarageStatus

        private fun initNotifications(context: Context) {
            val navChannel = NotificationChannelCompat.Builder(
                context.getString(R.string.app_name),
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
                .setName(context.getString(R.string.app_name)).build()
            CarNotificationManager.from(context).createNotificationChannel(navChannel)
        }
    }
}