package com.mcschummi.daylightlogger

import android.media.Image

/**
 * VIEW CHECK - "where is the light coming from, is the view even usable?"
 *
 * Splits the portrait view into a 3x4 grid and measures each cell's brightness
 * from the Y (luma) plane of every second camera frame. Bright cells are open
 * sky, much darker cells are something blocking the light (a wall on the right
 * half, furniture, the user's shadow...).
 *
 * Why it matters: auto-exposure averages the WHOLE frame. If a dark wall fills
 * half the view, the camera picks a longer exposure and the lux/PPFD numbers
 * read too HIGH. So this check both warns the user AND is stored in the CSV so
 * blocked samples can be filtered out later.
 */
object SceneCheck {

    data class Report(
        val openPct: Int,      // percent of the view that looks like open sky
        val darkSide: String   // "right" / "left" / "bottom" / "" (nothing dark)
    )

    const val COLS = 3
    const val ROWS = 4

    /**
     * The raw camera frame is landscape (640x480) but the view the user looks
     * at is portrait, rotated 90° clockwise. The grid is defined in PORTRAIT
     * coordinates (col 0 = viewer's left, row 0 = top) and mapped back into
     * raw pixel coordinates before sampling.
     */
    fun analyze(image: Image): Report? = try {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rawW = image.width     // 640
        val rawH = image.height    // 480
        val viewW = rawH           // portrait view size after the 90° rotation
        val viewH = rawW

        fun sampleRaw(rx: Int, ry: Int): Int {
            val x = rx.coerceIn(0, rawW - 1)
            val y = ry.coerceIn(0, rawH - 1)
            return buf.get(y * rowStride + x * pixelStride).toInt() and 0xFF
        }

        val cells = FloatArray(COLS * ROWS)
        for (gy in 0 until ROWS) {
            for (gx in 0 until COLS) {
                // cell center in portrait view coordinates...
                val vx = ((gx + 0.5f) / COLS * viewW).toInt()
                val vy = ((gy + 0.5f) / ROWS * viewH).toInt()
                // ...mapped back into raw frame coordinates (inverse of the
                // 90° clockwise display rotation: rx = vy, ry = rawH - vx).
                var sum = 0f; var n = 0
                var dy = -6
                while (dy <= 6) {
                    var dx = -6
                    while (dx <= 6) {
                        sum += sampleRaw(vy + dx, rawH - 1 - vx + dy)
                        n++
                        dx += 3
                    }
                    dy += 3
                }
                cells[gy * COLS + gx] = sum / n
            }
        }

        val maxCell = cells.max()
        if (maxCell <= 0) return null

        // A cell counts as "open sky" if it is within 70% of the brightest cell.
        val openCount = cells.count { it >= 0.7f * maxCell }
        val openPct = (100 * openCount) / cells.size

        // Compare column strips and row halves to find a blocking wall/shadow.
        val colAvg = Array(COLS) { c ->
            var s = 0f
            for (r in 0 until ROWS) s += cells[r * COLS + c]
            s / ROWS
        }
        var topSum = 0f; var bottomSum = 0f
        for (r in 0 until ROWS) {
            val rowAvg = (cells[r * COLS] + cells[r * COLS + 1] + cells[r * COLS + 2]) / 3f
            if (r < ROWS / 2) topSum += rowAvg else bottomSum += rowAvg
        }
        val topMean = topSum / (ROWS / 2)
        val bottomMean = bottomSum / (ROWS - ROWS / 2)

        // Left/right strips overlap the middle column so a thin frame edge
        // doesn't trigger a false alarm.
        val leftMean = (colAvg[0] + colAvg[1]) / 2f
        val rightMean = (colAvg[2] + colAvg[1]) / 2f

        val darkSide = when {
            rightMean < 0.55f * leftMean -> "right"
            leftMean < 0.55f * rightMean -> "left"
            bottomMean < 0.55f * topMean -> "bottom"
            else -> ""
        }
        Report(openPct, darkSide)
    } catch (_: Exception) {
        null
    }
}
