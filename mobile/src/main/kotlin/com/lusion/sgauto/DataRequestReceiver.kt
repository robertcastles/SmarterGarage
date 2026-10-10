package com.lusion.sgauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lusion.sgauto.PersistentLog as Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.lusion.sgauto.GarageUtility.GarageCommand
import com.lusion.sgauto.GarageUtility.garageActionTimeStamp
import com.lusion.sgauto.GarageUtility.ongoingGarageStatusThread
import com.lusion.sgauto.GarageUtility.sendGarageCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// This receiver in the main application is intended to receive requests from the auto app for data and send information back through a response intent.
class DataRequestReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onReceive(context: Context, intent: Intent) {
        // This method is called when the BroadcastReceiver is receiving
        // an Intent broadcast. like locally?
        try {
            val action = intent.getStringExtra(EXTRA_ACTION)
            Log.i(TAG, "onReceive - ACTION: $action")

            when (action) {
                ACTION_GET_GARAGE_STATUS -> handleGetGarageStatus(context)
                ACTION_OPEN, ACTION_CLOSE -> handleGarageCommand(context, action, intent.getStringExtra(EXTRA_SOURCE))
                else -> Log.w(TAG, "Unknown action received: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing intent", e)
        }

    }

    private fun handleGetGarageStatus(context: Context) {
        if (System.currentTimeMillis() - garageActionTimeStamp < Constants.GARAGE_ACTION_POLLING_DELAY) {
            val garageDelay =
                Constants.GARAGE_ACTION_POLLING_DELAY - (System.currentTimeMillis() - garageActionTimeStamp) + 500
            if (garageDelay > 0) {
                Log.i(
                    TAG,
                    "Ignoring GETGARAGESTATUS as garage command was just triggered. Sleep ${garageDelay}ms"
                )
                scope.launch {
                    LusionUtility.sleep(
                        garageDelay,
                        "DataRequestReceiver within _garageActionPollingDelay"
                    )
                    ongoingGarageStatusThread(context, TAG)
                }
            }
        }
    }

    private fun handleGarageCommand(context: Context, action: String?, source: String?) {
        val command = if (action == ACTION_OPEN) GarageCommand.OPEN else GarageCommand.CLOSE
        Log.i(TAG, "handleGarageCommand: action=$action, source=$source, command=$command")

        // Debounce: reject duplicate commands within COMMAND_DEBOUNCE_MS.
        // Android Auto can fire the PendingIntent more than once for a single
        // button press, especially when the notification is rebuilt frequently.
        val now = System.currentTimeMillis()
        if (action == lastCommandAction && now - lastCommandTime < COMMAND_DEBOUNCE_MS) {
            Log.w(TAG, "handleGarageCommand: DUPLICATE command $action ignored (${now - lastCommandTime}ms since last)")
            return
        }
        lastCommandAction = action
        lastCommandTime = now

        if (source == SOURCE_AUTO) {
            Log.i(TAG, "Auto source: command $command, appInForeground=${LusionUtility.isAppInForeground()}")

            // Try to start the foreground service — it owns both the command send AND
            // the elevated-priority polling loop. This is a single path: one command,
            // one monitoring thread.
            val fgStarted = try {
                GarageAutoForegroundService.startWithCommand(context, command)
            } catch (e: Exception) {
                Log.w(TAG, "Foreground service start failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }

            if (!fgStarted) {
                // FG service failed to start synchronously — send the command directly
                // and use WorkManager as a MONITOR-only fallback (NOT a resend — the
                // command was already sent on the line above; enqueueing the raw OPEN/CLOSE
                // action here would fire the SAME command at SmartThings a second time,
                // burning the request budget and risking the door being commanded twice,
                // which is exactly what was making the notification appear "stuck": the
                // duplicate command plus its own follow-up polls could exhaust the request
                // budget/trip a 429 right as the real confirmation poll needed to run).
                Log.i(TAG, "Foreground service not started, falling back to direct send + WorkManager monitor")
                sendGarageCommand(context, command)
                enqueueGarageMonitorWorkRequest(context)
            }
        } else {
            sendGarageCommand(context, command)
        }
    }

    private fun enqueueGarageMonitorWorkRequest(context: Context) {
        val garageWorkRequest: WorkRequest = OneTimeWorkRequest.Builder(GarageCommandWorker::class.java)
            .setInputData(Data.Builder().putString(EXTRA_ACTION, ACTION_MONITOR).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueue(garageWorkRequest)
    }

    companion object {
        private const val TAG = "DataRequestReceiver"
        private const val EXTRA_ACTION = "ACTION"
        private const val EXTRA_SOURCE = "SOURCE"
        private const val ACTION_GET_GARAGE_STATUS = "GETGARAGESTATUS"
        private const val ACTION_OPEN = "OPEN"
        private const val ACTION_CLOSE = "CLOSE"
        private const val ACTION_MONITOR = "MONITOR"
        private const val SOURCE_AUTO = "AUTO"

        /** Debounce window for duplicate command suppression */
        private const val COMMAND_DEBOUNCE_MS = 5_000L
        @Volatile private var lastCommandAction: String? = null
        @Volatile private var lastCommandTime = 0L
    }
}