package com.avonotes.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.avonotes.canvas.StrokeRenderer
import com.avonotes.model.NoteDocument
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders a small 4:3 preview of a note: the top of its content, scaled to fit the width.
 * Runs on a background thread with its own renderer (nothing shared with the UI thread).
 */
object Thumbnailer {
    const val W = 480
    const val H = 360
    private const val PAPER = 0xFFFCFCFA.toInt()

    fun render(doc: NoteDocument, pdfDir: File): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(PAPER)
        val content = doc.contentBounds() ?: return bmp

        // Show the top of the content; fit width unless the content is wide and short.
        val pad = max(content.width(), content.height()) * 0.04f + 8f
        val r = RectF(content).apply { inset(-pad, -pad) }
        val scale = minOf(W / r.width(), H / r.height()).let { fitAll ->
            val fitWidth = W / r.width()
            if (r.height() * fitWidth > H) fitWidth else fitAll
        }
        val ox = (W - r.width() * scale) / 2f - r.left * scale
        val oy = if (r.height() * scale > H) -r.top * scale else (H - r.height() * scale) / 2f - r.top * scale
        val view = RectF(-ox / scale, -oy / scale, (W - ox) / scale, (H - oy) / scale)

        drawPages(c, doc, pdfDir, scale, ox, oy, view)

        c.translate(ox, oy)
        c.scale(scale, scale)
        val renderer = StrokeRenderer()
        for (s in doc.strokes) {
            if (RectF.intersects(s.bounds, view)) renderer.draw(c, s.tool, s.color, s.width, s.points, s.pointCount)
        }
        return bmp
    }

    private fun drawPages(c: Canvas, doc: NoteDocument, pdfDir: File, scale: Float, ox: Float, oy: Float, view: RectF) {
        val visible = doc.pages.filter { RectF.intersects(it.bounds, view) }
        if (visible.isEmpty()) return
        val border = Paint().apply { style = Paint.Style.STROKE; color = 0x22000000; strokeWidth = 1f }
        val white = Paint().apply { color = Color.WHITE }
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        val renderers = HashMap<String, Pair<ParcelFileDescriptor, PdfRenderer>?>()
        try {
            for (p in visible) {
                val dst = RectF(p.x * scale + ox, p.y * scale + oy, (p.x + p.w) * scale + ox, (p.y + p.h) * scale + oy)
                c.drawRect(dst, white)
                val r = renderers.getOrPut(p.docId) { open(doc, pdfDir, p.docId) }?.second
                if (r != null && p.pageIndex < r.pageCount) {
                    val w = max(1, dst.width().roundToInt()); val h = max(1, dst.height().roundToInt())
                    if (w.toLong() * h <= 4_000_000L) {
                        val pb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        pb.eraseColor(Color.WHITE)
                        r.openPage(p.pageIndex).use { page ->
                            val m = Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }
                            page.render(pb, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                        c.drawBitmap(pb, null, dst, filter)
                        pb.recycle()
                    }
                }
                c.drawRect(dst, border)
            }
        } finally {
            for (v in renderers.values) if (v != null) { runCatching { v.second.close() }; runCatching { v.first.close() } }
        }
    }

    private fun open(doc: NoteDocument, pdfDir: File, docId: String): Pair<ParcelFileDescriptor, PdfRenderer>? {
        val name = doc.pdfDocs.firstOrNull { it.id == docId }?.fileName ?: return null
        val f = File(pdfDir, name)
        if (!f.exists()) return null
        return runCatching {
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            try { pfd to PdfRenderer(pfd) } catch (t: Throwable) { pfd.close(); throw t }
        }.getOrNull()
    }
}
