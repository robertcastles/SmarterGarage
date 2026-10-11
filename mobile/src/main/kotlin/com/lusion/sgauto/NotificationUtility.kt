package com.lusion.sgauto

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lusion.sgauto.PersistentLog as Log
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationManagerCompat
import com.lusion.sgauto.Constants.MAX_MEDIUM_POLLING_TIME_WINDOW
import com.lusion.sgauto.GarageUtility.GarageStatus
import com.lusion.sgauto.GarageUtility.gLastGarageStatusCheckTime
import com.lusion.sgauto.GarageUtility.getLastGarageStatus
import com.lusion.sgauto.GarageUtility.ongoingGarageStatusThread
import com.lusion.sgauto.LusionUtility.UpdateType
import com.lusion.sgauto.LusionUtility.getBitmapFromVectorDrawable
import com.lusion.sgauto.PreferenceUtility.getPreference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object NotificationUtility {
    private const val TAG = "NotificationUtility"
//    private var gNotificationLastGarageStatus: GarageStatus? = GarageStatus.UNKNOWN
    private const val GARAGE_CLOSE = "GARAGE_CLOSE"
    private const val GARAGE_OPEN = "GARAGE_OPEN"
    const val GEOFENCES_ADDED = "GEOFENCES_ADDED"
    const val GEOFENCES_FAILED = "GEOFENCES_FAILED"
    const val LOCATION_PERMISSIONS_ISSUE = "LOCATION_PERMISSIONS_ISSUE"
//    private var gLastUnknownGarageTime: Long = 0

    /** Hard floor: no two notification posts closer than this, regardless of update type */
    private const val NOTIFICATION_MIN_INTERVAL_MS = 2_000L
    @Volatile private var lastNotifyPostTimeMs = 0L
    /** Advances only for a real Android Auto notification post, never for an FGS build. */
    private val carRefreshSequence = AtomicInteger()
    /** Last status posted while an AA host was connected; repeated polls must not bounce its card. */
    @Volatile private var lastCarPostedStatus: GarageStatus? = null
    private var pendingCarStatus: GarageStatus? = null

    /**
     * Android Auto (current versions) does NOT re-render a notification that is updated
     * in place — it only draws the state present when the card is freshly ADDED. To change
     * the visible state we must cancel (remove) then re-notify (add). This is done on a
     * short gap so the host reliably observes the removal before the re-add. Because the
     * Auto carrier has its own identity, this never removes the phone controls or
     * either foreground monitor notification.
     */
    private const val AA_RECREATE_GAP_MS = 300L
    private val carPostScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    /** Guards the delayed re-post so only the most recent state is drawn. */
    private val carPostToken = AtomicInteger()

    /** Min spacing between arrival heads-ups so a double ENTER / re-entry doesn't spam. */
    private const val ARRIVAL_HEADS_UP_MIN_INTERVAL_MS = 60_000L
    /** Arrival heads-up auto-dismisses after this so it doesn't linger as a duplicate card. */
    private const val ARRIVAL_HEADS_UP_TIMEOUT_MS = 12_000L
    /** Only pop the arrival heads-up when this close to home (hard-coded for now). */
    private const val ARRIVAL_HEADS_UP_RADIUS_M = 10f
    /** After Android Auto attaches, wait this long before checking whether to pop the heads-up. */
    private const val CAR_START_ARRIVAL_HEADS_UP_DELAY_MS = 10_000L
    @Volatile private var lastArrivalHeadsUpTime = 0L
    private val arrivalLease = AutoArrivalLease()
    private var arrivalStatus: GarageStatus? = null

    @SuppressLint("MissingPermission")
    fun triggerNotification(
        context: Context,
        updateType: UpdateType,
        garageStatus: GarageStatus?
    ) {

        val localGarageStatus = if (updateType == UpdateType.GEOFENCE) getLastGarageStatus(context)
            else garageStatus ?: getLastGarageStatus(context)
        // if we are getting repeated calls to trigger the notification with the same info, just return
        if (localGarageStatus == GarageStatus.UNKNOWN && System.currentTimeMillis() - gLastGarageStatusCheckTime > MAX_MEDIUM_POLLING_TIME_WINDOW) ongoingGarageStatusThread(
            context,
            "triggerNotification got UNKNOWN"
        )
        val now = System.currentTimeMillis()

        // Is our garage notification actually still visible? If the system silently
        // dropped it (Samsung throttling / frozen process), the cached "last status"
        // can disagree with what the user sees — so we must always re-post to resync.
        val notificationVisible = isGarageNotificationActive(context)
        val carNeedsRecovery = LusionUtility.isInCarSession && !isAutoNotificationActive(context)

        // ---- Hard global rate-limit (prevents Samsung-visible notification spam) ----
        // GARAGESTATUS updates carry fresh data from the API and must not be blocked
        // by a preceding GEOFENCE notification burst — they get a much shorter floor.
        // The floor is only enforced while the notification is actually visible; if it
        // was dropped we bypass the floor to guarantee the user sees the current state.
        val effectiveFloor = if (updateType == UpdateType.GARAGESTATUS) 300L else NOTIFICATION_MIN_INTERVAL_MS
        if (notificationVisible && !carNeedsRecovery &&
            localGarageStatus == getLastNotificationStatus(context) &&
            now - lastNotifyPostTimeMs < effectiveFloor) {
            Log.d(TAG, "triggerNotification: global rate-limit, skipping ($updateType)")
            return
        }

        val lastStatusTime = (getPreference("lastNotificationStatusTime", Long::class.java, context) ?: 0L) as Long
        val timeDiff = now - lastStatusTime

        // OPENING/CLOSING are short-lived, actively-watched states — the user wants to
        // see fresh phone timestamps on transition polls. Auto independently recreates
        // its carrier when the status changes or a lifecycle refresh is requested.
        // Batch notification updates for STABLE states (OPEN/CLOSED/UNKNOWN): use a
        // longer skip time in car session to reduce Android Auto throttling.
        val isTransitional = localGarageStatus == GarageStatus.OPENING || localGarageStatus == GarageStatus.CLOSING
        val skipThreshold = when {
            isTransitional -> 0L
            LusionUtility.isInCarSession -> 30000L
            else -> 15000L
        }
        val isStatusUnchanged = localGarageStatus == getLastNotificationStatus(context)

        // Only skip the repeat post when the status is unchanged AND the notification
        // is still actually on screen. A dropped notification must always be re-posted.
        if (isStatusUnchanged && timeDiff < skipThreshold && notificationVisible && !carNeedsRecovery) {
            Log.i(TAG, "triggerNotification: repeat, skipping (type=$updateType, car=${LusionUtility.isInCarSession}, threshold=${skipThreshold}ms)")
            return
        }
        Log.i(TAG, "triggerNotification: $updateType $localGarageStatus (car=${LusionUtility.isInCarSession})")

        // Update phone controls in place; independently recreate only the Auto carrier.
        postGarageNotificationToCar(context, updateType, localGarageStatus)
        lastNotifyPostTimeMs = System.currentTimeMillis()
        setLastNotificationStatus(context, localGarageStatus)
    }

    /** Stable phone garage-controls identity; never canceled merely to refresh Auto. */
    fun garageNotificationId(context: Context): Int =
        context.resources.getInteger(R.integer.garage_notification_id)

    private fun autoNotificationId(context: Context): Int =
        context.resources.getInteger(R.integer.garage_auto_notification_id)

    /**
     * Builds the phone garage controls without posting or foreground ownership.
     */
    @SuppressLint("MissingPermission")
    fun buildGarageNotification(
        context: Context,
        updateType: UpdateType,
        garageStatus: GarageStatus?
    ): android.app.Notification = buildGarageNotificationBuilder(context, updateType, garageStatus).build()

    /** Builds phone controls, an Auto carrier, or an independent arrival heads-up. */
    @SuppressLint("MissingPermission")
    fun buildGarageNotificationBuilder(
        context: Context,
        updateType: UpdateType,
        garageStatus: GarageStatus?,
        carNotificationTitle: String = garageStatus?.name ?: "Smarter Garage",
        headsUp: Boolean = false,
        autoCarrier: Boolean = false
    ): androidx.core.app.NotificationCompat.Builder {
        var localGarageStatus = garageStatus

// https://stackoverflow.com/questions/20888013/go-back-to-previous-screen-on-backbutton-pressed-after-responding-to-notificatio
// https://stackoverflow.com/questions/19039189/intent-if-activity-is-running-bring-it-to-front-else-start-a-new-one-from-n

// Magic, the real solution is to leave the intent alone like below, but in manifest, set the activity to 'singleTask' This fixes everything. Animation is smooth from notification. back button works.
        val intent = Intent(context, MainActivity::class.java)
        val pendingNotificationIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_MUTABLE
        )

//        PendingIntent pendingAutoNotificationIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        // Build up action button intent for garage (phone notification — SOURCE=PHONE)
        val garageCloseIntent = Intent(context, DataRequestReceiver::class.java).apply {
            action = GARAGE_CLOSE
            putExtra("ACTION", "CLOSE")
            putExtra("SOURCE", "PHONE")
        }
        val garageClosePendingIntent =
            PendingIntent.getBroadcast(context, 0, garageCloseIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Build up action button intent for garage (phone notification — SOURCE=PHONE)
        val garageOpenIntent = Intent(context, DataRequestReceiver::class.java).apply {
            action = GARAGE_OPEN
            putExtra("ACTION", "OPEN")
            putExtra("SOURCE", "PHONE")
        }
        val garageOpenPendingIntent =
            PendingIntent.getBroadcast(context, 1, garageOpenIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Android Auto notification actions route through DataRequestReceiver which
        // handles the foreground service start with a proper fallback chain:
        // 1. Try startForegroundService() (works when BroadcastReceiver has PendingIntent exemption)
        // 2. Fall back to direct command send + WorkManager monitoring
        // This is more reliable than PendingIntent.getForegroundService() which can
        // silently fail on Android 14+ and get swallowed by the crash guard.
        val autoGarageCloseIntent = Intent(context, DataRequestReceiver::class.java).apply {
            action = "GARAGE_CLOSE"
            putExtra("ACTION", "CLOSE")
            putExtra("SOURCE", "AUTO")
        }
        val autoGarageClosePendingIntent = PendingIntent.getBroadcast(
            context, 101, autoGarageCloseIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val autoGarageOpenIntent = Intent(context, DataRequestReceiver::class.java).apply {
            action = "GARAGE_OPEN"
            putExtra("ACTION", "OPEN")
            putExtra("SOURCE", "AUTO")
        }
        val autoGarageOpenPendingIntent = PendingIntent.getBroadcast(
            context, 102, autoGarageOpenIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        var largeIcon =
            getBitmapFromVectorDrawable(context, R.drawable.smarter_garage_svg)
        val notificationImageID = R.drawable.smarter_garage_svg

        // Update the notification icon if needed
        if (updateType == UpdateType.GEOFENCE) localGarageStatus = getLastGarageStatus(context)
        if (localGarageStatus != null) {
            // use the last main activity _lastGarageStatus if we are entering the geofence, otherwise, use the new status from 'GARAGESTATUS'
            when (localGarageStatus) {
                GarageStatus.OPEN -> largeIcon = getBitmapFromVectorDrawable(
                    context,
                    R.drawable.garage_door_open_red_vector
                )
                GarageStatus.OPENING -> largeIcon = getBitmapFromVectorDrawable(
                    context,
                    R.drawable.garage_door_opening
                )
                GarageStatus.CLOSING -> largeIcon = getBitmapFromVectorDrawable(
                    context,
                    R.drawable.garage_door_closing
                )
                GarageStatus.UNKNOWN -> largeIcon = getBitmapFromVectorDrawable(
                    context,
                    R.drawable.garage_door_unknown
                )
                else -> {}
            }
        }

        // Get an 'importance' from the preference which matches NotificationManagerCompat values by name
        var importance =
            listOf(*context.resources.getStringArray(R.array.priority_values))
                .indexOf(getPreference("auto_notification_priority", String::class.java, context) as String?)
        if (importance < 0) {
            importance = NotificationManager.IMPORTANCE_DEFAULT
        }
        // Arrival heads-up must be high-importance to "peek" on the Android Auto screen,
        // regardless of the user's chosen priority for the persistent status card.
        if (headsUp) importance = NotificationManager.IMPORTANCE_HIGH
        val builder = NotificationBuilder.getNotificationBuilder(
            context,
            pendingNotificationIntent,
            notificationImageID,
            largeIcon,
            garageClosePendingIntent,
            garageOpenPendingIntent,
            autoGarageClosePendingIntent,
            autoGarageOpenPendingIntent,
            localGarageStatus,
            importance,
            carNotificationTitle,
            headsUp = headsUp,
            headsUpChannelId = if (headsUp) context.getString(R.string.garage_arrival_channel_id) else null,
            autoCarrier = autoCarrier
        )
        return builder
    }

    /**
     * Posts a one-shot, high-importance "arrival" heads-up that peeks on the Android Auto
     * screen (and phone) — surfacing the OPEN/CLOSE controls without needing to open the app.
     * It uses a separate id + high-importance channel so it does not disturb the persistent
     * quiet status card, and auto-dismisses after [ARRIVAL_HEADS_UP_TIMEOUT_MS].
     *
     * Fires only while connected to Android Auto, when the notification should be visible per
     * the geofence rule, no more than once per [ARRIVAL_HEADS_UP_MIN_INTERVAL_MS], and only
     * when actually within [ARRIVAL_HEADS_UP_RADIUS_M] of home (checked async against the
     * device's location, independent of the larger geofence radius).
     */
    @SuppressLint("MissingPermission")
    fun postArrivalHeadsUp(context: Context, status: GarageStatus) {
        if (!LusionUtility.isInCarSession) {
            Log.i(TAG, "postArrivalHeadsUp: not in a car session, skipping")
            return
        }
        val shouldBeVisible = GarageUtility.isInsideGeofence(context) ||
            GarageUtility.shouldKeepNotificationOnGeofenceExit(status)
        if (!shouldBeVisible) {
            Log.i(TAG, "postArrivalHeadsUp: intentionally hidden per geofence rule (status=$status)")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastArrivalHeadsUpTime < ARRIVAL_HEADS_UP_MIN_INTERVAL_MS) {
            Log.i(TAG, "postArrivalHeadsUp: fired ${now - lastArrivalHeadsUpTime}ms ago, skipping")
            return
        }

        val generation = synchronized(this) { arrivalLease.generation }
        carPostScope.launch {
            val distance = GeofenceUtility.distanceToHomeMeters(context)
            if (distance == null) {
                Log.i(TAG, "postArrivalHeadsUp: no home distance available, skipping")
                return@launch
            }
            if (distance > ARRIVAL_HEADS_UP_RADIUS_M) {
                Log.i(TAG, "postArrivalHeadsUp: ${distance.toInt()}m from home > ${ARRIVAL_HEADS_UP_RADIUS_M.toInt()}m, skipping")
                return@launch
            }
            // Only claim the interval slot once we're actually posting, so a far-away
            // ENTER doesn't block the later in-radius (car-start) attempt.
            synchronized(this@NotificationUtility) {
                val latest = getLastGarageStatus(context)
                val admittedAt = System.currentTimeMillis()
                if (!LusionUtility.isInCarSession ||
                    (!GarageUtility.isInsideGeofence(context) && !GarageUtility.shouldKeepNotificationOnGeofenceExit(latest)) ||
                    admittedAt - lastArrivalHeadsUpTime < ARRIVAL_HEADS_UP_MIN_INTERVAL_MS) return@synchronized
                val lease = arrivalLease.admit(generation) ?: return@synchronized
                val arrivalId = context.resources.getInteger(R.integer.garage_arrival_notification_id)
                val manager = CarNotificationManager.from(context)
                try {
                    carPostToken.incrementAndGet()
                    pendingCarStatus = null
                    lastCarPostedStatus = null
                    manager.cancel(autoNotificationId(context))
                    val builder = buildGarageNotificationBuilder(context, UpdateType.GARAGESTATUS, latest,
                        headsUp = true, carNotificationTitle = "Smarter Garage")
                        .setTimeoutAfter(ARRIVAL_HEADS_UP_TIMEOUT_MS)
                    manager.notify(arrivalId, builder)
                    arrivalStatus = latest
                    lastArrivalHeadsUpTime = admittedAt
                    Log.i(TAG, "postArrivalHeadsUp: posting exclusive arrival heads-up status=$latest")
                } catch (error: Exception) {
                    arrivalLease.release(lease)
                    arrivalStatus = null
                    manager.cancel(arrivalId)
                    refreshForCarSession(context)
                    Log.w(TAG, "Could not post arrival heads-up", error)
                    return@synchronized
                }
                carPostScope.launch {
                    delay(ARRIVAL_HEADS_UP_TIMEOUT_MS)
                    synchronized(this@NotificationUtility) {
                        if (!arrivalLease.release(lease)) return@synchronized
                        arrivalStatus = null
                        manager.cancel(arrivalId)
                        if (LusionUtility.isInCarSession) refreshForCarSession(context)
                    }
                }
            }
        }
    }

    /**
     * Called when Android Auto attaches: after a short delay, checks whether to pop the
     * arrival heads-up (covers "started the car while already parked within the radius" —
     * there is no geofence ENTER transition in that case).
     */
    fun scheduleArrivalHeadsUpAfterCarStart(context: Context) {
        val generation = synchronized(this) { arrivalLease.generation }
        carPostScope.launch {
            delay(CAR_START_ARRIVAL_HEADS_UP_DELAY_MS)
            Log.i(TAG, "scheduleArrivalHeadsUpAfterCarStart: delay elapsed, evaluating arrival heads-up")
            synchronized(this@NotificationUtility) {
                if (generation == arrivalLease.generation && LusionUtility.isInCarSession) {
                    postArrivalHeadsUp(context, getLastGarageStatus(context))
                }
            }
        }
    }

    /**
     * Re-posts the current status when an Android Auto host attaches. The phone notification
     * may already be visible and therefore be skipped by its normal duplicate/rate-limit logic,
     * but a newly attached car host still needs its separate CarAppExtender carrier.
     */
    @SuppressLint("MissingPermission")
    fun refreshForCarSession(context: Context) {
        val status = getLastGarageStatus(context)
        val shouldBeVisible = GarageUtility.isInsideGeofence(context) ||
            GarageUtility.shouldKeepNotificationOnGeofenceExit(status)
        if (!shouldBeVisible) {
            Log.i(TAG, "refreshForCarSession: notification is intentionally hidden (status=$status)")
            dismissNotification(context)
            return
        }

        Log.i(TAG, "refreshForCarSession: posting current status=$status for Android Auto")
        postGarageNotificationToCar(context, UpdateType.GARAGESTATUS, status, forceLifecycleRefresh = true)
        lastNotifyPostTimeMs = System.currentTimeMillis()
        setLastNotificationStatus(context, status)
    }

    /**
     * Updates the phone STATUS card (id=1) in place for a known status, honoring the
     * geofence visibility rule. Used by the foreground service to eagerly reflect an accepted
     * OPEN/CLOSE (moving state) and to confirm the final state — the service no longer owns
     * this notification id, so it just asks NotificationUtility to (re)post it like any other
     * status update. Only the separate Auto carrier uses cancel/recreate.
     */
    @SuppressLint("MissingPermission")
    fun postGarageStatusCard(context: Context, status: GarageStatus) {
        val shouldBeVisible = GarageUtility.isInsideGeofence(context) ||
            GarageUtility.shouldKeepNotificationOnGeofenceExit(status)
        if (!shouldBeVisible) {
            Log.i(TAG, "postGarageStatusCard: notification is intentionally hidden (status=$status)")
            dismissNotification(context)
            return
        }
        Log.i(TAG, "postGarageStatusCard: posting status=$status")
        postGarageNotificationToCar(context, UpdateType.GARAGESTATUS, status, forceLifecycleRefresh = true)
        lastNotifyPostTimeMs = System.currentTimeMillis()
        setLastNotificationStatus(context, status)
    }

    /**
     * Phone controls always update in place. Auto has an independent identity so its
     * cancel/recreate workaround cannot remove the phone controls or affect grouping.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun postGarageNotificationToCar(
        context: Context,
        updateType: UpdateType,
        garageStatus: GarageStatus?,
        forceLifecycleRefresh: Boolean = false
    ): android.app.Notification? {
        val appContext = context.applicationContext
        val status = if (updateType == UpdateType.GEOFENCE) getLastGarageStatus(appContext)
            else garageStatus ?: getLastGarageStatus(appContext)
        if (!GarageUtility.isInsideGeofence(appContext) &&
            !GarageUtility.shouldKeepNotificationOnGeofenceExit(status)) {
            dismissNotification(appContext)
            return null
        }

        val phoneNotification = buildGarageNotificationBuilder(appContext, updateType, status).build()
        synchronized(GarageNotificationGroup) {
            NotificationManagerCompat.from(appContext).notify(garageNotificationId(appContext), phoneNotification)
            GarageNotificationGroup.statusPosted(appContext, phoneNotification)
        }
        lastNotifyPostTimeMs = System.currentTimeMillis()
        setLastNotificationStatus(appContext, status)

        if (!LusionUtility.isInCarSession) {
            clearCarSessionNotification(appContext)
            return phoneNotification
        }
        if (arrivalLease.active) {
            // End the one-shot peek on a status change; restore a fresh persistent card.
            // Repeated identical polls must not recreate a second Auto surface.
            if (arrivalStatus == status) return phoneNotification
            arrivalLease.invalidate()
            arrivalStatus = null
            NotificationManagerCompat.from(appContext).cancel(appContext.resources.getInteger(R.integer.garage_arrival_notification_id))
        }
        // Do not let Auto deduplication suppress phone timestamp/action updates. Also
        // avoid restarting the gap for repeated polls of a state already pending.
        if (pendingCarStatus == status || (!forceLifecycleRefresh && pendingCarStatus == null &&
                lastCarPostedStatus == status && isAutoNotificationActive(appContext))) {
            return phoneNotification
        }
        val sequence = carRefreshSequence.getAndIncrement()
        val carTitle = if (sequence and 1 == 0) "Smarter Garage" else "Smarter Garage "
        val autoBuilder = buildGarageNotificationBuilder(appContext, updateType, status,
            carNotificationTitle = carTitle, autoCarrier = true)
        val manager = CarNotificationManager.from(appContext)
        val myToken = carPostToken.incrementAndGet()
        pendingCarStatus = status
        manager.cancel(autoNotificationId(appContext))
        Log.i(TAG, "Auto carrier cancel+recreate: status=$status, token=$myToken")
        carPostScope.launch {
            delay(AA_RECREATE_GAP_MS)
            synchronized(this@NotificationUtility) {
                if (carPostToken.get() != myToken) return@synchronized
                pendingCarStatus = null
                if (!LusionUtility.isInCarSession ||
                    (!GarageUtility.isInsideGeofence(appContext) &&
                        !GarageUtility.shouldKeepNotificationOnGeofenceExit(status))) {
                    clearCarSessionNotification(appContext)
                    return@synchronized
                }
                manager.notify(autoNotificationId(appContext), autoBuilder)
                lastCarPostedStatus = status
                Log.i(TAG, "Auto carrier re-posted: status=$status, token=$myToken")
            }
        }
        return phoneNotification
    }

    /** Disconnect cleanup must never remove phone controls or foreground monitors. */
    @Synchronized
    fun clearCarSessionNotification(context: Context) {
        arrivalLease.invalidate()
        arrivalStatus = null
        NotificationManagerCompat.from(context).cancel(context.resources.getInteger(R.integer.garage_arrival_notification_id))
        carPostToken.incrementAndGet()
        pendingCarStatus = null
        lastCarPostedStatus = null
        NotificationManagerCompat.from(context).cancel(autoNotificationId(context))
    }

    @Synchronized
    fun dismissNotification(context: Context) {
        Log.e(TAG, "dismissNotification")
        clearCarSessionNotification(context)
        synchronized(GarageNotificationGroup) {
            NotificationManagerCompat.from(context).cancel(garageNotificationId(context))
            GarageNotificationGroup.dismissSummary(context)
        }
    }

    /**
     * Returns true if our garage status notification is currently posted/visible.
     * Used to detect when the system silently dropped our notification (Samsung
     * throttling / frozen process) so callers can force a re-post to resync the
     * visible icon with the real garage status instead of trusting stale cached
     * dedup state. Fails "open" (returns true) on any error to preserve the
     * existing rate-limiting behavior.
     */
    fun isGarageNotificationActive(context: Context): Boolean {
        return isNotificationActive(context, garageNotificationId(context))
    }

    private fun isAutoNotificationActive(context: Context): Boolean =
        isNotificationActive(context, autoNotificationId(context))

    private fun isNotificationActive(context: Context, id: Int): Boolean {
        return try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.activeNotifications.any { it.id == id }
        } catch (e: Exception) {
            Log.w(TAG, "isGarageNotificationActive check failed: ${e.message}")
            true
        }
    }

    /**
     * Refreshes the notification based on geofence rules:
     * - If inside geofence: show the notification with current garage status
     * - If outside geofence: keep only confirmed OPEN/OPENING/CLOSING; dismiss CLOSED
     *   and UNKNOWN. This MUST match GeofenceBroadcastReceiver.handleTransition()'s EXIT
     *   logic exactly: this function is called periodically (GarageStatusWorker's
     *   15-minute worker, GaragePulseWorker) independent of the actual EXIT event, so
     *   if the two disagreed, one would silently override the other's decision the
     *   next time either ran — e.g. the receiver deliberately keeps the notification
     *   visible for an OPEN garage, only for this function to force-dismiss it
     *   minutes later regardless of status.
     * This function always acts regardless of whether the status changed.
     */
    @SuppressLint("MissingPermission")
    fun refreshNotificationBasedOnGeofence(context: Context) {
        val geofenceMessage = getPreference("geofenceMessage", String::class.java, context) as String?
        val isInsideGeofence = !geofenceMessage.isNullOrEmpty() &&
            (geofenceMessage.lowercase().startsWith("dwelling") || geofenceMessage.lowercase().startsWith("entered"))

        Log.i(TAG, "refreshNotificationBasedOnGeofence: geofenceMessage=$geofenceMessage, isInside=$isInsideGeofence")

        if (isInsideGeofence) {
            // Inside geofence - show/update notification with current status
            val currentStatus = getLastGarageStatus(context)
            Log.i(TAG, "refreshNotificationBasedOnGeofence: Inside geofence, showing notification with status=$currentStatus")
            // Force the notification by bypassing the duplicate check - use GEOFENCE update type
            triggerNotification(context, UpdateType.GEOFENCE, currentStatus)
        } else {
            // Outside geofence - only dismiss if the garage is NOT confirmed open/moving.
            // See GarageUtility.shouldKeepNotificationOnGeofenceExit: UNKNOWN is treated
            // like CLOSED here, not like OPEN.
            val currentStatus = getLastGarageStatus(context)
            if (GarageUtility.shouldKeepNotificationOnGeofenceExit(currentStatus)) {
                Log.i(TAG, "refreshNotificationBasedOnGeofence: Outside geofence but garage is $currentStatus (confirmed open/moving), keeping notification visible")
                triggerNotification(context, UpdateType.GEOFENCE, currentStatus)
            } else {
                Log.i(TAG, "refreshNotificationBasedOnGeofence: Outside geofence and garage is $currentStatus, dismissing notification")
                dismissNotification(context)
            }
        }
    }

    fun alertAutoActivity(context: Context, garageStatus: GarageStatus?) {
        val dataResponseIntent = Intent(context, AutoUpdateRequestReceiver::class.java).apply {
            action = "com.lusion.sgauto.AutoUpdateRequestReceiver"
            putExtra("TYPE", UpdateType.GARAGESTATUS)
            putExtra(UpdateType.GARAGESTATUS.name, garageStatus)
        }
        Log.i(TAG, "alertAutoActivity: sending intent to AutoUpdateRequestReceiver ${UpdateType.GARAGESTATUS} $garageStatus")
        context.sendBroadcast(dataResponseIntent)
        val autoAppIntent = Intent("com.lusion.sgauto.SGScreen").apply {
            setPackage(context.packageName)
            putExtra("TYPE", UpdateType.GARAGESTATUS)
            putExtra(UpdateType.GARAGESTATUS.name, garageStatus)
        }
        context.sendBroadcast(autoAppIntent)

    }

    fun alertMainActivity(context: Context, updateType: UpdateType, payload: String?) {
        Log.i(TAG, "alertMainActivity: ${updateType.name}, $payload")
        val alertAppIntent = Intent(context.getString(R.string.local_main_activity_listener)).apply {
            setPackage(context.packageName)
            putExtra("Message", updateType.name)
            putExtra("Payload", payload)
        }
        context.sendBroadcast(alertAppIntent)
    }

    fun notifyMainActivity(context: Context, message: String?) {
        Log.d(TAG, "LU.notifyMainActivity $message")
        // This simplified wrapper should send messages to MainActivity uiMessageBroadcastReceiver
        val intent = Intent(context.getString(R.string.local_main_activity_listener)).apply {
            setPackage(context.packageName)
            putExtra("Message", message)
        }
        notifyMainActivity(context, intent)
    }

    private fun notifyMainActivity(context: Context, intent: Intent?) {
        Log.i(TAG, "notifyMainActivity")
        // put whatever data you want to send, if any
        // intent.putExtra("message", message);

        // Looks like we should get the garage status and trigger the notification as needed
        //getGarageStatus(context);
        // send broadcast
        context.sendBroadcast(intent?.setPackage(context.packageName))
    }

//    fun dismissNotification(context: Context, notificationId: Int) {
//        NotificationManagerCompat.from(context).cancel(notificationId)
//    }

    fun getLastNotificationStatus(context: Context): GarageStatus {
        val lastGarageStatusString = getPreference("lastNotificationStatus", String::class.java, context) as String?
        return lastGarageStatusString?.let {
            try {
                GarageStatus.valueOf(it.uppercase())
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "Invalid garage status stored: $it")
                GarageStatus.UNKNOWN
            }
        } ?: GarageStatus.UNKNOWN
    }

    fun setLastNotificationStatus(context: Context, status: GarageStatus) {
        val lastStatusTime = (getPreference("lastNotificationStatusTime", Long::class.java, context) ?: 0L) as Long
        val now = System.currentTimeMillis()
        val timeDiff = now - lastStatusTime
        if (getLastNotificationStatus(context) == status && timeDiff < 15000) {
            return
        }
        PreferenceUtility.setPreference("lastNotificationStatus", status.toString(), context)
        PreferenceUtility.setPreference("lastNotificationStatusTime", System.currentTimeMillis(), context)
    }
}