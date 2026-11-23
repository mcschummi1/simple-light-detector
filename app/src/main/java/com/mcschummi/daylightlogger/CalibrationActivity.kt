package com.mcschummi.daylightlogger

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Calibration screen.
 *
 * 1. Put this phone and a reference lux meter (or another calibrated phone) side
 *    by side, both pointing at the same light.
 * 2. Read the current EV100 shown here.
 * 3. Type the reference lux value. The app computes:
 *        k = reference_lux / 2^EV100
 * 4. Several points can be stored; the app uses THEIR AVERAGE as k.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var editReference: EditText
    private lateinit var txtCurrentEv: TextView
    private lateinit var txtPoints: TextView
    private lateinit var txtExplain: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        editReference = findViewById(R.id.editReference)
        txtCurrentEv = findViewById(R.id.txtCurrentEv)
        txtPoints = findViewById(R.id.txtPoints)
        txtExplain = findViewById(R.id.txtExplain)

        findViewById<Button>(R.id.btnAddPoint).setOnClickListener { addPoint() }
        findViewById<Button>(R.id.btnClearPoints).setOnClickListener {
            Prefs.clearCalibrationPoints(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        // Make sure the camera is running so we have a live EV100 value.
        // (The main screen usually opened it already; this is a safety net.)
        CameraEngine.start(this)
        refresh()
        refreshEvOnly()   // keeps the EV line fresh once per second
    }

    override fun onPause() {
        super.onPause()
        // Keep the camera open while the logging service runs; otherwise release it.
        if (!LogState.logging) CameraEngine.stop()
    }

    private fun refresh() {
        // Prefer the live EV from the newest camera frame so calibration is
        // instant; fall back to the last logged sample.
        val liveEv = CameraEngine.currentEv100(this)
        val ev = liveEv ?: LogState.lastEv100.takeIf { LogState.lastLux > 0.0 }
        txtCurrentEv.text = if (ev != null) {
            getString(R.string.calib_current_ev, ev)
        } else {
            getString(R.string.calib_no_camera)
        }

        val points = Prefs.calibrationPoints(this)
        txtPoints.text = if (points.isEmpty()) {
            getString(R.string.calib_no_points)
        } else {
            getString(R.string.calib_points_header) + "\n" +
                points.mapIndexed { i, k -> "#${i + 1}: k = $k" }.joinToString("\n") +
                "\n" + getString(R.string.calib_avg, points.average())
        }
        txtExplain.text = getString(R.string.calib_explain)
    }

    /** Update only the EV line while staying on this screen. */
    private fun refreshEvOnly() {
        val liveEv = CameraEngine.currentEv100(this)
        val ev = liveEv ?: LogState.lastEv100.takeIf { LogState.lastLux > 0.0 }
        if (ev != null) {
            txtCurrentEv.text = getString(R.string.calib_current_ev, ev)
            txtCurrentEv.postDelayed({ if (!isFinishing) refreshEvOnly() }, 1000)
        }
    }

    private fun addPoint() {
        val refLux = editReference.text.toString().toDoubleOrNull()
        if (refLux == null || refLux <= 0) {
            Toast.makeText(this, R.string.bad_ref_lux, Toast.LENGTH_SHORT).show()
            return
        }
        val ev = CameraEngine.currentEv100(this)
            ?: LogState.lastEv100.takeIf { LogState.lastLux > 0.0 }
        if (ev == null) {
            Toast.makeText(this, R.string.calib_no_camera, Toast.LENGTH_LONG).show()
            return
        }
        val k = refLux / Math.pow(2.0, ev)
        Prefs.addCalibrationPoint(this, k)
        editReference.setText("")
        refresh()
        Toast.makeText(this, getString(R.string.calib_saved, k), Toast.LENGTH_LONG).show()
    }
}
