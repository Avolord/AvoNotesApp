package com.avonotes.canvas

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * Dark-mode color mapping shared by ink and PDF pages: invert lightness while keeping hue
 * (invert, then rotate hue by 180°), compressed into a range that maps white paper to the
 * dark background and black ink to near-white. Ink and the PDFs it annotates are mapped
 * the same way, so annotations keep their contrast. Notes are stored unchanged.
 */
object DarkInk {
    private const val LOW = 30f   // white maps to this
    private const val HIGH = 248f // black maps to this

    val matrix: ColorMatrix = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    ).apply {
        // Luminance-preserving hue rotation by 180° (cos = -1, sin = 0).
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    -0.574f, 1.430f, 0.144f, 0f, 0f,
                    0.426f, 0.430f, 0.144f, 0f, 0f,
                    0.426f, 1.430f, -0.856f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
        val s = (HIGH - LOW) / 255f
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    s, 0f, 0f, 0f, LOW,
                    0f, s, 0f, 0f, LOW,
                    0f, 0f, s, 0f, LOW,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }

    val filter = ColorMatrixColorFilter(matrix)

    /** Maps one ARGB color through [matrix]. */
    fun map(color: Int): Int {
        val m = matrix.array
        val r = Color.red(color).toFloat(); val g = Color.green(color).toFloat(); val b = Color.blue(color).toFloat()
        fun ch(o: Int) = (m[o] * r + m[o + 1] * g + m[o + 2] * b + m[o + 4]).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), ch(0), ch(5), ch(10))
    }

    /** How a stored ink color is shown: highlighters keep their color, pen ink is mapped. */
    fun display(color: Int, highlighter: Boolean, dark: Boolean): Int =
        if (!dark || highlighter) color else map(color)
}
