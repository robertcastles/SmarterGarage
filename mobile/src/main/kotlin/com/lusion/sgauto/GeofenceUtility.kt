package com.lusion.sgauto

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import com.lusion.sgauto.PersistentLog as Log
import android.widget.Toast
import android.location.Location
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.lusion.sgauto.GarageUtility.getLastGarageStatus
import com.lusion.sgauto.MainActivity.PendingGeofenceTask
import com.lusion.sgauto.NotificationUtility.GEOFENCES_ADDED
import com.lusion.sgauto.NotificationUtility.GEOFENCES_FAILED
import com.lusion.sgauto.NotificationUtility.LOCATION_PERMISSIONS_ISSUE
import com.lusion.sgauto.NotificationUtility.notifyMainActivity
import com.lusion.sgauto.PermissionUtility.checkLocationPermissions
import com.lusion.sgauto.PermissionUtility.permissionGranted
import com.lusion.sgauto.PreferenceUtility.getHomeLocation
import com.lusion.sgauto.PreferenceUtility.getLogMessages
import com.lusion.sgauto.PreferenceUtility.getPreference
import com.lusion.sgauto.PreferenceUtility.getSharedPreferences
import com.lusion.sgauto.PreferenceUtility.setPreference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

object GeofenceUtility {
    private const val TAG = "GeofenceUtility"
    private var geofencePendingIntent: PendingIntent? = null
    private var geofencingClient: GeofencingClient? = null
    private val reconciliationMutex = Mutex()
    private var latestTransitionNanos = 0L
    private val carLocationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var carLocationJob: Job? = null

    @Synchronized
    fun recordGeofenceTransition(context: Context, state: String) {
        latestTransitionNanos = SystemClock.elapsedRealtimeNanos()
        setPreference("geofenceMessage", state, context)
    }

    @Synchronized
    fun insideFromLocation(location: Location, distanceMeters: Float, radiusMeters: Int): Boolean? =
        GeofenceLocationPolicy.inside(
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L,
            location.accuracy.takeIf { location.hasAccuracy() },
            distanceMeters, radiusMeters, location.elapsedRealtimeNanos, latestTransitionNanos
        )

    @Synchronized
    fun startCarSessionMonitoring(context: Context) {
        if (!LusionUtility.isInCarSession || carLocationJob?.isActive == true) return
        val appContext = context.applicationContext
        carLocationJob = carLocationScope.launch {
            while (isActive && LusionUtility.isInCarSession) {
                delay(30_000L.milliseconds)
                if (!LusionUtility.isInCarSession) break
                reconcileGeofenceStateWithLocation(appContext)
                if (!GarageUtility.foregroundServicePolling.get() &&
                    !GarageUtility.isInsideGeofence(appContext) &&
                    GarageUtility.shouldKeepNotificationOnGeofenceExit(getLastGarageStatus(appContext))) {
                    GarageUtility.makeStatusRequest(appContext, ignoreTimers = true, "CarGeofenceMonitor")
                }
                NotificationUtility.refreshNotificationBasedOnGeofence(appContext)
            }
        }
    }

    @Synchronized
    fun stopCarSessionMonitoring() {
        carLocationJob?.cancel()
        carLocationJob = null
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    fun getGeofencePendingIntent(context: Context?): PendingIntent? {
        val appContext = context?.applicationContext ?: return null
        if (geofencePendingIntent != null) {
            return geofencePendingIntent
        }
        val intent = Intent(appContext, GeofenceBroadcastReceiver::class.java)
        geofencePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        ) 
        else PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        return geofencePendingIntent
    }

