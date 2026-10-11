// File: `GarageUtility.kt`
package com.lusion.sgauto

import android.content.Context
import android.content.Intent
import android.os.Build
import com.lusion.sgauto.PersistentLog as Log
import com.lusion.sgauto.Constants.GARAGE_ACTION_POLLING_DELAY
import com.lusion.sgauto.Constants.MAX_GARAGE_POLLING_TIME
import com.lusion.sgauto.Constants.MAX_SLOW_POLLING_TIME_WINDOW
import com.lusion.sgauto.Constants.MEDIUM_GARAGE_POLLING_PING_PERIOD
import com.lusion.sgauto.Constants.NORMAL_GARAGE_POLLING_PING_PERIOD
import com.lusion.sgauto.LusionUtility.UpdateType
import com.lusion.sgauto.LusionUtility.resumeFlag
import com.lusion.sgauto.LusionUtility.sleep
import com.lusion.sgauto.NotificationUtility.alertAutoActivity
import com.lusion.sgauto.NotificationUtility.alertMainActivity
import com.lusion.sgauto.NotificationUtility.triggerNotification
import com.lusion.sgauto.PreferenceUtility.getAccessKey
import com.lusion.sgauto.PreferenceUtility.getPreference
import com.lusion.sgauto.PreferenceUtility.validGarageTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import retrofit2.HttpException
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

object GarageUtility {
    private const val TAG = "GarageUtility"
    private val commandExecutor = CommandExecutor()
    var gStartFastLoop = true
    var garageUpdateRunning = AtomicBoolean(false)

    /** When true, the foreground service owns polling — skip starting ongoingGarageStatusThread */
    var foregroundServicePolling = AtomicBoolean(false)

    private var gGarageThreadRunnable: GarageMonitorControllableRunnable? = null
    var garageActionTimeStamp: Long = 0
    private var gGarageStatusSetFromCall = false
    var gLastGarageStatusCheckTime: Long = 0

    /**
     * Wall-clock time of the last SUCCESSFUL status confirmation from the API.
     * Unlike [gLastGarageStatusCheckTime] (which is also bumped on failed polls),
     * this is only updated when we actually receive a valid status. The phone
     * notification uses this so its displayed timestamp reflects real data
     * freshness instead of the moment the notification happened to be rebuilt —
     * otherwise a stale status can appear "fresh" with a current timestamp.
     */
    @Volatile
    var gLastSuccessfulStatusTime: Long = 0
    var garage_access_key: String = ""
    private var gGarageThread = GaragePollingHandlerThread()
    private var garagePollingTime = NORMAL_GARAGE_POLLING_PING_PERIOD
    private var gSlowPollingTimeWindow = MAX_SLOW_POLLING_TIME_WINDOW 
    private var gSlowPollingTimeRemaining = gSlowPollingTimeWindow
    private var gSlowPolling = false
    var garageCheckTimeStamp: Long = 0

    private var dnsRetryCount = 0
    private var lastDnsRetryTime = 0L
    private const val MAX_DNS_RETRIES = 3
    private const val DNS_RETRY_DELAY = 5000L 
    private const val DNS_RETRY_TIMEOUT = 60000L

    /**
     * Cooldown for foreground PulseWorker escalation. When the background process
     * hits enough consecutive errors to declare UNKNOWN, we try once via the
     * PulseWorker. Limit to once per hour to conserve battery.
     */
    private var lastFgEscalationTime = 0L
    private const val FG_ESCALATION_COOLDOWN_MS = 60 * 60 * 1000L // 1 hour between escalations

    /**
     * Consecutive network error counter. A single transient timeout should NOT
     * wipe a known-good status (OPEN/CLOSED) to UNKNOWN. We only transition to
     * UNKNOWN after [CONSECUTIVE_ERRORS_BEFORE_UNKNOWN] back-to-back failures.
     */
    private var consecutiveNetworkErrors = 0
    private const val CONSECUTIVE_ERRORS_BEFORE_UNKNOWN = 3

    /** Called by GaragePulseWorker after a successful poll to clear the failure counter */
    fun resetBgFailureCounter() {
        consecutiveNetworkErrors = 0
    }

    // ---- SmartThings HTTP 429 (Too Many Requests) backoff ----
    // Observed in the wild: our polling loops (foreground service @ ~1.1s/tick,
    // background monitor thread) can burn through SmartThings' short-window request
    // quota (their error body reports things like "attempt 11 limit 10 - retry in
    // 44239 millis") and then keep hammering the API every ~1-2s anyway, which just
    // re-triggers the same 429 over and over. That rapid-fire failure streak is what
    // was flipping the status to UNKNOWN within a few seconds of monitoring starting
    // (geofence entry / Android Auto connect) — NOT an actual connectivity problem.
    // Once we see a 429, respect the server's requested retry delay and suspend ALL
    // status polling (foreground + background) until it elapses, instead of treating
    // it like a generic transient error.
    @Volatile private var rateLimitedUntilMs: Long = 0L
    private const val DEFAULT_RATE_LIMIT_BACKOFF_MS = 15_000L
    private const val MAX_RATE_LIMIT_BACKOFF_MS = 90_000L

