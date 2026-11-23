package com.mcschummi.daylightlogger

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.ArrayDeque

/**
 * Owns the Camera2 camera AND the timing of the whole measurement pipeline.
 *
 * CYCLE MACHINE (every N seconds, default 10):
 *   - cycles are aligned to EXACT round clock times (e.g. 12:00:00, 12:00:10)
 *     by computing the next epoch-millisecond multiple of the cycle length;
 *   - at the exact mark: save the collected frames, the NEXT capture starts
 *     with settings the exposure machine pre-dialed during the idle time;
 *   - if the camera/analysis is behind schedule, up to +20 s of overtime is
 *     allowed, then whatever data exists is force-saved (flagged in the CSV)
 *     and the machine re-syncs to the next round clock time.
 *
 * HYBRID AUTO-MANUAL:
 *   - on open it PROBES with auto-exposure, reads what the camera chose and
 *     TRIES to disable AE (manual ISO + exposure time);
 *   - LightStateMachine verifies per frame that the driver really honors the
 *     manual settings; if it silently ignores them, we fall back to reading
 *     whatever auto-exposure chose (CSV mode column says 'Auto-Mode');
 *   - while idle between cycles the histogram dialer keeps fine-tuning the
 *     manual settings, so at the next exact mark everything is already set.
 */
object CameraEngine {

    private const val TAG = "CameraEngine"
    private const val IMAGE_WIDTH = 640
    private const val IMAGE_HEIGHT = 480
    private const val MIN_FRAMES_PER_SAVE = 3
    private const val OVERTIME_MS = 20_000L   // safety net: max 20 s overtime

    data class FrameStat(val exposureSeconds: Double, val iso: Double, val aperture: Double)

    // ------------------------------------------------------------ live plumbing

    private var appContext: Context? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var previewSurface: Surface? = null   // optional small preview (aiming)
    private var settling = 0                      // frames skipped after opening
    private val recentFrames = ArrayDeque<FrameStat>()   // live UI readings
    private var lastSceneCheckMs = 0L
    private var lastHistMs = 0L
    private var restartAttempts = 0

    @Volatile var isOpen = false
        private set

    // Info for the Settings screen ("camera details" box)
    var cameraInfoText = "camera not open"
        private set

    /** True if the camera supports AE metering regions (center weighting). */
    @Volatile var aeMeteringSupported = false
        private set

    // ---------------------------------------- hybrid exposure state machine data

    private var manualHwSupported = false
    private var limits = LightStateMachine.Limits(1_000L, 100_000_000L, 100, 6400, 200_000_000L)
    private var probeCount = 0

    // collected frames of the CURRENT cycle (worker thread only)
    private val cycleFrames = ArrayList<FrameStat>()
    private var nextCycleMs = 0L        // exact round clock time of next save
    private var forcedDeadlineMs = 0L   // nextCycleMs + 20 s overtime

    // ------------------------------------------------------------------- open

    /** Called from the UI thread or the service. Safe to call again. */
    @Synchronized
    fun start(context: Context) {
        if (isOpen) return
        appContext = context.applicationContext
        cameraManager =
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = findBackCamera() ?: run {
            Log.e(TAG, "No back camera found")
            return
        }
        cameraId = id

        if (workerThread == null) {
            workerThread = HandlerThread("CameraEngine").also { it.start() }
            workerHandler = Handler(workerThread!!.looper)
        }
        settling = 0
        recentFrames.clear()
        cycleFrames.clear()
        LightStateMachine.reset()
        try {
            cameraManager!!.openCamera(id, stateCallback, workerHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "CAMERA permission missing", e)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed", e)
            scheduleRestart()   // try again in a second
        }
    }

    @Synchronized
    fun stop() {
        isOpen = false
        workerHandler?.removeCallbacksAndMessages(null)
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
        workerThread?.quitSafely()
        workerThread = null; workerHandler = null
        recentFrames.clear()
        cycleFrames.clear()
        nextCycleMs = 0; forcedDeadlineMs = 0
        lastSceneCheckMs = 0; lastHistMs = 0
        LogState.viewOpenPct = -1
        LogState.viewDarkSide = ""
        cameraInfoText = "camera closed"
    }

