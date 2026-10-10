package com.lusion.sgauto

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.GeofenceStatusCodes

// Geofence error codes mapped to error messages.
class GeofenceErrorMessages {
    companion object {
        private val UNKNOWN_GEOFENCE_ERROR = R.string.unknown_geofence_error
        private val GEOFENCE_NOT_AVAILABLE = R.string.geofence_not_available
        private val GEOFENCE_TOO_MANY_GEOFENCES = R.string.geofence_too_many_geofences
        private val GEOFENCE_TOO_MANY_PENDING_INTENTS = R.string.geofence_too_many_pending_intents

        // Returns the error string for a geofencing exception.
        fun getErrorString(context: Context, e: Exception?): String {
            return if (e is ApiException) {
                getErrorString(context, e.statusCode)
            } else {
                context.resources.getString(UNKNOWN_GEOFENCE_ERROR)
            }
        }

        // Returns the error string for a geofencing error code.
        fun getErrorString(context: Context, errorCode: Int): String {
            val resources = context.resources
            return when (errorCode) {
                GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> resources.getString(GEOFENCE_NOT_AVAILABLE)
                GeofenceStatusCodes.GEOFENCE_TOO_MANY_GEOFENCES -> resources.getString(GEOFENCE_TOO_MANY_GEOFENCES)
                GeofenceStatusCodes.GEOFENCE_TOO_MANY_PENDING_INTENTS -> resources.getString(GEOFENCE_TOO_MANY_PENDING_INTENTS)
                else -> resources.getString(UNKNOWN_GEOFENCE_ERROR)
            }
        }
    }
}