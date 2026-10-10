package com.lusion.sgauto

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.lusion.sgauto.GarageUtility.GarageCommand
import com.lusion.sgauto.GarageUtility.ongoingGarageStatusThread
import com.lusion.sgauto.GarageUtility.sendGarageCommand

class GarageCommandWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val action = inputData.getString(EXTRA_ACTION)
            if (action != null) {
                // If the foreground service is already handling the command + polling,
                // skip to avoid duplicate network calls
                if (GarageUtility.foregroundServicePolling.get()) {
                    Log.i(TAG, "doWork: $action — foreground service is active, skipping")
                    return Result.success()
                }
                Log.i(TAG, "doWork: $action")
                when (action) {
                    ACTION_OPEN -> sendGarageCommand(applicationContext, GarageCommand.OPEN)
                    ACTION_CLOSE -> sendGarageCommand(applicationContext, GarageCommand.CLOSE)
                    ACTION_MONITOR -> ongoingGarageStatusThread(applicationContext, TAG, 0)
                    else -> Log.w(TAG, "Unknown action: $action")
                }
            } else {
                Log.w(TAG, "Action was null")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing work", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "GarageCommandWorker"
        private const val EXTRA_ACTION = "ACTION"
        private const val ACTION_OPEN = "OPEN"
        private const val ACTION_CLOSE = "CLOSE"
        private const val ACTION_MONITOR = "MONITOR"
    }
}