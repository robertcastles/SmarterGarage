package com.lusion.sgauto

import android.content.Context
import android.util.Log
import com.lusion.sgauto.PreferenceUtility.getSharedPreferences
import androidx.core.content.edit

internal object Utils {
    private const val TAG = "Utils"
    private const val KEY_REQUESTING_LOCATION_UPDATES = "requesting_location_updates"

    /**
     * Returns true if requesting location updates, otherwise returns false.
     *
     * @param context The [Context].
     */
    fun requestingLocationUpdates(context: Context): Boolean {
        Log.i(TAG, "requestingLocationUpdates")
        return getSharedPreferences(context)
            .getBoolean(KEY_REQUESTING_LOCATION_UPDATES, false)
    }

    /**
     * Stores the location updates state in SharedPreferences.
     * @param context The [Context].
     * @param requestingLocationUpdates The location updates state.
     */
    fun setRequestingLocationUpdates(context: Context, requestingLocationUpdates: Boolean) {
        Log.i(TAG, "setRequestingLocationUpdates $requestingLocationUpdates")
        getSharedPreferences(context)
            .edit {
                putBoolean(KEY_REQUESTING_LOCATION_UPDATES, requestingLocationUpdates)
            }
    }
}