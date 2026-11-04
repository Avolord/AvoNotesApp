package com.avonotes.canvas

import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.RenderNode
import com.avonotes.model.Stroke
import kotlin.math.floor
import kotlin.math.max

/**
 * Spatial index of strokes, bucketed into fixed-size world tiles.
 *
 * Each tile records its strokes once into a [RenderNode] (a GPU display list). Panning
 * and zooming then only replays visible tiles under a new transform, with no per-stroke
 * work on the UI thread; adding or erasing a stroke re-records only its own tile.
 * Display lists keep vector data, so ink stays crisp at every zoom level.
 */
class TileIndex(private val renderer: StrokeRenderer) {

    inner class Tile(val key: Long) {
        val strokes = ArrayList<Stroke>() // sorted by seq
        val bounds = RectF()
        val node = RenderNode("tile").apply { setClipToBounds(false) }
        var dirty = true

        fun recomputeBounds() {
            if (strokes.isEmpty()) { bounds.setEmpty(); return }
            bounds.set(strokes[0].bounds)
            for (i in 1 until strokes.size) bounds.union(strokes[i].bounds)
        }

        fun record() {
            val l = floor(bounds.left).toInt()
            val t = floor(bounds.top).toInt()
            val r = max(l + 1, kotlin.math.ceil(bounds.right).toInt())
            val b = max(t + 1, kotlin.math.ceil(bounds.bottom).toInt())
            node.setPosition(l, t, r, b)
            val c = node.beginRecording()
            try {
                c.translate(-l.toFloat(), -t.toFloat())
                for (s in strokes) drawStroke(c, s)
            } finally {
                node.endRecording()
            }
            dirty = false
        }
    }

    private val tiles = HashMap<Long, Tile>()
    var count = 0
        private set

    fun clear() {
        for (t in tiles.values) t.node.discardDisplayList()
        tiles.clear()
        count = 0
    }

    /** Forces every tile to re-record (e.g. after switching dark mode). */
    fun invalidateAll() {
        for (t in tiles.values) t.dirty = true
    }

    fun add(s: Stroke) {
        val key = keyFor(s)
        s.tileKey = key
        val tile = tiles.getOrPut(key) { Tile(key) }
        val list = tile.strokes
        if (list.isEmpty() || list[list.size - 1].seq <= s.seq) list.add(s)
        else {
            var i = list.binarySearch { it.seq.compareTo(s.seq) }
            if (i < 0) i = -i - 1
            list.add(i, s)
        }
        if (list.size == 1) tile.bounds.set(s.bounds) else tile.bounds.union(s.bounds)
        tile.dirty = true
        count++
    }

    fun remove(s: Stroke) {
        val tile = tiles[s.tileKey] ?: return
        if (tile.strokes.remove(s)) {
            count--
            if (tile.strokes.isEmpty()) {
                tile.node.discardDisplayList()
                tiles.remove(tile.key)
            } else {
                tile.recomputeBounds()
                tile.dirty = true
            }
        }
    }

    /** Draws tiles intersecting [visible] (world coords); [canvas] must already be in world space. */
    fun draw(canvas: Canvas, visible: RectF) {
        val hw = canvas.isHardwareAccelerated
        for (tile in tiles.values) {
            if (!RectF.intersects(tile.bounds, visible)) continue
            if (hw) {
                if (tile.dirty || !tile.node.hasDisplayList()) tile.record()
                canvas.drawRenderNode(tile.node)
            } else {
                for (s in tile.strokes) if (RectF.intersects(s.bounds, visible)) drawStroke(canvas, s)
            }
        }
    }

    /** Calls [block] for every stroke whose bounds intersect [area]. */
    inline fun forEachIn(area: RectF, block: (Stroke) -> Unit) {
        for (tile in tilesSnapshot()) {
            if (!RectF.intersects(tile.bounds, area)) continue
            for (i in tile.strokes.indices.reversed()) {
                val s = tile.strokes[i]
                if (RectF.intersects(s.bounds, area)) block(s)
            }
        }
    }

    fun tilesSnapshot(): List<Tile> = ArrayList(tiles.values)

    /** All strokes in creation order. */
    fun allStrokes(): ArrayList<Stroke> {
        val out = ArrayList<Stroke>(count)
        for (t in tiles.values) out.addAll(t.strokes)
        out.sortBy { it.seq }
        return out
    }

    private fun drawStroke(c: Canvas, s: Stroke) =
        renderer.draw(c, s.tool, s.color, s.width, s.points, s.pointCount)

    private fun keyFor(s: Stroke): Long {
        val cx = floor(s.bounds.centerX() / TILE_SIZE).toLong()
        val cy = floor(s.bounds.centerY() / TILE_SIZE).toLong()
        return (cx shl 32) or (cy and 0xFFFFFFFFL)
    }

    companion object {
        const val TILE_SIZE = 1024f
    }
}