    private fun getGeofencingRequest(
        context: Context,
        triggerInitialState: Boolean = true
    ): GeofencingRequest? {
        val homeFence = garageGeofence(context)?: return null
        return GeofencingRequest.Builder().apply {
            setInitialTrigger(
                if (triggerInitialState) {
                    GeofencingRequest.INITIAL_TRIGGER_DWELL or
                            GeofencingRequest.INITIAL_TRIGGER_ENTER or
                            GeofencingRequest.INITIAL_TRIGGER_EXIT
                } else {
                    0
                }
            )
            addGeofence(homeFence)
        }.build()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun enableGeofence(activity: AppCompatActivity) {
        val task = PendingGeofenceTask.ADD
        performPendingGeofenceTask(
            activity,
            task
        )
    }

    /**
     * Re-registers the home geofence without requiring an Activity — used by
     * [BootReceiver]. Registered geofences do NOT survive a device reboot (the OS
     * wipes GeofencingClient's state), so without this call the app would have zero
     * geofence monitoring active until the user happened to manually open the app
     * again (which re-arms it as a side effect of MainActivity's onResume). Silently
     * does nothing if location permissions aren't granted or home isn't set —
     * [addGeofences] already guards both cases.
     */
    fun ensureGeofencesRegistered(
        context: Context,
        triggerInitialState: Boolean = true
    ) {
        addGeofences(context, getGeofencingClient(context), triggerInitialState)
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun performPendingGeofenceTask(
        context: Context,
        pgt: PendingGeofenceTask
    ) {
        if (pgt == PendingGeofenceTask.ADD) {
            addGeofences(context, getGeofencingClient(context))
        } else if (pgt == PendingGeofenceTask.REMOVE) {
            removeGeofences(context, getGeofencingClient(context))
        }
    }

    private fun getGeofencingClient(context: Context): GeofencingClient {
        if (geofencingClient == null) {
            geofencingClient = LocationServices.getGeofencingClient(context.applicationContext)
        }
        return geofencingClient!!
    }

    @SuppressLint("MissingPermission")
    @JvmStatic
    fun addGeofences(
        context: Context,
        geofencingClient: GeofencingClient?,
        triggerInitialState: Boolean = true
    ) {
        val appContext = context.applicationContext
        queryGeofenceDetails(appContext)

        if (!checkLocationPermissions(appContext)) {
            Log.d(TAG, "Location permissions missing")
            notifyMainActivity(appContext, LOCATION_PERMISSIONS_ISSUE)
            return
        }

        if (!permissionGranted(appContext, Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.i(TAG, "ACCESS_FINE_LOCATION permission not granted")
            return
        }

        if (!isLocationEnabled(appContext)) {
            Log.w(TAG, "Location services are disabled")
            notifyMainActivity(appContext, GEOFENCES_FAILED)
            return
        }

        val geofenceRequest = getGeofencingRequest(appContext, triggerInitialState)
        if (geofenceRequest == null) {
            Log.e(TAG, "Geofence request creation failed - home location likely not set")
            return
        }

        val geofencePendingIntent = getGeofencePendingIntent(appContext)
        if (geofencePendingIntent != null && geofencingClient != null) {
            Log.d(TAG, "Removing existing geofences before adding...")
            geofencingClient.removeGeofences(geofencePendingIntent).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Previous geofences removed successfully")
                } else {
                    Log.w(TAG, "Previous geofence removal status: ${task.exception?.message}")
                }
                
                geofencingClient.addGeofences(
                    geofenceRequest, geofencePendingIntent
                ).run {
                    addOnSuccessListener {
                        Log.i(TAG, "SUCCESS: Geofence added with radius: ${getPreference("radius", Int::class.java, appContext)}m")
                        saveSuccessfulGeofence(appContext)
                        setPreference("geofences_active", true, appContext)
                        notifyMainActivity(appContext, GEOFENCES_ADDED)
                    }
                    addOnFailureListener { e ->
                        val errorMsg = GeofenceErrorMessages.getErrorString(appContext, e)
                        Log.e(TAG, "FAILURE: $errorMsg (Check Google Location Accuracy)")
                        setPreference("geofences_active", false, appContext)
                        notifyMainActivity(appContext, GEOFENCES_FAILED)
                    }
                }
            }
        }
    }

    private fun saveSuccessfulGeofence(context: Context) {
        val radius = getPreference("radius", Int::class.java, context)
        setPreference("last_successful_radius", radius, context)
        setPreference("last_successful_time", System.currentTimeMillis(), context)
    }

    fun queryGeofenceDetails(context: Context) {
        val appContext = context.applicationContext
        val homeLocation = getHomeLocation(appContext)
        val radius = getPreference("radius", Int::class.java, appContext) as? Int ?: 500
        val active = getPreference("geofences_active", Boolean::class.java, appContext) as? Boolean ?: false
        val bgPermission = permissionGranted(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val finePermission = permissionGranted(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        Log.i(TAG, "--- Geofence Debug Info ---")
        Log.i(TAG, "Active in Prefs: $active")
        Log.i(TAG, "Current Radius Setting: ${radius}m")
        Log.i(TAG, "Home Set: ${homeLocation != null}")
        Log.i(TAG, "Fine Location: $finePermission")
        Log.i(TAG, "Background Location: $bgPermission")
        Log.i(TAG, "GPS Enabled: ${locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
        Log.i(TAG, "Network Enabled: ${locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)} (REQUIRED for Geofencing)")
        Log.i(TAG, "---------------------------")
    }

    private fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @JvmStatic
    fun removeGeofences(
        context: Context,
        geofencingClient: GeofencingClient?
    ) {
        val appContext = context.applicationContext
        val sharedPreferences = getSharedPreferences(appContext)
        val pendingIntent = getGeofencePendingIntent(appContext)

        if (pendingIntent != null) {
            geofencingClient?.removeGeofences(pendingIntent)
                ?.addOnSuccessListener {
                    sharedPreferences.edit { putString("Geofences added", null) }
                    Log.i(TAG, "removeGeofences: SUCCESS")
                }?.addOnFailureListener { e: Exception ->
                    Log.e(TAG, "removeGeofences: FAILURE ${e.localizedMessage}")
                }
        }
    }

    private fun garageGeofence(context: Context): Geofence? {
        val geofenceResponsiveness = 10000
        val geofenceLoiteringDelay = 8000
        val homeLocation = getHomeLocation(context) ?: return null
        val radius = (getPreference("radius", Int::class.java, context) as Int?) ?: 500
        return Geofence.Builder()
            .setRequestId("SmarterGarage")
            .setLoiteringDelay(geofenceLoiteringDelay)
            .setNotificationResponsiveness(geofenceResponsiveness)
            .setCircularRegion(
                homeLocation.latitude, homeLocation.longitude, radius.toFloat()
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .build()
    }

    fun logGeofence(context: Context, transition: String) {
        val latestMessages = ArrayList(getLogMessages(context))
        val dateFormat = SimpleDateFormat("MMdd HH:mm:ss", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        val newMessage = "$transition $timestamp"
        latestMessages.add(newMessage)
        setPreference(
            "geofenceMessages",
            TextUtils.join(";", latestMessages),
            context
        )
        notifyMainActivity(context, newMessage)
    }

    fun increaseRadius(context: Context) {
        val currentRadius = getPreference("radius", Int::class.java, context) as? Int ?: 500
        val newRadius = currentRadius + 50
        setPreference("radius", newRadius, context)
        Toast.makeText(context, "Radius increased to ${newRadius}M", Toast.LENGTH_SHORT).show()
        addGeofences(context, getGeofencingClient(context))
    }

    fun decreaseRadius(context: Context) {
        val currentRadius = getPreference("radius", Int::class.java, context) as? Int ?: 500
        val newRadius = (currentRadius - 50).coerceAtLeast(50)
        setPreference("radius", newRadius, context)
        Toast.makeText(context, "Radius decreased to ${newRadius}M", Toast.LENGTH_SHORT).show()
        addGeofences(context, getGeofencingClient(context))
    }

    /**
     * Self-healing fallback for missed geofence transitions.
     *
     * The OS-level GeofencingClient broadcast is the primary ENTER/EXIT/DWELL signal,
     * but it can be silently dropped — most commonly by aggressive OEM battery
     * management (Samsung's "sleeping apps"/adaptive battery, seen elsewhere in this
     * codebase) killing the app process or deferring the PendingIntent before the
     * transition is delivered. When that happens, the cached "geofenceMessage"
     * preference (what [GarageUtility.isInsideGeofence] and
     * [NotificationUtility.refreshNotificationBasedOnGeofence] trust) gets stuck
     * disagreeing with reality — e.g. still saying "EXITED" after actually arriving
     * home — and the garage notification never reappears until some OTHER event
     * happens to correct it (like manually opening the app, which re-arms the
     * geofence and forces an INITIAL_TRIGGER re-evaluation).
     *
     * Uses a bounded current-location request. Old, inaccurate, boundary-ambiguous,
     * or pre-transition fixes must never overwrite a newer geofence event.
     * Also runs on car connection and roughly every 30 seconds during an active car session;
     * periodic WorkManager execution alone does not provide a recovery deadline.
     */
    @SuppressLint("MissingPermission")
    suspend fun reconcileGeofenceStateWithLocation(context: Context) = reconciliationMutex.withLock {
        val appContext = context.applicationContext
        if (!permissionGranted(appContext, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !permissionGranted(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return@withLock
        }
        val homeLocation = getHomeLocation(appContext) ?: return@withLock
        val radius = (getPreference("radius", Int::class.java, appContext) as? Int) ?: 500

        val fusedLocationClient = LocationUtility.initializeLocationClient(appContext)
        val cancellation = CancellationTokenSource()
        val currentLocation = try {
            withTimeoutOrNull(12_000L.milliseconds) {
                suspendCancellableCoroutine<Location?> { cont ->
                    cont.invokeOnCancellation { cancellation.cancel() }
                    val request = CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setMaxUpdateAgeMillis(GeofenceLocationPolicy.MAX_AGE_MS)
                        .setDurationMillis(10_000L)
                        .build()
                    fusedLocationClient.getCurrentLocation(request, cancellation.token)
                        .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) { _, _, _ -> } }
                        .addOnFailureListener { error -> if (cont.isActive) cont.resumeWith(Result.failure(error)) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "reconcileGeofenceStateWithLocation: location fetch failed: ${e.message}")
            null
        } finally {
            cancellation.cancel()
        } ?: run {
            Log.w(TAG, "reconcileGeofenceStateWithLocation: no current fix; retaining geofence state")
            return@withLock
        }

        val results = FloatArray(3)
        Location.distanceBetween(
            currentLocation.latitude, currentLocation.longitude,
            homeLocation.latitude, homeLocation.longitude, results
        )
        val distance = results[0]
        val ageMs = (SystemClock.elapsedRealtimeNanos() - currentLocation.elapsedRealtimeNanos) / 1_000_000L
        val correctedState = synchronized(this) {
            val inside = insideFromLocation(currentLocation, distance, radius)
            if (inside == null) {
                Log.i(TAG, "Geofence reconciliation: rejected fix (ageMs=$ageMs, accuracy=${currentLocation.accuracy}, distance=${distance.toInt()}m)")
                null
            } else {
                latestTransitionNanos = currentLocation.elapsedRealtimeNanos
                if (inside == GarageUtility.isInsideGeofence(appContext)) {
                    null
                } else {
                    val state = if (inside) "ENTERED" else "EXITED"
                    setPreference("geofenceMessage", state, appContext)
                    Log.w(TAG, "Geofence reconciliation: corrected to $state (ageMs=$ageMs, distance=${distance.toInt()}m, radius=${radius}m)")
                    state
                }
            }
        }
        if (correctedState != null) {
            logGeofence(appContext, "$correctedState (self-heal)")
            NotificationUtility.refreshNotificationBasedOnGeofence(appContext)
            // An EXIT can retain a cached OPEN/CLOSING card. Confirm the door on both
            // directions so that a physically closed door does not leave that card up.
            GarageUtility.makeStatusRequest(appContext, ignoreTimers = true, "GeofenceSelfHeal-$correctedState")
        }
    }

    /**
     * Returns the device's current distance to the configured home location in meters, or
     * null if home isn't set, location permission is missing, or no fix is available.
     * Used by the arrival heads-up to gate on a tight radius independent of the (much
     * larger) geofence radius.
     */
    @SuppressLint("MissingPermission")
    suspend fun distanceToHomeMeters(context: Context): Float? {
        val appContext = context.applicationContext
        if (!permissionGranted(appContext, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !permissionGranted(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return null
        }
        val homeLocation = getHomeLocation(appContext) ?: return null
        val fusedLocationClient = LocationUtility.initializeLocationClient(appContext)
        val currentLocation = try {
            withTimeoutOrNull(5000L.milliseconds) {
                suspendCancellableCoroutine<Location?> { cont ->
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) { _, _, _ -> } }
                        .addOnFailureListener { if (cont.isActive) cont.resume(null) { _, _, _ -> } }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "distanceToHomeMeters: location fetch failed: ${e.message}")
            null
        } ?: return null

        val results = FloatArray(3)
        Location.distanceBetween(
            currentLocation.latitude, currentLocation.longitude,
            homeLocation.latitude, homeLocation.longitude, results
        )
        return results[0]
    }
}