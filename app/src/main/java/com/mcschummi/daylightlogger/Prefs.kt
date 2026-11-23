package com.mcschummi.daylightlogger

import android.content.Context

/**
 * Small helper so all activities read/write the same SharedPreferences keys.
 */
object Prefs {
    private const val FILE = "daylight_prefs"

    const val KEY_MANUAL_K = "manual_k"           // fallback k when no calibration points
    const val KEY_POINTS = "calibration_points"   // "K1,k2,k3" -> averaged
    const val KEY_INTERVAL = "interval_seconds"   // one sample every N seconds
    const val KEY_DEVICE_MODE = "device_mode"     // AUTO / J3 / GENERIC
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

    fun get(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun intervalSeconds(context: Context): Int = get(context).getInt(KEY_INTERVAL, 60)

    /** Default k (2.5) if the user has typed nothing. */
    fun manualK(context: Context): Double {
        val s = get(context).getString(KEY_MANUAL_K, "2.5") ?: "2.5"
        return s.toDoubleOrNull() ?: 2.5
    }

    /** Store several calibration points as a comma separated list of k values. */
    fun calibrationPoints(context: Context): List<Double> =
        (get(context).getString(KEY_POINTS, "") ?: "")
            .split(',').mapNotNull { it.trim().toDoubleOrNull() }

    fun addCalibrationPoint(context: Context, k: Double) {
        val list = calibrationPoints(context).toMutableList()
        list.add(k)
        get(context).edit().putString(KEY_POINTS, list.joinToString(",")).apply()
    }

    fun clearCalibrationPoints(context: Context) =
        get(context).edit().putString(KEY_POINTS, "").apply()

    /**
     * The k actually used: the average of the stored calibration points,
     * falling back to the manually editable k (default 2.5).
     */
    fun effectiveK(context: Context): Double {
        val pts = calibrationPoints(context)
        return if (pts.isEmpty()) manualK(context) else pts.sum() / pts.size
    }

    fun deviceMode(context: Context): DeviceProfile.Mode {
        val name = get(context).getString(KEY_DEVICE_MODE, DeviceProfile.Mode.AUTO.name)
        return DeviceProfile.Mode.values().firstOrNull { it.name == name }
            ?: DeviceProfile.Mode.AUTO
    }
}