    /** True if we're currently inside a SmartThings-imposed 429 backoff window. */
    fun isRateLimited(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs

    /** Milliseconds remaining in the current 429 backoff window (0 if not rate-limited). */
    fun rateLimitRemainingMs(): Long = (rateLimitedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Parses SmartThings' "...retry in 44239 millis" hint out of a 429 error body, if present. */
    private fun parseRetryAfterMillis(errorBody: String?): Long? =
        errorBody?.let { Regex("retry in (\\d+) millis").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** Records a 429 response and starts/extends the backoff window accordingly. */
    private fun handleRateLimited(source: String, responseBody: String?) {
        val retryMs = (parseRetryAfterMillis(responseBody) ?: DEFAULT_RATE_LIMIT_BACKOFF_MS)
            .coerceIn(1_000L, MAX_RATE_LIMIT_BACKOFF_MS)
        val until = System.currentTimeMillis() + retryMs
        if (until > rateLimitedUntilMs) rateLimitedUntilMs = until
        Log.w(TAG, "$source: HTTP 429 from SmartThings, backing off ${retryMs}ms (all polling paused)")
    }

    // ---- Proactive client-side request budget (stay under SmartThings' ~10 req/min quota) ----
    // The reactive 429 backoff above only kicks in AFTER we've already been rejected — but a
    // SINGLE poller alone can blow through a 10-requests-per-minute quota on its own: the
    // foreground service's "fast" phase ticks every ~1.1s (~54 req/min), and even the
    // "medium" phase (5s ticks, ~12 req/min) is still over the limit. Rather than waiting to
    // get punished with a 429 (which imposes a much longer server-dictated backoff, up to
    // MAX_RATE_LIMIT_BACKOFF_MS), self-throttle our own outgoing requests.
    //
    // Modeled as a continuously-refilling TOKEN BUCKET rather than either a fixed-window
    // "burst N then reset" bucket (which lets all N requests fire back-to-back and can still
    // collide with the SERVER's own window boundary and trip a 429 — this is what a previous
    // version did) or a rigid fixed minimum interval between EVERY single request (which fixed
    // the 429s but made status feedback right after a user-initiated Open/Close command feel
    // "stuck" for up to 6s at a time instead of animating through opening/closing). A token
    // bucket gets the best of both: it never exceeds the same steady 10/min average over any
    // window (so it's just as safe against 429s as the strict fixed-interval version), but a
    // short burst of saved-up tokens lets the command + its first couple of confirmation polls
    // go out almost immediately when the user is actively watching, then throttles back down to
    // the steady rate for ongoing ambient monitoring. All request-issuing paths (status polls
    // AND commands) share this gate since SmartThings' quota is per-token, not per code path.
    private const val REQUEST_TOKEN_REFILL_MS = 6_000.0 // 60s / 10 requests = the full observed quota, as a refill rate
    private const val MAX_REQUEST_BURST_TOKENS = 3.0 // allow a command + ~2 fast confirmation polls back-to-back
    @Volatile private var requestTokens: Double = MAX_REQUEST_BURST_TOKENS
    @Volatile private var lastTokenRefillAtMs: Long = System.currentTimeMillis()

    @Synchronized
    private fun refillTokens() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTokenRefillAtMs
        if (elapsed <= 0) return
        requestTokens = (requestTokens + elapsed / REQUEST_TOKEN_REFILL_MS).coerceAtMost(MAX_REQUEST_BURST_TOKENS)
        lastTokenRefillAtMs = now
    }

    /** Reserves the next request slot from the token bucket. Returns false if no token is currently available. */
    @Synchronized
    private fun tryReserveRequestSlot(): Boolean {
        refillTokens()
        if (requestTokens < 1.0) return false
        requestTokens -= 1.0
        return true
    }

    /**
     * Deducts a token for a request that bypasses the gate (e.g. a user-initiated command) so
     * subsequent polls still respect the shared budget. Allowed to go negative — a command sent
     * while the bucket is already empty must still push out the next poll's wait time instead of
     * effectively granting it a free token.
     */
    @Synchronized
    private fun recordRequestSent() {
        refillTokens()
        requestTokens -= 1.0
    }

    /** ms until the next request token is available (0 if one is available right now). Public so pollers can pace their own sleep instead of spinning uselessly against the gate. */
    fun msUntilNextRequestSlot(): Long {
        refillTokens()
        if (requestTokens >= 1.0) return 0L
        return ((1.0 - requestTokens) * REQUEST_TOKEN_REFILL_MS).toLong().coerceAtLeast(0L)
    }

    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        if (model.startsWith(manufacturer)) {
            return model.capitalizeWords()
        }
        return "${manufacturer.capitalizeWords()} ${model.capitalizeWords()}"
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { word ->
            word.lowercase(Locale.getDefault())
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }


    fun requestGarageData(context: Context) {
        Log.i(TAG, "requestGarageData: Sending intent com.lusion.sgauto.DataRequestReceiver: " + LusionUtility.DataRequestType.GETGARAGESTATUS.ordinal)
        val intent = Intent(context, DataRequestReceiver::class.java)
        intent.putExtra("ACTION", "GETGARAGESTATUS")
        Log.i(TAG, "requestGarageData: broadcast GETGARAGESTATUS")
        context.sendBroadcast(intent)
    }

     fun pingDebugServer(context: Context, additionalInfo: String? = null) {
         val forcePing = getPreference("force_ping", Boolean::class.java, context) as? Boolean == true
         val debugMode = getPreference("debug_switch", Boolean::class.java, context) as? Boolean == true

         if(forcePing || debugMode) {
             Log.i(TAG, "sendPing: Sending ping data")

             CoroutineScope(Dispatchers.IO).launch {
                 try {
                     val date = java.util.Date(System.currentTimeMillis())
                     val formatter =
                         java.text.SimpleDateFormat("MM/dd/yyyy hh:mm:ss a", Locale.getDefault())
                     formatter.timeZone = java.util.TimeZone.getDefault()
                     val formattedDate = formatter.format(date)

                     val lastGarageStatus = getLastGarageStatus(context).toString()

                     val pingRequest = PingRequest(
                         timestamp = formattedDate,
                         device = getDeviceName(),
                         lastGarageStatus = lastGarageStatus,
                         additionalInfo = additionalInfo.takeIf { it != null } ?: ""
                     )

                     val response = SmartThingsService.getDiagnosticInstance(context)
                         .sendPing(pingRequest)

                     if (response.isSuccessful) {
                         Log.i(TAG, "pingDebugServer: Response: ${response.body()?.message}")
                     } else {
                         Log.e(TAG, "pingDebugServer: Error: ${response.errorBody()?.string()}")
                     }
                 } catch (e: Exception) {
                     Log.e(TAG, "pingDebugServer: Error", e)
                 }
             }
         }
     }
    
    /**
     * Suspend version of sendGarageCommand - can be called from structured coroutines
     * (e.g., foreground service scope) to inherit the caller's priority context.
     * Returns true if the command was accepted, false otherwise.
     */
    suspend fun sendGarageCommandSuspend(context: Context, command: GarageCommand): Boolean =
        commandExecutor.execute { dispatchGarageCommand(context.applicationContext, command) }

    private suspend fun dispatchGarageCommand(context: Context, command: GarageCommand): Boolean {
        try {
            if (!validGarageTokens(context)) return false

            val garageAccessKey = getPreference("garage_device_id", String::class.java, context) as String

            val commandRequest = GarageCommandRequest(
                commands = listOf(
                    GarageCommandRequest.Command(
                        command = command.toString().lowercase()
                    )
                )
            )

            val debugMode = getPreference("debug_switch", Boolean::class.java, context) as? Boolean == true
            val baseUrl = if (debugMode) (getPreference("debug_server", String::class.java, context) as? String) ?: "https://api.smartthings.com" else "https://api.smartthings.com"

            val response = SmartThingsService.getInstance(context)
                .sendGarageCommand(garageAccessKey, commandRequest)
            // Commands are rare and user-initiated (an Open/Close tap) — never block them on
            // our own throttle, but DO record them so status-poll self-throttling accounts
            // for the quota a command just consumed.
            recordRequestSent()

            if (response.isSuccessful) {
                response.body()?.let { commandResponse ->
                    val status = commandResponse.results.firstOrNull()?.status
                    handleGarageCommandResponse(context, status, command)
                    return status == "ACCEPTED"
                }
            } else {
                Log.e(TAG, "sendGarageCommand: unsuccessful response from $baseUrl: ${response.code()}")
                logApiError(context, response, "sendGarageCommand")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val debugMode = getPreference("debug_switch", Boolean::class.java, context) as? Boolean == true
            val baseUrl = if (debugMode) (getPreference("debug_server", String::class.java, context) as? String) ?: "https://api.smartthings.com" else "https://api.smartthings.com"
            Log.e(TAG, "Error sending garage command to $baseUrl", e)
            accessError(context, "sendGarageCommand", baseUrl, e)
        }
        return false
    }

    fun sendGarageCommand(context: Context, command: GarageCommand) {
        val request = OneTimeWorkRequestBuilder<GarageCommandWorker>()
            .setInputData(workDataOf("ACTION" to command.name))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(request)
    }

    private fun handleGarageCommandResponse(context: Context, response: String?, command: GarageCommand) {
        Log.d(TAG, "sendGarageCommand: response is $response!!")

        if (response == "ACCEPTED") {
            gGarageThreadRunnable?.interrupt()
            garageActionTimeStamp = System.currentTimeMillis()
            gGarageStatusSetFromCall = true
            val newState = when (command) {
                GarageCommand.OPEN -> {
                    setLastGarageStatus(context, GarageStatus.OPENING)
                    GarageStatus.OPENING
                }
                GarageCommand.CLOSE -> {
                    setLastGarageStatus(context, GarageStatus.CLOSING)
                    GarageStatus.CLOSING
                }
            }
            updateGarageAppAreas(context.applicationContext, newState)
            // Start background polling as a safety net, but ONLY if the foreground service
            // is not already handling polling. When the FG service is active, it owns the
            // polling loop and duplicate polling would cause duplicate status requests.
            if (!foregroundServicePolling.get()) {
                Log.i(TAG, "handleGarageCommandResponse: Starting background monitoring")
                ongoingGarageStatusThread(context, "sendGarageCommand", GARAGE_ACTION_POLLING_DELAY)
            } else {
                Log.i(TAG, "handleGarageCommandResponse: FG service is polling, skipping background monitoring")
            }
        }
    }

    private fun resetDnsRetryCountIfNeeded() {
        if (System.currentTimeMillis() - lastDnsRetryTime > DNS_RETRY_TIMEOUT) {
            dnsRetryCount = 0
        }
    }

    private fun logApiError(context: Context, response: retrofit2.Response<*>, source: String) {
        val bodyString = try { response.errorBody()?.string() } catch (e: Exception) { null }
        val errorInfo = buildString {
            appendLine("URL: ${response.raw().request.url}")
            appendLine("Code: ${response.code()}")
            appendLine("Message: ${response.message()}")
            appendLine("Body: ${bodyString ?: "No error body"}")
        }

        Log.e(TAG, "$source failed:\n$errorInfo")
        pingDebugServer(context, "$source HTTP error:\n$errorInfo")
        accessError(context, source, response.raw().request.url.toString(), HttpException(response), response.code(), bodyString)
    }

    private fun accessError(
        context: Context,
        src: String,
        finalUrl: String,
        throwable: Throwable?,
        httpCode: Int? = null,
        responseBody: String? = null
    ) {
        gLastGarageStatusCheckTime = System.currentTimeMillis()

        // HTTP 429 = SmartThings' own request-quota rate limiting, not a connectivity
        // problem. Hammering it with more requests (or resetting sockets, which only
        // helps with frozen/dead connections) just prolongs the outage. Back off for
        // the server-requested duration and skip the generic error bookkeeping below —
        // a rate limit alone should not flip a known-good status to UNKNOWN.
        if (httpCode == 429) {
            handleRateLimited(src, responseBody)
            return
        }

        val errorInfo = buildString {
            appendLine("Source: $src")
            appendLine("URL: $finalUrl")
            appendLine("Error: ${throwable?.localizedMessage}")

            if (finalUrl.isNotEmpty()) {
                try {
                    val host = URL(finalUrl).host
                    val ip = InetAddress.getByName(host).hostAddress
                    appendLine("Host: $host")
                    appendLine("IP: $ip")
                } catch (e: Exception) {
                    appendLine("DNS Resolution Failed: ${e.message}")
                    if (throwable is java.net.UnknownHostException) {
                        resetDnsRetryCountIfNeeded()

                        if (dnsRetryCount < MAX_DNS_RETRIES) {
                            dnsRetryCount++
                            lastDnsRetryTime = System.currentTimeMillis()
                            Log.i(TAG, "DNS retry attempt $dnsRetryCount of $MAX_DNS_RETRIES")
                            CoroutineScope(Dispatchers.IO).launch {
                                delay(DNS_RETRY_DELAY.milliseconds)
                                makeStatusRequest(context, false, "$src-retry-$dnsRetryCount")
                            }
                            return
                        } else {
                            dnsRetryCount = 0
                            appendLine("Max DNS retries reached")
                        }
                    }
                }
            }
        }

        Log.e(TAG, "Access Error:\n$errorInfo")
        pingDebugServer(context, errorInfo)

        consecutiveNetworkErrors++

        // Samsung Freecess/MARs (and stock Android background restrictions) can tag
        // this process's sockets as frozen once it is throttled, so every retry keeps
        // failing on the same dead sockets and never recovers — which is exactly what
        // makes us flash UNKNOWN right after a geofence/Android-Auto wake. Force a
        // fresh OkHttp client (new sockets) after repeated failures, mirroring the
        // foreground service's recovery path, giving the background poll a real chance
        // to recover BEFORE we give up and overwrite a known-good status.
        if (consecutiveNetworkErrors >= 2) {
            Log.w(TAG, "accessError: resetting OkHttp client after $consecutiveNetworkErrors consecutive errors (frozen-socket recovery)")
            try { SmartThingsService.resetClient() } catch (e: Exception) {
                Log.w(TAG, "accessError: resetClient failed: ${e.message}")
            }
        }

        // Don't overwrite a known status with UNKNOWN on a transient network error.
        // 1. Never overwrite a transitional state (OPENING/CLOSING) within a generous
        //    action window — the garage is physically moving.
        // 2. For final states (OPEN/CLOSED), tolerate up to N consecutive failures
        //    before declaring UNKNOWN so a single timeout doesn't flash the UI.
        val currentStatus = getLastGarageStatus(context)
        val isTransitional = currentStatus == GarageStatus.OPENING || currentStatus == GarageStatus.CLOSING
        val withinActionWindow = System.currentTimeMillis() - garageActionTimeStamp < GARAGE_ACTION_POLLING_DELAY * 4 // ~32s grace
        val isFinal = currentStatus == GarageStatus.OPEN || currentStatus == GarageStatus.CLOSED

        if (isTransitional && withinActionWindow) {
            Log.i(TAG, "accessError: Preserving transitional state $currentStatus (network error within action window, streak=$consecutiveNetworkErrors)")
        } else if (isFinal && consecutiveNetworkErrors < CONSECUTIVE_ERRORS_BEFORE_UNKNOWN) {
            Log.i(TAG, "accessError: Preserving final state $currentStatus (consecutive errors $consecutiveNetworkErrors < $CONSECUTIVE_ERRORS_BEFORE_UNKNOWN)")
        } else {
            Log.w(TAG, "accessError: Setting UNKNOWN (consecutive errors $consecutiveNetworkErrors, current=$currentStatus)")
            updateGarageAppAreas(context, GarageStatus.UNKNOWN)

            // We just hit UNKNOWN — immediately try to recover via the foreground
            // PulseWorker which gets elevated network access. Limit to once per hour
            // to avoid draining the battery with repeated foreground promotions.
            if (!foregroundServicePolling.get()) {
                val now = System.currentTimeMillis()
                if (now - lastFgEscalationTime > FG_ESCALATION_COOLDOWN_MS) {
                    lastFgEscalationTime = now
                    Log.i(TAG, "accessError: Auto-escalating to PulseWorker to recover from UNKNOWN")
                    try {
                        GaragePulseWorker.enqueue(context)
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto-escalation to PulseWorker failed: ${e.message}")
                    }
                } else {
                    Log.d(TAG, "accessError: FG escalation on cooldown (${(FG_ESCALATION_COOLDOWN_MS - (now - lastFgEscalationTime)) / 1000}s remaining)")
                }
            }
        }
    }

    /** Tracks when we last refreshed the phone notification even for unchanged status */
    private var lastNotificationRefreshTime = 0L
    /** Refresh the notification at least every 30 seconds so the timestamp stays current */
    private const val NOTIFICATION_REFRESH_INTERVAL = 30_000L

    fun updateGarageAppAreas(context: Context, garageStatus: GarageStatus) {
        val lastStatus = getLastGarageStatus(context)
        val isMoving = garageStatus == GarageStatus.OPENING || garageStatus == GarageStatus.CLOSING
        val now = System.currentTimeMillis()

        if (garageStatus != GarageStatus.UNKNOWN && !isMoving && garageStatus == lastStatus) {
            // Status hasn't changed, but refresh the notification periodically
            // so the displayed timestamp stays current — AND re-post if the
            // system silently dropped our notification (Samsung throttling / frozen
            // process), otherwise the visible icon can stay stale forever even
            // though our cached status is correct.
            //
            // BUT: a missing notification isn't always an accidental drop. If we're
            // outside the geofence and the garage isn't confirmed open, GeofenceBroadcastReceiver
            // / refreshNotificationBasedOnGeofence deliberately DISMISSED it. Some
            // unrelated poll (periodic worker, leftover FG-service loop, PulseWorker
            // escalation) landing right after that dismiss — reporting this SAME
            // unchanged status — must NOT resurrect it; that was observed as the
            // notification dismissing in Android Auto and immediately reappearing.
            val notificationMissing = !NotificationUtility.isGarageNotificationActive(context)
            val shouldBeVisible = isInsideGeofence(context) || shouldKeepNotificationOnGeofenceExit(garageStatus)
            if (notificationMissing && !shouldBeVisible) {
                Log.d(TAG, "updateGarageAppAreas: notification correctly absent (outside geofence, status=$garageStatus), not resyncing")
            } else if (!notificationMissing && !shouldBeVisible) {
                // Self-heal: the notification is VISIBLE but shouldn't be — e.g. a
                // geofence-EXIT dismiss attempt was silently swallowed while
                // GarageAutoForegroundService still owned the shared notification id
                // (Android ignores NotificationManager.cancel() on a notification bound
                // to an active foreground service), or some other race left it up.
                // Without this, the branch below would just keep periodically
                // refreshing a notification that should have been dismissed, and it
                // would never actually go away.
                Log.i(TAG, "updateGarageAppAreas: notification incorrectly visible outside geofence (status=$garageStatus), dismissing to self-heal")
                NotificationUtility.dismissNotification(context.applicationContext)
            } else if (notificationMissing || now - lastNotificationRefreshTime > NOTIFICATION_REFRESH_INTERVAL) {
                if (notificationMissing) {
                    Log.i(TAG, "updateGarageAppAreas: notification missing/dropped, re-posting $garageStatus to resync icon")
                }
                lastNotificationRefreshTime = now
                triggerNotification(context.applicationContext, UpdateType.GARAGESTATUS, garageStatus)
            } else {
                Log.d(TAG, "updateGarageAppAreas: Status $garageStatus unchanged, skipping relays")
            }
            return
        }

        lastNotificationRefreshTime = now
        // Persist the new status BEFORE triggering the notification so that any
        // concurrent reader (e.g. refreshNotificationBasedOnGeofence, periodic
        // workers) sees the latest value instead of the stale previous status.
        setLastGarageStatus(context, garageStatus)
        Log.i(TAG, "updateGarageAppAreas: status changed $lastStatus -> $garageStatus, alertMainActivity, alertAutoActivity")
        alertMainActivity(
            context.applicationContext,
            UpdateType.GARAGESTATUS,
            garageStatus.toString()
        )
        alertAutoActivity(context.applicationContext, garageStatus)

        // The phone notification itself MUST respect geofence-exit rules here too,
        // exactly like the "unchanged" branch above and GeofenceBroadcastReceiver's
        // EXIT handling — otherwise a transition that completes shortly AFTER you've
        // already left home (e.g. GeofenceBroadcastReceiver kept the notification up
        // during EXIT because the door was still CLOSING, then a moment later the
        // poll reports CLOSING -> CLOSED while you're now outside the geofence) would
        // unconditionally re-post/resurrect the notification. Nothing else proactively
        // re-dismisses an already-visible notification afterward (the EXIT check only
        // runs once, at the moment of the transition), so it would then never get
        // dismissed for the rest of the drive — exactly the "notification never
        // dismissed" symptom this guards against.
        val outsideGeofence = !isInsideGeofence(context)
        if (outsideGeofence && !shouldKeepNotificationOnGeofenceExit(garageStatus)) {
            Log.i(TAG, "updateGarageAppAreas: status changed to $garageStatus outside geofence and not confirmed open/moving — dismissing instead of posting")
            NotificationUtility.dismissNotification(context.applicationContext)
        } else {
            triggerNotification(context.applicationContext, UpdateType.GARAGESTATUS, garageStatus)
        }
    }

    /** Guards [startCarConnectMonitoring] against near-simultaneous duplicate triggers. */
    private var lastMonitoringTriggerTime = 0L
    private const val MONITORING_TRIGGER_DEBOUNCE_MS = 5_000L

    /**
     * How long to wait before verifying that a "successful" foreground-service start
     * actually resulted in polling. Must be comfortably longer than the ~5s window
     * Android gives a service to call startForeground() after onCreate().
     */
    private const val FG_START_WATCHDOG_DELAY_MS = 8_000L

    /**
     * Single, coordinated entry point for "the car just connected / we're in car mode,
     * make sure garage polling is running" triggers.
     *
     * Several INDEPENDENT listeners can fire for what is effectively the SAME real-world
     * event: SGAutoApp's in-process CarConnection LiveData observer, the manifest-registered
     * CarConnectionReceiver (CAR_CONNECTION_UPDATED broadcast), the ENTER_CAR_MODE broadcast
     * relayed via AutoUpdateRequestReceiver, a geofence enter/dwell transition, and a
     * cold-start car-mode check on process restart. If each of these independently decided
     * "try the foreground service, else fall back to the background thread", one of them
     * could succeed in starting GarageAutoForegroundService while another — evaluating at a
     * slightly different moment, or gated differently — falls back to the background
     * thread instead. That leaves TWO pollers running concurrently: duplicate/spammy API
     * calls, and a resetClient() race that can spuriously flip status to UNKNOWN.
     *
     * This function is [Synchronized] and debounces calls within
     * [MONITORING_TRIGGER_DEBOUNCE_MS]: only the first trigger in a short window actually
     * makes the FG-vs-background decision. Later triggers in that window are treated as
     * duplicate notifications of the same event and are skipped — whichever poller the
     * first trigger started is left alone.
     */
    @Synchronized
    fun startCarConnectMonitoring(context: Context, source: String) {
        val now = System.currentTimeMillis()
        if (now - lastMonitoringTriggerTime < MONITORING_TRIGGER_DEBOUNCE_MS) {
            Log.i(TAG, "startCarConnectMonitoring: [$source] within ${MONITORING_TRIGGER_DEBOUNCE_MS}ms of a prior trigger, skipping (already handled)")
            return
        }

        // Getting in the car (CarConnectionReceiver / ENTER_CAR_MODE) is a very common
        // way to reach this function, but it fires no matter WHERE the car is. If we're
        // outside the geofence and the garage is confirmed CLOSED (not OPEN/OPENING/
        // CLOSING — see shouldKeepNotificationOnGeofenceExit), there's normally nothing
        // useful to monitor.
        //
        // BUT: isInsideGeofence()/getLastGarageStatus() are both CACHED values, and they
        // are most likely to be stale in exactly the scenario that matters most — the
        // door was just opened (physically, via a remote, or by an app action the
        // poller hasn't reconciled yet) moments before driving off, and/or Android's
        // geofence ENTER/DWELL detection simply hasn't caught up yet. Trusting a stale
        // "outside + closed" read here silently skipped ALL monitoring for the rest of
        // the drive, leaving the Android Auto notification frozen on a stale status —
        // exactly the "didn't show the garage as open when I got in the car" symptom.
        // So: never skip on cached data alone. Fire a single fresh status probe first
        // (mirrors GeofenceBroadcastReceiver.handleExitTransition's fresh-probe pattern)
        // and only actually skip once THAT confirms outside+closed.
        val lastStatus = getLastGarageStatus(context)
        if (!isInsideGeofence(context) && !shouldKeepNotificationOnGeofenceExit(lastStatus)) {
            Log.i(TAG, "startCarConnectMonitoring: [$source] cached state looks like outside geofence + closed ($lastStatus) — running a fresh probe before deciding to skip")
            lastMonitoringTriggerTime = now
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val result = try {
                    makeStatusRequest(appContext, ignoreTimers = true, "$source-carConnectProbe")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "startCarConnectMonitoring: [$source] fresh probe failed: ${e.message}")
                    StatusFetchResult.Failed()
                }
                // Re-evaluate with whatever the probe just confirmed (updateGarageAppAreas,
                // invoked from the probe's response handling, updates getLastGarageStatus
                // and isInsideGeofence's backing preference if a geofence event raced in).
                val freshStatus = getLastGarageStatus(appContext)
                if (result !is StatusFetchResult.Fresh || result.status != GarageStatus.CLOSED ||
                    isInsideGeofence(appContext) || shouldKeepNotificationOnGeofenceExit(freshStatus)) {
                    Log.i(TAG, "startCarConnectMonitoring: [$source] fresh probe shows monitoring IS needed (status=$freshStatus) — starting")
                    startMonitoringNow(appContext, source)
                } else {
                    Log.i(TAG, "startCarConnectMonitoring: [$source] fresh probe confirms outside geofence and closed ($freshStatus) — nothing to monitor")
                }
            }
            return
        }
        lastMonitoringTriggerTime = now
        startMonitoringNow(context, source)
    }

    /**
     * Actually starts monitoring (foreground service, with background-poller fallback).
     * Split out of [startCarConnectMonitoring] so the fresh-probe path can invoke it
     * asynchronously after confirming monitoring is actually needed.
     */
    private fun startMonitoringNow(context: Context, source: String) {
        // Always force=true here: every caller of this function represents a "we are
        // definitely connected to the car, start monitoring" event. The
        // use_foreground_service_auto preference only matters for pure monitor-only
        // starts elsewhere; it must NOT gate this path, otherwise whichever caller
        // happens to run first would decide differently than a second, simultaneous
        // caller — reintroducing the exact race this function exists to prevent.
        val fgStarted = try {
            GarageAutoForegroundService.startIfNeeded(context, force = true)
        } catch (e: Exception) {
            Log.w(TAG, "startCarConnectMonitoring: [$source] FG service start failed: ${e.message}")
            false
        }

        if (fgStarted) {
            Log.i(TAG, "startCarConnectMonitoring: [$source] foreground service handling poll")

            // Defensive watchdog: startForegroundService() can report "success" here
            // (no synchronous exception) yet the service never actually promotes itself —
            // e.g. an async ForegroundServiceStartNotAllowedException delivered later via
            // Parcel.readException() (see SGAutoApp's crash guard), or the OS silently
            // deferring the start under Doze/App-Standby with no exception at all. Either
            // way, if foregroundServicePolling is still false after a grace period, the FG
            // service silently failed to take over — fall back so this "car connected"
            // event doesn't end up being a silent no-op for the rest of the drive (which
            // otherwise leaves the notification frozen on a stale cached status).
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.Default).launch {
                delay(FG_START_WATCHDOG_DELAY_MS.milliseconds)
                if (!foregroundServicePolling.get()) {
                    Log.w(TAG, "startCarConnectMonitoring: [$source] FG service watchdog: still not polling ${FG_START_WATCHDOG_DELAY_MS}ms after start, falling back to background poll")
                    ongoingGarageStatusThread(appContext, "$source-fgWatchdogFallback")
                }
            }
        } else {
            Log.i(TAG, "startCarConnectMonitoring: [$source] FG service not started, falling back to background poll + worker")
            ongoingGarageStatusThread(context, source)
            GarageStatusWorker.runNow(context, source)
        }
    }