    /** The main screen calls this when the preview TextureView is ready/hidden. */
    @Synchronized
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        if (isOpen && device != null) rebuildSession()
    }

    /** Apply a changed sample interval immediately (called from Settings). */
    @Synchronized
    fun reschedule() {
        val handler = workerHandler ?: return
        handler.removeCallbacks(cycleTask)
        cycleFrames.clear()
        scheduleNextCycle(System.currentTimeMillis())
    }

    /**
     * EV100 from the LATEST camera frame (no need to wait for a full cycle).
     * Used for the live on-screen reading and quick calibration.
     */
    fun currentEv100(context: Context): Double? {
        val f = synchronized(recentFrames) { recentFrames.lastOrNull() } ?: return null
        return LightMath.ev100(f.aperture, f.exposureSeconds, f.iso)
    }

    // ---------------------------------------------------------------- internals

    private fun findBackCamera(): String? {
        cameraManager ?: return null
        for (id in cameraManager!!.cameraIdList) {
            val c = cameraManager!!.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return cameraManager!!.cameraIdList.firstOrNull()
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            isOpen = true
            restartAttempts = 0
            readCharacteristics()
            buildReader()
            rebuildSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            // Happens when another app grabs the camera. Try to get it back.
            Log.w(TAG, "camera disconnected, reopening")
            try { camera.close() } catch (_: Exception) {}
            device = null; session = null
            if (isOpen) scheduleRestart()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "camera error $error, reopening")
            try { camera.close() } catch (_: Exception) {}
            device = null; session = null
            if (isOpen) scheduleRestart()
        }
    }

    /** Read capabilities once: does THIS driver accept manual exposure at all? */
    private fun readCharacteristics() {
        val camId = cameraId ?: return
        characteristics = try {
            cameraManager!!.getCameraCharacteristics(camId)
        } catch (e: Exception) {
            Log.e(TAG, "characteristics failed", e); null
        }
        val chars = characteristics
        if (chars == null) { manualHwSupported = false; return }

        val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val frameRange = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)

        manualHwSupported =
            aeModes?.contains(CameraCharacteristics.CONTROL_AE_MODE_OFF) == true &&
            expRange != null && expRange.upper > 0 && isoRange != null

        limits = if (expRange != null && isoRange != null) {
            // Don't allow exposure longer than 100 ms (motion smear) or
            // shorter than 20 us (too noisy for the tiny J3 sensor).
            LightStateMachine.Limits(
                minExposureNs = maxOf(expRange.lower, 20_000L).coerceAtLeast(1L),
                maxExposureNs = minOf(expRange.upper, 100_000_000L),
                minIso = maxOf(isoRange.lower, 50),
                maxIso = isoRange.upper,
                maxFrameDurationNs = frameRange ?: 200_000_000L
            )
        } else {
            LightStateMachine.Limits(1_000L, 100_000_000L, 100, 6400, 200_000_000L)
        }
    }

    private fun buildReader() {
        reader?.close()
        val r = ImageReader.newInstance(
            IMAGE_WIDTH, IMAGE_HEIGHT,
            android.graphics.ImageFormat.YUV_420_888, 2
        )
        // Normally frames are not decoded: the reader surface only exists so
        // the capture request has a target; the numbers come from the capture
        // result. But once per 500 ms we DO look at the image (histogram dialer
        // + once per second the SceneCheck wall detector).
        r.setOnImageAvailableListener({ imgReader ->
            val img = try { imgReader.acquireLatestImage() } catch (_: Exception) { null }
            if (img != null) {
                val now = System.currentTimeMillis()
                if (now - lastHistMs > 500) {
                    lastHistMs = now
                    val (hist, total) = lumaHistogram(img)
                    if (LightStateMachine.isManual()) {
                        execute(LightStateMachine.onHistogram(hist, total, limits, now))
                    }
                }
                if (now - lastSceneCheckMs > 1000) {
                    lastSceneCheckMs = now
                    SceneCheck.analyze(img)?.let { rep ->
                        LogState.viewOpenPct = rep.openPct
                        LogState.viewDarkSide = rep.darkSide
                    }
                }
                try { img.close() } catch (_: Exception) {}
            }
        }, workerHandler)
        reader = r
    }

    /** Fast 64-bin histogram of the Y (luma) plane, subsampled every 8th pixel. */
    private fun lumaHistogram(image: android.media.Image): Pair<IntArray, Int> {
        val bins = IntArray(64)
        var total = 0
        return try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            var y = 0
            while (y < image.height) {
                val rowOff = y * rowStride
                var x = 0
                while (x < image.width) {
                    val v = buf.get(rowOff + x * pixelStride).toInt() and 0xFF
                    bins[v ushr 2]++
                    total++
                    x += 8
                }
                y += 8
            }
            bins to total
        } catch (_: Exception) {
            bins to 0
        }
    }

    /** (Re)create the capture session with image reader + optional preview. */
    private fun rebuildSession() {
        val dev = device ?: return
        session?.close()
        session = null
        val targets = mutableListOf<Surface>(reader!!.surface)
        if (previewSurface?.isValid == true) previewSurface?.let { targets.add(it) }
        try {
            dev.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startRepeating(s, dev)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "session configure failed")
                }
            }, workerHandler)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
        }
    }

    @SuppressLint("Recycle")
    private fun buildRequest(dev: CameraDevice): CaptureRequest.Builder {
        val chars = characteristics ?: cameraManager!!.getCameraCharacteristics(cameraId!!)
        val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(reader!!.surface)
        val ps = previewSurface
        if (ps != null && ps.isValid) builder.addTarget(ps)

        if (LightStateMachine.mode == LightStateMachine.CapMode.MANUAL) {
            // HYBRID: manual exposure - the driver was verified to accept it.
            val m = LightStateMachine.manual
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, m.exposureNs)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, m.iso)
            builder.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                (m.exposureNs * 2).coerceIn(limits.minExposureNs, limits.maxFrameDurationNs)
            )
        } else {
            // AUTO (probe / fallback): auto exposure does the work; we only
            // read the result afterwards.
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)

        // AE METERING (auto modes only): the window view has a huge dark wall
        // on the right half. Meter the CENTER so the wall does not push the
        // exposure up and blow out the sky.
        if (LightStateMachine.mode != LightStateMachine.CapMode.MANUAL) {
            val maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (maxAeRegions > 0 && active != null) {
                try {
                    val cw = active.width() / 4
                    val ch = active.height() / 4
                    val rect = Rect(
                        active.left + (active.width() - cw) / 2,
                        active.top + (active.height() - ch) / 2,
                        active.left + (active.width() + cw) / 2,
                        active.top + (active.height() + ch) / 2
                    )
                    builder.set(
                        CaptureRequest.CONTROL_AE_REGIONS,
                        arrayOf(MeteringRectangle(rect, 1000))
                    )
                    aeMeteringSupported = true
                } catch (e: Exception) {
                    Log.w(TAG, "center AE metering not applied", e)
                    aeMeteringSupported = false
                }
            } else {
                aeMeteringSupported = false
            }
        }
        return builder
    }

    /** Push the current mode/settings into the running session (no reconfigure). */
    private fun refreshRepeating() {
        val dev = device ?: return
        val s = session ?: return
        try {
            s.setRepeatingRequest(buildRequest(dev).build(), captureCallback, workerHandler)
        } catch (e: Exception) {
            Log.e(TAG, "setRepeatingRequest failed", e)
        }
    }

    private fun startRepeating(s: CameraCaptureSession, dev: CameraDevice) {
        updateCameraInfo()
        refreshRepeating()
        if (nextCycleMs == 0L) scheduleNextCycle(System.currentTimeMillis())
    }

    private fun updateCameraInfo() {
        val chars = characteristics ?: return
        val camId = cameraId ?: return
        val hw = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "EXTERNAL"
        }
        val isoMax = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper ?: 0
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        aeMeteringSupported = maxAeRegions > 0
        cameraInfoText = "Camera id=$camId, hardware level: $hw, " +
                "max ISO: $isoMax, lenses: ${apertures?.contentToString()}, " +
                "AE metering regions: $maxAeRegions, " +
                "manual exposure supported: $manualHwSupported, mode: ${LightStateMachine.mode}"
    }

    // --------------------------------------------------------- capture callback

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val profile = DeviceProfile.resolve(appContext ?: return)
            // Let auto-exposure settle: skip the first ~10 frames.
            if (settling < profile.framesToSkip) { settling++; return }

            val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.toInt() ?: return
            if (exposureNs <= 0 || iso <= 0) return
            val aperture = (result.get(CaptureResult.LENS_APERTURE)?.toDouble()
                ?: defaultAperture())

            val stat = FrameStat(exposureNs / 1_000_000_000.0, iso.toDouble(), aperture)
            synchronized(recentFrames) {
                recentFrames.addLast(stat)
                while (recentFrames.size > profile.framesPerSample) recentFrames.removeFirst()
            }
            // Worker-thread-only collection for the next cycle save.
            cycleFrames.add(stat)

            when (LightStateMachine.mode) {
                LightStateMachine.CapMode.PROBE -> {
                    probeCount++
                    if (probeCount >= 3) {
                        execute(
                            LightStateMachine.onProbeFrame(exposureNs, iso, manualHwSupported)
                        )
                    }
                }
                LightStateMachine.CapMode.MANUAL -> {
                    val reported = LightStateMachine.ManualState(exposureNs, iso)
                    execute(
                        LightStateMachine.onManualResult(
                            LightStateMachine.manual, reported, System.currentTimeMillis()
                        )
                    )
                }
                else -> { /* AUTO_FALLBACK: nothing to verify */ }
            }
        }
    }

    /** Execute a state-machine decision (switch request mode/settings). */
    private fun execute(action: LightStateMachine.Action) {
        when (action) {
            is LightStateMachine.Action.SwitchManual, is LightStateMachine.Action.Dial,
            is LightStateMachine.Action.SwitchAuto -> refreshRepeating()
            LightStateMachine.Action.None -> { /* nothing */ }
        }
    }

    /** LENS_APERTURE missing -> first available aperture -> device default. */
    private fun defaultAperture(): Double {
        val profile = DeviceProfile.resolve(appContext ?: return 2.2)
        val chars = characteristics
        if (chars != null) {
            val aps = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            aps?.firstOrNull()?.let { return it.toDouble() }
        }
        return profile.defaultAperture
    }

    /**
     * Average that ignores bad frames: sorts the values and drops the lowest
     * and highest 25% (auto-exposure jumps mid-burst, backlights flicker...).
     */
    private fun trimmedMean(values: List<Double>): Double {
        if (values.size <= 2) return values.average()
        val sorted = values.sorted()
        val cut = sorted.size / 4
        if (cut == 0) return sorted.average()
        return sorted.subList(cut, sorted.size - cut).average()
    }

    // ------------------------------------------------------- predictive cycles

    /**
     * Align the next cycle to an exact round clock time: the next epoch-ms
     * multiple of the cycle length. For a 10 s cycle that is 12:00:00,
     * 12:00:10, 12:00:20...
     */
    private fun scheduleNextCycle(fromMs: Long) {
        val ctx = appContext ?: return
        val cycle = Prefs.intervalSeconds(ctx).toLong() * 1000L
        val next = ((fromMs / cycle) + 1) * cycle
        nextCycleMs = next
        forcedDeadlineMs = next + OVERTIME_MS
        val delay = (next - fromMs).coerceAtLeast(100L)
        workerHandler?.let {
            it.removeCallbacks(cycleTask)
            it.postDelayed(cycleTask, delay)
        }
        Log.d(TAG, "next cycle at $next (in ${delay} ms)")
    }

    private val cycleTask = Runnable { runCycleTick() }

    private fun runCycleTick() {
        val ctx = appContext ?: return
        if (!isOpen || device == null) return
        val now = System.currentTimeMillis()

        if (now < nextCycleMs) {   // spurious early tick: wait for the exact mark
            workerHandler?.postDelayed(cycleTask, nextCycleMs - now)
            return
        }

        if (cycleFrames.size < MIN_FRAMES_PER_SAVE) {
            // Camera/analysis struggling: OVERTIME. Re-check every second, at
            // most 20 s, then force-save whatever exists and re-sync.
            if (now < forcedDeadlineMs) {
                workerHandler?.postDelayed(cycleTask, 1000)
                return
            }
            if (cycleFrames.isNotEmpty()) saveCycle(ctx, forced = true)
            cycleFrames.clear()
            scheduleNextCycle(now)   // reset for the next round clock time
            return
        }

        // NORMAL PATH: the exact mark hit and data is ready. The dialer already
        // pre-calculated and applied the next settings during the idle time.
        saveCycle(ctx, forced = false)
        cycleFrames.clear()
        scheduleNextCycle(now)
    }

    /** Write one averaged sample to LogState (and the CSV when logging). */
    private fun saveCycle(context: Context, forced: Boolean) {
        val frames = ArrayList(cycleFrames)
        if (frames.isEmpty()) return
        val intervalSec = (Prefs.intervalSeconds(context)).toDouble()
        val k = Prefs.effectiveK(context)

        val meanExposure = trimmedMean(frames.map { it.exposureSeconds })
        val meanIso = trimmedMean(frames.map { it.iso })
        val meanAperture = frames.map { it.aperture }.average()

        val ev = LightMath.ev100(meanAperture, meanExposure, meanIso)
        val lux = LightMath.lux(ev, k)
        val ppfd = LightMath.ppfd(lux)

        // CSV capture_status: Normal | Auto-Mode (driver ignored manual) |
        // Forced-Save (the 20 s overtime safety net had to intervene).
        val captureStatus = when {
            forced -> "Forced-Save"
            LightStateMachine.mode == LightStateMachine.CapMode.AUTO_FALLBACK -> "Auto-Mode"
            else -> "Normal"
        }

        LogState.addSample(lux, ev, ppfd, meanIso, meanExposure, meanAperture, intervalSec)
        if (LogState.logging) {
            try {
                CsvStore.appendSample(
                    context, meanExposure, meanIso, meanAperture, ev, lux, ppfd,
                    k, WeatherClient.latest, captureStatus
                )
            } catch (e: Exception) {
                Log.e(TAG, "CSV write failed", e)
            }
        }
    }

    /** Camera died -> reopen with a small backoff (max ~10 tries). */
    private fun scheduleRestart() {
        if (restartAttempts++ > 10) { Log.e(TAG, "giving up reopening camera"); return }
        val delay = 1000L * restartAttempts
        workerHandler?.postDelayed({
            val ctx = appContext ?: return@postDelayed
            stop()
            start(ctx)
        }, delay)
    }
}
