package com.mcschummi.daylightlogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Simple line chart of today's lux, drawn directly on a Canvas.
 * The lux scale is logarithmic (dark = 1 lux, night, shade = 1000, sun = 100000),
 * otherwise daylight values would squash everything else into the floor.
 */
class LuxChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x331565C0
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt()
        strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF616161.toInt()
        textSize = 26f
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        textSize = 34f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val history = LogState.luxHistory
        canvas.drawColor(0xFFFAFAFA.toInt())

        if (history.isEmpty()) {
            canvas.drawText(
                context.getString(R.string.chart_empty),
                width / 2f, height / 2f, emptyPaint
            )
            return
        }

        // Log-ish vertical scale: 0.1 .. 100000 lux -> y.
        fun luxToY(lux: Double): Float {
            val v = log10(max(lux, 0.1) + 1.0)      // 0 .. 5
            val maxV = 5.0
            val padTop = 40f
            val usable = (height - padTop - 40f).coerceAtLeast(1f)
            return padTop + usable * (1.0 - v / maxV).toFloat()
        }

        val first = history.first().first
        val last = max(history.last().first, first + 1)
        fun timeToX(t: Long): Float = (t - first).toFloat() / (last - first).toFloat() * width

        // Horizontal grid lines at 1, 10, 100, 1k, 10k, 100k lux.
        for (i in 0..5) {
            val lux = 10.0.pow(i) - 1.0
            val y = luxToY(lux)
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            canvas.drawText(luxLabel(10.0.pow(i)), 8f, y - 6f, labelPaint)
        }

        val path = Path()
        history.forEachIndexed { i, (t, lux) ->
            val x = timeToX(t); val y = luxToY(lux)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path(path)
        fill.lineTo(width.toFloat(), height.toFloat())
        fill.lineTo(0f, height.toFloat())
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)
    }

    private fun luxLabel(lux: Double): String = when {
        lux >= 1000 -> "${(lux / 1000).toInt()}k"
        else -> lux.toInt().toString()
    }
}
