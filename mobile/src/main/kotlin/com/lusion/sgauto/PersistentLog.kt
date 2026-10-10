package com.lusion.sgauto

import android.content.Context
import android.util.Log as ALog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drop-in wrapper around [android.util.Log] that ALSO appends every log line to a
 * small rotating file on disk, in the app's own external-files directory
 * (`/sdcard/Android/data/com.lusion.sgauto/files/sgauto_persistent_log.txt`).
 *
 * That location is world-pullable via a plain `adb pull` — no `run-as`, root, or
 * debuggable build required — unlike `/data/data/<pkg>/...` internal storage. This
 * exists because live `adb logcat` cannot be relied on after the fact: on at least
 * one test device the main ring buffer was observed to rotate out in well under 15
 * minutes under heavy unrelated system log volume, long before anyone could pull
 * logs for an intermittent field issue (stuck/incorrect garage notification) that
 * may only be noticed hours later.
 *
 * Usage: import this in place of `android.util.Log`:
 *   import com.lusion.sgauto.PersistentLog as Log
 * Existing `Log.i(TAG, msg)` / `Log.w(...)` / `Log.e(...)` call sites then need no
 * other changes — they still also go to logcat as before.
 *
 * [init] must be called once (from [SGAutoApp.onCreate]) before file writes can
 * happen; until then, calls silently fall back to logcat-only (never crashes the
 * app over logging).
 */
object PersistentLog {
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024 // 2MB per file before rotating
    private const val LOG_FILE_NAME = "sgauto_persistent_log.txt"
    private const val OLD_LOG_FILE_NAME = "sgauto_persistent_log.old.txt"

    @Volatile private var logDir: File? = null
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /** Call once from Application.onCreate(). Safe to call multiple times. */
    fun init(context: Context) {
        if (logDir != null) return
        logDir = try {
            context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
        } catch (e: Exception) {
            null
        }
        i("PersistentLog", "init: logging to ${logDir?.absolutePath}/$LOG_FILE_NAME")
    }

    fun i(tag: String, msg: String): Int { write("I", tag, msg); return ALog.i(tag, msg) }
    fun d(tag: String, msg: String): Int { write("D", tag, msg); return ALog.d(tag, msg) }
    fun w(tag: String, msg: String): Int { write("W", tag, msg); return ALog.w(tag, msg) }
    fun e(tag: String, msg: String): Int { write("E", tag, msg); return ALog.e(tag, msg) }

    fun w(tag: String, msg: String, tr: Throwable): Int {
        write("W", tag, "$msg\n${ALog.getStackTraceString(tr)}")
        return ALog.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable): Int {
        write("E", tag, "$msg\n${ALog.getStackTraceString(tr)}")
        return ALog.e(tag, msg, tr)
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val dir = logDir ?: return
        try {
            val file = File(dir, LOG_FILE_NAME)
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val old = File(dir, OLD_LOG_FILE_NAME)
                if (old.exists()) old.delete()
                file.renameTo(old)
            }
            val ts = dateFormat.get()!!.format(Date())
            file.appendText("$ts $level/$tag: $msg\n")
        } catch (_: Exception) {
            // Best-effort only — logging must never crash the app.
        }
    }
}

