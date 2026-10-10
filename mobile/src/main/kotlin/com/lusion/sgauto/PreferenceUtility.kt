package com.lusion.sgauto

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lusion.sgauto.GarageUtility.garage_access_key
import com.lusion.sgauto.LusionUtility.isValidGUID
import com.lusion.sgauto.LusionUtility.personal_access_token
import org.json.JSONArray
import org.json.JSONObject

object PreferenceUtility {
    private const val TAG = "PreferenceUtility"

    // Backup file format version. Bump only if the on-disk schema changes in a
    // way that older importers must reject/branch on.
    private const val BACKUP_SCHEMA_VERSION = 1

    // Storage is kept in device-protected storage context to ensure
    // preferences are available during early app startup (before user unlock)
    // and during background tasks triggered by system events.
    private fun getSharedPreferencesSafe(context: Context): SharedPreferences {
        // We use the device-protected storage context to ensure
        // preferences are available even before user unlock.
        val dpsContext = context.createDeviceProtectedStorageContext()
        val sharedPrefsName = "${context.packageName}_preferences"

        // Migrate from regular shared preferences if they exist and haven't been migrated yet
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(dpsContext)
        if (sharedPrefs.all.isEmpty()) {
            Log.i(TAG, "Attempting migration to device protected storage")
            dpsContext.moveSharedPreferencesFrom(context, sharedPrefsName)
        }
        return sharedPrefs
    }

    fun setPreference(name: String?, value: Any?, context: Context) {
        val sharedPref = getSharedPreferencesSafe(context)
        sharedPref.edit {
            when (value) {
                is Location -> {
                    val locationJson = JSONObject().apply {
                        put("latitude", value.latitude)
                        put("longitude", value.longitude)
                    }
                    putString(name, locationJson.toString())
                }

                is String -> putString(name, value)
                is Boolean -> putBoolean(name, value)
                is Double -> putLong(name, java.lang.Double.doubleToRawLongBits(value))
                is Int -> putInt(name, value)
                is Long -> putLong(name, value)
                else -> {
                    putString(name, value.toString())
                }
            }
        }
    }

