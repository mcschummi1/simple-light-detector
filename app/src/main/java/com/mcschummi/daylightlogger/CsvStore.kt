package com.mcschummi.daylightlogger

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * One CSV file per day, in app storage:
 *   /data/data/<pkg>/files/logs/daylight_2024-05-01.csv
 *
 * Columns: timestamp, exposure_time_s, iso, aperture, ev100, lux, ppfd
 */
object CsvStore {

    private fun dir(context: Context): File {
        val d = File(context.filesDir, "logs")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun fileForDay(context: Context, dayKey: String): File =
        File(dir(context), "daylight_$dayKey.csv")

    fun todayFile(context: Context): File =
        fileForDay(context, DeviceProfile.dayKey(System.currentTimeMillis()))

    /** Append one sample as a CSV row. Returns the file the row went into. */
    fun appendSample(
        context: Context,
        exposureSeconds: Double, iso: Double, aperture: Double,
        ev100: Double, lux: Double, ppfd: Double
    ): File {
        val f = todayFile(context)
        if (!f.exists()) {
            f.writeText("timestamp,exposure_time_s,iso,aperture,ev100,lux,ppfd\n")
        }
        val ts = DeviceProfile.timestamp_ISO8601(System.currentTimeMillis())
        f.appendText(
            String.format(
                Locale.US,
                "%s,%.8f,%.0f,%.2f,%.3f,%.1f,%.2f\n",
                ts, exposureSeconds, iso, aperture, ev100, lux, ppfd
            )
        )
        return f
    }

    /** All log files, oldest first. */
    fun allLogs(context: Context): List<File> =
        dir(context).listFiles()?.sortedBy { it.name } ?: emptyList()

    fun clearAll(context: Context) {
        allLogs(context).forEach { it.delete() }
        LogState.resetAll()
    }

    /** Content URI so the share sheet can attach the CSV file. */
    fun todayShareUri(context: Context): Uri =
        FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", todayFile(context)
        )
}
