package com.mcschummi.daylightlogger

import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen (opened with the gear icon on the main screen).
 * Here the user can update:
 *   - sample interval (one sample every N seconds)
 *   - the manual calibration constant k (used when no calibration points exist)
 *   - device mode: Auto / Galaxy J3 (2017) dedicated / Generic (optional mode)
 *   - keep screen on while the app is open
 * plus: camera details, Export CSV and Clear data (also reachable from main).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var editInterval: EditText
    private lateinit var editManualK: EditText
    private lateinit var radioMode: RadioGroup
    private lateinit var switchScreenOn: Switch
    private lateinit var txtCameraInfo: TextView
    private lateinit var txtDeviceInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        editInterval = findViewById(R.id.editInterval)
        editManualK = findViewById(R.id.editManualK)
        radioMode = findViewById(R.id.radioMode)
        switchScreenOn = findViewById(R.id.switchScreenOn)
        txtCameraInfo = findViewById(R.id.txtCameraInfo)
        txtDeviceInfo = findViewById(R.id.txtDeviceInfo)

        editInterval.setText(Prefs.intervalSeconds(this).toString())
        editManualK.setText(Prefs.manualK(this).toString())
        radioMode.check(
            when (Prefs.deviceMode(this)) {
                DeviceProfile.Mode.J3_DEDICATED -> R.id.radioJ3
                DeviceProfile.Mode.GENERIC -> R.id.radioGeneric
                else -> R.id.radioAuto
            }
        )
        switchScreenOn.isChecked = Prefs.get(this).getBoolean(Prefs.KEY_KEEP_SCREEN_ON, false)

        val profile = DeviceProfile.resolve(this)
        txtDeviceInfo.text = getString(
            R.string.device_info,
            android.os.Build.MANUFACTURER, android.os.Build.MODEL,
            if (DeviceProfile.isGalaxyJ3_2017()) "yes" else "no", profile.label
        )
        txtCameraInfo.text = getString(R.string.camera_info_prefix) + "\n" + cameraDetails()

        findViewById<Button>(R.id.btnCalibration).setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            val file = CsvStore.todayFile(this)
            if (!file.exists()) {
                Toast.makeText(this, R.string.no_data, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, CsvStore.todayShareUri(this@SettingsActivity))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.export_title)))
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAndFinish() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClearData() }
    }

    private fun cameraDetails(): String {
        return try {
            val cm = getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val sb = StringBuilder()
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                val facing = if (c.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK) "back" else "front"
                val hw = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    else -> "?"
                }
                sb.append("id=$id $facing hw=$hw ")
                sb.append(
                    "maxISO=${c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper}\n"
                )
            }
            sb.toString().trim()
        } catch (e: Exception) {
            getString(R.string.camera_details_error)
        }
    }

    private fun saveAndFinish() {
        val interval = editInterval.text.toString().toIntOrNull()
        if (interval == null || interval < 5 || interval > 3600) {
            Toast.makeText(this, R.string.bad_interval, Toast.LENGTH_SHORT).show()
            return
        }
        val k = editManualK.text.toString().toDoubleOrNull()
        if (k == null || k <= 0.0 || k > 500.0) {
            Toast.makeText(this, R.string.bad_k, Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.get(this).edit()
            .putInt(Prefs.KEY_INTERVAL, interval)
            .putString(Prefs.KEY_MANUAL_K, k.toString())
            .putBoolean(Prefs.KEY_KEEP_SCREEN_ON, switchScreenOn.isChecked)
            .putString(
                Prefs.KEY_DEVICE_MODE,
                when (radioMode.checkedRadioButtonId) {
                    R.id.radioJ3 -> DeviceProfile.Mode.J3_DEDICATED.name
                    R.id.radioGeneric -> DeviceProfile.Mode.GENERIC.name
                    else -> DeviceProfile.Mode.AUTO.name
                }
            )
            .apply()
        CameraEngine.reschedule()   // new interval takes effect immediately
        finish()
    }

    private fun confirmClearData() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_message)
            .setPositiveButton(R.string.clear_yes) { _, _ ->
                CsvStore.clearAll(this)
                Toast.makeText(this, R.string.cleared_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