    fun getPreference(key: String, context: Context, defaultValue: String): String {
        val prefs = getSharedPreferencesSafe(context)
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun getPreference(name: String, t: Class<*>, context: Context): Any? {
        val sharedPref = getSharedPreferencesSafe(context)
        return when (t) {
            String::class.java -> sharedPref.getString(name, "")?.trim()
            Location::class.java -> sharedPref.getString(name, null)?.let {
                try {
                    JSONObject(it).run {
                        Location("Home").apply {
                            latitude = getDouble("latitude")
                            longitude = getDouble("longitude")
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
            Boolean::class.java -> sharedPref.getBoolean(name, false)
            Double::class.java -> java.lang.Double.longBitsToDouble(sharedPref.getLong(name, 0))
            Int::class.java -> sharedPref.getInt(name, 0)
            Long::class.java -> sharedPref.getLong(name, 0)
            else -> sharedPref.getString(name, "")
        }
    }

    fun getAccessKey(context: Context): Boolean {
        val sharedPref = getSharedPreferencesSafe(context)
        garage_access_key = sharedPref.getString("garage_device_id", "")?.trim().orEmpty()
        personal_access_token = sharedPref.getString("personal_access_token", "")?.trim().orEmpty()
        return isValidGUID(garage_access_key) && isValidGUID(personal_access_token)
    }

    fun getLogMessages(context: Context): List<String?> {
        val logMaxLen = 100
        val geofenceMessagesString = getPreference("geofenceMessages", String::class.java, context) as String?
        val messages = geofenceMessagesString?.split(";")?.filter { it.isNotEmpty() } ?: emptyList()
        return if (messages.size > logMaxLen) messages.takeLast(logMaxLen) else messages
    }

    fun validGarageTokens(context: Context?): Boolean {
        val sharedPref = getSharedPreferencesSafe(
            context!!
        )
        garage_access_key = sharedPref.getString("garage_device_id", "")?.trim().orEmpty()
        personal_access_token = sharedPref.getString("personal_access_token", "")?.trim().orEmpty()
        if (!isValidGUID(garage_access_key) || !isValidGUID(personal_access_token)) {
            Log.e(TAG, "validGarageTokens: invalid garage tokens. key: $garage_access_key, token length: ${personal_access_token.length}")
            return false
        }
        return true
    }

    fun getHomeLocation(context: Context): Location? {
        return try {
            getPreference("home", Location::class.java, context) as Location?
        } catch (_: Exception) {
            null
        }
    }

    fun getSharedPreferences(context: Context): SharedPreferences {
        return getSharedPreferencesSafe(context)
    }

    /**
     * Serializes ALL of the app's SharedPreferences into a single, type-tagged
     * JSON document suitable for writing to a user-chosen file (via the Storage
     * Access Framework). Because a build swap (release <-> debug) forces an
     * uninstall — which wipes the sandbox — this backup is how a user carries
     * their full setup (home location, geofence radius, SmartThings credentials,
     * notification prefs, etc.) across reinstalls.
     *
     * Each entry is stored as { "t": <type>, "v": <value> } so types survive the
     * round-trip (SharedPreferences is otherwise type-strict on read).
     * Credentials ARE included by design (user opted in); the resulting file is
     * plaintext, so it should be stored somewhere private.
     */
    fun exportPreferencesToJson(context: Context): String {
        val prefs = getSharedPreferencesSafe(context)
        val entries = JSONObject()
        for ((key, value) in prefs.all) {
            if (value == null) continue
            val entry = JSONObject()
            when (value) {
                is Boolean -> { entry.put("t", "bool"); entry.put("v", value) }
                is Int -> { entry.put("t", "int"); entry.put("v", value) }
                is Long -> { entry.put("t", "long"); entry.put("v", value) }
                is Float -> { entry.put("t", "float"); entry.put("v", value.toDouble()) }
                is String -> { entry.put("t", "string"); entry.put("v", value) }
                is Set<*> -> {
                    entry.put("t", "stringset")
                    entry.put("v", JSONArray(value.map { it.toString() }))
                }
                else -> {
                    // Fallback: persist unknown types as their string form.
                    entry.put("t", "string"); entry.put("v", value.toString())
                }
            }
            entries.put(key, entry)
        }

        val root = JSONObject()
        root.put("schema", BACKUP_SCHEMA_VERSION)
        root.put("package", context.packageName)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("prefs", entries)
        return root.toString(2)
    }

    /**
     * Restores preferences previously produced by [exportPreferencesToJson].
     * Performs a full replace (clear-then-write) so the result exactly matches the
     * snapshot. Uses a synchronous commit so callers can safely re-arm the geofence
     * / refresh UI immediately afterward. Returns true on success.
     */
    fun importPreferencesFromJson(context: Context, json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val entries = root.optJSONObject("prefs")
                ?: run {
                    Log.e(TAG, "importPreferencesFromJson: no 'prefs' object in backup")
                    return false
                }
            val prefs = getSharedPreferencesSafe(context)
            val editor = prefs.edit()
            editor.clear()
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = entries.optJSONObject(key) ?: continue
                when (entry.optString("t")) {
                    "bool" -> editor.putBoolean(key, entry.optBoolean("v"))
                    "int" -> editor.putInt(key, entry.optInt("v"))
                    "long" -> editor.putLong(key, entry.optLong("v"))
                    "float" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                    "string" -> editor.putString(key, entry.optString("v"))
                    "stringset" -> {
                        val arr = entry.optJSONArray("v") ?: JSONArray()
                        val set = HashSet<String>(arr.length())
                        for (i in 0 until arr.length()) set.add(arr.optString(i))
                        editor.putStringSet(key, set)
                    }
                    else -> Log.w(TAG, "importPreferencesFromJson: skipping key '$key' with unknown type")
                }
            }
            val committed = editor.commit()
            Log.i(TAG, "importPreferencesFromJson: restore committed=$committed, ${entries.length()} keys")
            committed
        } catch (e: Exception) {
            Log.e(TAG, "importPreferencesFromJson: failed to parse/restore backup", e)
            false
        }
    }
}