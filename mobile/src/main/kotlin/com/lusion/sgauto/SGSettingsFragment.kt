package com.lusion.sgauto

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

/**
 * Fragment to handle the settings/preferences of the application.
 */
class SGSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Use device-protected storage for consistency with the rest of the app
        preferenceManager?.setStorageDeviceProtected()
        // Load the preferences from an XML resource
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }
}