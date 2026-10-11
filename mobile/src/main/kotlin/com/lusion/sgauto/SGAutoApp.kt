package com.lusion.sgauto

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.lusion.sgauto.PersistentLog as Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import com.google.firebase.crashlytics.FirebaseCrashlytics

//import android.os.StrictMode

// For use in strict mode, to enable, update auto manifest with
// android:name=".SGAutoApp"
class SGAutoApp : Application() {

    private var lastConnectionType: Int = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
    private var carConnectionObserver: Observer<Int>? = null
    private var carConnection: CarConnection? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.init(applicationContext)
        installForegroundServiceCrashGuard()
        logVersionInfo()
        Log.i(TAG, "SGAutoApp onCreate")

        // Firebase and WorkManager auto-initialize via their ContentProviders
        // (FirebaseInitProvider and WM-WrkMgrInitializer), so no manual init needed here.

        // Defer non-critical startup work to after the first frame so MainActivity
        // can begin rendering immediately.  Post to the front of the main-thread
        // queue — this runs after Activity.onCreate/onResume finishes its first pass.
        Handler(Looper.getMainLooper()).post {
            GarageNotificationGroup.reconcile(applicationContext)
            // Schedule the periodic background worker
            GarageStatusWorker.scheduleGarageStatusWork(applicationContext, TAG)
            // Observe car connection using LiveData
            observeCarConnection()
            // Immediately check if we're already in car mode (e.g., process was killed
            // while Android Auto was connected and restarted by WorkManager/notification).
            // CAR_CONNECTION_UPDATED is NOT a protected broadcast, so it won't be
            // delivered to a dead process. This synchronous check fills that gap.
            checkCarModeOnStart()
        }
    }

    private fun observeCarConnection() {
        try {
            Log.i(TAG, "Setting up CarConnection observer")
            carConnection = CarConnection(this)
            carConnectionObserver = Observer { connectionType ->
                try {
                    Log.i(TAG, "CarConnection LiveData observed: connectionType=$connectionType (previous=$lastConnectionType)")

                    val isNowConnected = connectionType == CarConnection.CONNECTION_TYPE_PROJECTION ||
                            connectionType == CarConnection.CONNECTION_TYPE_NATIVE

                    val wasConnected = lastConnectionType == CarConnection.CONNECTION_TYPE_PROJECTION ||
                            lastConnectionType == CarConnection.CONNECTION_TYPE_NATIVE

                    // Update global car session state for tiered polling
                    LusionUtility.isInCarSession = isNowConnected
                    Log.i(TAG, "isInCarSession set to: ${LusionUtility.isInCarSession}")
                    if (!isNowConnected) {
                        NotificationUtility.clearCarSessionNotification(applicationContext)
                    }

                    if (isNowConnected && !wasConnected) {
                        Log.i(TAG, "Car connection established! Triggering garage status refresh...")
                        // Routed through the shared, debounced helper — CarConnectionReceiver
                        // (manifest broadcast) reacts to the SAME event and would otherwise
                        // race this observer, potentially leaving both a foreground service
                        // AND a background poller running concurrently.
                        GarageUtility.startCarConnectMonitoring(applicationContext, "SGAutoApp-carConnect")
                    } else if (!isNowConnected && wasConnected) {
                        Log.i(TAG, "Car connection lost.")
                        try {
                            // Only tear the monitor down if the garage is CONFIRMED CLOSED.
                            // Otherwise (OPEN/OPENING/CLOSING/UNKNOWN) we must keep an elevated
                            // foreground monitor alive: once Android Auto detaches there is no
                            // foreground component left, so the plain background poller gets
                            // frozen by the OS and would silently miss the door finishing its
                            // close after the user parks and walks away.
                            val status = GarageUtility.getLastGarageStatus(applicationContext)
                            if (status == GarageUtility.GarageStatus.CLOSED) {
                                Log.i(TAG, "Car disconnect: garage CLOSED, stopping foreground service")
                                GarageAutoForegroundService.stop(applicationContext)
                            } else {
                                Log.i(TAG, "Car disconnect: garage $status (not confirmed closed) — keeping monitor alive to watch for close")
                                GarageAutoForegroundService.startIfNeeded(
                                    applicationContext,
                                    force = true,
                                    watchUntilClosed = true
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not handle foreground service on car disconnect: ${e.message}")
                        }
                    }

                    lastConnectionType = connectionType
                } catch (e: Exception) {
                    // Guard against DeadObjectException / RemoteException when Android Auto
                    // projection provider dies — prevents the app from being killed along with it
                    Log.e(TAG, "CarConnection observer error (projection provider may have died): ${e.message}")
                }
            }
            carConnection?.type?.observeForever(carConnectionObserver!!)
            Log.i(TAG, "CarConnection observer registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to observe CarConnection: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "SGAutoApp"
        private lateinit var instance: SGAutoApp

        fun getAppContext(): Context {
            return instance.applicationContext
        }
    }

    /**
     * Synchronously check if the device is currently in car mode (UiModeManager).
     * This catches the case where the process was killed while Android Auto was
     * connected and then restarted by WorkManager, notification, or BootReceiver.
     * The CAR_CONNECTION_UPDATED broadcast is not a protected system broadcast,
     * so it is never delivered to a dead process.
     */
    private fun checkCarModeOnStart() {
        try {
            val uiModeManager = getSystemService(UI_MODE_SERVICE) as? UiModeManager
            val isCarMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR
            Log.i(TAG, "checkCarModeOnStart: isCarMode=$isCarMode")

            if (isCarMode && !LusionUtility.isInCarSession) {
                Log.i(TAG, "Process restarted while in car mode — starting garage monitoring")
                LusionUtility.isInCarSession = true
                // Routed through the shared, debounced helper — same reasoning as
                // observeCarConnection().
                GarageUtility.startCarConnectMonitoring(applicationContext, "SGAutoApp-carModeRestart")
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkCarModeOnStart failed: ${e.message}")
        }
    }

    /**
     * Log app version info at startup so logcat always shows which build is running.
     */
    private fun logVersionInfo() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            Log.i(TAG, "App version: ${pInfo.versionName} (code=${pInfo.longVersionCode})")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not read version info: ${e.message}")
        }
    }

    /**
     * Install a global uncaught exception handler that intercepts
     * ForegroundServiceStartNotAllowedException.
     *
     * On Android 12+, startForegroundService() can throw this exception
     * *asynchronously* via Parcel.readException(), which means the caller's
     * try/catch never sees it. If left unhandled, it causes a fatal crash loop
     * (the app restarts, CarConnection LiveData fires again, and it crashes again).
     *
     * We catch it here, log it as non-fatal in Crashlytics, and let the app continue.
     *
     * BUG FIX: because this exception arrives asynchronously, it is never seen by
     * GarageAutoForegroundService.startIfNeeded()'s try/catch — that call already
     * returned `true` (no synchronous exception), so GarageUtility.startCarConnectMonitoring
     * believes the foreground service is handling polling and never falls back to the
     * background poller. Net result: the FGS silently never starts (e.g. Android Auto
     * connects while the app is in a restricted App Standby bucket), NO status poll
     * happens for the rest of the drive, and the garage notification is stuck showing
     * whatever status was last cached. Since we now know for certain the foreground
     * service failed, always kick off the background monitoring thread here as a
     * fallback so the connection isn't silently a no-op.
     */
    private fun installForegroundServiceCrashGuard() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                isForegroundServiceStartException(throwable)
            ) {
                Log.e(TAG, "Caught ForegroundServiceStartNotAllowedException (non-fatal)", throwable)
                try {
                    FirebaseCrashlytics.getInstance().recordException(throwable)
                } catch (_: Exception) { /* ignore */ }
                try {
                    GarageUtility.foregroundServicePolling.set(false)
                    Log.w(TAG, "FGS start silently failed async — falling back to background garage monitoring")
                    GarageUtility.ongoingGarageStatusThread(applicationContext, "ForegroundServiceCrashGuard-fallback")
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback background monitoring after FGS crash guard failed: ${e.message}")
                }
                // Do NOT delegate to default handler — this would crash the app.
                // The exception is non-fatal; the foreground service simply couldn't start.
                return@setDefaultUncaughtExceptionHandler
            }
            // All other exceptions: delegate to the default handler (crash normally)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Check if the throwable (or its cause chain) is a ForegroundServiceStartNotAllowedException.
     * We check by class name to avoid hard dependency on API level 31.
     */
    private fun isForegroundServiceStartException(throwable: Throwable?): Boolean {
        var t = throwable
        while (t != null) {
            val name = t.javaClass.name
            if (name == "android.app.ForegroundServiceStartNotAllowedException" ||
                name == "android.app.InvalidForegroundServiceTypeException") {
                return true
            }
            t = t.cause
        }
        return false
    }
}