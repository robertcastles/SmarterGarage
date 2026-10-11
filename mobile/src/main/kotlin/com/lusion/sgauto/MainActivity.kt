package com.lusion.sgauto

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.lusion.sgauto.GarageUtility.GarageCommand
import com.lusion.sgauto.GarageUtility.GarageStatus
import com.lusion.sgauto.GarageUtility.garageActionTimeStamp
import com.lusion.sgauto.GarageUtility.garageUpdateRunning
import com.lusion.sgauto.GarageUtility.getLastGarageStatus
import com.lusion.sgauto.GarageUtility.ongoingGarageStatusThread
import com.lusion.sgauto.GarageUtility.sendGarageCommand
import com.lusion.sgauto.GeofenceErrorMessages.Companion.getErrorString
import com.lusion.sgauto.GeofenceUtility.enableGeofence
import com.lusion.sgauto.GeofenceUtility.logGeofence
import com.lusion.sgauto.LocationServiceUpdate.LocalBinder
import com.lusion.sgauto.LusionUtility.showSnackbar
import com.lusion.sgauto.NotificationUtility.LOCATION_PERMISSIONS_ISSUE
import com.lusion.sgauto.NotificationUtility.dismissNotification
import com.lusion.sgauto.NotificationUtility.triggerNotification
import com.lusion.sgauto.PermissionUtility.checkLocationPermissions
import com.lusion.sgauto.PermissionUtility.clearRequestingFlag
import com.lusion.sgauto.PermissionUtility.requestNotificationPermissionIfNeeded
import com.lusion.sgauto.PreferenceUtility.getHomeLocation
import com.lusion.sgauto.PreferenceUtility.getPreference
import com.lusion.sgauto.PreferenceUtility.getSharedPreferences
import com.lusion.sgauto.PreferenceUtility.setPreference
import java.util.Collections.reverse

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity(), LocationListener, OnSharedPreferenceChangeListener,
    OnCompleteListener<Void?> {

    // TODO when the API can't be reached, like with debug and server is offline, the app never stops polling until it is closed.  Could be a battery drain.  Need to look into it.

    // TODO BUG!!! revoke location permission and restart app, goes into infinite prompt loop

    private val distanceText = mutableStateOf("?")
    private val radiusText = mutableStateOf("")
    val geofenceStatusText = mutableStateOf("")
    val garageStatus = mutableStateOf(GarageStatus.UNKNOWN)
    private val geofenceIndicator = mutableStateOf(false)
    private val showLog = mutableStateOf(false)
    private val debugViewState = mutableStateOf(false)
    private val emptyLog: List<String>? = null
    private val logMessages = mutableStateOf(emptyLog)
    private var mService: LocationServiceUpdate? = null

    private var myLocationServiceBroadcastReceiver: MyLocationServiceBroadcastReceiver? = null
    private var uiReceiverRegistered = false
    private var mBound = false
    private val mServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as LocalBinder
            mService = binder.service
            mBound = true
            Log.i(TAG, "onServiceConnected: $mService")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mService = null
            mBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThemedMainLayout()
        }
    }

    @SuppressLint("MissingPermission")
    public override fun onResume() {
        Log.i(TAG, "onResume")
        super.onResume()

        // Clear any stale permission request guard
        clearRequestingFlag()

        // Check if setup is complete; if not, navigate to Settings which owns the permission flow
        val navigatedToSettings = showSettingsIfNeeded()
        if (!navigatedToSettings) {
            // All setup is complete — just activate features if permissions are good
            if (checkLocationPermissions(this)) {
                onPermissionsGranted()
            }
            // Request notification permission only if we're staying on MainActivity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermissionIfNeeded(this)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerBroadcastReceivers()
        } else {
            // Older version registration logic if different
            initializeLocationServiceBroadcastReceiver()
            registerLocalBroadcastReceiver()
        }

        updateGarageStatus()
        handleDebugMode()
        updateRadius(this)
        updateGeofenceStatus()
        handleForegroundUpdates()

        handleGarageAnimation()
    }

    private fun updateGarageStatus() {
        garageStatus.value = getLastGarageStatus(applicationContext)
    }

    @SuppressLint("MissingPermission")
    fun onPermissionsGranted() {
        if (!checkLocationPermissions(this)) {
            Log.w(TAG, "onPermissionsGranted: permissions not fully granted")
            return
        }
        bindLocationServiceIfNeeded()
        startLocationUpdates()
        enableGeofence(this)
    }

    @SuppressLint("MissingPermission")
    private fun bindLocationServiceIfNeeded() {
        if (!mBound) {
            bindService(
                Intent(this, LocationServiceUpdate::class.java), mServiceConnection,
                BIND_AUTO_CREATE
            )
        }
        val homeLocation = getHomeLocation(applicationContext)
        if (homeLocation != null) enableGeofence(this)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBroadcastReceivers() {
        initializeLocationServiceBroadcastReceiver()
        registerLocalBroadcastReceiver()
        registerUiMessageBroadcastReceiver()
    }

    private fun initializeLocationServiceBroadcastReceiver() {
        if (myLocationServiceBroadcastReceiver == null) {
            Log.i(TAG, "onResume: initialize myLocationServiceBroadcastReceiver")
            myLocationServiceBroadcastReceiver = MyLocationServiceBroadcastReceiver()
        }
    }

    private fun registerLocalBroadcastReceiver() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            myLocationServiceBroadcastReceiver!!,
            IntentFilter(LocationServiceUpdate.ACTION_BROADCAST)
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerUiMessageBroadcastReceiver() {
        this.registerReceiver(
            uiMessageBroadcastReceiver,
            IntentFilter(getString(R.string.local_main_activity_listener)),
            RECEIVER_NOT_EXPORTED
        )
        uiReceiverRegistered = true
    }

    private fun handleDebugMode() {
        val debug = getPreference("debug_switch", Boolean::class.java, applicationContext) as Boolean?
        debugViewState.value = debug == true
    }

    private fun updateGeofenceStatus() {
        val sharedPreferences = getSharedPreferences(applicationContext)
        val geofenceMessage = sharedPreferences.getString("geofenceMessage", "No status")
        geofenceMessage?.let { setGeofenceMessage(it) }
        geofenceIndicator.value = geofenceMessage!!.startsWith("ENTERED") || geofenceMessage.startsWith("DWELLING")
    }

    private fun showSettingsIfNeeded(): Boolean {
        if (!PreferenceUtility.validGarageTokens(applicationContext)) {
            showSettings()
            showSnackbar(this@MainActivity, "Please enter your SmartThings key information to get started")
            return true
        }

        if (!checkLocationPermissions(this)) {
            showSettings()
            showSnackbar(this@MainActivity, "Please grant location permissions to enable geofencing")
            return true
        }

        if (getHomeLocation(applicationContext) == null) {
            showSettings()
            showSnackbar(this@MainActivity, "Please set your home location to enable geofencing")
            return true
        }

        return false
    }

    private fun updateRadius(context: Context) {
        var radius = getPreference("radius", Int::class.java, applicationContext) as Int?
        if (radius != null && radius == 0) {
            radius = INITIAL_RADIUS
            setPreference("radius", INITIAL_RADIUS, context)
        }
        radius?.let { showRadius(it) }
    }

    private fun handleForegroundUpdates() {
        val foregroundUpdates = getPreference("foreground_updates", Boolean::class.java, applicationContext) as Boolean?
        if (foregroundUpdates == true) {
            requestForegroundUpdates(this)
        } else {
            mService?.removeLocationUpdates()
        }
    }

    private fun handleGarageAnimation() {
        if ((getLastGarageStatus(applicationContext) == GarageStatus.CLOSING || getLastGarageStatus(applicationContext) == GarageStatus.OPENING) && System.currentTimeMillis() - garageActionTimeStamp < 10000) {
            Log.i(TAG, "onResume: garage is moving, garageActionTimeStamp within 10 seconds, so animateGarage, triggerNotification")
            garageStatus.value = getLastGarageStatus(applicationContext)
            triggerNotification(applicationContext, LusionUtility.UpdateType.GARAGESTATUS, getLastGarageStatus(applicationContext))
        }
        ongoingGarageStatusThread(applicationContext, TAG)
    }

    private fun showSettings() {
        val intent = Intent(this@MainActivity, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val menuInflater = menuInflater
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == R.id.action_settings) {
            val intent = Intent(this@MainActivity, SettingsActivity::class.java)
            startActivity(intent)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showRadius(radius: Int) {
        radiusText.value =
            " / " + String.format(resources.getString(R.string.radius_in_meters), radius)
    }

    private fun loadLogTVFromPreferences() {
        val sharedPreferences: SharedPreferences =
            getSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        showLog.value = sharedPreferences.getBoolean("show_log", false)
    }

    override fun onPause() {
        super.onPause()
        val status = getLastGarageStatus(applicationContext)
        if (status != GarageStatus.OPENING && status != GarageStatus.CLOSING) {
            garageUpdateRunning.set(false)
        }

        if (uiReceiverRegistered) {
            try {
                unregisterReceiver(uiMessageBroadcastReceiver)
                uiReceiverRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "onPause: error, " + e.message)
            }
        }
        if (myLocationServiceBroadcastReceiver != null) try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(
                myLocationServiceBroadcastReceiver!!
            )
        } catch (e: Exception) {
            Log.e(TAG, "onPause: error, " + e.message)
        }
        if (mBound) {
            unbindService(mServiceConnection)
            mBound = false
        }
    }


    override fun onStop() {
        super.onStop()
        val status = getLastGarageStatus(applicationContext)
        if (status != GarageStatus.OPENING && status != GarageStatus.CLOSING) {
            garageUpdateRunning.set(false)
        }
        if (uiReceiverRegistered) {
            try {
                unregisterReceiver(uiMessageBroadcastReceiver)
                uiReceiverRegistered = false
            } catch (e: Exception) {
                Log.w(TAG, "onStop: warning, uiMessageBroadcastReceiver ${e.message}")
            }
        }
        if (myLocationServiceBroadcastReceiver != null) try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(
                myLocationServiceBroadcastReceiver!!
            )
        } catch (e: Exception) {
            Log.w(TAG, "onStop: warning, myLocationServiceBroadcastReceiver ${e.message}")
        }
        if (mBound) {
            unbindService(mServiceConnection)
            mBound = false
        }
        getSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onComplete(task: Task<Void?>) {
        if (task.isSuccessful) {
            updateGeofencesAdded(true, this)
        } else {
            updateGeofencesAdded(false, this)
            val errorMessage = getErrorString(this, task.exception)
            Log.w(TAG, "onComplete: $errorMessage")
        }
    }


    private fun updateGeofencesAdded(added: Boolean, context: Context) {
        setPreference(
            Constants.GEOFENCES_ADDED_KEY,
            added,
            context
        )
    }

    internal enum class PendingGeofenceTask {
        ADD, REMOVE
    }

    private fun setGeofenceMessage(message: String) {
        geofenceStatusText.value = message
    }

    private fun requestForegroundUpdates(context: Context) {
        Log.d(TAG, "requestForegroundUpdates")
        setPreference(
            "foreground_updates",
            true,
            context
        )
        if (mService != null) mService!!.requestLocationUpdates()
        else
            Log.d(
                TAG,
                "requestForegroundUpdates: mService was null, not doing requestLocationUpdates",
            )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        Log.d("onRequestPermissionResult", "${permissions.contentToString()} $requestCode")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        clearRequestingFlag()

        when (requestCode) {
            Constants.POST_NOTIFICATIONS_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showSnackbar(this, "Notifications access granted")
                }
            }
        }
    }

    private inner class MyLocationServiceBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "myLocationServiceBroadcastReceiver.onReceive")
            val location: Location? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    LocationServiceUpdate.EXTRA_LOCATION,
                    Location::class.java
                )
            } else {
                intent.getParcelableExtra(LocationServiceUpdate.EXTRA_LOCATION)
            }
            location?.let { updateLocationDisplayAndShowDistance(it) }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, s: String?) {
        if (s == "geofenceMessage") {
            runOnUiThread { 
                sharedPreferences!!.getString("geofenceMessage", "No status")
                    ?.let { setGeofenceMessage(it) }
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        updateLocationDisplayAndShowDistance(location)
    }

    private fun updateLocationDisplayAndShowDistance(location: Location?) {
        Log.i(TAG, "updateLocationDisplayAndShowDistance, location is $location")
        if (location == null) return
        showDistance(location)
    }

    fun displayGarageStatus(lGarageStatus: GarageStatus, gStatus: GarageStatus) {
        Log.i(TAG, "setGarageStatus: $lGarageStatus, $gStatus")
        when (gStatus) {
            GarageStatus.OPEN -> {
                triggerNotification(
                    applicationContext,
                    LusionUtility.UpdateType.GARAGESTATUS,
                    GarageStatus.OPEN
                )
                mLastAnimatedGarageStatus = GarageStatus.UNKNOWN
            }

            GarageStatus.CLOSED -> {
                triggerNotification(
                    applicationContext,
                    LusionUtility.UpdateType.GARAGESTATUS,
                    GarageStatus.CLOSED
                )
                mLastAnimatedGarageStatus = GarageStatus.UNKNOWN
            }

            else -> {
                Log.w(TAG, "setGarageStatus: gStatus $gStatus was not processed ")
            }

        }
        GarageUtility.setLastGarageStatus(applicationContext, gStatus)
        garageStatus.value = gStatus

    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        LocationUtility.startLocationUpdates(
            applicationContext,
            ::updateLocationDisplayAndShowDistance
        )
    }

    @SuppressLint("StringFormatMatches")
    fun showDistance(currentLocation: Location?) {
        Log.i(TAG, "showDistance")
        if (currentLocation == null) {
            showSnackbar(this@MainActivity, getString(R.string.no_location))
            return
        }

        val homeLocation = getHomeLocation(applicationContext)
        if (homeLocation == null) {
            distanceText.value = getString(R.string.no_home)
            return
        }

        val distanceInMeters = calculateDistance(currentLocation, homeLocation)
        val fenceRadius = getFenceRadius()

        updateGeofenceStatus(distanceInMeters, fenceRadius)
        updateDistanceText(distanceInMeters, fenceRadius)
    }

    private fun calculateDistance(currentLocation: Location, homeLocation: Location): Double {
        val results = FloatArray(3)
        Location.distanceBetween(
            currentLocation.latitude,
            currentLocation.longitude,
            homeLocation.latitude,
            homeLocation.longitude,
            results
        )
        return results[0].toDouble()
    }

    private fun getFenceRadius(): Int {
        var radius = getPreference("radius", Int::class.java, applicationContext) as Int?
        if (radius != null && radius == 0) {
            radius = INITIAL_RADIUS
            setPreference("radius", INITIAL_RADIUS, applicationContext)
        }
        return radius ?: INITIAL_RADIUS
    }

    private fun updateGeofenceStatus(distanceInMeters: Double, fenceRadius: Int) {
        val isNowInside = distanceInMeters <= fenceRadius
        val wasInside = geofenceIndicator.value

        // Only run the log/notify side effects on an ACTUAL enter/exit transition, not
        // on every single location update while already inside/outside — this was
        // re-triggering "ENTERED" (log spam + a redundant notification post) on every
        // GPS fix as long as the app stayed in the foreground near home.
        if (!isNowInside) {
            if (wasInside) {
                Log.i(TAG, "showDistance: EXITED UI")
                // Keep the shared "geofenceMessage" preference in sync with this
                // foreground, distance-based exit detection — it's read by
                // GarageUtility.isInsideGeofence() (used by updateGarageAppAreas'
                // dismiss/keep decision) and by refreshNotificationBasedOnGeofence().
                // Without this, those would still see the stale "ENTERED"/"DWELLING"
                // value until the OS GeofencingClient's own EXIT broadcast eventually
                // arrives (which can lag well behind this faster foreground check),
                // wrongly keeping/resurrecting the notification in the meantime.
                setPreference("geofenceMessage", "EXITED", applicationContext)
                logGeofence(applicationContext, "EXITED UI")
                runOnUiThread { loadLogTVFromPreferences() }
                // Only keep the notification for a CONFIRMED open/moving door — UNKNOWN
                // is treated like CLOSED (see GarageUtility.shouldKeepNotificationOnGeofenceExit),
                // matching GeofenceBroadcastReceiver's exit logic exactly.
                val lastStatus = getLastGarageStatus(applicationContext)
                if (GarageUtility.shouldKeepNotificationOnGeofenceExit(lastStatus)) {
                    triggerNotification(applicationContext, LusionUtility.UpdateType.GEOFENCE, lastStatus)
                } else {
                    dismissNotification(applicationContext)
                }
            }
            geofenceIndicator.value = false
        } else {
            if (!wasInside) {
                Log.i(TAG, "showDistance: ENTERED UI")
                setPreference("geofenceMessage", "ENTERED", applicationContext)
                logGeofence(applicationContext, "ENTERED")
                triggerNotification(applicationContext, LusionUtility.UpdateType.GEOFENCE, null)
                runOnUiThread { loadLogTVFromPreferences() }
            }
            geofenceIndicator.value = true
        }
    }

    private fun updateDistanceText(distanceInMeters: Double, fenceRadius: Int) {
        val distanceInMetersInt = distanceInMeters.toInt()
        Log.i(TAG, "showDistance: setting distanceText to $distanceInMetersInt")
        distanceText.value = String.format(
            resources.getString(R.string.radius_in_meters),
            distanceInMetersInt
        )
        if (distanceInMeters > fenceRadius * 2) {
            radiusText.value = ""
        }
    }

    // Utility method to receive messages from other utility classes to update the UI, etc.
        private val uiMessageBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runOnUiThread { loadLogTVFromPreferences() }
            var message = intent.getStringExtra("Message")
            val payload = intent.getStringExtra("Payload")
            Log.i(TAG, "uiMessageBroadcastReceiver.onReceive: $message")
            if (message == null) {
                return
            }
            if (message == LOCATION_PERMISSIONS_ISSUE) {
                showSnackbar(this@MainActivity, getString(R.string.insufficient_permissions))
                return
            }
            if (message == "GARAGESTATUS") {
                if (payload == null) {
                    Log.w(
                        TAG,
                        "uiMessageBroadcastReceiver.onReceive: MainActivity BroadcastReceiver payload is null and should not be"
                    )
                    return
                }
                garageStatus.value = getLastGarageStatus(applicationContext)
                val payloadGarageStatus = GarageStatus.entries.firstOrNull { it.name == payload }
                if (payloadGarageStatus == null) {
                    Log.w(TAG, "Ignoring invalid garage status payload")
                    return
                }
                when (payloadGarageStatus) {
                    GarageStatus.OPENING, GarageStatus.CLOSING -> {
                        triggerNotification(
                            applicationContext,
                            LusionUtility.UpdateType.GARAGESTATUS,
                            payloadGarageStatus
                        )
                    }

                    GarageStatus.OPEN, GarageStatus.CLOSED, GarageStatus.UNKNOWN -> {
                        displayGarageStatus(getLastGarageStatus(context), payloadGarageStatus)
                        triggerNotification(
                            applicationContext,
                            LusionUtility.UpdateType.GARAGESTATUS,
                            payloadGarageStatus
                        )
                    }
                }
            }
            // Update UI when receiving geofence message
            if (message.startsWith("ENTERED") || message.startsWith("DWELLING")) {
                if (message.startsWith("ENTERED")) message = "ENTERED"
                if (message.startsWith("DWELLING")) message = "DWELLING"
                geofenceIndicator.value = true
                geofenceStatusText.value = message
            } else if (message.startsWith("EXITED")) {
                message = "EXITED"
                geofenceIndicator.value = false
                geofenceStatusText.value = message
            }
            if (intent.action != null) {
                when (intent.action) {
                    getString(R.string.geofence_transition_exited) -> {
                        geofenceStatusText.value = "Geofence exited"
                        showSnackbar(this@MainActivity, "Geofence exited")
                    }
                    getString(R.string.geofence_transition_entered) -> {
                        geofenceStatusText.value = "Geofence entered"
                        showSnackbar(this@MainActivity, "Geofence entered")
                    }
                    getString(R.string.geofence_transition_dwelled) -> {
                        geofenceStatusText.value = "Geofence dwelled"
                        showSnackbar(this@MainActivity, "Geofence dwelled")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    companion object {
        private val TAG = (MainActivity::class.qualifiedName)!!
        private const val INITIAL_RADIUS = 400
        var mLastAnimatedGarageStatus = GarageStatus.UNKNOWN
    }


    @Composable
    fun TopBarWidgets() {
        Row {
            SettingsImage()
            DebugImage()
        }
    }

    @Composable
    fun SettingsImage() {
        Image(
            painter = painterResource(id = R.drawable.ic_cog),
            contentDescription = stringResource(id = R.string.settings),
            modifier = Modifier
                .size(48.dp, 48.dp)
                .clickable(
                    onClick = {
                        showSettings()
                    }
                )
        )
    }

    @Composable
    fun DebugImage() {
        val localViewState by debugViewState
        if (localViewState)
            Image(
                painter = painterResource(id = R.drawable.debug),
                contentDescription = stringResource(id = R.string.debugging),
                modifier = Modifier
                    .size(48.dp, 48.dp)
                    .clickable(
                        onClick = {
                            showSettings()
                        }
                    )
            )
    }

    @Composable
    fun ThemedMainLayout() {
        val darkTheme = isSystemInDarkTheme()

        SGTheme(useDarkTheme = darkTheme) {
            val surfaceColor = MaterialTheme.colorScheme.surface

            SideEffect {
                val window = this@MainActivity.window
                @Suppress("DEPRECATION")
                window.statusBarColor = surfaceColor.hashCode()
                @Suppress("DEPRECATION")
                window.navigationBarColor = surfaceColor.hashCode()
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = surfaceColor
            ) {
                MainLayout()
            }
        }
    }

    @Composable
    fun MainLayout() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    )
                )
        )
        {
            Row(
                Modifier
                    .padding(top = 10.dp, start = 10.dp)
                    .fillMaxWidth()
            ) {
                TopBarWidgets()
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxHeight()

            )
            {
                Spacer(modifier = Modifier.weight(.7f))
                Box(
                    contentAlignment = Alignment.Center,
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(150.dp))
                        GeoFenceIndicator()
                    }
                    Column(Modifier.align(Alignment.Center))
                    {
                        GarageIndicator()
                    }
                }
                Spacer(modifier = Modifier.weight(.1f))

            }
        }
    }

    @Preview(
        showSystemUi = true,
        device = "spec:width=411dp,height=891dp,dpi=420,isRound=false,chinSize=0dp,orientation=landscape"
    )
    @PreviewLightDark
    @PreviewScreenSizes
    @Composable
    fun MainLayoutPreview() {
        MainLayout()
    }

    @Composable
    fun GeoFenceIndicator() {
        val localIn by geofenceIndicator
        val drawableId = if (localIn)
            R.drawable.halo_in
        else
            R.drawable.halo_out

        val haloVector = ImageVector.vectorResource(id = drawableId)
        val haloPainter = rememberVectorPainter(image = haloVector)
        Column {
            Image(
                painter = haloPainter,
                contentDescription = stringResource(id = R.string.geofence_indicator),
                modifier = Modifier
                    .wrapContentSize()
                    .width(360.dp)
                ,
                contentScale = ContentScale.FillWidth
            )
        }
    }

    data class GarageIndicator(
        val drawableID: Int,
        val garageCommand: GarageCommand?,
        val description: String
    )

    @Composable
    fun GarageIndicatorImage(garageIndicator: GarageIndicator) {
        val vector = ImageVector.vectorResource(id = garageIndicator.drawableID)
        val painter = rememberVectorPainter(image = vector)

        Box(modifier = Modifier.size(360.dp), contentAlignment = Alignment.Center) {
            Image(
                painter = painter,
                contentDescription = garageIndicator.description,
                modifier = Modifier
                    .wrapContentSize()
                    .clickable(
                        onClick = {
                            if (garageIndicator.garageCommand != null) sendGarageCommand(
                                applicationContext, garageIndicator.garageCommand
                            )
                        }
                    ),
                contentScale = ContentScale.Crop
            )
        }
    }

    @Composable
    fun GarageArrow(arrowID: Int) {
        Image(
            painter = painterResource(id = arrowID),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth()
        )
    }

    @Composable
    fun MoveGarageArrow(
        yOffset: Dp,
        item: @Composable () -> Unit
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.offset { IntOffset(0, yOffset.roundToPx()) }) {
                item()
            }
        }
    }

    @Composable
    fun GarageIndicatorOpening() {
        val backgroundVector = ImageVector.vectorResource(id = R.drawable.garage_door_open)
        val garageBackgroundPainter = rememberVectorPainter(image = backgroundVector)

        Box(modifier = Modifier.size(360.dp), contentAlignment = Alignment.Center) {
            Image(
                painter = garageBackgroundPainter,
                contentDescription = null,
                modifier = Modifier
                    .wrapContentSize(),
                contentScale = ContentScale.Crop
            )

            // Fly-in entrance effect for arrows: Start oversized (2.0f) and scale down to regular size
            var arrowsVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { arrowsVisible = true }
            val arrowAlpha by animateFloatAsState(
                targetValue = if (arrowsVisible) 1f else 0f,
                animationSpec = tween(1000),
                label = "ArrowFlyInAlpha"
            )
            val arrowScale by animateFloatAsState(
                targetValue = if (arrowsVisible) 1f else 2.0f,
                animationSpec = tween(1000),
                label = "ArrowFlyInScale"
            )

            val infiniteTransition = rememberInfiniteTransition(label = "OpeningArrows")
            val yOffset by infiniteTransition.animateValue(
                initialValue = 60.dp,
                targetValue = (-100).dp,
                typeConverter = Dp.VectorConverter,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "OpeningOffset"
            )
            MoveGarageArrow(yOffset = yOffset) {
                Box(Modifier.graphicsLayer(alpha = arrowAlpha, scaleX = arrowScale, scaleY = arrowScale)) {
                    GarageArrow(arrowID = R.drawable.garage_door_opening_arrow_anim)
                }
            }
        }
    }

    @Composable
    fun GarageIndicatorClosing() {
        val backgroundVector = ImageVector.vectorResource(id = R.drawable.garage_door_open)
        val garageBackgroundPainter = rememberVectorPainter(image = backgroundVector)

        Box(modifier = Modifier.size(360.dp), contentAlignment = Alignment.Center) {
            Image(
                painter = garageBackgroundPainter,
                contentDescription = null,
                modifier = Modifier
                    .wrapContentSize(),
                contentScale = ContentScale.Crop
            )

            // Fly-in entrance effect for arrows: Start oversized (2.0f) and scale down to regular size
            var arrowsVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { arrowsVisible = true }
            val arrowAlpha by animateFloatAsState(
                targetValue = if (arrowsVisible) 1f else 0f,
                animationSpec = tween(1000),
                label = "ArrowFlyInAlpha"
            )
            val arrowScale by animateFloatAsState(
                targetValue = if (arrowsVisible) 1f else 2.0f,
                animationSpec = tween(1000),
                label = "ArrowFlyInScale"
            )

            val infiniteTransition = rememberInfiniteTransition(label = "ClosingArrows")
            val yOffset by infiniteTransition.animateValue(
                initialValue = (-100).dp,
                targetValue = 60.dp,
                typeConverter = Dp.VectorConverter,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "ClosingOffset"
            )
            MoveGarageArrow(yOffset = yOffset) {
                Box(Modifier.graphicsLayer(alpha = arrowAlpha, scaleX = arrowScale, scaleY = arrowScale)) {
                    GarageArrow(arrowID = R.drawable.garage_door_closing_arrow_anim)
                }
            }
        }
    }

    @Composable
    fun GarageIndicator() {
        val localGarageStatus by garageStatus

        AnimatedContent(
            targetState = localGarageStatus,
            transitionSpec = {
                val enter = when (targetState) {
                    GarageStatus.OPEN -> EnterTransition.None
                    GarageStatus.CLOSED -> fadeIn(tween(1000))
                    else -> fadeIn(tween(500))
                }

                val exit = fadeOut(animationSpec = tween(1000))

                (enter togetherWith exit)
                    .using(SizeTransform(clip = false) { _, _ -> snap() })
                    .apply {
                        // When transitioning away from OPENING/CLOSING (the moving arrows),
                        // ensure the exiting content (the arrows) stays on top of the entering content (the flat image).
                        targetContentZIndex = if (initialState == GarageStatus.OPENING || initialState == GarageStatus.CLOSING) -1f else 0f
                    }
            },
            contentAlignment = Alignment.Center,
            label = "GarageStatusTransition"
        ) { status ->
            Box(modifier = Modifier.size(360.dp), contentAlignment = Alignment.Center) {
                when (status) {
                    GarageStatus.CLOSING -> GarageIndicatorClosing()
                    GarageStatus.OPENING -> GarageIndicatorOpening()
                    GarageStatus.CLOSED -> GarageIndicatorImage(
                        GarageIndicator(
                            drawableID = R.drawable.smarter_garage_svg,
                            garageCommand = GarageCommand.OPEN,
                            description = getString(R.string.garage_status_closed)
                        )
                    )

                    GarageStatus.OPEN -> GarageIndicatorImage(
                        GarageIndicator(
                            drawableID = R.drawable.garage_door_open,
                            garageCommand = GarageCommand.CLOSE,
                            description = getString(R.string.garage_status_open)
                        )
                    )

                    GarageStatus.UNKNOWN -> GarageIndicatorImage(
                        GarageIndicator(
                            drawableID = R.drawable.garage_door_unknown,
                            garageCommand = null,
                            description = "Unknown"
                        )
                    )
                }
            }
        }
    }

    @Composable
    fun Log() {
        val localShowLog by showLog
        val localLogText by logMessages
        var logTexts: String? = ""
        val scroll = rememberScrollState(0)

        if (localShowLog) {
            if (localLogText != null) {
                localLogText?.let { reverse(it) }
                for (message in localLogText!!) {
                    logTexts += message.trimIndent()
                }
            }
            Text(
                text = (logTexts ?: ""),
                modifier = Modifier
                    .verticalScroll(scroll)
                    .fillMaxWidth(),
                maxLines = 10,
                fontSize = 12.sp
            )
        }
    }
}
