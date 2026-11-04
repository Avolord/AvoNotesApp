package com.avonotes.model

import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Toolbar tools, and also the kind of a stored stroke (PEN, HIGHLIGHTER and the
 * brushes FOUNTAIN, PENCIL, MARKER). The code is what the note file stores.
 */
enum class Tool(val code: Int) {
    PEN(0), HIGHLIGHTER(1), ERASER(2), LASSO(3),
    FOUNTAIN(4), PENCIL(5), MARKER(6);

    val isBrush get() = this == PEN || this == FOUNTAIN || this == PENCIL || this == MARKER

    companion object {
        fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: PEN
    }
}

/** Paper pattern drawn behind a note's content. */
enum class Pattern(val code: Int) {
    DOTS(0), LINES(1), GRID(2), BLANK(3);

    companion object {
        fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: DOTS
    }
}

/**
 * A committed ink stroke. Immutable once created, which lets saving snapshot
 * the stroke list without copying point data.
 *
 * [points] is packed as (x, y, pressure) triples in world units.
 */
class Stroke(
    val tool: Tool,
    val color: Int,
    val width: Float,
    val points: FloatArray,
) {
    val pointCount: Int get() = points.size / 3
    val bounds: RectF = computeBounds()

    /** Tile this stroke lives in (assigned by the tile index). */
    @JvmField var tileKey: Long = 0L

    /** Creation order; keeps z-order stable across erase/undo. */
    @JvmField var seq: Long = 0L

    private fun computeBounds(): RectF {
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var i = 0
        while (i < points.size) {
            val x = points[i]; val y = points[i + 1]
            if (x < r.left) r.left = x
            if (x > r.right) r.right = x
            if (y < r.top) r.top = y
            if (y > r.bottom) r.bottom = y
            i += 3
        }
        val pad = width * 1.5f + 1f
        r.inset(-pad, -pad)
        return r
    }

    /** A copy moved by (dx, dy), keeping z-order. */
    fun translated(dx: Float, dy: Float): Stroke {
        val p = points.copyOf()
        var i = 0
        while (i < p.size) { p[i] += dx; p[i + 1] += dy; i += 3 }
        return Stroke(tool, color, width, p).also { it.seq = seq }
    }

    /** Fraction of this stroke's points inside [poly] (packed x, y pairs; [n] vertices). */
    fun fractionInside(poly: FloatArray, n: Int): Float {
        var inside = 0
        for (i in 0 until pointCount) if (pointInPolygon(points[i * 3], points[i * 3 + 1], poly, n)) inside++
        return inside.toFloat() / pointCount
    }

    /** True if any segment of this stroke passes within [radius] of (x, y). */
    fun hitTest(x: Float, y: Float, radius: Float): Boolean {
        if (x < bounds.left - radius || x > bounds.right + radius ||
            y < bounds.top - radius || y > bounds.bottom + radius
        ) return false
        val r = radius + width / 2f
        val r2 = r * r
        val n = pointCount
        if (n == 1) {
            val dx = points[0] - x; val dy = points[1] - y
            return dx * dx + dy * dy <= r2
        }
        for (i in 0 until n - 1) {
            val ax = points[i * 3]; val ay = points[i * 3 + 1]
            val bx = points[i * 3 + 3]; val by = points[i * 3 + 4]
            if (distSqToSegment(x, y, ax, ay, bx, by) <= r2) return true
        }
        return false
    }

    /**
     * Cuts away the part of this stroke covered by a circle of [radius] around (cx, cy), so the
     * ink edge (half the stroke width out from the center line) no longer overlaps it.
     *
     * Returns null if the circle doesn't touch the stroke, otherwise the remaining pieces (empty if
     * nothing is left). Cuts land exactly on the circle, with pressure interpolated, so erasing
     * leaves clean ends however sparse the samples are. Pieces keep this stroke's z-order.
     */
    fun erase(cx: Float, cy: Float, radius: Float): List<Stroke>? {
        val r = radius + width / 2f
        if (cx < bounds.left - r || cx > bounds.right + r || cy < bounds.top - r || cy > bounds.bottom + r) return null
        val r2 = r * r
        // Points exactly on the circle (earlier cut ends) count as outside, so holding the eraser
        // still at a cut doesn't keep "cutting" the same stroke.
        val inside2 = r2 * (1f - 1e-3f)
        val n = pointCount
        fun inside(i: Int): Boolean {
            val dx = points[i * 3] - cx; val dy = points[i * 3 + 1] - cy
            return dx * dx + dy * dy < inside2
        }
        if (n == 1) return if (inside(0)) emptyList() else null

        val pieces = ArrayList<Stroke>()
        var cur = FloatArray(minOf(n, 64) * 3)
        var len = 0
        var touched = false
        fun push(x: Float, y: Float, p: Float) {
            if (len + 3 > cur.size) cur = cur.copyOf(cur.size * 2)
            cur[len++] = x; cur[len++] = y; cur[len++] = p
        }
        fun pushAt(a: Int, t: Float) {
            val ax = points[a * 3]; val ay = points[a * 3 + 1]; val ap = points[a * 3 + 2]
            val b = a + 1
            push(ax + (points[b * 3] - ax) * t, ay + (points[b * 3 + 1] - ay) * t, ap + (points[b * 3 + 2] - ap) * t)
        }
        fun close() {
            if (len >= 6) {
                val pts = cur.copyOf(len)
                // Drop slivers shorter than a hair: they'd only show up as specks.
                var length = 0f
                for (k in 3 until len step 3) length += hypot(pts[k] - pts[k - 3], pts[k + 1] - pts[k - 2])
                if (length > width * 0.25f) pieces.add(Stroke(tool, color, width, pts).also { it.seq = seq })
            }
            len = 0
        }

        if (!inside(0)) push(points[0], points[1], points[2])
        for (a in 0 until n - 1) {
            val b = a + 1
            val ax = points[a * 3]; val ay = points[a * 3 + 1]
            val dx = points[b * 3] - ax; val dy = points[b * 3 + 1] - ay
            val fx = ax - cx; val fy = ay - cy
            val aIn = inside(a); val bIn = inside(b)
            val qa = dx * dx + dy * dy
            val qb = 2f * (fx * dx + fy * dy)
            val qc = fx * fx + fy * fy - r2
            val disc = qb * qb - 4f * qa * qc
            if (qa <= 0f || disc <= 0f) {
                // No crossing: the segment is wholly inside or outside.
                if (aIn) touched = true else if (!bIn) push(points[b * 3], points[b * 3 + 1], points[b * 3 + 2])
                continue
            }
            val sq = sqrt(disc)
            val t1 = ((-qb - sq) / (2f * qa)).coerceIn(0f, 1f)
            val t2 = ((-qb + sq) / (2f * qa)).coerceIn(0f, 1f)
            when {
                !aIn && !bIn -> {
                    if (t1 > T_EPS && t2 < 1f - T_EPS && t2 - t1 > T_EPS) {
                        // Passes through the circle: cut out the middle.
                        pushAt(a, t1); close(); pushAt(a, t2); touched = true
                    }
                    push(points[b * 3], points[b * 3 + 1], points[b * 3 + 2])
                }
                !aIn && bIn -> { pushAt(a, t1); close(); touched = true }
                aIn && !bIn -> {
                    touched = true
                    pushAt(a, t2); push(points[b * 3], points[b * 3 + 1], points[b * 3 + 2])
                }
                else -> touched = true
            }
        }
        if (!touched) return null
        close()
        return pieces
    }

    companion object {
        private const val T_EPS = 1e-4f

        /** Even-odd ray casting test. */
        fun pointInPolygon(x: Float, y: Float, poly: FloatArray, n: Int): Boolean {
            var inside = false
            var j = n - 1
            for (i in 0 until n) {
                val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
                val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        fun distSqToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            var t = if (len2 > 0f) ((px - ax) * dx + (py - ay) * dy) / len2 else 0f
            t = t.coerceIn(0f, 1f)
            val cx = ax + t * dx - px; val cy = ay + t * dy - py
            return cx * cx + cy * cy
        }
    }
}

/** A PDF file embedded in a note. [fileName] is relative to the note's pdf/ folder. */
data class PdfDoc(val id: String, val fileName: String, val displayName: String)

/** One page of an embedded PDF placed on the canvas, in world units (1 unit = 1 PDF point). */
class PdfPageItem(
    val docId: String,
    val pageIndex: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    val bounds = RectF(x, y, x + w, y + h)

    fun translated(dx: Float, dy: Float) = PdfPageItem(docId, pageIndex, x + dx, y + dy, w, h)
}

/** Everything persisted for a note. */
class NoteDocument {
    var title: String = ""
    val strokes = ArrayList<Stroke>()
    val pdfDocs = ArrayList<PdfDoc>()
    val pages = ArrayList<PdfPageItem>()

    // Last viewport, restored on open.
    var viewX = 0f
    var viewY = 0f
    var viewZoom = 1f

    fun contentBounds(): RectF? {
        var r: RectF? = null
        for (s in strokes) r = r?.apply { union(s.bounds) } ?: RectF(s.bounds)
        for (p in pages) r = r?.apply { union(p.bounds) } ?: RectF(p.bounds)
        return r
    }
}
