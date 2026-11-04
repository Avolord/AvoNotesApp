package com.avonotes.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.avonotes.canvas.StrokeRenderer
import com.avonotes.model.Tool
import kotlin.math.PI
import kotlin.math.sin

/** A sample squiggle drawn with a brush, for the brush picker. */
class BrushPreviewView(context: Context) : View(context) {
    var brush = Tool.PEN
        set(v) { field = v; invalidate() }
    var color = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }
    var dark = false

    private val renderer = StrokeRenderer()
    private var pts = FloatArray(0)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // A steep wave with a pressure swell, so direction-dependent (fountain) and
        // pressure-dependent (pen, pencil) brushes look clearly different.
        val n = 64
        val padX = h * 0.3f
        pts = FloatArray(n * 3)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            pts[i * 3] = padX + t * (w - 2 * padX)
            pts[i * 3 + 1] = h / 2f - sin(t * 3 * PI).toFloat() * h * 0.3f
            pts[i * 3 + 2] = 0.15f + 0.85f * sin(t * PI).toFloat()
        }
    }

    override fun onDraw(canvas: Canvas) {
        renderer.dark = dark
        val base = resources.displayMetrics.density * 3f
        renderer.draw(canvas, brush, color, base, pts, pts.size / 3)
    }
}
