package com.avonotes.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.avonotes.model.PdfPageItem
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders PDF pages to bitmaps off the UI thread.
 *
 * - Whole pages are rendered at power-of-two "levels" (pixels per world unit = 2^level)
 *   and kept in an LRU cache, so zooming in and out reuses work.
 * - Whole-page bitmaps are capped at [MAX_PAGE_PIXELS]; beyond that, a "patch" covering
 *   just the visible part of the page is rendered at exact screen resolution once the
 *   view stops moving, so small text stays sharp at any zoom.
 * - Requests are processed newest-first and dropped if the page scrolled out of view,
 *   so flinging through a 500-page document never builds up a backlog.
 *
 * All [PdfRenderer] access happens on the single worker thread (it is not thread-safe).
 */
class PdfPageCache(
    private val pdfDir: File,
    private val fileNameForDoc: (String) -> String?,
    private val onRendered: () -> Unit,
) {
    class Patch(val item: PdfPageItem, val region: RectF, val zoom: Float, val bitmap: Bitmap)

    private sealed class Job {
        abstract val key: String
        class Page(override val key: String, val item: PdfPageItem, val level: Int) : Job()
        class PatchJob(override val key: String, val item: PdfPageItem, val region: RectF, val zoom: Float) : Job()
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingDeque<Job>()
    private val pending = HashSet<String>() // main thread only

    // Keys still wanted; replaced from the main thread every frame, read by the worker.
    @Volatile private var wanted: Set<String> = emptySet()
    @Volatile private var wantedPatches: Set<String> = emptySet()
    private val wantedBuilder = HashSet<String>()

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val patches = HashMap<PdfPageItem, Patch>() // main thread only

    // Worker-thread state.
    private val renderers = HashMap<String, Pair<ParcelFileDescriptor, PdfRenderer>>()
    @Volatile private var closed = false

    private val worker = Thread({ workLoop() }, "pdf-render").apply {
        priority = Thread.NORM_PRIORITY - 1
        isDaemon = true
        start()
    }

    // ---------------------------------------------------------------- main thread API

    /** Call at the start of each frame before [bitmapFor]/[patchFor]. */
    fun beginFrame() {
        wantedBuilder.clear()
    }

    /** Call at the end of each frame. */
    fun endFrame() {
        wanted = HashSet(wantedBuilder)
    }

    /**
     * Best available whole-page bitmap for [item] at [zoom] (screen px per world unit).
     * Requests the ideal level if it isn't cached yet.
     */
    fun bitmapFor(item: PdfPageItem, zoom: Float): Bitmap? {
        val ideal = idealLevel(item, zoom)
        val key = key(item, ideal)
        wantedBuilder.add(key)
        cache.get(key)?.let { return it }
        enqueue(Job.Page(key, item, ideal))
        // Fall back to any cached level, preferring sharper ones.
        for (l in ideal - 1 downTo MIN_LEVEL) cache.get(key(item, l))?.let { return it }
        for (l in ideal + 1..ideal + 4) cache.get(key(item, l))?.let { return it }
        return null
    }

    /** True if whole-page rendering at this zoom would be blurry (patch needed). */
    fun needsPatch(item: PdfPageItem, zoom: Float): Boolean {
        val l = idealLevel(item, zoom)
        return pow2(l) < zoom * 0.95f
    }

    /** Current patch for [item], if one exists and matches [zoom]. */
    fun patchFor(item: PdfPageItem, zoom: Float): Patch? {
        val p = patches[item] ?: return null
        return if (kotlin.math.abs(p.zoom - zoom) / zoom < 0.01f) p else null
    }

    /** Requests a sharp patch of [visibleWorld] ∩ page at [zoom]. Call once the view is idle. */
    fun requestPatch(item: PdfPageItem, visibleWorld: RectF, zoom: Float) {
        val region = RectF(item.bounds)
        if (!region.intersect(visibleWorld)) return
        // Snap the region outward to whole screen pixels to avoid seams.
        region.set(
            floor(region.left * zoom) / zoom, floor(region.top * zoom) / zoom,
            ceil(region.right * zoom) / zoom, ceil(region.bottom * zoom) / zoom,
        )
        val existing = patches[item]
        if (existing != null && existing.zoom == zoom && existing.region.contains(region)) return
        val key = "patch:${System.identityHashCode(item)}:$zoom:${region}"
        wantedPatches = wantedPatches + key
        enqueue(Job.PatchJob(key, item, region, zoom))
    }

    /** The view moved: outstanding patch requests are stale. */
    fun cancelPatchRequests() {
        wantedPatches = emptySet()
    }

    fun close() {
        closed = true
        queue.clear()
        worker.interrupt()
        cache.evictAll()
        patches.clear()
    }

    private fun enqueue(job: Job) {
        if (closed || !pending.add(job.key)) return
        queue.offerFirst(job) // newest first
    }

    // ---------------------------------------------------------------- worker

    private fun workLoop() {
        try {
            while (!closed) {
                val job = queue.takeFirst()
                if (job.key !in wanted && job.key !in wantedPatches) {
                    main.post { pending.remove(job.key) }
                    continue
                }
                val result: Any? = try {
                    when (job) {
                        is Job.Page -> renderPage(job)
                        is Job.PatchJob -> renderPatch(job)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "render failed for ${job.key}", t)
                    null
                }
                main.post {
                    pending.remove(job.key)
                    if (closed) return@post
                    when (result) {
                        is Bitmap -> cache.put(job.key, result)
                        is Patch -> {
                            // Patches are screen-sized; keep only a handful at the current zoom.
                            patches.entries.removeAll { it.value.zoom != result.zoom }
                            if (patches.size >= MAX_PATCHES) patches.clear()
                            patches[result.item] = result
                        }
                    }
                    if (result != null) onRendered()
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            for ((pfd, r) in renderers.values) {
                runCatching { r.close() }
                runCatching { pfd.close() }
            }
            renderers.clear()
        }
    }

    private fun rendererFor(docId: String): PdfRenderer? {
        renderers[docId]?.let { return it.second }
        val name = fileNameForDoc(docId) ?: return null
        val f = File(pdfDir, name)
        if (!f.exists()) return null
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = try {
            PdfRenderer(pfd)
        } catch (t: Throwable) {
            pfd.close(); throw t
        }
        renderers[docId] = pfd to r
        return r
    }

    private fun renderPage(job: Job.Page): Bitmap? {
        val r = rendererFor(job.item.docId) ?: return null
        if (job.item.pageIndex >= r.pageCount) return null
        val scale = pow2(job.level)
        val w = max(1, (job.item.w * scale).roundToInt())
        val h = max(1, (job.item.h * scale).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        r.openPage(job.item.pageIndex).use { page ->
            val m = Matrix()
            m.setScale(w.toFloat() / page.width, h.toFloat() / page.height)
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        return bmp
    }

    private fun renderPatch(job: Job.PatchJob): Patch? {
        val r = rendererFor(job.item.docId) ?: return null
        if (job.item.pageIndex >= r.pageCount) return null
        val w = max(1, (job.region.width() * job.zoom).roundToInt())
        val h = max(1, (job.region.height() * job.zoom).roundToInt())
        if (w.toLong() * h > MAX_PATCH_PIXELS) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        r.openPage(job.item.pageIndex).use { page ->
            // page units -> world units -> patch pixels
            val sx = job.item.w / page.width
            val sy = job.item.h / page.height
            val m = Matrix()
            m.setScale(sx * job.zoom, sy * job.zoom)
            m.postTranslate(
                (job.item.x - job.region.left) * job.zoom,
                (job.item.y - job.region.top) * job.zoom,
            )
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        return Patch(job.item, RectF(job.region), job.zoom, bmp)
    }

    // ---------------------------------------------------------------- helpers

    private fun idealLevel(item: PdfPageItem, zoom: Float): Int {
        var l = ceil(ln(zoom.toDouble()) / LN2).toInt().coerceIn(MIN_LEVEL, MAX_LEVEL)
        while (l > MIN_LEVEL) {
            val s = pow2(l)
            if ((item.w * s).toDouble() * (item.h * s) <= MAX_PAGE_PIXELS) break
            l--
        }
        return l
    }

    // The size is part of the key: the same page can be placed (copied, resized) more than once.
    private fun key(item: PdfPageItem, level: Int) =
        "${item.docId}#${item.pageIndex}:${item.w.roundToInt()}x${item.h.roundToInt()}@$level"

    companion object {
        private const val TAG = "PdfPageCache"
        private val LN2 = ln(2.0)
        private const val MIN_LEVEL = -5
        private const val MAX_LEVEL = 4
        private const val MAX_PAGE_PIXELS = 4_000_000.0
        private const val MAX_PATCH_PIXELS = 16_000_000L
        private const val MAX_PATCHES = 6

        fun pow2(level: Int): Float =
            if (level >= 0) (1 shl level).toFloat() else 1f / (1 shl -level)

        private fun cacheBytes(): Int =
            min(Runtime.getRuntime().maxMemory() / 4, Int.MAX_VALUE.toLong()).toInt()

        /** Reads page sizes (in PDF points) of [file]. Throws if the PDF can't be opened. */
        fun readPageSizes(file: File): List<Pair<Float, Float>> {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    return (0 until r.pageCount).map { i ->
                        r.openPage(i).use { p -> p.width.toFloat() to p.height.toFloat() }
                    }
                }
            }
        }
    }
}
