package com.lusion.sgauto

import android.content.Context
import com.lusion.sgauto.PersistentLog as Log
import com.lusion.sgauto.Constants.MAX_FAST_POLLING_TIME_WINDOW
import com.lusion.sgauto.Constants.MAX_GARAGE_POLLING_TIME
import com.lusion.sgauto.Constants.MAX_MEDIUM_POLLING_TIME_WINDOW
import com.lusion.sgauto.Constants.MEDIUM_GARAGE_POLLING_PING_PERIOD
import com.lusion.sgauto.Constants.SLOW_GARAGE_POLLING_PING_PERIOD
import com.lusion.sgauto.GarageUtility.gStartFastLoop
import com.lusion.sgauto.GarageUtility.garageUpdateRunning
import com.lusion.sgauto.GarageUtility.monitorGarageStatus
import com.lusion.sgauto.PreferenceUtility.validGarageTokens
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class GarageMonitorControllableRunnable internal constructor(context: Context, caller: String, delay:Long = 0) : Runnable {
    private val _worker: Thread = Thread(this)
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var waitDelay: Long = delay
    private val iCaller: String = caller

    //private int interval;
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _context: WeakReference<Context> = WeakReference(context)

    init {
        Log.i(TAG, "ControllableRunnable: from $caller")
    }

    // ...
    fun interrupt() {
        running.set(false)
        _worker.interrupt()
    }

    private fun isMoving(): Boolean {
        val context = _context.get() ?: return false
        val status = GarageUtility.getLastGarageStatus(context)
        // Include UNKNOWN - we need to keep polling to recover from network errors
        return status == GarageUtility.GarageStatus.OPENING ||
               status == GarageUtility.GarageStatus.CLOSING ||
               status == GarageUtility.GarageStatus.UNKNOWN
    }

    override fun run() {
        running.set(true)
        stopped.set(false)
        try {
            if (!validGarageTokens(_context.get())) return
            if(waitDelay > 0) {
                runBlocking { // This will block the thread but that's OK since we're in a Runnable
                    LusionUtility.sleep(waitDelay, "GarageMonitorControllableRunnable from $iCaller")
                }
            }
            garageUpdateRunning.set(true)
            gStartFastLoop = true
            var startTime = System.currentTimeMillis()
            while ((garageUpdateRunning.get() || isMoving()) && running.get() && !_worker.isInterrupted) {
                // ... monitoring code ...
                if (gStartFastLoop) {
                    gStartFastLoop = false
                    startTime = System.currentTimeMillis()
                }
                //lastGarageStatus = "UNKNOWN";
                try {
                    val timeSinceLoopStarted = System.currentTimeMillis() - startTime

                    runBlocking {
                        _context.get()?.let {
                            monitorGarageStatus(
                                it,
                                true,
                                MAX_GARAGE_POLLING_TIME,
                                false,
                                "ControllableRunnable",
                                true
                            )
                        }

                        when {
                            timeSinceLoopStarted < MAX_FAST_POLLING_TIME_WINDOW -> {
                                // Use tiered polling based on context (foreground/background/car session)
                                val baseDelay = Constants.NORMAL_GARAGE_POLLING_PING_PERIOD + 100
                                val multiplier = LusionUtility.getPollingMultiplier()
                                val delay = baseDelay * multiplier
                                Log.d(TAG, "Fast polling with multiplier $multiplier, delay ${delay}ms (car=${LusionUtility.isInCarSession}, fg=${LusionUtility.isAppInForeground()})")
                                LusionUtility.sleep(delay, "fastest loop")
                            }
                            timeSinceLoopStarted < MAX_MEDIUM_POLLING_TIME_WINDOW -> {
                                val multiplier = LusionUtility.getPollingMultiplier()
                                val delay = MEDIUM_GARAGE_POLLING_PING_PERIOD * multiplier
                                Log.d(TAG, "Medium polling with multiplier $multiplier, delay ${delay}ms")
                                LusionUtility.sleep(delay, "medium loop")
                            }
                            else -> {
                                LusionUtility.sleep(SLOW_GARAGE_POLLING_PING_PERIOD, "slow loop")
                            }
                        }
                    }
                    // Check MAX_GARAGE_POLLING_TIME after sleep — but NOT if the garage is
                    // confirmed OPEN. An open garage at home is exactly the state we need to
                    // keep watching so we notice it closing (e.g. the user walks inside and
                    // closes it a few minutes later); stopping here regardless of status left
                    // monitoring completely silent with no automatic resumption. Extend the
                    // window (like a fresh loop) instead of stopping while OPEN — this keeps
                    // polling at the slow tier indefinitely until it's confirmed CLOSED (or
                    // starts moving again, which resets startTime via gStartFastLoop anyway).
                    if (System.currentTimeMillis() - startTime > MAX_GARAGE_POLLING_TIME) {
                        val status = _context.get()?.let { GarageUtility.getLastGarageStatus(it) }
                        if (status == GarageUtility.GarageStatus.OPEN) {
                            Log.d(TAG, "MAX_GARAGE_POLLING_TIME reached but garage still OPEN — continuing to watch for it closing")
                            startTime = System.currentTimeMillis()
                        } else {
                            running.set(false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during garage status monitoring", e)
                }
            }
        } finally {
            scope.cancel()
            stopped.set(true)
        }
    }

    companion object {
        private const val TAG = "ControllableRunnable"
    }
}