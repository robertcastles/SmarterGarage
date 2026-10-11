package com.lusion.sgauto

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import com.lusion.sgauto.PersistentLog as Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lusion.sgauto.Constants.MAX_GARAGE_POLLING_TIME
import com.lusion.sgauto.GarageUtility.monitorGarageStatus
import com.lusion.sgauto.GarageUtility.GarageStatus
import com.lusion.sgauto.NotificationUtility.refreshNotificationBasedOnGeofence
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class GarageStatusWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        val source = inputData.getString(KEY_SOURCE)
            ?: PreferenceUtility.getPreference(PREF_LAST_SOURCE, applicationContext, "unknown")

        val isFastPoll = inputData.getBoolean(KEY_IS_FAST_POLL, false)
        val fastPollCount = inputData.getInt(KEY_FAST_POLL_COUNT, 0)
        val allowForegroundEscalation = inputData.getBoolean(KEY_ALLOW_FOREGROUND_ESCALATION, true)

        // Log the source of the work request
        Log.i(TAG, "doWork called from: $source (isFastPoll=$isFastPoll, fastPollCount=$fastPollCount)")

        // Sync car-mode flag — the process may have been restarted by WorkManager
        // while Android Auto was connected, and CAR_CONNECTION_UPDATED was never delivered.
        try {
            val uiModeManager = applicationContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val isCarMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR
            if (isCarMode && !LusionUtility.isInCarSession) {
                Log.i(TAG, "Worker detected car mode — setting isInCarSession=true")
                LusionUtility.isInCarSession = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check car mode: ${e.message}")
        }

        if (!PreferenceUtility.validGarageTokens(applicationContext)) {
            Log.w(TAG, "Skipping work: Missing or invalid garage tokens")
            return Result.failure()
        }

        // Check if the source is BootReceiver or AutoUpdateReqReceiver and set callOnce accordingly
        val callOnce = (source == "BootReceiver" || source == "AutoUpdateReqReceiver")

        var attempt = 1
        val maxAttempts = if (isFastPoll) 1 else 4 // Fast polls only try once

        while (attempt <= maxAttempts) {
            val result = monitorGarageStatus(
                applicationContext,
                true,
                MAX_GARAGE_POLLING_TIME,
                callOnce, // Use callOnce here to also ignore timers
                "ControllableRunnable",
                callOnce
            )

            val status = GarageUtility.getLastGarageStatus(applicationContext)
            Log.i(TAG, "Attempt $attempt status result: $status")


            // Check if we need to continue fast polling for transitional states
            if (isTransitionalState(status)) {
                if (fastPollCount < MAX_FAST_POLLS) {
                    scheduleFastPoll(source, fastPollCount + 1, allowForegroundEscalation)
                    Log.d(TAG, "Scheduled fast poll #${fastPollCount + 1} for transitional state: $status")
                } else {
                    Log.d(TAG, "Max fast polls ($MAX_FAST_POLLS) reached, transitional state may not resolve: $status")
                }
                break
            } else if (isFastPoll && result is StatusFetchResult.Fresh &&
                (result.status == GarageStatus.OPEN || result.status == GarageStatus.CLOSED)) {
                // Reached final state during fast polling, cancel any pending fast polls
                cancelFastPolls()
                Log.d(TAG, "Final state reached during fast poll: $status")
                break
            }

            if (result is StatusFetchResult.Fresh) {
                break
            }

            if (attempt < maxAttempts) {
                Log.i(TAG, "Status unknown/unreachable, retrying in 1 minute... (Attempt $attempt of ${maxAttempts - 1})")
                // Wait 1 minute before retrying
                delay(60000.milliseconds)
                attempt++
            } else {
                Log.w(TAG, "Max retries reached. Status remains unknown.")
                // Escalate to foreground service for one more attempt with elevated priority
                if (allowForegroundEscalation) {
                    escalateToForegroundService()
                } else {
                    Log.i(TAG, "Foreground escalation disabled for this work request")
                }
                break
            }
        }

        // Self-heal against a dropped OS geofence broadcast (e.g. Samsung battery
        // management silently killing the transition delivery) by cross-checking
        // actual last-known location vs the cached geofence state. Must run BEFORE
        // refreshNotificationBasedOnGeofence so a correction is reflected immediately
        // instead of on the NEXT 15-minute cycle.
        try {
            GeofenceUtility.reconcileGeofenceStateWithLocation(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "reconcileGeofenceStateWithLocation failed: ${e.message}")
        }

        // Refresh notification once after all attempts complete (not per-attempt)
        refreshNotificationBasedOnGeofence(applicationContext)

        PreferenceUtility.setPreference(
            TAG,
            System.currentTimeMillis().toString(),
            applicationContext
        )
        return Result.success()
    }

    private fun isTransitionalState(status: GarageStatus): Boolean {
        val transitional = status == GarageStatus.OPENING || status == GarageStatus.CLOSING
        Log.d(TAG, "isTransitionalState: status=$status, transitional=$transitional")
        return transitional
    }

    /**
     * When background polling exhausts all retries with UNKNOWN status (network errors),
     * try escalating to a foreground service for one more attempt with elevated process priority.
     * This helps overcome Android OS throttling of background network calls.
     *
     * Uses a cooldown to prevent repeated escalation during extended outages.
     */
    private fun escalateToForegroundService() {
        val lastEscalation = PreferenceUtility.getPreference(
            PREF_FG_ESCALATION_TIME, Long::class.java, applicationContext
        ) as? Long ?: 0L

        val cooldown = 15 * 60 * 1000L // 15 minutes
        if (System.currentTimeMillis() - lastEscalation < cooldown) {
            Log.d(TAG, "Foreground service escalation on cooldown, skipping")
            return
        }

        Log.i(TAG, "Escalating to foreground service after network failures")
        PreferenceUtility.setPreference(
            PREF_FG_ESCALATION_TIME,
            System.currentTimeMillis(),
            applicationContext
        )

        // Enqueue a PulseWorker which uses WorkManager's setForeground() to get elevated
        // network priority — works reliably from any context on Android 14+
        try {
            GaragePulseWorker.enqueue(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Could not escalate to foreground service: ${e.message}")
        }
    }

    private fun scheduleFastPoll(
        source: String,
        pollCount: Int,
        allowForegroundEscalation: Boolean
    ) {
        val fastPollRequest = OneTimeWorkRequestBuilder<GarageStatusWorker>()
            .setInitialDelay(FAST_POLL_DELAY_SECONDS, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    KEY_SOURCE to source,
                    KEY_IS_FAST_POLL to true,
                    KEY_FAST_POLL_COUNT to pollCount,
                    KEY_ALLOW_FOREGROUND_ESCALATION to allowForegroundEscalation
                )
            )
            .addTag(TAG_FAST_POLL)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                WORK_NAME_FAST_POLL,
                ExistingWorkPolicy.REPLACE,
                fastPollRequest
            )
        Log.d(TAG, "Enqueued fast poll request #$pollCount with ${FAST_POLL_DELAY_SECONDS}s delay")
    }

    private fun cancelFastPolls() {
        WorkManager.getInstance(applicationContext)
            .cancelAllWorkByTag(TAG_FAST_POLL)
        Log.d(TAG, "Cancelled all pending fast polls")
    }

    companion object {
        private const val KEY_SOURCE = "source"
        private const val KEY_IS_FAST_POLL = "is_fast_poll"
        private const val KEY_FAST_POLL_COUNT = "fast_poll_count"
        private const val KEY_ALLOW_FOREGROUND_ESCALATION = "allow_foreground_escalation"
        private const val TAG = "GarageStatusWorker"
        private const val PREF_LAST_SOURCE = "last_garage_worker_source"
        private const val TAG_FAST_POLL = "garage_fast_poll"
        private const val WORK_NAME_FAST_POLL = "GarageStatusFastPoll"
        private const val MAX_FAST_POLLS = 30 // Allow up to 30 fast polls, a minute
        private const val FAST_POLL_DELAY_SECONDS = 2L
        private const val PREF_FG_ESCALATION_TIME = "fg_service_escalation_time"

        /**
         * WorkManager cannot be used until the user has unlocked the device.
         *
         * Its auto-init ContentProvider (androidx.startup.InitializationProvider) is NOT
         * directBootAware, so during the Direct Boot window (after reboot, before first
         * unlock) it never runs — and WorkManager's database lives in credential-encrypted
         * storage that isn't accessible yet anyway. Our directBootAware BootReceiver
         * (LOCKED_BOOT_COMPLETED) can start the process in exactly that window, so any
         * WorkManager.getInstance() call there throws
         * "WorkManager is not initialized properly." Guard every access with this check.
         *
         * When locked, we skip: BOOT_COMPLETED (delivered after unlock) and normal app
         * launch will (re)schedule the work.
         */
        fun isWorkManagerAvailable(context: Context): Boolean {
            return try {
                val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
                val unlocked = userManager?.isUserUnlocked ?: true
                if (!unlocked) {
                    Log.w(TAG, "Skipping WorkManager access: user is locked (Direct Boot)")
                }
                unlocked
            } catch (e: Exception) {
                Log.w(TAG, "Could not determine user-unlock state: ${e.message}")
                true
            }
        }

        fun runNow(
            context: Context,
            source: String,
            allowForegroundEscalation: Boolean = true
        ) {
            if (!isWorkManagerAvailable(context)) return
            try {
                val workRequest = OneTimeWorkRequestBuilder<GarageStatusWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_SOURCE to source,
                            KEY_ALLOW_FOREGROUND_ESCALATION to allowForegroundEscalation
                        )
                    )
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        "GarageStatusNow",
                        if (allowForegroundEscalation) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
                        workRequest
                    )
                Log.d(TAG, "Enqueued OneTimeWorkRequest for GarageStatusWorker with source: $source")
            } catch (e: IllegalStateException) {
                // WorkManager not initialized (e.g. Direct Boot race). Non-fatal.
                Log.w(TAG, "runNow: WorkManager unavailable: ${e.message}")
            }
        }

        fun scheduleGarageStatusWork(
            context: Context,
            source: String,
            allowForegroundEscalation: Boolean = true
        ) {
            // Save source to preferences
            PreferenceUtility.setPreference(PREF_LAST_SOURCE, source, context)

            if (!isWorkManagerAvailable(context)) return
            try {
                val workRequest = PeriodicWorkRequestBuilder<GarageStatusWorker>(
                    15, TimeUnit.MINUTES
                ).setInputData(
                    workDataOf(
                        KEY_SOURCE to source,
                        KEY_ALLOW_FOREGROUND_ESCALATION to allowForegroundEscalation
                    )
                )
                    .build()

                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        "GarageStatus",
                        if (allowForegroundEscalation) {
                            ExistingPeriodicWorkPolicy.KEEP
                        } else {
                            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                        },
                        workRequest
                    )
                Log.d(TAG, "Scheduling GarageStatusWorker with source: $source")
                getNextScheduledRunTime(context)
            } catch (e: IllegalStateException) {
                // WorkManager not initialized (e.g. Direct Boot race). Non-fatal.
                Log.w(TAG, "scheduleGarageStatusWork: WorkManager unavailable: ${e.message}")
            }
        }

        fun getNextScheduledRunTime(context: Context): Long {
            if (!isWorkManagerAvailable(context)) return 0L
            val workManager = try {
                WorkManager.getInstance(context)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "getNextScheduledRunTime: WorkManager unavailable: ${e.message}")
                return 0L
            }
            val workInfoFuture = workManager.getWorkInfosForUniqueWork("GarageStatus")

            // Use addListener to avoid blocking the calling thread (often main thread at startup)
            workInfoFuture.addListener({
                try {
                    val workInfo = workInfoFuture.get().firstOrNull()
                    workInfo?.nextScheduleTimeMillis?.let { nextRun ->
                        val date = java.util.Date(nextRun)
                        val formatter = java.text.SimpleDateFormat("MM/dd/yyyy hh:mm:ss a", java.util.Locale.getDefault())
                        formatter.timeZone = java.util.TimeZone.getDefault()
                        Log.d(TAG, "Next scheduled run: ${formatter.format(date)}")
                    } ?: Log.w(TAG, "No next run time available")
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting next schedule time: ${e.message}")
                }
            }, { it.run() })

            return 0L // Actual value is logged asynchronously
        }
    }
}