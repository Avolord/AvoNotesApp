package com.avonotes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/**
 * A circle showing a tool's size on screen: filled with the ink color for pens and the
 * highlighter, an outline like the on-canvas cursor for the eraser. Sizes larger than the view
 * are drawn as big as fits, with a dashed edge hinting that the real size is bigger.
 */
class SizePreviewView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density

    /** Diameter in pixels, as it will appear on the canvas. */
    var diameter = 0f
        set(v) { field = v; invalidate() }
    var color = 0xFF000000.toInt()
        set(v) { field = v; invalidate() }
    /** Outline instead of a fill (eraser). */
    var outline = false
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashed = android.graphics.DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val maxR = min(width, height) / 2f - 2f * d
        val r = (diameter / 2f).coerceIn(0.5f, maxR)
        val clipped = diameter / 2f > maxR
        if (outline) {
            fill.color = (color and 0x00FFFFFF) or 0x1A000000
            canvas.drawCircle(cx, cy, r, fill)
            stroke.color = color
            stroke.strokeWidth = 1.5f * d
            stroke.pathEffect = if (clipped) dashed else null
            canvas.drawCircle(cx, cy, r, stroke)
        } else {
            fill.color = color
            canvas.drawCircle(cx, cy, r, fill)
            if (clipped) {
                stroke.color = color
                stroke.strokeWidth = 1.5f * d
                stroke.pathEffect = dashed
                canvas.drawCircle(cx, cy, maxR + 1.5f * d, stroke)
            }
        }
    }
}
