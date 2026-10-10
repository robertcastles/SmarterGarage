package com.lusion.sgauto

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.delay
import java.util.regex.Pattern
import androidx.core.graphics.createBitmap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

object LusionUtility {
    private const val TAG = "LusionUtility"
    var personal_access_token: String = ""
    var resumeFlag = false

    /** Cache for Bitmaps rasterized from vector drawables (keyed by resource ID).
     *  Avoids re-allocating ~1.4MB per icon on every notification update. */
    private val bitmapCache = android.util.LruCache<Int, Bitmap>(6) // 5 status icons + app icon

    /**
     * Track whether we're currently in an Android Auto session.
     * Android Auto sessions have stricter throttling, so we use slower polling.
     */
    @Volatile
    var isInCarSession: Boolean = false

    /**
     * Check if the app is currently in the foreground.
     * Used to determine polling speed - slower in background to reduce Android throttling.
     */
    fun isAppInForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /**
     * Get the polling multiplier based on current context.
     * - Foreground: 1x (normal speed)
     * - Background (including car session): 2x slower
     *
     * Note: We no longer use 4x for car sessions — the foreground service elevates
     * process priority so 2x is sufficient, and 4x was making polling too slow
     * for garage status updates to arrive in time.
     */
    fun getPollingMultiplier(): Int {
        return when {
            !isAppInForeground() -> 2  // Any background context: 2x slower
            else -> 1                  // Foreground: normal
        }
    }

    fun getBitmapFromVectorDrawable(context: Context?, drawableId: Int): Bitmap? {
        if (context == null) return null

        bitmapCache.get(drawableId)?.let { return it }

        val drawable = ContextCompat.getDrawable(context, drawableId) ?: return null

        return createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight).apply {
            Canvas(this).apply {
                drawable.setBounds(0, 0, width, height)
                drawColor(Color.TRANSPARENT)
                drawable.draw(this)
            }
        }.also { bitmapCache.put(drawableId, it) }
    }

    fun isValidGUID(str: String?): Boolean {
        val regex = "^[{]?[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}[}]?$"
        return str?.let { Pattern.compile(regex).matcher(it).matches() } == true
    }

    fun showAlert(activity: Activity?, title: String?, message: String?) {
        AlertDialog.Builder(activity!!).apply {
            setTitle(title)
            setMessage(message)
            setPositiveButton(android.R.string.ok, null)
            show()
        }
    }

    @JvmStatic
    fun showSnackbar(activity: Activity, text: String?) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    suspend fun sleep(waitMs: Long, source: String) {
        val timeStamp = System.currentTimeMillis()
        Log.i(TAG, "sleep: $waitMs from $source $timeStamp")
        try {
            delay(waitMs.milliseconds)
            Log.i(TAG, "sleep: done $timeStamp waitMs $waitMs actual was ${System.currentTimeMillis() - timeStamp}")
        } catch (e: Exception) {
            Log.e(TAG, "sleep: exception ${e.localizedMessage}")
            when (e) {
                is CancellationException -> Log.w(TAG, "sleep: coroutine was cancelled")
                else -> throw e
            }
        }
        Log.i(TAG, "sleep: got past timer $source")
    }

    enum class DataRequestType {
        GETGARAGESTATUS
    }

    enum class UpdateType {
        GEOFENCE, GARAGESTATUS
    }
}