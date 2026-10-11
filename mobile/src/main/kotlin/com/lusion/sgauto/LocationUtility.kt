package com.lusion.sgauto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import com.lusion.sgauto.PreferenceUtility.setPreference

object LocationUtility {
    private const val TAG = "LocationUtility"
    private var mFusedLocationClient: FusedLocationProviderClient? = null

    fun initializeLocationClient(context: Context): FusedLocationProviderClient {
        if (mFusedLocationClient == null) {
            mFusedLocationClient = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        }
        return mFusedLocationClient!!
    }

    private fun isGooglePlayServicesAvailable(context: Context): Boolean {
        val googleApiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context)
        return resultCode == ConnectionResult.SUCCESS
    }

    private fun permissionGranted(context: Context, permissionName: String): Boolean {
        Log.d(TAG, "LU.permissionGranted $permissionName")
        return (ActivityCompat.checkSelfPermission(context, permissionName) == PackageManager.PERMISSION_GRANTED)
    }

    private fun requestPermissions(activity: Activity) {
        val shouldProvideRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        val message = if (shouldProvideRationale) {
            activity.applicationContext.getString(R.string.fine_location_permission_rationale)
        } else {
            "Requesting permission"
        }
        showToast(activity, message)
    }

    private fun showToast(activity: Activity, text: String?) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    fun setHomeWithCurrentLocation(activity: AppCompatActivity, context: Context) {
        if (!isGooglePlayServicesAvailable(context)) {
            Log.e(TAG, "Google Play Services is not available.")
            showToast(activity, "Google Play Services is not available")
            return
        }
        Log.i(TAG, "setHomeWithCurrentLocation")
        if (!permissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) || !permissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            requestPermissions(activity)
            return
        }

        showToast(activity, "Getting current location…")

        val fusedLocationClient = initializeLocationClient(context)

        // Cancel replacement requests and any request whose UI owner stops.
        homeRequests.remove(activity)?.close()
        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        val active = AtomicBoolean(true)
        val handler = Handler(Looper.getMainLooper())
        lateinit var observer: DefaultLifecycleObserver
        lateinit var timeout: Runnable
        val subscription = AutoCloseable {
            if (active.compareAndSet(true, false)) {
                cts.cancel()
                handler.removeCallbacks(timeout)
                activity.lifecycle.removeObserver(observer)
                homeRequests.remove(activity)
            }
        }
        observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = subscription.close()
        }
        timeout = Runnable {
            if (active.get()) {
                subscription.close()
                showToast(activity, "Location timed out; please try again")
            }
        }
        homeRequests[activity] = subscription
        activity.lifecycle.addObserver(observer)
        handler.postDelayed(timeout, 30_000L)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(15_000L)
            .setDurationMillis(30_000L)
            .build()
        fusedLocationClient.getCurrentLocation(request, cts.token)
            .addOnSuccessListener { location ->
                if (!active.get()) return@addOnSuccessListener
                subscription.close()
                val ageMs = location?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
                if (location != null && ageMs != null && ageMs in 0..15_000 &&
                    location.hasAccuracy() && location.accuracy <= 100f) {
                    setHomeWithLocation(location, context)
                    showToast(activity, "Home location set")
                    SettingsActivity.refreshGeofenceUI()
                } else {
                    showToast(activity, "No accurate fresh location; please try again")
                }
            }.addOnFailureListener { e ->
                if (active.get()) {
                    subscription.close()
                    Log.e(TAG, "getCurrentLocation failed", e)
                    showToast(activity, "Failed to get location; please try again")
                }
            }
    }

    @SuppressLint("MissingPermission")
    fun setHomeWithLocation(location: Location?, context: Context) {
        Log.i(TAG, "setHomeWithLocation")
        if (!isGooglePlayServicesAvailable(context)) {
            Log.e(TAG, "Google Play Services is not available.")
            return
        }

        if (location == null) {
            return
        }

        setPreference("home", location, SGAutoApp.getAppContext())
    }


    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(context: Context, onLocationUpdated: (Location) -> Unit ): AutoCloseable {
        Log.d(TAG, "startLocationUpdates")
        val fusedLocationClient = initializeLocationClient(context)
        val active = AtomicBoolean(true)
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Only use the most recent location. When Android unfreezes the app
                // it delivers all queued locations at once, causing a flood of
                // notification/UI update calls. We only need the latest position.
                if (active.get()) locationResult.lastLocation?.let { onLocationUpdated(it) }
            }
        }

        val locationRequest = createLocationRequest(context)
        if (!permissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) || !permissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return AutoCloseable { active.set(false) }
        }

        val subscription = AutoCloseable {
            if (active.compareAndSet(true, false)) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                    .addOnFailureListener { Log.w(TAG, "Failed to remove location updates", it) }
            }
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            .addOnSuccessListener {
                // Removal may race asynchronous registration: remove again once registered.
                if (!active.get()) fusedLocationClient.removeLocationUpdates(locationCallback)
            }
            .addOnFailureListener {
                subscription.close()
                Log.w(TAG, "Failed to register location updates", it)
            }
        return subscription
    }

    fun createLocationRequest(context: Context): LocationRequest {
        Log.i(TAG, "createLocationRequest")
        var locationUpdateRate = PreferenceUtility.getSharedPreferences(context).all["locationUpdateRate"]
            ?.toString()?.toIntOrNull()?.coerceIn(2, 3600) ?: 50
        locationUpdateRate *= 1000
        return LocationRequest.Builder(locationUpdateRate.toLong())
            .setMinUpdateIntervalMillis((locationUpdateRate / 2).toLong())
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun getLastLocation(fusedLocationClient: FusedLocationProviderClient) {
        fusedLocationClient.lastLocation.addOnCompleteListener { task: Task<Location?> ->
            if (!(task.isSuccessful && task.result != null)) {
                Log.w(TAG, "Failed to get location.")
            }
        }
    }

    private val homeRequests = java.util.WeakHashMap<AppCompatActivity, AutoCloseable>()
}