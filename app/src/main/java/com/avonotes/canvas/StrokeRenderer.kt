package com.avonotes.canvas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.avonotes.model.Tool
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws ink strokes.
 *
 *  - PEN: pressure sensitive. The polyline is split into runs of similar width (quantized),
 *    each drawn as one smoothed, round-capped path; runs share their boundary point so
 *    opaque ink joins seamlessly.
 *  - FOUNTAIN: like PEN, but the width also follows the stroke direction relative to a
 *    45° nib, giving calligraphic thick/thin strokes.
 *  - PENCIL: thin, constant width; pressure sets how dark it is.
 *  - MARKER: constant width, opaque, ignores pressure.
 *  - HIGHLIGHTER: constant width, translucent, square ends; one path so it doesn't double up.
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
        var shown = DarkInk.display(color, tool == Tool.HIGHLIGHTER, dark)
        if (tool == Tool.PENCIL) shown = withAlpha(shown, pencilAlpha(pts, count))
        paint.color = shown
        dotPaint.color = shown

        if (count == 1) {
            canvas.drawCircle(pts[0], pts[1], widthAt(tool, width, pts, 0, count) / 2f, dotPaint)
            return
        }
        when (tool) {
            Tool.HIGHLIGHTER -> single(canvas, width, Paint.Cap.SQUARE, pts, count)
            Tool.MARKER -> single(canvas, width, Paint.Cap.ROUND, pts, count)
            Tool.PENCIL -> single(canvas, width * PENCIL_SCALE, Paint.Cap.ROUND, pts, count)
            else -> variable(canvas, tool, width, pts, count)
        }
    }

    private fun single(canvas: Canvas, w: Float, cap: Paint.Cap, pts: FloatArray, count: Int) {
        paint.strokeWidth = maxOf(w, MIN_WIDTH)
        paint.strokeCap = cap
        buildSmooth(pts, 0, count - 1)
        canvas.drawPath(path, paint)
        paint.strokeCap = Paint.Cap.ROUND
    }

    /** Variable width: split into runs of (quantized) equal width. */
    private fun variable(canvas: Canvas, tool: Tool, width: Float, pts: FloatArray, count: Int) {
        val step = width * WIDTH_QUANTUM
        var runStart = 0
        var runBucket = bucket(widthAt(tool, width, pts, 0, count), step)
        for (i in 1 until count) {
            val b = bucket(widthAt(tool, width, pts, i, count), step)
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
        private const val PENCIL_SCALE = 0.6f
        private const val NIB_ANGLE = -Math.PI / 4 // 45°, rising to the right (screen y points down)

        /** Width of the stroke at point [i] (for PEN and FOUNTAIN this varies along the stroke). */
        fun widthAt(tool: Tool, base: Float, pts: FloatArray, i: Int, count: Int): Float {
            val p = pts[i * 3 + 2].coerceIn(0f, 1f)
            return when (tool) {
                Tool.PEN -> base * (0.3f + 0.7f * p)
                Tool.FOUNTAIN -> {
                    // Direction over a few neighbours so tiny jitters don't flicker the width.
                    val a = maxOf(0, i - 2); val b = minOf(count - 1, i + 2)
                    val dx = pts[b * 3] - pts[a * 3]; val dy = pts[b * 3 + 1] - pts[a * 3 + 1]
                    val nib = if (dx == 0f && dy == 0f) 1f
                    else abs(sin(atan2(dy, dx).toDouble() - NIB_ANGLE)).toFloat()
                    base * 1.6f * (0.15f + 0.85f * nib) * (0.65f + 0.35f * p)
                }
                Tool.PENCIL -> base * PENCIL_SCALE
                else -> base
            }
        }

        /** Pencil darkness from the average pressure of the stroke. */
        private fun pencilAlpha(pts: FloatArray, count: Int): Float {
            var sum = 0f
            for (i in 0 until count) sum += pts[i * 3 + 2]
            val p = (sum / count).coerceIn(0f, 1f)
            return 0.35f + 0.55f * p
        }

        private fun withAlpha(c: Int, a: Float) =
            Color.argb((Color.alpha(c) * a).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    }
}
