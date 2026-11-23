package com.mcschummi.daylightlogger

/**
 * In-memory state shared between the camera engine, the service and the UI.
 * Simpler than binders/LiveData: the UI just polls it once per second.
 */
object LogState {

    var logging = false          // is the foreground service logging?

    // Latest sample values (0 until the first sample after Start)
    var lastLux = 0.0
    var lastEv100 = 0.0
    var lastPpfd = 0.0
    var apertureUsed = 0.0
    var isoUsed = 0.0
    var exposureUsedS = 0.0
    var sampleCountToday = 0

    // Today's Daily Light Integral (mol/m2/day)
    var dliToday = 0.0
    var maxLuxToday = 0.0
    var dayOfDli = DeviceProfile.dayKey(System.currentTimeMillis())

    // Today's lux history for the chart: (timeMillis, lux)
    val luxHistory = mutableListOf<Pair<Long, Double>>()

    fun addSample(lux: Double, ev100: Double, ppfd: Double,
                  iso: Double, exposureS: Double, aperture: Double,
                  intervalSeconds: Double) {
        val today = DeviceProfile.dayKey(System.currentTimeMillis())
        if (today != dayOfDli) {          // new day -> restart DLI and chart
            dayOfDli = today
            dliToday = 0.0
            luxHistory.clear()
            sampleCountToday = 0
        }
        lastLux = lux; lastEv100 = ev100; lastPpfd = ppfd
        isoUsed = iso; exposureUsedS = exposureS; apertureUsed = aperture
        dliToday += LightMath.dliStep(ppfd, intervalSeconds)
        if (lux > maxLuxToday) maxLuxToday = lux
        sampleCountToday++
        luxHistory.add(System.currentTimeMillis() to lux)
        if (luxHistory.size > 1440) luxHistory.removeAt(0)   // cap: 1 day at 60 s
    }

    fun clearReading() {
        lastLux = 0.0; lastEv100 = 0.0; lastPpfd = 0.0
        isoUsed = 0.0; exposureUsedS = 0.0; apertureUsed = 0.0
    }

    fun dliReset() {
        dliToday = 0.0
        maxLuxToday = 0.0
        sampleCountToday = 0
        luxHistory.clear()
    }

    fun resetAll() {
        clearReading()
        dliReset()
    }
}
