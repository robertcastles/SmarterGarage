package com.lusion.sgauto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import com.lusion.sgauto.PersistentLog as Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lusion.sgauto.GarageUtility.GarageStatus
import com.lusion.sgauto.NotificationUtility.refreshNotificationBasedOnGeofence
import kotlin.time.Duration.Companion.milliseconds

/**
 * A lightweight expedited worker that promotes itself to foreground priority using
 * WorkManager's setForeground() API.
 *
 * This is used as an automatic escalation when background polling detects consecutive
 * network failures caused by Android throttling. Unlike startForegroundService(),
 * WorkManager's setForeground() does NOT require a foreground service start exemption,
 * so it works reliably from background contexts on Android 14+.
 *
 * Makes a few poll attempts with elevated priority, updates the notification, then exits.
 */
class GaragePulseWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationId: Int
        get() = applicationContext.resources.getInteger(R.integer.garage_pulse_notification_id)

    override suspend fun doWork(): Result = try {
        runPulse()
    } finally {
        GarageNotificationGroup.monitorStopped(applicationContext, notificationId, this)
    }

    private suspend fun runPulse(): Result {
        Log.i(TAG, "GaragePulseWorker starting (attempt ${runAttemptCount + 1})")

        if (!PreferenceUtility.validGarageTokens(applicationContext)) {
            Log.w(TAG, "No valid tokens, skipping pulse")
            return Result.failure()
        }

        // Promote to foreground priority — this gives us elevated network access
        try {
            setForeground(createForegroundInfo())
            GarageNotificationGroup.monitorStarted(applicationContext, notificationId, this)
            SmartThingsService.resetClient() // Fresh sockets for foreground priority
            Log.i(TAG, "Promoted to foreground priority")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote to foreground: ${e.message}")
            // Continue anyway — the poll attempt might still work
        }

        // Make up to 3 poll attempts
        var success = false
        for (attempt in 1..MAX_ATTEMPTS) {
            Log.d(TAG, "Poll attempt $attempt/$MAX_ATTEMPTS")
            GarageUtility.makeStatusRequest(
                applicationContext,
                ignoreTimers = true,
                caller = "PulseWorker-$attempt"
            )

            val status = GarageUtility.getLastGarageStatus(applicationContext)
            if (status != GarageStatus.UNKNOWN) {
                Log.i(TAG, "Got valid status: $status on attempt $attempt")
                success = true
                break
            }

            if (attempt < MAX_ATTEMPTS) {
                Log.d(TAG, "Status UNKNOWN, retrying in ${RETRY_DELAY_MS}ms...")
                kotlinx.coroutines.delay(RETRY_DELAY_MS.milliseconds)
            }
        }

        // Refresh notification regardless of success
        refreshNotificationBasedOnGeofence(applicationContext)

        if (success) {
            Log.i(TAG, "Pulse completed successfully")
            // Reset the background failure counter since we got a valid response
            GarageUtility.resetBgFailureCounter()
        } else {
            Log.w(TAG, "Pulse completed without successful status fetch")
        }

        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Smarter Garage")
            .setContentText("Checking garage status…")
            .setSmallIcon(R.drawable.smarter_garage_svg)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GarageNotificationGroup.GROUP_KEY)
            .setSortKey("99_monitoring")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Garage Status Check",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Brief elevated-priority garage status check"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "GaragePulseWorker"
        private const val CHANNEL_ID = "garage_pulse_check"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val UNIQUE_WORK_NAME = "GaragePulse"

        /**
         * Enqueue an expedited pulse worker. Safe to call from any context
         * (background threads, broadcast receivers, etc.).
         */
        fun enqueue(context: Context) {
            Log.i(TAG, "Enqueuing expedited pulse worker")
            val request = OneTimeWorkRequestBuilder<GaragePulseWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}

