package com.mcschummi.daylightlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Main screen.
 *  - live camera display (optional, small, for aiming the phone)
 *  - current lux / EV100 / PPFD / today's DLI / current k
 *  - line chart of today's lux
 *  - Start / Stop button, Export CSV, Clear data
 *  - gear icon in the top-right corner -> Settings
 */
class MainActivity : AppCompatActivity() {

    private lateinit var preview: TextureView
    private lateinit var chart: LuxChartView
    private lateinit var btnStartStop: Button
    private lateinit var btnPreview: Button
    private lateinit var btnExport: Button
    private lateinit var btnClear: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtLux: TextView
    private lateinit var txtEv: TextView
    private lateinit var txtPpfd: TextView
    private lateinit var txtDli: TextView
    private lateinit var txtK: TextView
    private lateinit var txtDevice: TextView

    private var previewEnabled = true   // stored only for this session
    private val uiHandler = Handler(Looper.getMainLooper())
    private var wantPreviewSurface = false

    // Poll LogState once a second and refresh the numbers on screen.
    private val uiTick = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            if (wantPreviewSurface) attachPreview(Surface(st))
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            attachPreview(null)
            return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        chart = findViewById(R.id.chart)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnPreview = findViewById(R.id.btnPreview)
        btnExport = findViewById(R.id.btnExport)
        btnClear = findViewById(R.id.btnClear)
        txtStatus = findViewById(R.id.txtStatus)
        txtLux = findViewById(R.id.txtLux)
        txtEv = findViewById(R.id.txtEv)
        txtPpfd = findViewById(R.id.txtPpfd)
        txtDli = findViewById(R.id.txtDli)
        txtK = findViewById(R.id.txtK)
        txtDevice = findViewById(R.id.txtDevice)

        val gear = findViewById<ImageButton>(R.id.btnGear)
        gear.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        txtDevice.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        btnStartStop.setOnClickListener { onStartStopClicked() }
        btnPreview.setOnClickListener { togglePreview() }
        btnExport.setOnClickListener { exportCsv() }
        btnClear.setOnClickListener { confirmClear() }

        preview.surfaceTextureListener = surfaceListener
        updateStartStopButton()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        if (!cameraPermissionGranted()) {
            // First run: ask the user for CAMERA (+ notifications on Android 13+).
            requestPermissions(
                buildList {
                    add(Manifest.permission.CAMERA)
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray(), REQ_PERMS
            )
        }
        if (previewEnabled) showPreview()
        uiHandler.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiTick)
        // While the foreground service logs, the camera MUST stay open.
        // Otherwise (no logging) release it so other apps can use the camera.
        attachPreview(null)
        if (!LogState.logging) CameraEngine.stop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (cameraPermissionGranted()) {
                if (previewEnabled) showPreview()
            } else {
                Toast.makeText(this, R.string.camera_needed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun cameraPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun onStartStopClicked() {
        if (!cameraPermissionGranted()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_PERMS)
            return
        }
        if (LogState.logging) {
            stopService(Intent(this, LoggingService::class.java))
            LogState.dliReset()   // fresh start next time
            Toast.makeText(this, R.string.stopped_toast, Toast.LENGTH_SHORT).show()
        } else {
            ContextCompat.startForegroundService(
                this, Intent(this, LoggingService::class.java)
            )
            Toast.makeText(this, R.string.started_toast, Toast.LENGTH_SHORT).show()
        }
        updateStartStopButton()
    }

    private fun togglePreview() {
        previewEnabled = !previewEnabled
        if (previewEnabled) showPreview() else hidePreview()
    }

    private fun showPreview() {
        preview.visibility = View.VISIBLE
        wantPreviewSurface = true
        if (preview.isAvailable) {
            attachPreview(Surface(preview.surfaceTexture))
        }
        btnPreview.setText(R.string.hide_preview)
    }

    private fun hidePreview() {
        wantPreviewSurface = false
        attachPreview(null)
        preview.visibility = View.GONE
        btnPreview.setText(R.string.show_preview)
        // No logging running -> give the camera back.
        if (!LogState.logging) CameraEngine.stop()
    }

    private fun attachPreview(surface: Surface?) {
        CameraEngine.setPreviewSurface(surface)
        if (surface != null && !cameraPermissionGranted()) return
        if (surface != null && !CameraEngine.isOpen) CameraEngine.start(this)
    }

    private fun exportCsv() {
        val file = CsvStore.todayFile(this)
        if (!file.exists()) {
            Toast.makeText(this, R.string.no_data, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = CsvStore.todayShareUri(this)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_subject))
        }
        startActivity(Intent.createChooser(send, getString(R.string.export_title)))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_message)
            .setPositiveButton(R.string.clear_yes) { _, _ ->
                CsvStore.clearAll(this)
                LogState.dliReset()
                refreshUi()
                Toast.makeText(this, R.string.cleared_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------------- UI

    private fun updateStartStopButton() {
        btnStartStop.setText(if (LogState.logging) R.string.stop else R.string.start)
    }

    private fun refreshUi() {
        updateStartStopButton()
        val st = LogState
        if (st.lastLux <= 0.0) {
            txtStatus.setText(if (st.logging) R.string.status_first_sample else R.string.status_stopped)
            txtLux.text = getString(R.string.lux_value, 0.0)
            txtEv.text = getString(R.string.ev_value, 0.0)
            txtPpfd.text = getString(R.string.ppfd_value, 0.0)
        } else {
            txtStatus.text = getString(
                R.string.status_running,
                DeviceProfile.dayKey(System.currentTimeMillis()), st.sampleCountToday
            )
            txtLux.text = getString(R.string.lux_value, st.lastLux)
            txtEv.text = getString(R.string.ev_value, st.lastEv100)
            txtPpfd.text = getString(R.string.ppfd_value, st.lastPpfd)
            // Direct sun melts a small phone sensor: warn the user.
            val profile = DeviceProfile.resolve(this)
            if (st.lastEv100 >= profile.saturationEv100) txtLux.append(getString(R.string.saturated))
        }
        txtDli.text = getString(R.string.dli_value, st.dliToday)
        txtK.text = getString(R.string.k_value, Prefs.effectiveK(this))
        txtDevice.text = DeviceProfile.resolve(this).label
        chart.invalidate()   // redraw today's lux curve
    }

    companion object {
        const val REQ_PERMS = 42
    }
}
