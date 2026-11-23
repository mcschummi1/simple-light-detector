package com.mcschummi.daylightlogger

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.ArrayDeque

/**
 * Owns the Camera2 camera. The (optional) preview surface from the main screen and
 * a tiny 640x480 YUV ImageReader are both attached to one repeating request.
 *
 * IMPORTANT: we never set exposure/ISO ourselves. CONTROL_AE_MODE_ON lets auto
 * exposure decide, and we READ BACK its chosen values from TotalCaptureResult on
 * every frame:
 *   SENSOR_EXPOSURE_TIME (ns), SENSOR_SENSITIVITY (ISO), LENS_APERTURE (f-number).
 */
object CameraEngine {

    private const val TAG = "CameraEngine"
    private const val IMAGE_WIDTH = 640
    private const val IMAGE_HEIGHT = 480

    data class FrameStat(val exposureSeconds: Double, val iso: Double, val aperture: Double)

    private var appContext: Context? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var previewSurface: Surface? = null   // optional small preview (aiming)
    private var settling = 0                      // frames skipped after opening
    private val recentFrames = ArrayDeque<FrameStat>()
    private var sampleScheduled = false
    private var restartAttempts = 0

    @Volatile var isOpen = false
        private set

    // Info for the Settings screen ("camera details" box)
    var cameraInfoText = "camera not open"
        private set

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
        val profile = DeviceProfile.resolve(context)
        settling = 0
        recentFrames.clear()
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
        sampleScheduled = false
        workerHandler?.removeCallbacksAndMessages(null)
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
        workerThread?.quitSafely()
        workerThread = null; workerHandler = null
        recentFrames.clear()
        cameraInfoText = "camera closed"
    }

    /** The main screen calls this when the preview TextureView is ready/hidden. */
    @Synchronized
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        if (isOpen && device != null) rebuildSession()
    }

    // ------------------------------------------------------------------ internals

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

    private fun buildReader() {
        reader?.close()
        val r = ImageReader.newInstance(
            IMAGE_WIDTH, IMAGE_HEIGHT,
            android.graphics.ImageFormat.YUV_420_888, 2
        )
        // We never decode the images: this surface only exists so the capture
        // request has a target. The numbers we need come from the capture result.
        r.setOnImageAvailableListener({ imgReader ->
            val img = try { imgReader.acquireLatestImage() } catch (_: Exception) { null }
            try { img?.close() } catch (_: Exception) {}
        }, workerHandler)
        reader = r
    }

    /** (Re)create the capture session with image reader + optional preview. */
    private fun rebuildSession() {
        val dev = device ?: return
        val camId = cameraId ?: return
        session?.close()
        session = null
        val targets = mutableListOf<Surface>(reader!!.surface)
        previewSurface?.let { if (it.isValid) targets.add(it) }
        try {
            dev.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startRepeating(s, dev, camId)
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
    private fun startRepeating(s: CameraCaptureSession, dev: CameraDevice, camId: String) {
        val chars = cameraManager!!.getCameraCharacteristics(camId)
        val profile = DeviceProfile.resolve(appContext!!)
        updateCameraInfo(camId, chars)

        val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(reader!!.surface)
        if (previewSurface?.isValid == true) builder.addTarget(previewSurface)
        // Auto exposure does all the work; we only read the result afterwards.
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
            CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)

        try {
            s.setRepeatingRequest(builder.build(), captureCallback, workerHandler)
        } catch (e: Exception) {
            Log.e(TAG, "setRepeatingRequest failed", e)
        }

        // One sample every N seconds, averaged over several frames.
        if (!sampleScheduled) {
            sampleScheduled = true
            workerHandler?.postDelayed(sampleTask, Prefs.intervalSeconds(appContext!!) * 1000L)
        }
    }

    private fun updateCameraInfo(camId: String, chars: CameraCharacteristics) {
        val hw = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            else -> "EXTERNAL"
        }
        val isoRange = chars.get(CameraCharacteristics.SENSOR_MAX_SENSITIVITY) ?: 0
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        cameraInfoText = "Camera id=$camId, hardware level: $hw, " +
                "max ISO: $isoRange, lenses: ${apertures?.contentToString()}"
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession, request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val profile = DeviceProfile.resolve(appContext ?: return)
            // Let auto-exposure settle: skip the first ~10 frames.
            if (settling < profile.framesToSkip) { settling++; return }

            val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)?.toDouble() ?: return
            val aperture = (result.get(CaptureResult.LENS_APERTURE)?.toDouble()
                ?: defaultAperture(profile, appContext!!))

            if (exposureNs <= 0) return
            val stat = FrameStat(exposureNs / 1_000_000_000.0, iso, aperture)
            recentFrames.addLast(stat)
            while (recentFrames.size > profile.framesPerSample) recentFrames.removeFirst()
        }
    }

    /** LENS_APERTURE missing -> first available aperture -> device default. */
    private fun defaultAperture(profile: DeviceProfile.Profile, context: Context): Double {
        cameraId ?: return profile.defaultAperture
        return try {
            val aps = cameraManager!!
                .getCameraCharacteristics(cameraId!!)
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            aps?.firstOrNull()?.toDouble() ?: profile.defaultAperture
        } catch (_: Exception) {
            profile.defaultAperture
        }
    }

    /** Runs on the camera thread once per interval: mean of recent frames -> sample. */
    private val sampleTask = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            val intervalSec = Prefs.intervalSeconds(context).toDouble()
            val k = Prefs.effectiveK(context)
            val profile = DeviceProfile.resolve(context)

            val frames = synchronized(recentFrames) { recentFrames.toList() }
            recentFrames.clear()

            if (frames.isEmpty()) {
                workerHandler?.postDelayed(this, (intervalSec * 1000).toLong())
                return
            }
            val meanExposure = frames.map { it.exposureSeconds }.average()
            val meanIso = frames.map { it.iso }.average()
            val meanAperture = frames.map { it.aperture }.average()

            val ev = LightMath.ev100(meanAperture, meanExposure, meanIso)
            val lux = LightMath.lux(ev, k)
            val ppfd = LightMath.ppfd(lux)

            LogState.addSample(lux, ev, ppfd, meanIso, meanExposure, meanAperture, intervalSec)
            if (LogState.logging) {
                try {
                    CsvStore.appendSample(context, meanExposure, meanIso, meanAperture, ev, lux, ppfd)
                } catch (e: Exception) {
                    Log.e(TAG, "CSV write failed", e)
                }
            }
            workerHandler?.postDelayed(this, (intervalSec * 1000).toLong())
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