    fun ongoingGarageStatusThread(context: Context, caller: String, delay:Long = 0) {
        Log.i(TAG, "ongoingGarageStatusThread: called from $caller")
        if (!validGarageTokens(context)) return
        if (gGarageThreadRunnable != null) {
            gGarageThreadRunnable!!.interrupt()
        }
        // Quit the old handler thread to free its Looper and native thread
        try { gGarageThread.quitSafely() } catch (_: Exception) {}
        gGarageThread = GaragePollingHandlerThread()
        if (!gGarageThread.isAlive) {
            gGarageThread.start()
        }

        gGarageThreadRunnable = GarageMonitorControllableRunnable(context, "caller $caller", delay)
        gGarageThread.getHandler().post(gGarageThreadRunnable!!)
    }

    /**
     * Stops the ambient background poller (if one is running) WITHOUT starting a
     * replacement — unlike [ongoingGarageStatusThread], which always spins a fresh one
     * up.
     *
     * Needed because [GarageAutoForegroundService] only checks "is a command/monitor
     * request already being handled" before taking over, but never checked whether an
     * OLDER [GarageMonitorControllableRunnable] (e.g. started earlier for plain "in car,
     * watch for changes" ambient monitoring) was still alive. That left two independent
     * pollers hitting the API concurrently — both competing for the same shared
     * request-budget tokens (see [msUntilNextRequestSlot]) — which could delay or starve
     * the FG service's OWN confirmation poll right when a user-initiated Open/Close
     * command needed a fast, uncontested response, making the notification/icon look
     * "stuck" until the FG service's poll finally got a free slot.
     */
    fun stopBackgroundPolling(caller: String) {
        if (gGarageThreadRunnable != null) {
            Log.i(TAG, "stopBackgroundPolling: interrupting existing ambient background poller (from $caller)")
            gGarageThreadRunnable!!.interrupt()
            gGarageThreadRunnable = null
        }
    }

