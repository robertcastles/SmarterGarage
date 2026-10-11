package com.lusion.sgauto

sealed interface StatusFetchResult {
    /** Receipt time of a validated API reading, not the device's own event time. */
    data class Fresh(val status: GarageUtility.GarageStatus, val receivedAt: Long) : StatusFetchResult
    data class Skipped(val reason: Reason) : StatusFetchResult
    data class Failed(val httpCode: Int? = null) : StatusFetchResult

    enum class Reason { RATE_LIMITED, BUDGET, ACTION_DELAY, INTERVAL, ACTION_WINDOW_SUPPRESSED }
}
