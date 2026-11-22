package com.lusion.sgauto

/** Accessed under NotificationUtility's lock; independent of Android scheduling. */
internal class AutoArrivalLease {
    var sessionGeneration = 0L
        private set
    var generation = 0L
        private set
    var active = false
        private set
    private var token = 0L

    fun admit(expectedGeneration: Long): Long? {
        if (expectedGeneration != generation || active) return null
        active = true
        return ++token
    }

    fun admitForSession(expectedSessionGeneration: Long): Long? =
        if (expectedSessionGeneration == sessionGeneration) admit(generation) else null

    fun release(expectedToken: Long): Boolean {
        if (!active || expectedToken != token) return false
        active = false
        return true
    }

    fun invalidate() {
        generation++
        token++
        active = false
    }

    fun invalidateSession() {
        sessionGeneration++
        invalidate()
    }
}