    suspend fun monitorGarageStatus(
        context: Context,
        initialCall: Boolean,
        monitorTimeRemaining: Long,
        ignoreTimers: Boolean,
        caller: String,
        callOnce: Boolean
    ): StatusFetchResult {
        var localMonitorTimeRemaining = monitorTimeRemaining

        Log.i(TAG, "monitorGarageStatus: from $caller initialCall $initialCall monitorTimeRemaining, $monitorTimeRemaining ignoreTimers, $ignoreTimers callOnce $callOnce")

        // NOTE: we intentionally do NOT gate polling here based on cached geofence/
        // garage-status state. A previous version skipped polling outright when the
        // cached last-known status looked like "outside geofence + closed" — but that
        // cache can be stale (e.g. the door was opened moments ago and no poll has run
        // yet), and skipping the poll meant we could NEVER learn the real status, so a
        // wrongly-stale cache became permanently stuck. Callers that want to avoid
        // starting monitoring in the first place based on that logic (e.g.
        // startCarConnectMonitoring) do a single fresh probe BEFORE deciding whether to
        // keep monitoring — this function's job is just to actually fetch the truth.
        // updateGarageAppAreas still respects geofence rules for whether to actually
        // show/dismiss the notification once a real status comes back.
        if (callOnce) {
            return makeStatusRequest(context, ignoreTimers, caller)
        }

        if (initialCall) {
            garagePollingTime = NORMAL_GARAGE_POLLING_PING_PERIOD
            gSlowPollingTimeRemaining = MAX_GARAGE_POLLING_TIME
        }

        val delayTime = GARAGE_ACTION_POLLING_DELAY - (System.currentTimeMillis() - garageActionTimeStamp) + 50
        if (garageActionTimeStamp == 0L) {
            garageActionTimeStamp =
                System.currentTimeMillis() - GARAGE_ACTION_POLLING_DELAY - 500
            Log.d(TAG, "monitorGarageStatus: _garageActionTimeStamp was 0, set to $garageActionTimeStamp")
        } else if (System.currentTimeMillis() - garageActionTimeStamp < GARAGE_ACTION_POLLING_DELAY) {
            sleep(delayTime, "monitorGarageStatus within _garageActionPollingDelay")
            return monitorGarageStatus(context, false, MAX_GARAGE_POLLING_TIME, ignoreTimers, "MG $caller", false)
        }
        
        // Continuous (non-callOnce) polling proceeds unconditionally here — see the note
        // near the top of this function for why we don't gate on cached geofence/status.
        Log.i(TAG, "monitorGarageStatus: $initialCall $localMonitorTimeRemaining ignoreTimers $ignoreTimers")

        localMonitorTimeRemaining -= garagePollingTime 

        if (!getAccessKey(context)) {
            Log.w(TAG, "monitorGarageStatus: keys not yet set, returning")
            return StatusFetchResult.Failed()
        }

        val newMonitorTimeRemaining = localMonitorTimeRemaining - garagePollingTime
        Log.i(TAG, "monitorGarageStatus: newMonitorTimeRemaining $newMonitorTimeRemaining")

        if (newMonitorTimeRemaining > 0) {
            gSlowPolling = false
            garagePollingTime = NORMAL_GARAGE_POLLING_PING_PERIOD
        }
        else  
        {
            gSlowPolling = true
            garagePollingTime = MEDIUM_GARAGE_POLLING_PING_PERIOD
            gSlowPollingTimeRemaining -= MEDIUM_GARAGE_POLLING_PING_PERIOD
            if (gSlowPollingTimeRemaining <= 0) {
                gSlowPolling = false
                garagePollingTime = NORMAL_GARAGE_POLLING_PING_PERIOD
                gSlowPollingTimeRemaining = MAX_SLOW_POLLING_TIME_WINDOW
                return StatusFetchResult.Skipped(StatusFetchResult.Reason.INTERVAL)
            }
        }

        if (getLastGarageStatus(context) == GarageStatus.OPEN || getLastGarageStatus(context) == GarageStatus.CLOSED) {
            gSlowPollingTimeRemaining = MAX_SLOW_POLLING_TIME_WINDOW
        }

        if ((getLastGarageStatus(context) == GarageStatus.CLOSING || getLastGarageStatus(context) == GarageStatus.OPENING || getLastGarageStatus(context) == GarageStatus.UNKNOWN) && System.currentTimeMillis() - garageActionTimeStamp < GARAGE_ACTION_POLLING_DELAY) {
            sleep(GARAGE_ACTION_POLLING_DELAY - (System.currentTimeMillis() - garageActionTimeStamp) + 500, "MGS just opened or closed")
            return monitorGarageStatus(context, false, newMonitorTimeRemaining, ignoreTimers, "garage activity delayed call", false)
        }

        resumeFlag = false

        if (System.currentTimeMillis() - gLastGarageStatusCheckTime < Constants.GARAGE_POLLING_MINIMUM_RECHECK_TIME) {
            return StatusFetchResult.Skipped(StatusFetchResult.Reason.INTERVAL)
        }

        return makeStatusRequest(context, ignoreTimers, caller)
    }

