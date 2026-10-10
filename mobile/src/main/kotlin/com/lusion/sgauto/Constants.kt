package com.lusion.sgauto

object Constants {
    private const val PACKAGE_NAME = "com.lusion.smartergarage"

    const val GEOFENCES_ADDED_KEY = "$PACKAGE_NAME.GEOFENCES_ADDED_KEY"
    const val ACCESS_FINE_LOCATION_REQUEST = 33
    const val ACCESS_BACKGROUND_LOCATION_REQUEST = 34
    const val POST_NOTIFICATIONS_REQUEST = 35
    const val MAX_SLOW_POLLING_TIME_WINDOW:Long = 120000
    const val MAX_FAST_POLLING_TIME_WINDOW:Long = 40000
    const val MAX_MEDIUM_POLLING_TIME_WINDOW:Long = 90000
    const val SLOW_GARAGE_POLLING_PING_PERIOD:Long = 60000
    const val NORMAL_GARAGE_POLLING_PING_PERIOD:Long = 1000
    const val MEDIUM_GARAGE_POLLING_PING_PERIOD:Long = 5000
    const val GARAGE_ACTION_POLLING_DELAY:Long = 8000 // How long after getting a garage command accepted to hold the status as opening or closing
    const val GARAGE_POLLING_MINIMUM_RECHECK_TIME: Long = 1000
    const val MAX_GARAGE_POLLING_TIME:Long = 300000
}