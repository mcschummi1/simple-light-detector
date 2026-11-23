package com.mcschummi.daylightlogger

import kotlin.math.log2

/**
 * HYBRID AUTO-MANUAL EXPOSURE STATE MACHINE (pure logic, no Android calls).
 *
 * The J3's camera driver MAY accept manual exposure (AE off + fixed ISO/time)
 * even on the LIMITED hardware level - but some Samsung firmwares silently
 * ignore it and keep auto-exposure running. So we:
 *
 *  1. PROBE  - run auto first, read what AE chose, then try to switch it off.
 *  2. VERIFY - every frame, compare REPORTED exposure/ISO against REQUESTED.
 *              5 mismatches in a row -> driver ignores manual -> FALLBACK.
 *  3. DIAL   - a brightness histogram gives one knob: increase or decrease
 *              exposure when highlights are blown or shadows crushed.
 *
 * CameraEngine executes the actions (rebuild capture requests) and feeds back
 * per-frame results; this class only decides.
 */
object LightStateMachine {

    enum class CapMode { PROBE, MANUAL, AUTO_FALLBACK }

    /** What CameraEngine should do next. */
    sealed class Action {
        object None : Action()
        data class SwitchManual(val exposureNs: Long, val iso: Int) : Action()
        object SwitchAuto : Action()
        data class Dial(val newExposureNs: Long, val newIso: Int) : Action()
    }

    data class Limits(
        val minExposureNs: Long, val maxExposureNs: Long,
        val minIso: Int, val maxIso: Int,
        val maxFrameDurationNs: Long
    )

    data class ManualState(
        val exposureNs: Long, val iso: Int
    )

    private const val VERIFY_MISMATCH_LIMIT = 5

    // ---- mutable state ------------------------------------------------------
    @Volatile var mode = CapMode.PROBE
        private set
    @Volatile var manual = ManualState(10_000_000L, 200)   // 1/100 s, ISO 200
        private set
    private var mismatches = 0
    private var lastDialMs = 0L

    fun reset() {
        mode = CapMode.PROBE
        manual = ManualState(10_000_000L, 200)
        mismatches = 0
        lastDialMs = 0
    }

    /** Probe step: AE ran for a few frames - lock its choice as manual start. */
    fun onProbeFrame(exposureNs: Long, iso: Int, manualSupportedByHw: Boolean): Action {
        if (!manualSupportedByHw) {
            mode = CapMode.AUTO_FALLBACK
            return Action.SwitchAuto
        }
        if (exposureNs <= 0 || iso <= 0) return Action.None
        mode = CapMode.MANUAL
        manual = ManualState(exposureNs, iso)
        return Action.SwitchManual(exposureNs, iso)
    }

    /** Verify one frame in MANUAL mode. mode flips to AUTO_FALLBACK on 5 misses.
     *  Right after a dial the still-arriving frames report the OLD settings,
     *  so verification is paused for 500 ms to avoid false fallbacks. */
    fun onManualResult(requested: ManualState, reported: ManualState, nowMs: Long): Action {
        if (nowMs - lastDialMs < 500) return Action.None
        val expOk = Math.abs(reported.exposureNs - requested.exposureNs) <=
            (requested.exposureNs * 0.05).toLong()
        val isoOk = Math.abs(reported.iso - requested.iso) <= 5
        if (expOk && isoOk) {
            mismatches = 0
            return Action.None
        }
        mismatches++
        if (mismatches >= VERIFY_MISMATCH_LIMIT) {
            mode = CapMode.AUTO_FALLBACK
            return Action.SwitchAuto
        }
        return Action.None
    }

    /** Accepted manual values changed nothing? (driver echoes AE's choice) */
    fun isManual(): Boolean = mode == CapMode.MANUAL

    /**
     * DIAL from a histogram of the Y (luma) plane, 64 bins.
     * Returns the new exposure to try, or MotionAction.None if it looks good
     * and/or we dialed too recently (max one dial every 2 s).
     */
    fun onHistogram(
        hist: IntArray, total: Int, limits: Limits, nowMs: Long
    ): Action {
        if (mode != CapMode.MANUAL || total <= 0) return Action.None
        if (nowMs - lastDialMs < 2000) return Action.None   // dial slowly, calmly

        // fraction of pixels blown out (3 brightest bins) / crushed (3 darkest)
        var high = 0L; var low = 0L
        for (i in hist.indices) {
            when {
                i >= hist.size - 3 -> high += hist[i]
                i < 3 -> low += hist[i]
            }
        }
        val highFrac = high.toFloat() / total
        val lowFrac = low.toFloat() / total

        // Half-EV steps. Highlights have priority: blown pixels carry no data.
        val evDelta = when {
            highFrac > 0.02f -> -0.5f
            lowFrac > 0.25f && highFrac < 0.005f -> +0.5f
            else -> 0f
        }
        if (evDelta == 0f) return Action.None
        lastDialMs = nowMs

        var t = manual.exposureNs * Math.pow(2.0, evDelta.toDouble())
        var iso = manual.iso
        // Prefer turning exposure time; if at its limit, move ISO instead.
        if (t > limits.maxExposureNs) {
            val overshoot = log2(t / limits.maxExposureNs)
            t = limits.maxExposureNs.toDouble()
            iso = (iso * Math.pow(2.0, overshoot)).toInt().coerceIn(limits.minIso, limits.maxIso)
        } else if (t < limits.minExposureNs) {
            val shortfall = log2(limits.minExposureNs / t)
            t = limits.minExposureNs.toDouble()
            iso = (iso * Math.pow(2.0, -shortfall)).toInt().coerceIn(limits.minIso, limits.maxIso)
        }
        manual = ManualState(t.toLong().coerceIn(limits.minExposureNs, limits.maxExposureNs), iso)
        return Action.Dial(manual.exposureNs, iso)
    }
}
