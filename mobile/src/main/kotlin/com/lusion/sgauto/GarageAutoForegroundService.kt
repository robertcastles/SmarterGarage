package com.lusion.sgauto

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lusion.sgauto.PersistentLog as Log
import com.lusion.sgauto.Constants.GARAGE_ACTION_POLLING_DELAY
import com.lusion.sgauto.Constants.MAX_FAST_POLLING_TIME_WINDOW
import com.lusion.sgauto.Constants.MAX_MEDIUM_POLLING_TIME_WINDOW
import com.lusion.sgauto.Constants.MEDIUM_GARAGE_POLLING_PING_PERIOD
import com.lusion.sgauto.Constants.NORMAL_GARAGE_POLLING_PING_PERIOD
import com.lusion.sgauto.Constants.SLOW_GARAGE_POLLING_PING_PERIOD
import com.lusion.sgauto.GarageUtility.GarageCommand
import com.lusion.sgauto.GarageUtility.GarageStatus
import com.lusion.sgauto.LusionUtility.UpdateType
import com.lusion.sgauto.PreferenceUtility.getPreference
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * A foreground service that owns garage command + polling network calls directly.
 *
 * When commands come from Android Auto notifications (no app UI open), the OS
 * heavily throttles WorkManager workers and background threads. By making the
 * actual network calls inside this foreground service's coroutine scope,
 * the calls inherit the elevated OOM-adj / scheduling priority.
 *
 * Uses CONNECTED_DEVICE type (no hard time limit), which tells Samsung's
 * Freecess/MARs system that the app has an active IoT device connection,
 * preventing aggressive process freezing and network traffic blocking.
 */
class GarageAutoForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    /**
     * Incremented each time a new command arrives via onStartCommand.
     * The polling coroutine captures the current value at launch. When its finally
     * block runs, if the value has changed, a newer command has replaced this job
     * and the finally block should NOT call stopSelf() or reset foregroundServicePolling.
     */
    private val jobGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
    }

    /**
     * The foreground service's OWN notification id — a separate, minimally-visible
     * "monitoring" card on a low-importance channel. It is intentionally NOT the garage
     * status card (id=1): keeping them separate means the foreground service never owns
     * or churns the status notification, so Android Auto can update/redraw the status card
     * cleanly. This monitoring card is removed the moment monitoring stops.
     */
    private val fgServiceNotificationId: Int
        get() = resources.getInteger(R.integer.garage_fg_service_notification_id)

    @SuppressLint("NewApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: intent=$intent, flags=$flags, startId=$startId")

        val notification = buildMonitoringNotification()
        try {
            synchronized(GarageNotificationGroup) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        fgServiceNotificationId,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(fgServiceNotificationId, notification)
                }
                GarageNotificationGroup.monitorStarted(applicationContext, fgServiceNotificationId, this)
            }
            Log.i(TAG, "startForeground succeeded")
            // Force a fresh OkHttpClient so sockets are created with foreground priority.
            // Android tags sockets with OOM-adj at creation time; a client created while
            // backgrounded may remain throttled even after the process is promoted.
            SmartThingsService.resetClient()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}", e)
            // Foreground promotion failed, but we may still have a garage command to send.
            // Fire it off directly before dying so the user's button press isn't lost.
            val fallbackCmd = intent?.getStringExtra(EXTRA_COMMAND)
            if (fallbackCmd != null) {
                Log.i(TAG, "Sending command $fallbackCmd via fallback (FG start failed)")
                val cmd = if (fallbackCmd == "OPEN") GarageCommand.OPEN else GarageCommand.CLOSE
                serviceScope.launch {
                    try {
                        GarageUtility.sendGarageCommandSuspend(applicationContext, cmd)
                        GarageUtility.ongoingGarageStatusThread(applicationContext, "FGService-fallback-after-fail")
                    } catch (ex: Exception) {
                        Log.e(TAG, "Fallback command also failed: ${ex.message}")
                    } finally {
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }
            // No command — this was a monitor-only start (e.g. geofence / car connect).
            // FG promotion was blocked, so fall back to background polling so we still
            // refresh the status instead of silently doing nothing.
            Log.i(TAG, "Monitor-only FG start failed, falling back to background polling")
            GarageUtility.ongoingGarageStatusThread(applicationContext, "FGService-monitor-fallback-after-fail")
            stopSelf()
            return START_NOT_STICKY
        }

        // Read command from intent (OPEN / CLOSE / null for monitor-only)
        val commandStr = intent?.getStringExtra(EXTRA_COMMAND)
        val command = when (commandStr) {
            "OPEN" -> GarageCommand.OPEN
            "CLOSE" -> GarageCommand.CLOSE
            else -> null
        }

        // When true (set by the car-disconnect handler), a monitor-only session must NOT
        // give up after settling on a final OPEN state — it has to keep watching until the
        // door actually reaches CLOSED (or maxServiceTime), because nothing else is left
        // alive to catch the close once Android Auto has detached.
        val watchUntilClosed = intent?.getBooleanExtra(EXTRA_WATCH_UNTIL_CLOSED, false) == true

        Log.i(TAG, "onStartCommand: command=$commandStr, parsed=$command, watchUntilClosed=$watchUntilClosed")

        // Increment generation BEFORE cancelling old job — the old job's finally block
        // will see the new generation and know not to call stopSelf()
        val myGeneration = jobGeneration.incrementAndGet()
        Log.i(TAG, "Generation: $myGeneration, cancelling old monitorJob=${monitorJob != null}")
        monitorJob?.cancel()
        GarageUtility.foregroundServicePolling.set(true)
        // Stop any ambient background poller (e.g. one already running for plain
        // "in car, watch for changes" monitoring) so it doesn't compete with THIS
        // service's own polling for the same shared request-budget tokens — see
        // stopBackgroundPolling's doc for why that starves/delays our confirmation polls.
        GarageUtility.stopBackgroundPolling("GarageAutoFGService.onStartCommand (gen=$myGeneration)")

        monitorJob = serviceScope.launch {
            Log.i(TAG, "monitorJob launched (gen=$myGeneration)")
            // Kept in coroutine scope so the final cleanup can distinguish a confirmed
            // physical transition from a timeout, cancellation, or monitor-only exit.
            var sawFinalAfterTransitional = false
            var completedObservation: GarageStatus? = null
            try {
                // --- Phase 1: Send garage command (if any) ---
                // All sources share command serialization. Queue admission remains
                // cancellable; the executor protects an already-dispatched request.
                if (command != null) {
                    Log.i(TAG, "Phase 1: Sending garage command: $command")
                    val accepted = GarageUtility.sendGarageCommandSuspend(applicationContext, command)
                    Log.i(TAG, "Phase 1: Command result: accepted=$accepted")

                    if (accepted) {
                        // The command was accepted. Eagerly reflect the moving state on the
                        // (separate) garage status card so the Android Auto user immediately
                        // sees OPENING/CLOSING. The status card is independent of this
                        // service's own monitoring notification, so there is no detach/repost
                        // dance — we just post the status like any other update.
                        val movingStatus = when (command) {
                            GarageCommand.OPEN -> GarageStatus.OPENING
                            GarageCommand.CLOSE -> GarageStatus.CLOSING
                        }
                        NotificationUtility.postGarageStatusCard(applicationContext, movingStatus)
                        Log.i(TAG, "Phase 1: Waiting ${GARAGE_ACTION_POLLING_DELAY}ms for garage to begin moving...")
                        delay((GARAGE_ACTION_POLLING_DELAY + 500).milliseconds)
                        Log.i(TAG, "Phase 1: Delay complete, entering Phase 2")
                    } else {
                        Log.w(TAG, "Phase 1: Command NOT accepted, entering Phase 2 anyway")
                    }
                } else {
                    Log.i(TAG, "Phase 1: No command, entering Phase 2 (monitor-only)")
                }

                // --- Phase 2: Poll for garage status ---
                Log.i(TAG, "Phase 2: Starting polling loop (gen=$myGeneration, isActive=$isActive)")
                val pollStartTime = System.currentTimeMillis()
                val maxServiceTime = 300_000L // 5 minutes max for a garage cycle
                var pollCount = 0
                var consecutiveErrors = 0
                // Track whether we ever saw a transitional state to know the command took effect
                var sawTransitional = false
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - pollStartTime

                    // Stop if max service time exceeded
                    if (elapsed > maxServiceTime) {
                        Log.i(TAG, "Max service time exceeded (${elapsed}ms), stopping")
                        break
                    }

                    // Make the status request DIRECTLY in the foreground service scope
                    Log.d(TAG, "Polling #${++pollCount} at ${elapsed}ms")
                    val result = GarageUtility.makeStatusRequest(
                        applicationContext,
                        ignoreTimers = true, // We control our own timing
                        caller = "FGService-poll-$pollCount"
                    )

                    // Check garage state to decide polling speed and whether to stop
                    val status = GarageUtility.getLastGarageStatus(applicationContext)
                    val observed = (result as? StatusFetchResult.Fresh)?.status
                    val isTransitional = observed == GarageStatus.OPENING || observed == GarageStatus.CLOSING
                    val isFinal = observed == GarageStatus.OPEN || observed == GarageStatus.CLOSED
                    val isUnknown = result is StatusFetchResult.Failed
                    System.currentTimeMillis() - GarageUtility.garageActionTimeStamp

                    if (isTransitional) {
                        sawTransitional = true
                        consecutiveErrors = 0
                    } else if (isFinal) {
                        consecutiveErrors = 0
                        if (sawTransitional) {
                            sawFinalAfterTransitional = true
                        }
                    } else if (isUnknown) {
                        consecutiveErrors++
                        // Samsung MARs/Freecess may have tagged old sockets as frozen;
                        // reset the client to create fresh sockets with foreground priority
                        if (consecutiveErrors >= 2) {
                            Log.w(TAG, "Resetting OkHttp client after $consecutiveErrors consecutive errors (Samsung Freecess workaround)")
                            SmartThingsService.resetClient()
                        }
                    }

                    // Determine delay based on polling tier
                    // Use 1x multiplier (not the background 2x) since we ARE the elevated context
                    val pollDelay = when {
                        elapsed < MAX_FAST_POLLING_TIME_WINDOW -> {
                            NORMAL_GARAGE_POLLING_PING_PERIOD + 100
                        }
                        elapsed < MAX_MEDIUM_POLLING_TIME_WINDOW -> {
                            MEDIUM_GARAGE_POLLING_PING_PERIOD
                        }
                        else -> {
                            SLOW_GARAGE_POLLING_PING_PERIOD
                        }
                    }
                    Log.d(TAG, "Status=$status, transitional=$isTransitional, sawTransitional=$sawTransitional, sawFinal=$sawFinalAfterTransitional, errors=$consecutiveErrors, delay=${pollDelay}ms")                    // Stop conditions:
                    // 1. We saw the garage transition and then reach a final state (the normal success case)
                    val mayFinish = AutoMonitorPolicy.mayFinish(watchUntilClosed, result)
                    if (sawFinalAfterTransitional && mayFinish) {
                        completedObservation = observed
                        Log.i(TAG, "Garage completed transition to final state ($status), stopping")
                        break
                    }
                    // 2. If we never sent a command (monitor-only), stop after reaching a final non-UNKNOWN state after 30s
                    if (command == null && mayFinish && elapsed > 30_000) {
                        // ...unless this is a "watch until closed" session (started on car
                        // disconnect with the door still open): keep polling an OPEN door
                        // until it actually closes so we don't abandon the very transition
                        // we were kept alive to catch.
                        val keepWatchingOpen = watchUntilClosed && status != GarageStatus.CLOSED
                        if (!keepWatchingOpen) {
                            completedObservation = observed
                            Log.i(TAG, "Monitor-only: garage in final state ($status), stopping")
                            break
                        }
                    }
                    // 3. If command was sent, but we never saw transitional AND enough time passed,
                    //    the command may not have taken effect. Stop after 60s.
                    if (command != null && !sawTransitional && mayFinish && elapsed > 60_000) {
                        completedObservation = observed
                        Log.i(TAG, "Command sent but no transition seen, garage stable at $status after ${elapsed}ms, stopping")
                        break
                    }
                    // 4. Too many consecutive network errors — slow down but DON'T stop
                    //    (the foreground service is our best chance at network access)
                    val adjustedDelay = if (consecutiveErrors > 5) {
                        Log.w(TAG, "Many consecutive errors ($consecutiveErrors), using slow polling")
                        SLOW_GARAGE_POLLING_PING_PERIOD
                    } else {
                        pollDelay
                    }

                    // Pace against the shared request budget too: our fast tier alone
                    // (~1.1s) exceeds SmartThings' ~10/min quota, so ticking sooner than a
                    // slot will actually be available just wakes us up to silently skip a
                    // throttled request. Sleep at least until the next slot frees up.
                    val budgetWaitMs = GarageUtility.msUntilNextRequestSlot()
                    val finalDelay = maxOf(adjustedDelay, budgetWaitMs, GarageUtility.rateLimitRemainingMs())
                    if (budgetWaitMs > adjustedDelay) {
                        Log.d(TAG, "Pacing to request budget: waiting ${finalDelay}ms (budget frees in ${budgetWaitMs}ms) instead of ${adjustedDelay}ms")
                    }

                    delay(finalDelay.milliseconds)
                }
            } catch (_: CancellationException) {
                Log.i(TAG, "Monitor job cancelled (gen=$myGeneration, current=${jobGeneration.get()})")
            } catch (e: Exception) {
                Log.e(TAG, "Error in monitor job", e)
            } finally {
                val isReplaced = jobGeneration.get() != myGeneration
                if (isReplaced) {
                    // A newer command has taken over — do NOT stop the service or reset polling.
                    Log.i(TAG, "Monitor job (gen=$myGeneration) replaced by newer command (gen=${jobGeneration.get()}), skipping cleanup")
                } else {
                    Log.i(TAG, "Monitor job (gen=$myGeneration) finished, cleaning up")
                    GarageUtility.foregroundServicePolling.set(false)

                    // Hand off to background polling unless the garage is CONFIRMED CLOSED.
                    // Includes OPEN (not just OPENING/CLOSING/UNKNOWN) — an open garage still
                    // needs to be watched until it closes; otherwise hitting maxServiceTime
                    // (5 min) while stably OPEN would silently end all monitoring with no
                    // automatic resumption until the next unrelated trigger (car reconnect,
                    // geofence change, or the 15-minute periodic worker).
                    val finalStatus = GarageUtility.getLastGarageStatus(applicationContext)
                    if (completedObservation != GarageStatus.CLOSED) {
                        Log.i(TAG, "Garage still $finalStatus (not confirmed closed), starting background polling fallback")
                        GarageUtility.ongoingGarageStatusThread(applicationContext, "FGService-fallback")
                    }
                    // Remove this service's own monitoring notification, then confirm the
                    // final garage status on the (independent) status card if we saw a real
                    // physical transition complete.
                    stopMonitoring(if (sawFinalAfterTransitional) completedObservation else null)
                    reconcileNotificationAfterRelease()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        monitorJob?.cancel()
        GarageUtility.foregroundServicePolling.set(false)
        serviceScope.cancel()
        synchronized(GarageNotificationGroup) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.w(TAG, "onDestroy: stopForeground failed: ${e.message}")
            } finally {
                GarageNotificationGroup.monitorStopped(applicationContext, fgServiceNotificationId, this)
            }
        }
        super.onDestroy()
    }

    /**
     * Called by the system when foreground service time limit is about to be reached.
     * connectedDevice type has no hard limit, but we keep this as a safety net.
     */
    @SuppressLint("NewApi")
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout called by system (startId=$startId, fgsType=$fgsType)")
        GarageUtility.foregroundServicePolling.set(false)
        monitorJob?.cancel()

        // Hand off to background polling unless the garage is confirmed CLOSED — see the
        // matching comment in the monitorJob finally block for why OPEN must be included.
        val status = GarageUtility.getLastGarageStatus(applicationContext)
        if (status != GarageStatus.CLOSED) {
            Log.i(TAG, "System timeout: garage still $status, starting background fallback")
            GarageUtility.ongoingGarageStatusThread(applicationContext, "FGService-timeout-fallback")
        }

        stopMonitoring()
        reconcileNotificationAfterRelease()
    }

    /** Re-applies the normal geofence visibility rule after monitoring finishes. */
    private fun reconcileNotificationAfterRelease() {
        try {
            NotificationUtility.refreshNotificationBasedOnGeofence(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "reconcileNotificationAfterRelease failed: ${e.message}")
        }
    }

    /** Ensures the low-importance channel used for this service's own monitoring card exists. */
    private fun createFgServiceChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Remove the older LOW-importance channel from a previous build so the monitoring
        // card is truly minimal (an existing channel's importance can't be lowered in code).
        try { nm.deleteNotificationChannel("garage_foreground_service") } catch (_: Exception) {}
        val channel = NotificationChannel(
            getString(R.string.garage_fg_service_channel_id),
            getString(R.string.garage_fg_service_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.garage_fg_service_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Builds the service's quiet monitoring card. Group ordering requests that it sits
     * below the garage status card; Android and the device manufacturer control rendering.
     * Foreground ownership remains separate from the status card and phone group summary.
     */
    private fun buildMonitoringNotification(): android.app.Notification {
        createFgServiceChannel()
        return NotificationCompat.Builder(this, getString(R.string.garage_fg_service_channel_id))
            .setSmallIcon(R.drawable.smarter_garage_svg)
            .setContentTitle(getString(R.string.garage_monitoring))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setGroup(GarageNotificationGroup.GROUP_KEY)
            .setSortKey("99_monitoring")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    /**
     * Removes this service's own monitoring notification (so it is not left lingering in the
     * shade) and stops the service. The garage STATUS card (id=1) is independent and is left
     * alone — its visibility follows the normal geofence rule. If a real physical transition
     * completed, [finalStatus] is posted once to confirm the final OPEN/CLOSED state.
     */
    private fun stopMonitoring(finalStatus: GarageStatus? = null) {
        synchronized(GarageNotificationGroup) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                Log.i(TAG, "stopMonitoring: monitoring notification removed")
            } catch (e: Exception) {
                Log.w(TAG, "stopMonitoring: stopForeground failed: ${e.message}")
            }
            GarageNotificationGroup.monitorStopped(applicationContext, fgServiceNotificationId, this)
        }
        if (finalStatus != null) {
            Log.i(TAG, "stopMonitoring: confirming final status=$finalStatus on status card")
            NotificationUtility.postGarageStatusCard(applicationContext, finalStatus)
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "GarageAutoFGService"
        private const val PREF_KEY = "use_foreground_service_auto"
        private const val EXTRA_COMMAND = "garage_command"
        private const val EXTRA_WATCH_UNTIL_CLOSED = "watch_until_closed"

        /**
         * ALWAYS starts the foreground service with a garage command (OPEN/CLOSE).
         * No preference gate - commands from Android Auto notification MUST go through
         * the foreground service to get network access on Android 12+.
         *
         * @param command The garage command (OPEN/CLOSE) to execute.
         * @return true if the service was started successfully.
         */
        fun startWithCommand(context: Context, command: GarageCommand): Boolean {
            Log.i(TAG, "startWithCommand: command=$command, appForeground=${LusionUtility.isAppInForeground()}")
            val intent = Intent(context, GarageAutoForegroundService::class.java).apply {
                putExtra(EXTRA_COMMAND, command.name)
            }
            return try {
                context.startForegroundService(intent)
                Log.i(TAG, "startWithCommand: startForegroundService() call returned (may still fail async)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "startWithCommand: startForegroundService() FAILED immediately: ${e.javaClass.simpleName}: ${e.message}")
                // Don't send command here — the caller (DataRequestReceiver) already sent it
                false
            }
        }

        /**
         * Starts the foreground service for ongoing monitoring (no command).
         *
         * Normally gated behind the "use_foreground_service_auto" preference, but
         * callers can pass [force] = true to always attempt a start — used by the
         * geofence and Android-Auto wake receivers, which want the elevated,
         * fresh-socket polling of the FG service rather than the throttleable
         * background thread.
         *
         * @param command Optional garage command - if non-null, delegates to startWithCommand().
         * @param force When true, ignore the preference gate and always try to start.
         * @return true if a foreground start was requested (the caller should rely on
         *         the service for polling); false if it was skipped or the start threw
         *         (the caller should fall back to background polling).
         */
        fun startIfNeeded(context: Context, command: GarageCommand? = null, force: Boolean = false, watchUntilClosed: Boolean = false): Boolean {
            // If a command is provided, always start (no preference gate)
            if (command != null) {
                return startWithCommand(context, command)
            }

            if (LusionUtility.isAppInForeground()) {
                Log.d(TAG, "App is in foreground, foreground service not needed")
                return false
            }

            val enabled = force || (getPreference(PREF_KEY, Boolean::class.java, context) as? Boolean ?: false)
            if (!enabled) {
                Log.d(TAG, "Foreground service preference not enabled and not forced, skipping monitor-only start")
                return false
            }
            Log.i(TAG, "Starting foreground service for monitoring (force=$force, watchUntilClosed=$watchUntilClosed)")
            val intent = Intent(context, GarageAutoForegroundService::class.java).apply {
                if (watchUntilClosed) putExtra(EXTRA_WATCH_UNTIL_CLOSED, true)
            }
            return try {
                context.startForegroundService(intent)
                Log.i(TAG, "startIfNeeded: startForegroundService() requested")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service for monitoring: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

        /**
         * Stops the foreground service.
         */
        fun stop(context: Context) {
            Log.i(TAG, "Stopping foreground service")
            val intent = Intent(context, GarageAutoForegroundService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop foreground service: ${e.message}", e)
            }
        }

    }
}




