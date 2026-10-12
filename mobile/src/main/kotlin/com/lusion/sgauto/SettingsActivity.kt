package com.lusion.sgauto

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.Editor
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Configuration
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NavUtils
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.gms.location.LocationServices
import com.lusion.sgauto.GeofenceUtility.decreaseRadius
import com.lusion.sgauto.GeofenceUtility.increaseRadius
import com.lusion.sgauto.LocationUtility.setHomeWithCurrentLocation
import com.lusion.sgauto.PermissionUtility.isBackgroundLocationGranted
import com.lusion.sgauto.PermissionUtility.isFineLocationGranted
import com.lusion.sgauto.PermissionUtility.permissionGranted
import com.lusion.sgauto.PermissionUtility.requestPermission
import com.lusion.sgauto.PreferenceUtility.getPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private class PrefProps(
        var name: String?,
        var pref: Preference?,
        var summary: String?,
        icon: Int
    ) {
        var icon: Int? = icon
    }


    /**
     * Callback received when a permissions request has been completed.
     * Simply chains to the next missing permission on success.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            Constants.POST_NOTIFICATIONS_REQUEST -> {
                // Chain to next permission regardless of result
                requestNextMissingPermission()
            }

            Constants.ACCESS_FINE_LOCATION_REQUEST -> {
                if (isFineLocationGranted(this)) {
                    // Fine location granted — set home and refresh UI
                    mThis?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setHomeWithCurrentLocation(it, applicationContext)
                        }
                    }
                    refreshGeofenceUI()
                    // Check if "Allow all the time" also granted background
                    if (isBackgroundLocationGranted(this)) {
                        Log.d(TAG, "Both fine and background location granted from single prompt")
                    } else {
                        // Chain to request background location
                        requestNextMissingPermission()
                    }
                }
                // If denied, do nothing — onResume will re-check
            }

            Constants.ACCESS_BACKGROUND_LOCATION_REQUEST -> {
                if (isBackgroundLocationGranted(this)) {
                    LusionUtility.showSnackbar(mThis as Activity, "Background location access granted")
                } else {
                    Log.d(TAG, "Background location not granted by system prompt")
                }
                refreshGeofenceUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: ")
        // Make the activity edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Check if device is in dark mode
        val isDarkMode = resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        // Set status bar icons based on theme
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkMode
        }

        setContentView(R.layout.settings_activity)
        mThis = this
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.activity_settings, SettingsFragment())
                .commit()
        }
        val actionBar = supportActionBar
        actionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this)
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        refreshGeofenceUI()
        refreshBatteryOptimizationUI()
        requestNextMissingPermission()
    }

    private fun requestNextMissingPermission() {
        // 1. Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (PermissionUtility.requestNotificationPermissionIfNeeded(this)) return
        }
        // 2. Location permissions (fine → background, with settings fallback)
        if (PermissionUtility.requestNextLocationPermission(this)) return
        // 3. Battery optimization exemption (critical for Samsung Freecess/MARs)
        if (PermissionUtility.requestBatteryOptimizationExemption(this)) return
        // 4. Samsung-specific "Never sleeping apps" guidance (one-time prompt)
        if (PermissionUtility.promptSamsungUnrestrictedIfNeeded(this)) return
        // All permissions granted
        Log.d(TAG, "All permissions granted")
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        // SAF launchers for settings backup/restore. Registered as fields so they
        // are ready before the fragment reaches STARTED. Export creates a JSON
        // document the user places anywhere (Drive/Documents); import reads one
        // back. This is how a full setup survives the uninstall that a
        // release<->debug build swap requires.
        private val backupLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? -> if (uri != null) writeBackupTo(uri) }

        private val restoreLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? -> if (uri != null) readBackupFrom(uri) }

        private fun updateSmartThingsHelp(token: String?) {
            findPreference<Preference>("smartthings_token_help")?.apply {
                isVisible = true
                summary = if (LusionUtility.isValidGUID(token?.trim())) null
                    else getString(R.string.smartthings_token_help_collapsed)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            mPreferenceFragmentCompat = this
            preferenceManager?.setStorageDeviceProtected()
            val scale = resources.displayMetrics.density
            val editTextHeightInDP = 48
            val etHeightInPx = (editTextHeightInDP * scale).toInt()
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            findPreference<Preference>("smartthings_token_help")?.setOnPreferenceClickListener {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.smartthings_token_help_title)
                    .setMessage(R.string.smartthings_token_help_summary)
                    .setPositiveButton(R.string.smartthings_token_page) { _, _ ->
                        val url = "https://account.smartthings.com/tokens"
                        try {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: android.content.ActivityNotFoundException) {
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setMessage(url).setPositiveButton(android.R.string.ok, null).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            findPreference<Preference>("assistant_help")?.setOnPreferenceClickListener {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.assistant_help_title)
                    .setMessage(R.string.assistant_help_details)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            val homePref = findPreference<Preference>("set_home")
            if (homePref == null) {
                Log.w(TAG, "Could not find homePref set_home")
                return
            }

            // Find out how when this is set to change the text that it is set on a callback or prefchangelistener
            val location = getPreference(
                "home",
                Location::class.java,
                requireContext()
            ) as Location?
            if (location != null)
                setPrefProps(PrefProps(null, homePref, "Home is set", R.drawable.geofence_in))
            homePref.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    if (!permissionGranted(
                            requireContext(),
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    ) {
                        Log.d(SettingsActivity.TAG, "calling requestPermission for ACCESS_FINE_LOCATION")
                        requestPermission(
                            mThis!!,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Constants.ACCESS_FINE_LOCATION_REQUEST
                        )
                    } else {
                        mThis?.let { activity ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                setHomeWithCurrentLocation(activity, requireContext())
                            }
                        }
                        // Delay the UI update slightly to let the async location callback finish
                        view?.postDelayed({ updateGeofenceUI() }, 2000)
                    }
                    true
                }
            val increasePerimeter = findPreference<Preference>("increase_perimeter")
            if (increasePerimeter != null) {
                increasePerimeter.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        context?.let {
                            increaseRadius(it)
                            updateGeofenceUI()
                        }
                        true
                    }
            }
            val decreasePerimeter = findPreference<Preference>("decrease_perimeter")
            if (decreasePerimeter != null) {
                decreasePerimeter.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        context?.let {
                            decreaseRadius(it)
                            updateGeofenceUI()
                        }
                        true
                    }
            }
            val autoNotificationPriorityLP =
                findPreference<ListPreference>("auto_notification_priority")
            if (autoNotificationPriorityLP != null) {
                autoNotificationPriorityLP.summary = autoNotificationPriorityLP.entry
                autoNotificationPriorityLP.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { preference: Preference, newValue: Any ->
                        val lp = preference as ListPreference
                        val entries = lp.entries
                        val index = lp.findIndexOfValue(newValue.toString())
                        lp.summary = entries[index]
                        true
                    }
            }

            val patETP = findPreference<EditTextPreference>("personal_access_token")
            updateSmartThingsHelp(patETP?.text)
            val garageDeviceLP = findPreference<ListPreference>("garage_device_id")
            if(garageDeviceLP!!.value !=null)
            {
                val context = activity?.applicationContext
                val debugEnabled = getPreference("debug_switch", Boolean::class.java, requireContext()) as Boolean

                if (!debugEnabled && patETP != null && checkIfGUID(patETP, patETP.text))
                {
                    if (context != null) {
                        getGarageDevices(
                            context,
                            garageDeviceLP,
                            garageDeviceLP.value
                        )
                    }
                    if (checkIfGUID(garageDeviceLP, garageDeviceLP.value)) {
                        setPrefProps(PrefProps(null, garageDeviceLP, "", R.drawable.checkmark))
                        //                    setPrefProps(new PrefProps(null, auth_tokenETP, "✔", android.R.color.transparent));
                    } else setPrefProps(PrefProps(null, garageDeviceLP, "", R.drawable.exclamation))
                }
            }
            garageDeviceLP.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                setPrefProps(PrefProps(null, garageDeviceLP, "", R.drawable.checkmark))
                true
            }

            if (patETP != null) {
                // TODO: Add some validation to the provided token to confirm access and notify if garages are found
                patETP.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _: Preference, newValue: Any? ->
                        updateSmartThingsHelp(newValue as? String)
                        if (checkIfGUID(patETP, newValue as String)) {
                            setPrefProps(PrefProps(null, patETP, "", R.drawable.checkmark))
                            val context = activity?.applicationContext
                            if (context != null) {
                                getGarageDevices(context, garageDeviceLP)
                            }
                            //                        setPrefProps(new PrefProps(null, auth_tokenETP, "✔", android.R.color.transparent));
                        } else setPrefProps(
                            PrefProps(
                                null,
                                patETP,
                                "",
                                R.drawable.exclamation
                            )
                        )
                        true
                    }
                if (checkIfGUID(patETP, patETP.text)) {
                    setPrefProps(PrefProps(null, patETP, "", R.drawable.checkmark))
                    //                    setPrefProps(new PrefProps(null, auth_tokenETP, "✔", android.R.color.transparent));
                } else setPrefProps(PrefProps(null, patETP, "", R.drawable.exclamation))
                patETP.setOnBindEditTextListener { editText: EditText ->
                    editText.hint = getString(R.string.personal_access_token_hint)
                    editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            val prefID = "debug_server"
            setETPSingleLine(this, prefID)

            // Add URL validation and formatting
            val debugServerPref = findPreference<EditTextPreference>(prefID)
            debugServerPref?.setOnPreferenceChangeListener { _, newValue ->
                val urlStr = newValue as String
                val formattedUrl = if (!urlStr.startsWith("https://", ignoreCase = true)) {
                    "https://$urlStr"
                } else {
                    urlStr
                }

                // Update the preference with formatted URL
                val settings = PreferenceUtility.getSharedPreferences(requireContext())
                settings.edit { putString(prefID, formattedUrl) }
                debugServerPref.text = formattedUrl

                false // Return false to prevent the original value from being set
            }

            val locationUpdateRateETP = findPreference<EditTextPreference>("locationUpdateRate")
            locationUpdateRateETP?.setOnBindEditTextListener { editText: EditText ->
                editText.hint = getString(R.string.locationUpdateRate_hint)
                editText.height = etHeightInPx
                editText.inputType = InputType.TYPE_CLASS_TEXT
            }
            val debugSwitch = preferenceScreen.findPreference<SwitchPreference>("debug_switch")
            if (debugSwitch != null) {
                debugSwitch.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                        if (newValue as Boolean) {
                            setPreferenceCategory("debugging", "DEBUGGING ACTIVE")
                        } else {
                            val title = requireContext().getString(R.string.debugging)
                            val prefCatID = "debugging"
                            setPreferenceCategory(prefCatID, title)
                        }
                        true
                    }
            }
            val debug = getPreference(
                "debug_switch",
                Boolean::class.java,
                requireContext()
            ) as Boolean
            if (debug) {
                setPreferenceCategory("debugging", "DEBUGGING ACTIVE")
            }
            val versionPref = findPreference<EditTextPreference>("version")
            versionPref?.setOnBindEditTextListener { editText ->
                editText.isEnabled = false
                editText.isFocusable = false
                editText.isFocusableInTouchMode = false
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editText.setSingleLine(false)
                editText.maxLines = 5 // Adjust the number of lines as needed
            }
            val version: String? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requireContext().packageManager.getPackageInfo(
                        requireContext().packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    ).versionName
                } else {
                    requireContext().packageManager.getPackageInfo(
                        requireContext().packageName,
                        0
                    ).versionName
                }
            } catch (e: NameNotFoundException) {
                throw RuntimeException(e)
            }
            val isDebuggable =
                0 != requireContext().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE

            val lastGarageJobServiceTimeStamp = getPreference("GarageStatusWorker", String::class.java, requireContext()) as String?
            var formattedDate = ""
            if(!lastGarageJobServiceTimeStamp.isNullOrEmpty()) {
                val timestamp = lastGarageJobServiceTimeStamp.toLong()
                val date = Date(timestamp)
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                formattedDate = format.format(date)
            }

            if (versionPref != null) versionPref.title =
                getString(R.string.version) + ": " + version + " " + (if (isDebuggable) "Debug" else "Release") + (if (formattedDate != "") "\nLast Job: $formattedDate" else "\nNo job ran yet")

            val geofenceCustomNamePref = findPreference<EditTextPreference>("geofence_custom_name")
            geofenceCustomNamePref?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, _ ->
                    view?.post { updateGeofenceUI() }
                    true
                }

            // Battery optimization preference
            val batteryPref = findPreference<Preference>("battery_optimization")
            batteryPref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                mThis?.let { activity ->
                    if (PermissionUtility.isBatteryOptimizationExempt(activity)) {
                        // Already exempt — on Samsung, guide to "Never sleeping apps"
                        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                            PermissionUtility.promptSamsungUnrestrictedIfNeeded(activity)
                        } else {
                            LusionUtility.showSnackbar(activity, "Battery optimization already unrestricted")
                        }
                    } else {
                        PermissionUtility.requestBatteryOptimizationExemption(activity)
                    }
                }
                true
            }
            updateBatteryOptimizationUI()

            findPreference<Preference>("backup_settings")?.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    backupLauncher.launch(getString(R.string.backup_default_filename))
                    true
                }
            findPreference<Preference>("restore_settings")?.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    // Accept the json we write plus common fallbacks providers use.
                    restoreLauncher.launch(
                        arrayOf("application/json", "application/octet-stream", "text/plain")
                    )
                    true
                }

            updateGeofenceUI()
        }

        private fun writeBackupTo(uri: Uri) {
            val context = context ?: return
            try {
                val json = PreferenceUtility.exportPreferencesToJson(context)
                // "wt" truncates, so re-selecting an existing file overwrites cleanly.
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Could not open output stream")
                Log.i(TAG, "writeBackupTo: settings exported to $uri")
                (activity as? Activity)?.let {
                    LusionUtility.showSnackbar(it, getString(R.string.backup_success))
                }
            } catch (e: Exception) {
                Log.e(TAG, "writeBackupTo: export failed", e)
                (activity as? Activity)?.let {
                    LusionUtility.showSnackbar(it, getString(R.string.backup_failed))
                }
            }
        }

        private fun readBackupFrom(uri: Uri) {
            val context = context ?: return
            try {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: throw IllegalStateException("Could not open input stream")
                val ok = PreferenceUtility.importPreferencesFromJson(context, json)
                if (!ok) {
                    (activity as? Activity)?.let {
                        LusionUtility.showSnackbar(it, getString(R.string.restore_failed))
                    }
                    return
                }
                Log.i(TAG, "readBackupFrom: settings restored from $uri; re-arming geofence")
                // Restored home/radius must take effect immediately.
                GeofenceUtility.ensureGeofencesRegistered(context)
                (activity as? Activity)?.let {
                    LusionUtility.showSnackbar(it, getString(R.string.restore_success))
                }
                // Rebuild the settings UI so every restored value is reflected.
                view?.post { activity?.recreate() }
            } catch (e: Exception) {
                Log.e(TAG, "readBackupFrom: restore failed", e)
                (activity as? Activity)?.let {
                    LusionUtility.showSnackbar(it, getString(R.string.restore_failed))
                }
            }
        }

        @SuppressLint("MissingPermission")
        fun updateGeofenceUI() {
            val context = context ?: return
            val generalCategory = findPreference<PreferenceCategory>("general") ?: return
            val homePref = findPreference<Preference>("set_home")

            val homeLocation = getPreference(
                "home",
                Location::class.java,
                context
            ) as Location?

            val radius = getPreference("radius", Int::class.java, context) as? Int ?: 500
            val customName = getPreference("geofence_custom_name", String::class.java, context) as? String
            val nameToShow = if (customName.isNullOrBlank()) "Home" else customName

            if (homeLocation == null) {
                generalCategory.title = "Geofence Size: Geofence not yet set"
                homePref?.summary = "Home is set?"
                homePref?.setIcon(R.drawable.exclamation)
                return
            }

            homePref?.summary = "$nameToShow is set"
            homePref?.setIcon(R.drawable.geofence_in)

            if (permissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                fusedLocationClient.lastLocation.addOnCompleteListener { task ->
                    if (!isAdded) return@addOnCompleteListener
                    val lastLocation = task.result
                    val distanceInfo = if (lastLocation != null) {
                        val distance = lastLocation.distanceTo(homeLocation).toInt()
                        " | Center: ${distance}m"
                    } else {
                        " | Distance: Unknown"
                    }
                    generalCategory.title = "Geofence Size: ${radius}m$distanceInfo"
                }
            }

            generalCategory.title = "Geofence Size: ${radius}m"
        }

        fun updateBatteryOptimizationUI() {
            val context = context ?: return
            val batteryPref = findPreference<Preference>("battery_optimization") ?: return

            if (PermissionUtility.isBatteryOptimizationExempt(context)) {
                batteryPref.summary = "Unrestricted ✓"
                batteryPref.setIcon(R.drawable.checkmark)
            } else {
                batteryPref.summary = "Restricted — tap to fix (recommended)"
                batteryPref.setIcon(R.drawable.exclamation)
            }
        }

        private fun setPreferenceCategory(prefCatID: String, prefCatTitle: String) {
            val prefCat = findPreference<PreferenceCategory>(prefCatID)
            if (prefCat != null) prefCat.title = prefCatTitle
        }

        private fun checkIfGUID(preference: Preference, newValue: String?): Boolean {
            val settings: SharedPreferences =
                PreferenceUtility.getSharedPreferences(requireContext())
            val editor: Editor = settings.edit()
            if (newValue != null) {
                val cleanInput = newValue.trim { it <= ' ' }
                editor.putString(preference.key, cleanInput)
                editor.apply()
                val isGuid = LusionUtility.isValidGUID(cleanInput)
                if (isGuid) return true
                val message = getString(R.string.guid_requirement)
                LusionUtility.showAlert(
                    activity,
                    getString(R.string.invalid_input) + preference.title,
                    message
                )
                //                        LusionUtility.showSnackbar(getActivity(), "The value must be a 'GUID'", false);
            }
            return false
        }

        companion object {
            private const val TAG = "SettingsActivity"
            private fun setETPSingleLine(settingsFragment: SettingsFragment, prefID: String) {
                val etp = settingsFragment.findPreference<EditTextPreference>(prefID)
                etp?.setOnBindEditTextListener { obj: EditText -> obj.setSingleLine() }
            }
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"
        private var mThis: AppCompatActivity? = null
        private var mPreferenceFragmentCompat: PreferenceFragmentCompat? = null

        fun refreshGeofenceUI() {
            (mPreferenceFragmentCompat as? SettingsFragment)?.updateGeofenceUI()
        }

        fun refreshBatteryOptimizationUI() {
            (mPreferenceFragmentCompat as? SettingsFragment)?.updateBatteryOptimizationUI()
        }

        private fun setPrefProps(prefProps: PrefProps) {
            var pref = prefProps.pref
            if (pref == null && prefProps.name != null) pref =
                mPreferenceFragmentCompat!!.findPreference(
                    prefProps.name!!
                )
            if (pref != null) {
                if (prefProps.summary != null) pref.summary = prefProps.summary
                if (prefProps.icon != null) pref.setIcon(prefProps.icon!!)
            }
        }

        private fun getGarageDevices(context: Context, selectedDeviceLP: ListPreference, default: String? = null) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val api = SmartThingsService.getInstance(context)
                    val response: Response<DevicesResponse> = withContext(Dispatchers.IO) {
                        api.getDevices()
                    }

                    if (response.isSuccessful && response.body() != null) {
                        val devices = response.body()!!.items
                        val garageDoors = devices.filter { device ->
                            device.components.any { component ->
                                component.capabilities.any { capability ->
                                    capability.id == "doorControl"
                                }
                            }
                        }

                        when {
                            garageDoors.isEmpty() -> {
                                selectedDeviceLP.summary = "No garage doors found"
                            }
                            garageDoors.size == 1 -> {
                                setPrefProps(PrefProps(null, selectedDeviceLP, "", R.drawable.checkmark))
                                selectedDeviceLP.summary = "Garage door found and set"
                                selectedDeviceLP.entries = arrayOf(garageDoors[0].label)
                                selectedDeviceLP.entryValues = arrayOf(garageDoors[0].deviceId)
                                selectedDeviceLP.value = garageDoors[0].deviceId
                            }
                            else -> {
                                selectedDeviceLP.summary = "Select your garage door"
                                val entries = mutableListOf("Please select a garage door")
                                val entryValues = mutableListOf("0")
                                garageDoors.forEach { garage ->
                                    entries.add(garage.label)
                                    entryValues.add(garage.deviceId)
                                }
                                Log.i(TAG, "Found ${garageDoors.size} garage door devices")
                                selectedDeviceLP.entries = entries.toTypedArray()
                                selectedDeviceLP.entryValues = entryValues.toTypedArray()
                                default?.let { selectedDeviceLP.value = it }
                            }
                        }
                    } else {
                        throw Exception("Failed to fetch devices: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching garage devices", e)
                    setPrefProps(PrefProps(null, selectedDeviceLP, "Unable to retrieve garage data", R.drawable.exclamation))
                }
            }
        }
    }
}
