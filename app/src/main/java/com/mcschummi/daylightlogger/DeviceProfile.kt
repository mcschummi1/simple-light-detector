package com.mcschummi.daylightlogger

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Decides tuning values depending on which phone we run on.
 *
 * The app is DEDICATED to the Samsung Galaxy J3 (2017) but has an optional
 * compatibility mode so it also works on other devices. On the J3:
 *   - the camera is 5 MP with a FIXED FOCUS, f/2.2, LIMITED hardware level,
 *   - some J3 firmware omits LENS_APERTURE in capture results, hence the
 *     hardcoded f/2.2 fallback below.
 */
object DeviceProfile {

    enum class Mode { AUTO, J3_DEDICATED, GENERIC }

    data class Profile(
        val isJ3: Boolean,                 // dedicated J3 tuning active?
        val label: String,                 // what to show the user
        val defaultAperture: Double,       // used if the camera refuses to tell us
        val framesToSkip: Int,             // let auto-exposure settle first (~10)
        val framesPerSample: Int,          // average N frames per sample
        val saturationEv100: Double        // warn the user about direct sun at this EV
    )

    private val J3_DEVICES = setOf(
        "j3y17lte", "j3y17ltechn", "j3topelte", "j3topeltekt", "j3popelte", "j3lte", "j3xlte"
    )

    /** Is this phone a Galaxy J3 (2017) family device (SM-J330 / SM-J327)? */
    fun isGalaxyJ3_2017(): Boolean {
        val model = android.os.Build.MODEL ?: return false
        val device = android.os.Build.DEVICE ?: ""
        return android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                (model.startsWith("SM-J33") || device in J3_DEVICES)
    }

    fun resolve(context: Context): Profile {
        val detected = isGalaxyJ3_2017()
        val mode = Prefs.deviceMode(context)
        return when (mode) {
            Mode.J3_DEDICATED -> j3Profile("Galaxy J3 (2017) - dedicated mode (forced)")
            Mode.GENERIC -> genericProfile("Generic device - compatibility mode (forced)")
            Mode.AUTO ->
                if (detected) j3Profile("Galaxy J3 (2017) - dedicated mode")
                else genericProfile("Other device - compatibility mode (auto)")
        }
    }

    private fun j3Profile(label: String) = Profile(
        isJ3 = true, label = label,
        defaultAperture = 2.2,          // J3 2017 has a fixed f/2.2 lens
        framesToSkip = 10,
        framesPerSample = 12,
        saturationEv100 = 14.5          // f/2.2 + tiny sensor saturates early in direct sun
    )

    private fun genericProfile(label: String) = Profile(
        isJ3 = false, label = label,
        defaultAperture = 2.0,          // educated guess for other phones
        framesToSkip = 10,
        framesPerSample = 16,
        saturationEv100 = 15.0
    )

    /** Human readable timestamp used in the CSV (ISO 8601). */
    fun timestamp_ISO8601(epochMillis: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))

    /** Just "2024-05-01" so we can split the CSV per day. */
    fun dayKey(epochMillis: Long): String =
        DateTimeFormatter.ISO_LOCAL_DATE
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
}