    suspend fun makeStatusRequest(
        context: Context,
        ignoreTimers: Boolean,
        caller: String
    ): StatusFetchResult {
        resetDnsRetryCountIfNeeded()

        // If SmartThings recently rate-limited us (HTTP 429), don't make another
        // request until the backoff window elapses — every request sent during a 429
        // backoff just re-triggers the same rate limit and prolongs the outage. This
        // is checked even when ignoreTimers=true (foreground service) since the FG
        // service polling loop is exactly the caller that can burn through the quota
        // fastest (~1 request/second).
        if (isRateLimited()) {
            Log.w(TAG, "makeStatusRequest: rate-limited by SmartThings, skipping (caller=$caller, ${rateLimitRemainingMs()}ms remaining)")
            return StatusFetchResult.Skipped(StatusFetchResult.Reason.RATE_LIMITED)
        }

        // Self-throttle BEFORE we ever get a 429: a single poller's fast-phase ticks alone
        // (~1.1s apart) can exceed the server's ~10/min quota. Skipping here just means this
        // tick is a no-op; the poller's own loop will try again on its next scheduled tick,
        // so status still converges once the budget frees up — without provoking an actual
        // 429 and its much longer server-imposed backoff.
        // Only send debug ping AFTER status request completes to avoid concurrent connections
        // that may overwhelm the local server
        Log.i(TAG, "makeStatusRequest: caller $caller")

        if (!validGarageTokens(context)) return StatusFetchResult.Failed()
        val currentTime = System.currentTimeMillis()

        if (garageActionTimeStamp == 0L) {
            garageActionTimeStamp = currentTime - (GARAGE_ACTION_POLLING_DELAY + 500)
        }
        if (!ignoreTimers && currentTime - garageActionTimeStamp < GARAGE_ACTION_POLLING_DELAY) {
            Log.w(TAG, "makeStatusRequest: called within _garageActionPollingDelay, returning")
            return StatusFetchResult.Skipped(StatusFetchResult.Reason.ACTION_DELAY)
        }
        if (!ignoreTimers && currentTime - garageCheckTimeStamp + 50 < NORMAL_GARAGE_POLLING_PING_PERIOD) {
            Log.w(TAG, "makeStatusRequest: called too fast, returning.")
            return StatusFetchResult.Skipped(StatusFetchResult.Reason.INTERVAL)
        }

        try {
            val garageAccessKey = getPreference("garage_device_id", String::class.java, context) as String

            val debugMode = getPreference("debug_switch", Boolean::class.java, context) as? Boolean == true
            val baseUrl = if (debugMode) (getPreference("debug_server", String::class.java, context) as? String) ?: "https://api.smartthings.com" else "https://api.smartthings.com"

            val api = SmartThingsService.getInstance(context)
            Log.d(TAG, "makeStatusRequest: hitting $baseUrl")
            if (!tryReserveRequestSlot()) return StatusFetchResult.Skipped(StatusFetchResult.Reason.BUDGET)
            val response = api.getGarageStatus(garageAccessKey)

            when {
                response.isSuccessful && response.body() != null -> {
                    dnsRetryCount = 0
                    // Don't clear recentBgFailureTimes here — let old entries age out
                    // of the sliding window naturally. A single success between throttled
                    // calls shouldn't mask the overall degraded connectivity.
                    val result = handleGarageStatusResponse(context, response.body()!!)
                    // Send debug ping AFTER status request completes (sequential, not parallel)
                    if (result is StatusFetchResult.Fresh) pingDebugServer(context, "makeStatusRequest from $caller completed successfully")
                    return result
                }

                else -> {
                    Log.e(TAG, "makeStatusRequest: unsuccessful response from $baseUrl: ${response.code()}")
                    logApiError(context, response, "makeStatusRequest")
                    return StatusFetchResult.Failed(response.code())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val debugMode = getPreference("debug_switch", Boolean::class.java, context) as? Boolean == true
            val baseUrl = if (debugMode) (getPreference("debug_server", String::class.java, context) as? String) ?: "https://api.smartthings.com" else "https://api.smartthings.com"

            if (e is java.net.UnknownHostException) {
                Log.e(TAG, "makeStatusRequest: UnknownHostException for $baseUrl")
                accessError(context, "makeStatusRequest", baseUrl, e)
            } else if (e is java.net.SocketTimeoutException) {
                Log.e(TAG, "makeStatusRequest: TIMEOUT connecting to $baseUrl. Check your network or local server.")
                accessError(context, "makeStatusRequest", baseUrl, e)
            } else {
                Log.e(TAG, "Error in makeStatusRequest hitting $baseUrl", e)
                pingDebugServer(context, "makeStatusRequest exception error: ${e.localizedMessage}")
                accessError(context, "makeStatusRequest", baseUrl, e)
            }
        }
        return StatusFetchResult.Failed()
    }

    private fun handleGarageStatusResponse(
        context: Context,
        statusResponse: GarageStatusResponse
    ): StatusFetchResult {
        val status = statusResponse.components.main.doorControl.door.value
        val currentStatus = GarageStatus.entries.firstOrNull { it.name.equals(status, ignoreCase = true) }
            ?.takeIf { it != GarageStatus.UNKNOWN } ?: return StatusFetchResult.Failed()
        Log.i(TAG, "handleGarageStatusResponse: Status: $status")

        val lastStatus = getLastGarageStatus(context)
        fun publish(): StatusFetchResult.Fresh {
            val now = System.currentTimeMillis()
            garageCheckTimeStamp = now
            gLastGarageStatusCheckTime = now
            gLastSuccessfulStatusTime = now
            consecutiveNetworkErrors = 0
            updateGarageAppAreas(context, currentStatus)
            return StatusFetchResult.Fresh(currentStatus, now)
        }

        when (status.lowercase()) {
            "opening" -> {
                if (lastStatus != GarageStatus.OPENING) {
                    gStartFastLoop = true
                }
                return publish()
            }
            "closing" -> {
                if (lastStatus != GarageStatus.CLOSING) {
                    gStartFastLoop = true
                }
                return publish()
            }
        }

        if (System.currentTimeMillis() - garageActionTimeStamp < GARAGE_ACTION_POLLING_DELAY) {
            // We're within the action polling delay window - garage command was just sent
            // Keep the transitional state (OPENING/CLOSING) that was set by the command handler
            // and continue fast polling until we see the actual transitional state from the API
            val lastStatus = getLastGarageStatus(context)
            if (lastStatus == GarageStatus.OPENING || lastStatus == GarageStatus.CLOSING) {
                Log.i(TAG, "handleGarageStatusResponse: Within action delay window, keeping transitional state: $lastStatus (API returned: $status)")
                gStartFastLoop = true
                return StatusFetchResult.Skipped(StatusFetchResult.Reason.ACTION_WINDOW_SUPPRESSED)
            }
        }

        return publish()
    }

    fun getLastGarageStatus(context: Context): GarageStatus {
        val lastGarageStatusString = getPreference("lastGarageStatus", String::class.java, context) as String?
        return lastGarageStatusString?.let {
            try {
                GarageStatus.valueOf(it.uppercase())
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "Invalid garage status stored: $it")
                GarageStatus.UNKNOWN
            }
        } ?: GarageStatus.UNKNOWN
    }

    fun setLastGarageStatus(context: Context, status: GarageStatus) {
        PreferenceUtility.setPreference("lastGarageStatus", status.toString(), context)
    }

    /**
     * Whether the garage-status notification should be kept visible after leaving the
     * geofence, instead of being dismissed.
     *
     * Product spec: while outside the geofence, keep the notification (updated live)
     * as long as the door is OPEN, OPENING, or CLOSING — i.e. not fully closed. Once
     * it's confirmed CLOSED, dismiss it. UNKNOWN deliberately does NOT count as
     * "keep": it just means the last poll failed to confirm a status, not that the
     * door is open, so nagging the user with a stale/unverified notification after
     * they've already left is just noise (and was being confused for "the
     * notification didn't dismiss").
     *
     * Used consistently by GeofenceBroadcastReceiver (the real GPS geofence exit
     * event), MainActivity's foreground distance-based exit check,
     * NotificationUtility.refreshNotificationBasedOnGeofence (the periodic worker),
     * and updateGarageAppAreas (both the "status changed" and "status unchanged"
     * paths) so all of them agree instead of one silently overriding another's
     * decision.
     */
    fun shouldKeepNotificationOnGeofenceExit(status: GarageStatus): Boolean =
        status == GarageStatus.OPEN || status == GarageStatus.OPENING || status == GarageStatus.CLOSING

    /**
     * True if the last known geofence state is "inside" (ENTERED or DWELLING).
     * Shared by [updateGarageAppAreas]'s missing-notification resync check and
     * [NotificationUtility.refreshNotificationBasedOnGeofence] so both agree on
     * what "should the notification currently be visible" means.
     */
    fun isInsideGeofence(context: Context): Boolean {
        val geofenceMessage = getPreference("geofenceMessage", String::class.java, context) as String?
        return !geofenceMessage.isNullOrEmpty() &&
            (geofenceMessage.lowercase().startsWith("dwelling") || geofenceMessage.lowercase().startsWith("entered"))
    }

    enum class GarageStatus {
        OPEN,
        CLOSED,
        OPENING,
        CLOSING,
        UNKNOWN;
    }

    enum class GarageCommand {
        OPEN, CLOSE
    }

}