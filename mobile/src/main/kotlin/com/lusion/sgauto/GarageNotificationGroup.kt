package com.lusion.sgauto

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lusion.sgauto.PersistentLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Phone grouping only. Never takes ownership of or cancels a foreground notification. */
object GarageNotificationGroup {
    const val GROUP_KEY = "com.lusion.sgauto.GARAGE_STATUS"
    private const val TAG = "GarageNotificationGroup"
    private const val RECONCILE_INTERVAL_MS = 2_000L
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchJob: Job? = null
    private val monitorOwners = mutableMapOf<Int, Any>()
    private var summarySource: Pair<Long, String>? = null
    private var latestStatus: Notification? = null
    private var statusHidden = false
    private var statusRefreshing = false
    private const val CAR_EXTENSION_KEY = "androidx.car.app.EXTENSIONS"

    @Synchronized
    fun monitorStarted(context: Context, notificationId: Int, owner: Any) {
        monitorOwners[notificationId] = owner
        reconcile(context)
    }

    @Synchronized
    fun monitorStopped(context: Context, notificationId: Int, owner: Any) {
        // An old REPLACE worker must not remove its replacement's summary.
        if (monitorOwners[notificationId] !== owner) return
        monitorOwners.remove(notificationId)
        // WorkManager removes its notification after doWork returns. Keep watching the
        // physical notification until its owner actually removes it.
        reconcile(context)
    }

    @Synchronized
    fun statusPosted(context: Context, notification: Notification) {
        latestStatus = notification
        statusHidden = false
        statusRefreshing = false
        reconcile(context)
    }

    @Synchronized
    fun statusRefreshPending() {
        statusRefreshing = true
    }

    @Synchronized
    fun dismissSummary(context: Context) {
        latestStatus = null
        statusHidden = true
        statusRefreshing = false
        reconcile(context)
    }

    /** Recover from process restart, monitor teardown, user dismissal, and deferred FGS posts. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun reconcile(context: Context) {
        if (statusRefreshing) return
        val appContext = context.applicationContext
        try {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            val active = manager.activeNotifications
            val statusId = NotificationUtility.garageNotificationId(appContext)
            val serviceId = appContext.resources.getInteger(R.integer.garage_fg_service_notification_id)
            val pulseId = appContext.resources.getInteger(R.integer.garage_pulse_notification_id)
            val summaryId = appContext.resources.getInteger(R.integer.garage_status_summary_notification_id)
            val children = active.filter { it.id == statusId || it.id == serviceId || it.id == pulseId }
            val status = children.firstOrNull { it.id == statusId && !statusHidden }
            val hasMonitor = children.any { it.id == serviceId || it.id == pulseId }
            // Without a visible status card, there must be no summary reviving a card
            // deliberately hidden by the geofence rule. A single card needs no summary.
            val needsSummary = status != null && hasMonitor
            val summary = active.firstOrNull { it.id == summaryId }
            if (needsSummary) {
                // Prefer the latest posted payload while Android's active snapshot catches up.
                val statusNotification = latestStatus ?: status.notification
                children.filter { it.notification.group != GROUP_KEY &&
                    (it.id == statusId || monitorOwners.containsKey(it.id)) }.forEach {
                    val original = if (it.id == statusId) statusNotification else it.notification
                    val child = NotificationCompat.Builder(appContext, original)
                        .setGroup(GROUP_KEY)
                        .setSortKey(if (it.id == statusId) "00_garage" else "99_monitoring")
                        .setSilent(true)
                        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                        .build()
                    manager.notify(it.id, child)
                }
                val source = statusNotification.`when` to statusNotification.channelId
                if (source != summarySource || summary == null) {
                    // Copy the displayed status, timestamp, artwork, phone actions, and
                    // channel exactly. Do not export a second garage card to Android Auto.
                    val builder = NotificationCompat.Builder(appContext, statusNotification)
                        .setGroup(GROUP_KEY)
                        .setGroupSummary(true)
                        .setSilent(true)
                        .setOnlyAlertOnce(true)
                        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    builder.extras.remove(CAR_EXTENSION_KEY)
                    NotificationManagerCompat.from(appContext).notify(summaryId, builder.build())
                    summarySource = source
                }
            } else if (summary != null || summarySource != null) {
                val groupedChildren = children.filter { it.notification.group == GROUP_KEY }
                if (groupedChildren.isNotEmpty()) {
                    // Detach first, then wait for a fresh system snapshot before cancelling
                    // the summary, since summary cancellation can also remove children.
                    groupedChildren.filter {
                        if (it.id == statusId) !statusHidden else monitorOwners.containsKey(it.id)
                    }.forEach {
                        val original = if (it.id == statusId) latestStatus ?: it.notification else it.notification
                        manager.notify(it.id, NotificationCompat.Builder(appContext, original)
                            .setGroup(null).setSilent(true).build())
                    }
                } else {
                    NotificationManagerCompat.from(appContext).cancel(summaryId)
                    summarySource = null
                }
            }

            // Posting/removing notifications is asynchronous. Check only while monitoring
            // is live; this also catches shade dismissal without a permanent polling job.
            val needsWatch = monitorOwners.isNotEmpty() || hasMonitor || summary != null || summarySource != null
            if (needsWatch && watchJob == null) {
                watchJob = scope.launch {
                    while (true) {
                        delay(RECONCILE_INTERVAL_MS)
                        reconcile(appContext)
                    }
                }
            } else if (!needsWatch) {
                watchJob?.cancel()
                watchJob = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not reconcile garage notification group: ${e.message}")
        }
    }
}



