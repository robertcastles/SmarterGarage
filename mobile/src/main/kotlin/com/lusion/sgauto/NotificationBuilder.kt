package com.lusion.sgauto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import com.lusion.sgauto.GarageUtility.GarageStatus
import com.lusion.sgauto.PreferenceUtility.getPreference
import java.time.format.DateTimeFormatter

object NotificationBuilder {
    private const val CATEGORY_EVENT = NotificationCompat.CATEGORY_EVENT
    private const val CONTENT_TEXT = "Smarter Garage"

    fun getNotificationBuilder(
        context: Context,
        pendingNotificationIntent: PendingIntent?,
        notificationImageID: Int?,
        largeIcon: Bitmap?,
        garageClosePendingIntent: PendingIntent?,
        garageOpenPendingIntent: PendingIntent?,
        autoGarageClosePendingIntent: PendingIntent?,
        autoGarageOpenPendingIntent: PendingIntent?,
        garageStatus: GarageStatus?,
        importance: Int,
        carNotificationTitle: String,
        headsUp: Boolean = false,
        headsUpChannelId: String? = null,
        autoCarrier: Boolean = false
    ): NotificationCompat.Builder {
        var icon1 = R.drawable.garage_door_closing_arrow
        var icon2 = R.drawable.garage_door_opening_arrow
        var title1 = "CLOSE"
        var title2 = "OPEN"
        var pendingIntent1 = garageClosePendingIntent
        var pendingIntent2 = garageOpenPendingIntent
        var autoPendingIntent1 = autoGarageClosePendingIntent
        var autoPendingIntent2 = autoGarageOpenPendingIntent
        if (garageStatus === GarageStatus.CLOSED) {
            icon1 = R.drawable.garage_door_opening_arrow
            icon2 = R.drawable.garage_door_closing_arrow
            title1 = "OPEN"
            title2 = "CLOSE"
            pendingIntent1 = garageOpenPendingIntent
            pendingIntent2 = garageClosePendingIntent
            autoPendingIntent1 = autoGarageOpenPendingIntent
            autoPendingIntent2 = autoGarageClosePendingIntent
        }

        //TODO - use collapsed view based on preference?
        val collapsedView = RemoteViews(context.packageName, R.layout.notification_collapsed)
        val notificationText = (getPreference("show_request_time", Boolean::class.java, context) as Boolean).let {
            if (it) {
                "${garageStatus!!.name} ${statusTimeString()}"
            } else {
                garageStatus!!.name
            }
        }
        //TODO - add a refresh to update garage status in expanded view
        //TODO - change time display as XX minutes ago, or XX seconds ago, etc. and "None" if more than 4 hrs ago
        collapsedView.setTextViewText(R.id.collapsed_notification_info, notificationText)

        val notificationPriority = getPreference("auto_notification_priority", context, "IMPORTANCE_DEFAULT")
        // convert string to priority value
        val priorityValue = when {
            headsUp -> NotificationCompat.PRIORITY_HIGH
            autoCarrier -> NotificationCompat.PRIORITY_LOW
            notificationPriority == "IMPORTANCE_MIN" -> NotificationCompat.PRIORITY_MIN
            notificationPriority == "IMPORTANCE_LOW" -> NotificationCompat.PRIORITY_LOW
            notificationPriority == "IMPORTANCE_DEFAULT" -> NotificationCompat.PRIORITY_DEFAULT
            notificationPriority == "IMPORTANCE_HIGH" -> NotificationCompat.PRIORITY_HIGH
            notificationPriority == "IMPORTANCE_MAX" -> NotificationCompat.PRIORITY_MAX
            else -> NotificationCompat.PRIORITY_DEFAULT // fallback to default if unknown
        }

        // Heads-up (arrival) posts use a dedicated high-importance channel so the host
        // "peeks" them; the normal status card keeps its user-selected priority channel.
        val channelId = if (autoCarrier) context.getString(R.string.garage_auto_channel_id)
            else headsUpChannelId ?: "${context.getString(R.string.garage_notification)}_$notificationPriority"
        if (autoCarrier) {
            val channel = NotificationChannel(channelId,
                context.getString(R.string.garage_auto_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.garage_auto_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        } else {
            createNotificationChannel(context, channelId, importance)
        }

        // set notification title to current time to force Android Auto to update icon on new notifications
        val builder = NotificationCompat.Builder(context, context.getString(R.string.garage_notification))
//        return NotificationCompat.Builder(context, nowString())
            .setContentTitle(garageStatus!!.name)
            .setContentText(CONTENT_TEXT)
//            .setContentText(nowString())
            .setSmallIcon(notificationImageID!!)
            .setCustomContentView(collapsedView)
            .setOngoing(!headsUp)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setLargeIcon(largeIcon) //                .setStyle(new NotificationCompat.BigTextStyle().bigText("Smarter Garage hopes you are having an excellent day"))
            .setContentIntent(pendingNotificationIntent)
            .setSilent(!headsUp)
            .setPriority(priorityValue)
            .setChannelId(channelId)
            .setAutoCancel(headsUp)
            .setOngoing(!headsUp)
            .setCategory(CATEGORY_EVENT)
            .setLocalOnly(!headsUp && !autoCarrier)
            .apply {
                if (!headsUp && !autoCarrier) {
                    setGroup(GarageNotificationGroup.GROUP_KEY)
                    setSortKey("00_garage")
                    setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    setOnlyAlertOnce(true)
                }
            }
            .addAction(icon1, title1, pendingIntent1)
            .addAction(
                icon2,
                title2,
                pendingIntent2
            ) //                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            //                .setCustomContentView(notificationLayout)
            //                .setCustomBigContentView(notificationLayoutExpanded)
        if (autoCarrier) {
            // This is the sole persistent status/control card during a car session.
            // Keep PHONE actions on the base notification and AUTO actions on the
            // extender. Leave it ungrouped so Auto recreation cannot remove monitors.
            builder.setOnlyAlertOnce(true)
        }
        if (headsUp || autoCarrier) {
            builder.extend(
                CarAppExtender.Builder()
                    .setContentTitle(carNotificationTitle)
//                    .setContentTitle(nowString())
                    // The car screen renders ONLY what's set on THIS extender — it does
                    // NOT inherit the base builder's contentText/largeIcon/RemoteViews.
                    // Without these two lines, Android Auto showed a static
                    // "Smarter Garage" title with no status text and no status icon on
                    // EVERY post, regardless of how many times notify() was called.
                    .setContentText(notificationText)
                    .apply { largeIcon?.let { setLargeIcon(it) } }
                    .setContentIntent(
//                        pendingIntent)
                        CarPendingIntent.getCarApp(
                            context.applicationContext, 0,
                            Intent(Intent.ACTION_VIEW).setComponent(
                                ComponentName(
                                    context.applicationContext,
                                    SGService::class.java
                                )
                            ), 0
                        )
                    )
                    .setChannelId(context.getString(R.string.garage_notification))
                    .addAction(icon1, title1, autoPendingIntent1!!)
                    .addAction(icon2, title2, autoPendingIntent2!!)
                    .setImportance(importance).build()
            )
        }
        return builder
    }

    private fun createNotificationChannel(context: Context, channelId: String, importance: Int) {
        val description = context.getString(R.string.garage_notification_details)

        val name = when (importance) {
            NotificationManager.IMPORTANCE_MIN -> "Minimal Priority"
            NotificationManager.IMPORTANCE_LOW -> "Low Priority"
            NotificationManager.IMPORTANCE_DEFAULT -> "Normal Priority"
            NotificationManager.IMPORTANCE_HIGH -> "High Priority"
            NotificationManager.IMPORTANCE_MAX -> "Maximum Priority"
            else -> "Normal Priority"
        }
        val channel = NotificationChannel(channelId, name, importance).apply {
            this.description = description
            setShowBadge(false)
        }.apply {
            setSound(null, null)
            setShowBadge(false)
            enableVibration(false)
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Formats the timestamp shown in the notification based on when the garage
     * status was actually last CONFIRMED from the API
     * ([GarageUtility.gLastSuccessfulStatusTime]) — NOT the time this notification
     * happened to be (re)built. This is the core fix for "notification shows a
     * recent timestamp but a stale status": a geofence/periodic rebuild used to
     * stamp the current time onto a status that may be minutes old.
     *
     * Shows just the confirmation clock time (e.g. "14:03:21").
     */
    private fun statusTimeString(): String {
        val confirmedAt = GarageUtility.gLastSuccessfulStatusTime
        if (confirmedAt == 0L) return "—"

        return java.time.Instant.ofEpochMilli(confirmedAt)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }
}