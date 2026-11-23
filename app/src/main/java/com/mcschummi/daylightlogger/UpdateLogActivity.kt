package com.mcschummi.daylightlogger

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * UPDATE LOG - shows every released version with its notes and a button that
 * opens the GitHub release page, so you can always re-download an OLDER APK
 * and go back to a previous version (a downgrade needs the old app to be
 * uninstalled first; updates install over without uninstalling).
 */
class UpdateLogActivity : AppCompatActivity() {

    private data class Entry(val version: String, val date: String, val notes: String)

    private val entries = listOf(
        Entry(
            "v0.3.0", "in development",
            "- Open-Meteo weather context: cloud cover, UV index, temperature " +
                "(Timisoara), with exponential-backoff retries and instant " +
                "retry when Wi-Fi returns; CSV fallback 'Pending' while offline\n" +
                "- Exact CSV schema with calibration_k and capture_status " +
                "(Normal / Auto-Mode / Forced-Save)\n" +
                "- In-app update log + older-version downloads (this screen)"
        ),
        Entry(
            "v0.2.0", "2026-11",
            "- Predictive 10 s state machine aligned to exact clock marks\n" +
                "- Hybrid auto-manual exposure with per-frame driver " +
                "verification and Auto-Mode fallback\n" +
                "- Luma histogram dialer (blown highlight / crushed shadow " +
                "correction, pre-calculated headstart settings)\n" +
                "- 20 s overtime safety net with Forced-Save flag"
        ),
        Entry(
            "v0.1.0", "2026-11",
            "- First release: EV100/lux/PPFD/DLI camera logger for the " +
                "Galaxy J3 (2017)\n" +
                "- Live camera display, view check (wall detection), center " +
                "AE metering, foreground service, 24 h logging, CSV export"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_log)

        val container = findViewById<LinearLayout>(R.id.updateLogContainer)
        entries.forEach { entry -> container.addView(cardFor(entry)) }
    }

    private fun cardFor(entry: Entry): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        box.addView(TextView(this).apply {
            text = "${entry.version}   (${entry.date})"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = entry.notes
            textSize = 13f
            setTextColor(0xFF616161.toInt())
            setPadding(0, dp(4), 0, dp(8))
        })
        box.addView(Button(this@UpdateLogActivity).apply {
            text = getString(R.string.update_log_open_release, entry.version)
            setOnClickListener {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://github.com/mcschummi1/simple-light-detector/releases/tag/${entry.version}"
                        )
                    )
                )
            }
        })
        return box
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
}
