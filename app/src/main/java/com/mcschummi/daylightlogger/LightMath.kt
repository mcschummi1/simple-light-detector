package com.mcschummi.daylightlogger

import kotlin.math.ln
import kotlin.math.pow

/**
 * The whole measurement algorithm in one place.
 *
 * The Galaxy J3 (2017) has no ambient light sensor and its Camera2 hardware level
 * is LIMITED, so we never control the camera manually. We let auto-exposure do its
 * thing and then READ BACK what the camera chose:
 *
 *   exposure time t (seconds), ISO, and aperture N (f-number).
 *
 * From the "sunny 16" rules of exposure:
 *   EV100 = log2(N^2 / t) - log2(ISO / 100)
 *   lux   = k * 2^EV100     (k is a calibration constant, default 2.5)
 *   PPFD  = lux / 54        (approximation for daylight, in umol/m2/s)
 */
object LightMath {

    fun log2(x: Double): Double = ln(x) / ln(2.0)

    /** Convert brute camera settings to an EV100 value. */
    fun ev100(aperture: Double, exposureSeconds: Double, iso: Double): Double {
        // Guard against crazy values (0 exposure while the AE is settling etc.)
        val t = exposureSeconds.coerceIn(1e-6, 60.0)
        val n = aperture.coerceIn(0.5, 32.0)
        val i = iso.coerceIn(1.0, 100000.0)
        return log2(n * n / t) - log2(i / 100.0)
    }

    /** lux = k * 2^EV100 with the user calibration constant k. */
    fun lux(ev100: Double, k: Double): Double = k * 2.0.pow(ev100)

    /** Photosynthetic Photon Flux Density, roughly lux/54 for daylight. */
    fun ppfd(lux: Double): Double = lux / 54.0

    /** Daily Light Integral contribution of one sample: ppmol -> mol = /1e6. */
    fun dliStep(ppfd: Double, intervalSeconds: Double): Double =
        ppfd * intervalSeconds / 1_000_000.0
}
