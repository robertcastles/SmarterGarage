package com.lusion.sgauto

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lusion.sgauto.GarageUtility.GarageCommand
import com.lusion.sgauto.GarageUtility.ongoingGarageStatusThread
import com.lusion.sgauto.GarageUtility.sendGarageCommandSuspend
import kotlinx.coroutines.CancellationException

class GarageCommandWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val action = inputData.getString(EXTRA_ACTION)
            // Process interruption can leave delivery unknown. Do not replay door actions.
            if (action != ACTION_MONITOR && runAttemptCount > 0) return Result.failure()
            if (action != null) {
                // If the foreground service is already handling the command + polling,
                // skip to avoid duplicate network calls
                if (action == ACTION_MONITOR && GarageUtility.foregroundServicePolling.get()) {
                    Log.i(TAG, "doWork: $action — foreground service is active, skipping")
                    return Result.success()
                }
                Log.i(TAG, "doWork: $action")
                when (action) {
                    ACTION_OPEN -> return if (sendGarageCommandSuspend(applicationContext, GarageCommand.OPEN)) Result.success() else Result.failure()
                    ACTION_CLOSE -> return if (sendGarageCommandSuspend(applicationContext, GarageCommand.CLOSE)) Result.success() else Result.failure()
                    ACTION_MONITOR -> ongoingGarageStatusThread(applicationContext, TAG, 0)
                    else -> return Result.failure()
                }
            } else {
                Log.w(TAG, "Action was null")
                return Result.failure()
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
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