package com.lusion.sgauto

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log

class GaragePollingHandlerThread :
    HandlerThread("GaragePollingHandlerThread", Process.THREAD_PRIORITY_BACKGROUND) {

    private val _handler: Handler by lazy {
        Handler(looper)
    }

    override fun onLooperPrepared() {
//        handler = Handler(looper)
    }

    fun getHandler(): Handler {
        return _handler
    }

    override fun quit(): Boolean {
        Log.w(TAG, "quit")
        return super.quit()
    }

    override fun quitSafely(): Boolean {
        Log.i(TAG, "quitSafely")
        return super.quitSafely()
    }

    companion object {
        private const val TAG = "GaragePollingHandlerThread"
    }
}