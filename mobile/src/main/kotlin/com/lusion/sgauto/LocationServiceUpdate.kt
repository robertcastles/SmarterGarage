package com.lusion.sgauto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.location.Location
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.lusion.sgauto.LocationUtility.createLocationRequest
import com.lusion.sgauto.LocationUtility.getLastLocation

//        import com.google.android.gms.location.LocationListener;
/**
 * A bound and started service that is promoted to a foreground service when location updates have
 * been requested and all clients unbind.
 * For apps running in the background on "O" devices, location is computed only once every 10
 * minutes and delivered batched every 30 minutes. This restriction applies even to apps
 * targeting "N" or lower which are run on "O" devices.
 * This sample show how to use a long-running service for location updates. When an activity is
 * bound to this service, frequent location updates are permitted. When the activity is removed
 * from the foreground, the service promotes itself to a foreground service, and location updates
 * continue. When the activity comes back to the foreground, the foreground service stops, and the
 * notification associated with that service is removed.
 */

//TODO this class has an entry in the manifest, but that entry is complaining about requiring the below
// foregroundServiceType:location requires permission:[android. permission. FOREGROUND_SERVICE_LOCATION] AND any permission in list:[android. permission. ACCESS_COARSE_LOCATION, android. permission. ACCESS_FINE_LOCATION]
// See if we need this in the manifest, since I think we only want this while the UI is running in the foreground

class LocationServiceUpdate : Service() {
    private val binder = LocalBinder()
    private var changingConfiguration = false
    private var locationRequest: LocationRequest? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var serviceHandler: Handler? = null
    /**
     * Used to check whether the bound activity has really gone away and not unbound as part of an
     * orientation change. We create a foreground service notification only if the former takes
     * place.
     */

    override fun onCreate() {
        Log.i(TAG, "onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                Log.i(TAG, "onLocationResult")
                super.onLocationResult(locationResult)
                onNewLocation(locationResult.lastLocation)
            }
        }
        locationRequest = createLocationRequest(this)
        getLastLocation(fusedLocationClient!!)
        val handlerThread = HandlerThread(TAG)
        handlerThread.start()
        serviceHandler = Handler(handlerThread.looper)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val name: CharSequence = getString(R.string.app_name)
        val channel = NotificationChannel(getString(R.string.app_name), name, NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        Log.i(TAG, "Service started")
//        if (intent == null) {
//            Log.e(TAG, "Received null intent in onStartCommand")
//            return START_REDELIVER_INTENT
//        }
//        val startedFromNotification = intent.getBooleanExtra(EXTRA_STARTED_FROM_NOTIFICATION, false)
//        if (startedFromNotification) {
//            removeLocationUpdates()
//            stopSelf()
//        }
        return START_REDELIVER_INTENT
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        changingConfiguration = true
    }

    override fun onBind(intent: Intent): IBinder {
        // Called when a client (MainActivity in case of this sample) comes to the foreground
        // and binds with this service. The service should cease to be a foreground service
        // when that happens.
        Log.i(TAG, "in onBind()")
        stopForeground(STOP_FOREGROUND_REMOVE)
        changingConfiguration = false
        return binder
    }

    override fun onRebind(intent: Intent) {
        // Called when a client (MainActivity in case of this sample) returns to the foreground
        // and binds once again with this service. The service should cease to be a foreground
        // service when that happens.
        Log.i(TAG, "onRebind()")
        stopForeground(STOP_FOREGROUND_REMOVE)
        changingConfiguration = false
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.i(TAG, "onUnbind")

        // Called when the last client (MainActivity in case of this sample) unbinds from this
        // service. If this method is called due to a configuration change in MainActivity, we
        // do nothing. Otherwise, we make this service a foreground service.
        if (!changingConfiguration && Utils.requestingLocationUpdates(this)) {
            Log.i(TAG, "Starting foreground service")
        }
        return true // Ensures onRebind() is called when a client re-binds.
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        serviceHandler?.removeCallbacksAndMessages(null)
    }

    /**
     * Makes a request for location updates. Note that in this sample we merely log the
     * [SecurityException].
     */
    fun requestLocationUpdates() {
        Log.i(TAG, "requestLocationUpdates")
        Utils.setRequestingLocationUpdates(this, true)
        try {
            startService(Intent(applicationContext, LocationServiceUpdate::class.java))
            fusedLocationClient?.requestLocationUpdates(locationRequest!!, locationCallback!!, Looper.myLooper())
        } catch (e: SecurityException) {
            Utils.setRequestingLocationUpdates(this, false)
            Log.w(TAG, "Lost location permission. Could not request updates. $e")
        }
    }

    /**
     * Removes location updates. Note that in this sample we merely log the
     * [SecurityException].
     */
    fun removeLocationUpdates() {
        Log.i(TAG, "removeLocationUpdates")
        try {
            fusedLocationClient?.removeLocationUpdates(locationCallback!!)
            Utils.setRequestingLocationUpdates(this, false)
            stopSelf()
        } catch (e: SecurityException) {
            Utils.setRequestingLocationUpdates(this, true)
            Log.w(TAG, "Lost location permission. Could not remove updates. $e")
        }
    }

    private fun onNewLocation(location: Location?) {
        val intent = Intent(ACTION_BROADCAST)
        intent.putExtra(EXTRA_LOCATION, location)
        Log.i(TAG, "onNewLocation broadcasting ${intent.action}")
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    /**
     * Class used for the client Binder.  Since this service runs in the same process as its
     * clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        val service: LocationServiceUpdate
            get() = this@LocationServiceUpdate
    }

    companion object {
        private const val PACKAGE_NAME = "com.lusion.smartergarage"
        private val TAG = LocationServiceUpdate::class.java.simpleName
        const val ACTION_BROADCAST = "$PACKAGE_NAME.broadcast"
        const val EXTRA_LOCATION = "$PACKAGE_NAME.location"
//        private const val EXTRA_STARTED_FROM_NOTIFICATION = "$PACKAGE_NAME.started_from_notification"
    }
}