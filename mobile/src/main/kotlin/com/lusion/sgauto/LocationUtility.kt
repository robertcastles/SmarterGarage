package com.lusion.sgauto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import com.lusion.sgauto.PreferenceUtility.getPreference
import com.lusion.sgauto.PreferenceUtility.setPreference

object LocationUtility {
    private const val TAG = "LocationUtility"
    private var mFusedLocationClient: FusedLocationProviderClient? = null

    fun initializeLocationClient(context: Context): FusedLocationProviderClient {
        if (mFusedLocationClient == null) {
            mFusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
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

        // Try getCurrentLocation for a fresh fix (better than lastLocation which can be null/stale)
        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    Log.i(TAG, "getCurrentLocation success: $location")
                    setHomeWithLocation(location, context)
                    showToast(activity, "Home location set")
                    SettingsActivity.refreshGeofenceUI()
                } else {
                    // Fall back to lastLocation
                    Log.d(TAG, "getCurrentLocation returned null, trying lastLocation")
                    fusedLocationClient.lastLocation.addOnCompleteListener { task: Task<Location?> ->
                        val lastLoc = task.result
                        if (lastLoc != null) {
                            setHomeWithLocation(lastLoc, context)
                            showToast(activity, "Home location set")
                            SettingsActivity.refreshGeofenceUI()
                        } else {
                            Log.d(TAG, "lastLocation also null, requesting location updates")
                            showToast(activity, "Waiting for location fix, please try again in a moment")
                            // Kick off a location update so a future attempt will work
                            val locationRequest = createLocationRequest(context)
                            fusedLocationClient.requestLocationUpdates(locationRequest, mSetHomeLocationCallback, Looper.myLooper())
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "getCurrentLocation failed", e)
                showToast(activity, "Failed to get location: ${e.message}")
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
            val locationRequest = createLocationRequest(context)
            val fusedLocationClient = initializeLocationClient(context)
            fusedLocationClient.requestLocationUpdates(locationRequest, mSetHomeLocationCallback, Looper.myLooper())
            return
        }

        setPreference("home", location, SGAutoApp.getAppContext())
    }


    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(context: Context, onLocationUpdated: (Location) -> Unit ) {
        Log.d(TAG, "startLocationUpdates")
        val fusedLocationClient = initializeLocationClient(context)
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Only use the most recent location. When Android unfreezes the app
                // it delivers all queued locations at once, causing a flood of
                // notification/UI update calls. We only need the latest position.
                locationResult.lastLocation?.let { onLocationUpdated(it) }
            }
        }

        val locationRequest = createLocationRequest(context)
        if (!permissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) || !permissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper())
    }

    fun createLocationRequest(context: Context): LocationRequest {
        Log.i(TAG, "createLocationRequest")
        var locationUpdateRate = try {
            getPreference("locationUpdateRate", Int::class.java, context) as Int
        } catch (_: Exception) {
            50  // default to 50 if no rate set
        }
        if (locationUpdateRate < 2) locationUpdateRate = 2
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

    private val mSetHomeLocationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            Log.i(TAG, "mSetHomeLocationCallback: onLocationResult")
            val mLastLocation = locationResult.lastLocation
            setPreference("home", mLastLocation, SGAutoApp.getAppContext())
        }
    }
}