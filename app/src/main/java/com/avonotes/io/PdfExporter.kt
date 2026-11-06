package com.avonotes.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.avonotes.canvas.StrokeRenderer
import com.avonotes.model.NoteDocument
import com.avonotes.model.PdfPageItem
import java.io.File
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Exports a note as a PDF. World units are PDF points, so content keeps its real size.
 *
 *  - [Mode.CANVAS]: one page holding everything in the note (scaled down only if it would exceed
 *    the PDF page size limit).
 *  - [Mode.PAGES]: one page per placed PDF page, with the ink on it (ink elsewhere is left out).
 *
 * Ink is written as vector paths in its stored (light-mode) colors. Imported PDF pages are
 * re-rendered as images (the platform can't copy pages between PDFs), at about [PAGE_DPI].
 * Runs on a background thread.
 */
object PdfExporter {
    enum class Mode { CANVAS, PAGES }

    private const val MARGIN = 36f            // 1/2 inch around the canvas content
    private const val MAX_PAGE = 14_400f      // PDF page size limit (200 inches)
    private const val PAGE_DPI = 200f
    private const val MAX_PAGE_PIXELS = 12_000_000L

    /** Writes the PDF to [out]. Returns the number of pages written (0 = nothing to export). */
    fun export(doc: NoteDocument, pdfDir: File, mode: Mode, out: OutputStream): Int {
        val pdf = PdfDocument()
        val renderers = Renderers(doc, pdfDir)
        try {
            val n = when (mode) {
                Mode.CANVAS -> exportCanvas(doc, pdf, renderers)
                Mode.PAGES -> exportPages(doc, pdf, renderers)
            }
            if (n > 0) pdf.writeTo(out)
            return n
        } finally {
            renderers.close()
            pdf.close()
        }
    }

    private fun exportCanvas(doc: NoteDocument, pdf: PdfDocument, renderers: Renderers): Int {
        val content = doc.contentBounds() ?: return 0
        val r = RectF(content).apply { inset(-MARGIN, -MARGIN) }
        val scale = min(1f, min(MAX_PAGE / r.width(), MAX_PAGE / r.height()))
        val w = max(1, ceil(r.width() * scale).toInt())
        val h = max(1, ceil(r.height() * scale).toInt())
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        c.translate(-r.left, -r.top)
        for (p in doc.pages) drawPdfPage(c, p, renderers, scale)
        drawInk(c, doc, r)
        pdf.finishPage(page)
        return 1
    }

    private fun exportPages(doc: NoteDocument, pdf: PdfDocument, renderers: Renderers): Int {
        // Reading order: top to bottom, then left to right.
        val ordered = doc.pages.sortedWith(compareBy<PdfPageItem>({ it.y }, { it.x }))
        ordered.forEachIndexed { i, p ->
            val scale = min(1f, min(MAX_PAGE / p.w, MAX_PAGE / p.h))
            val w = max(1, (p.w * scale).roundToInt())
            val h = max(1, (p.h * scale).roundToInt())
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            val c = page.canvas
            c.drawColor(Color.WHITE)
            c.scale(scale, scale)
            c.translate(-p.x, -p.y)
            c.clipRect(p.bounds)
            drawPdfPage(c, p, renderers, scale, border = false)
            drawInk(c, doc, p.bounds)
            pdf.finishPage(page)
        }
        return ordered.size
    }

    private fun drawInk(c: Canvas, doc: NoteDocument, area: RectF) {
        val renderer = StrokeRenderer() // light colors, as stored
        for (s in doc.strokes) {
            if (RectF.intersects(s.bounds, area)) renderer.draw(c, s.tool, s.color, s.width, s.points, s.pointCount)
        }
    }

    /** Draws placed page [p] (world coordinates) as an image at about [PAGE_DPI]. */
    private fun drawPdfPage(c: Canvas, p: PdfPageItem, renderers: Renderers, scale: Float, border: Boolean = true) {
        c.drawRect(p.bounds, Paint().apply { color = Color.WHITE })
        val r = renderers.get(p.docId)
        if (r != null && p.pageIndex < r.pageCount) {
            // Pixels per world unit: aim for PAGE_DPI at the exported size, within a memory cap.
            var px = PAGE_DPI / 72f * scale
            val total = p.w * px * p.h * px
            if (total > MAX_PAGE_PIXELS) px *= sqrt(MAX_PAGE_PIXELS / total)
            val w = max(1, (p.w * px).roundToInt()); val h = max(1, (p.h * px).roundToInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bmp.eraseColor(Color.WHITE)
                r.openPage(p.pageIndex).use { page ->
                    val m = Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                }
                c.drawBitmap(bmp, null, p.bounds, Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {
                bmp.recycle()
            }
        }
        if (border) {
            c.drawRect(p.bounds, Paint().apply { style = Paint.Style.STROKE; color = 0x22000000; strokeWidth = 0.75f })
        }
    }

    /** One PdfRenderer per embedded document, opened on demand. */
    private class Renderers(private val doc: NoteDocument, private val pdfDir: File) {
        private val open = HashMap<String, Pair<ParcelFileDescriptor, PdfRenderer>?>()

        fun get(docId: String): PdfRenderer? = open.getOrPut(docId) {
            val name = doc.pdfDocs.firstOrNull { it.id == docId }?.fileName ?: return@getOrPut null
            val f = File(pdfDir, name)
            if (!f.exists()) return@getOrPut null
            runCatching {
                val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                try { pfd to PdfRenderer(pfd) } catch (t: Throwable) { pfd.close(); throw t }
            }.getOrNull()
        }?.second

        fun close() {
            for (v in open.values) if (v != null) { runCatching { v.second.close() }; runCatching { v.first.close() } }
            open.clear()
        }
    }
}
