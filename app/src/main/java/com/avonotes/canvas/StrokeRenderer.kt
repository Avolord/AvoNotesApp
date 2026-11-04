package com.avonotes.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.avonotes.model.Tool
import kotlin.math.roundToInt

/**
 * Draws ink strokes. Pen strokes are pressure sensitive: the polyline is split into
 * runs of similar width (quantized), each run drawn as one smoothed, round-capped path.
 * Runs share their boundary point, so opaque ink joins seamlessly. Highlighter strokes
 * are constant width and drawn as a single path so translucency doesn't double up.
 */
class StrokeRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    /** Show pen ink in its dark-mode color (see [DarkInk]). Stored colors are unchanged. */
    var dark = false

    fun draw(canvas: Canvas, tool: Tool, color: Int, width: Float, pts: FloatArray, count: Int) {
        if (count <= 0) return
        val shown = DarkInk.display(color, tool == Tool.HIGHLIGHTER, dark)
        paint.color = shown
        dotPaint.color = shown
        if (count == 1) {
            canvas.drawCircle(pts[0], pts[1], widthAt(tool, width, pts[2]) / 2f, dotPaint)
            return
        }
        if (tool == Tool.HIGHLIGHTER) {
            paint.strokeWidth = width
            paint.strokeCap = Paint.Cap.SQUARE
            buildSmooth(pts, 0, count - 1)
            canvas.drawPath(path, paint)
            paint.strokeCap = Paint.Cap.ROUND
            return
        }

        val step = width * WIDTH_QUANTUM
        var runStart = 0
        var runBucket = bucket(widthAt(tool, width, pts[2]), step)
        for (i in 1 until count) {
            val b = bucket(widthAt(tool, width, pts[i * 3 + 2]), step)
            val last = i == count - 1
            if (b != runBucket || last) {
                paint.strokeWidth = maxOf(runBucket * step, MIN_WIDTH)
                buildSmooth(pts, runStart, i)
                canvas.drawPath(path, paint)
                runStart = i
                runBucket = b
            }
        }
    }

    /** Quadratic smoothing through segment midpoints for points [from]..[to] inclusive. */
    private fun buildSmooth(pts: FloatArray, from: Int, to: Int) {
        path.rewind()
        path.moveTo(pts[from * 3], pts[from * 3 + 1])
        if (to - from == 1) {
            path.lineTo(pts[to * 3], pts[to * 3 + 1])
            return
        }
        for (i in from + 1 until to) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]
            val nx = pts[i * 3 + 3]; val ny = pts[i * 3 + 4]
            path.quadTo(x, y, (x + nx) / 2f, (y + ny) / 2f)
        }
        path.lineTo(pts[to * 3], pts[to * 3 + 1])
    }

    private fun bucket(w: Float, step: Float) = maxOf(1, (w / step).roundToInt())

    companion object {
        private const val WIDTH_QUANTUM = 0.08f
        private const val MIN_WIDTH = 0.3f

        fun widthAt(tool: Tool, base: Float, pressure: Float): Float =
            if (tool == Tool.HIGHLIGHTER) base
            else base * (0.3f + 0.7f * pressure.coerceIn(0f, 1f))
    }
}
