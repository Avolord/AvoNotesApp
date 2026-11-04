package com.avonotes.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderNode
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.avonotes.model.NoteDocument
import com.avonotes.model.PdfDoc
import com.avonotes.model.PdfPageItem
import com.avonotes.model.Stroke
import com.avonotes.model.Tool
import com.avonotes.pdf.PdfPageCache
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The infinite canvas. Screen = world * zoom + (tx, ty).
 *
 * Input model:
 *  - Stylus (and mouse) always uses the current tool; the stylus eraser end or side button erases.
 *  - Fingers pan (one) and pinch-zoom (two). With "finger draws" on, one finger uses the tool
 *    and two fingers still pan/zoom.
 *  - While a stylus is down, finger touches are ignored (palm rejection).
 *  - Lasso tool: draw a loop to select ink (and PDF pages fully inside it).
 *  - Long-press a PDF page with a finger to select it.
 *  - Drag inside a selection (pen or finger) to move it; tap outside to deselect.
 */
class InfiniteCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onNoteChanged()
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean)
    }

    var listener: Listener? = null

    // ---------------------------------------------------------------- settings
    var tool = Tool.PEN
        set(v) {
            field = v
            if (v != Tool.LASSO) clearSelection()
        }
    var penColor = Color.BLACK
    var penWidth = 2.5f
    var highlighterColor = 0x66FFEB3B
    var highlighterWidth = 18f
    var fingerDraws = false
    var showGrid = true
        set(v) { field = v; invalidate() }
    private val density = resources.displayMetrics.density
    private val eraserRadiusPx = 14f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    // ---------------------------------------------------------------- view transform
    var zoom = density
        private set
    private var tx = 0f
    private var ty = 0f
    /** Extra top inset (toolbar) so new content isn't placed under it. */
    var topInset = 0

    // ---------------------------------------------------------------- content
    private val renderer = StrokeRenderer()
    private val tiles = TileIndex(renderer)
    private val pages = ArrayList<PdfPageItem>()
    private val pdfDocs = ArrayList<PdfDoc>()
    private var nextSeq = 0L
    private var pdfCache: PdfPageCache? = null
    private var loaded = false

    // ---------------------------------------------------------------- history
    private sealed class Edit {
        class AddStrokes(val strokes: List<Stroke>) : Edit()
        class RemoveStrokes(val strokes: List<Stroke>) : Edit()
        class AddPdf(val doc: PdfDoc, val pages: List<PdfPageItem>) : Edit()
        class Move(
            val oldStrokes: List<Stroke>, val newStrokes: List<Stroke>,
            val oldPages: List<PdfPageItem>, val newPages: List<PdfPageItem>,
        ) : Edit()
        /** [pages] are (original index, page), ascending by index. */
        class Delete(val strokes: List<Stroke>, val pages: List<Pair<Int, PdfPageItem>>) : Edit()
    }
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    // ---------------------------------------------------------------- selection
    private val selStrokes = ArrayList<Stroke>()
    private val selPages = ArrayList<PdfPageItem>()
    private val selBounds = RectF()
    val hasSelection get() = selStrokes.isNotEmpty() || selPages.isNotEmpty()
    private var moveDx = 0f
    private var moveDy = 0f
    private var moveStartX = 0f
    private var moveStartY = 0f
    private val floatingNode = RenderNode("selection").apply { setClipToBounds(false) }
    private val chipRect = RectF()

    // ---------------------------------------------------------------- input state
    private enum class Mode { NONE, DRAW, ERASE, LASSO, MOVE, GESTURE, CHIP }
    private var mode = Mode.NONE
    private var activePointer = -1
    private var livePts = FloatArray(3 * 256)
    private var liveCount = 0
    private var liveTool = Tool.PEN
    private var liveColor = 0
    private var liveWidth = 0f
    private val erased = ArrayList<Stroke>()
    private var eraserX = Float.NaN
    private var eraserY = Float.NaN
    private var lastEraseWX = Float.NaN
    private var lastEraseWY = Float.NaN
    private var lassoPts = FloatArray(2 * 256)
    private var lassoCount = 0

    // long press
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downPointer = -1
    private var panStarted = false
    private val longPress = Runnable { onLongPress() }

    // gesture
    private var gCx = 0f
    private var gCy = 0f
    private var gSpan = 0f
    private var gCount = 0

    // ---------------------------------------------------------------- drawing helpers
    private val visible = RectF()
    private val tmpRect = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pageFill = Paint().apply { color = Color.WHITE }
    private val pageBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x22000000; strokeWidth = 1f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; strokeCap = Paint.Cap.ROUND
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x88000000.toInt(); strokeWidth = 1.5f * density
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = ACCENT; strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val selFill = Paint().apply { color = 0x142E7D32 }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2B2B2B.toInt() }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14f * density; textAlign = Paint.Align.CENTER
    }
    private val lassoPath = Path()
    private var gridBuf = FloatArray(0)
    private var paperColor = 0xFFFCFCFA.toInt()
    private val patchPaint = Paint()

    /** Dark mode: dark paper, inverted PDF pages, pen ink shown light. Stored data is unchanged. */
    var dark = false
        set(v) {
            if (field == v) return
            field = v
            renderer.dark = v
            tiles.invalidateAll()
            floatingNode.discardDisplayList()
            if (mode == Mode.MOVE) recordFloating()
            paperColor = if (v) 0xFF1E1E1C.toInt() else 0xFFFCFCFA.toInt()
            pageFill.color = if (v) DarkInk.map(Color.WHITE) else Color.WHITE
            pageBorder.color = if (v) 0x33FFFFFF else 0x22000000
            gridPaint.color = if (v) 0x33FFFFFF else 0x33000000
            cursorPaint.color = if (v) 0x88FFFFFF.toInt() else 0x88000000.toInt()
            val accent = if (v) 0xFF81C784.toInt() else ACCENT
            dashPaint.color = accent
            selFill.color = (accent and 0x00FFFFFF) or (if (v) 0x22000000 else 0x14000000)
            chipPaint.color = if (v) 0xFFE6E6E3.toInt() else 0xFF2B2B2B.toInt()
            chipText.color = if (v) 0xFF1E1E1C.toInt() else Color.WHITE
            bmpPaint.colorFilter = if (v) DarkInk.filter else null
            patchPaint.colorFilter = bmpPaint.colorFilter
            invalidate()
        }

    private val patchRunnable = Runnable { requestPatches() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ================================================================ document

    fun setDocument(doc: NoteDocument, cache: PdfPageCache) {
        pdfCache?.close()
        pdfCache = cache
        tiles.clear()
        clearSelection()
        pages.clear(); pages.addAll(doc.pages)
        pdfDocs.clear(); pdfDocs.addAll(doc.pdfDocs)
        nextSeq = 0
        for (s in doc.strokes) { s.seq = nextSeq++; tiles.add(s) }
        undoStack.clear(); redoStack.clear()
        if (doc.viewZoom > 0f) { zoom = doc.viewZoom; tx = doc.viewX; ty = doc.viewY }
        else { zoom = density; tx = 24f * density; ty = topInset + 24f * density }
        loaded = true
        notifyHistory()
        invalidate()
    }

    fun release() {
        removeCallbacks(patchRunnable)
        removeCallbacks(longPress)
        pdfCache?.close()
        pdfCache = null
        tiles.clear()
        floatingNode.discardDisplayList()
    }

    /** Snapshot of the current content for saving (safe to hand to another thread). */
    fun snapshot(into: NoteDocument) {
        val strokes = tiles.allStrokes()
        if (mode == Mode.MOVE) { strokes.addAll(selStrokes); strokes.sortBy { it.seq } } // lifted strokes
        into.strokes.clear(); into.strokes.addAll(strokes)
        into.pages.clear(); into.pages.addAll(pages)
        into.pdfDocs.clear(); into.pdfDocs.addAll(pdfDocs)
        into.viewX = tx; into.viewY = ty; into.viewZoom = zoom
    }

    fun docFileName(docId: String): String? = pdfDocs.firstOrNull { it.id == docId }?.fileName

    /** World rect currently on screen (below the toolbar). */
    fun visibleWorld(): RectF = RectF(
        (0 - tx) / zoom, (topInset - ty) / zoom, (width - tx) / zoom, (height - ty) / zoom,
    )

    fun addPdf(doc: PdfDoc, pageSizes: List<Pair<Float, Float>>) {
        val v = visibleWorld()
        val gap = 24f
        val maxW = pageSizes.maxOfOrNull { it.first } ?: return
        val x = v.centerX() - maxW / 2f
        var y = v.top + gap
        val items = pageSizes.mapIndexed { i, (w, h) ->
            PdfPageItem(doc.id, i, x + (maxW - w) / 2f, y, w, h).also { y += h + gap }
        }
        clearSelection()
        apply(Edit.AddPdf(doc, items), record = true)
        // Fit the first page's width on screen.
        val targetZoom = min(width * 0.9f / maxW, zoom * 4f)
        zoomTo(targetZoom, x + maxW / 2f, items[0].y, width / 2f, topInset + gap * targetZoom)
    }

    // ================================================================ undo / redo

    fun undo() {
        if (mode != Mode.NONE && mode != Mode.GESTURE) return
        val e = undoStack.removeLastOrNull() ?: return
        clearSelection()
        revert(e); redoStack.addLast(e); changed()
    }

    fun redo() {
        if (mode != Mode.NONE && mode != Mode.GESTURE) return
        val e = redoStack.removeLastOrNull() ?: return
        clearSelection()
        apply(e, record = false); undoStack.addLast(e); changed()
    }

    private fun apply(e: Edit, record: Boolean) {
        when (e) {
            is Edit.AddStrokes -> e.strokes.forEach { tiles.add(it) }
            is Edit.RemoveStrokes -> e.strokes.forEach { tiles.remove(it) }
            is Edit.AddPdf -> { pdfDocs.add(e.doc); pages.addAll(e.pages) }
            is Edit.Move -> {
                e.oldStrokes.forEach { tiles.remove(it) }
                e.newStrokes.forEach { tiles.add(it) }
                replacePages(e.oldPages, e.newPages)
            }
            is Edit.Delete -> {
                e.strokes.forEach { tiles.remove(it) }
                pages.removeAll(e.pages.map { it.second }.toSet())
            }
        }
        if (record) {
            undoStack.addLast(e)
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            redoStack.clear()
            changed()
        }
    }

    private fun revert(e: Edit) {
        when (e) {
            is Edit.AddStrokes -> e.strokes.forEach { tiles.remove(it) }
            is Edit.RemoveStrokes -> e.strokes.forEach { tiles.add(it) }
            is Edit.AddPdf -> { pdfDocs.remove(e.doc); pages.removeAll(e.pages.toSet()) }
            is Edit.Move -> {
                e.newStrokes.forEach { tiles.remove(it) }
                e.oldStrokes.forEach { tiles.add(it) }
                replacePages(e.newPages, e.oldPages)
            }
            is Edit.Delete -> {
                e.strokes.forEach { tiles.add(it) }
                for ((i, p) in e.pages) pages.add(min(i, pages.size), p)
            }
        }
    }

    /** Swaps pages in place so their stacking order is preserved. */
    private fun replacePages(from: List<PdfPageItem>, to: List<PdfPageItem>) {
        for (k in from.indices) {
            val i = pages.indexOf(from[k])
            if (i >= 0) pages[i] = to[k] else pages.add(to[k])
        }
    }

    private fun changed() {
        invalidate()
        notifyHistory()
        listener?.onNoteChanged()
    }

    private fun notifyHistory() =
        listener?.onHistoryChanged(undoStack.isNotEmpty(), redoStack.isNotEmpty())

    // ================================================================ selection

    fun clearSelection() {
        if (!hasSelection) return
        selStrokes.clear(); selPages.clear(); selBounds.setEmpty()
        invalidate()
    }

    private fun setSelection(strokes: Collection<Stroke>, pgs: Collection<PdfPageItem>) {
        selStrokes.clear(); selStrokes.addAll(strokes); selStrokes.sortBy { it.seq }
        selPages.clear(); selPages.addAll(pgs)
        selBounds.setEmpty()
        for (s in selStrokes) if (selBounds.isEmpty) selBounds.set(s.bounds) else selBounds.union(s.bounds)
        for (p in selPages) if (selBounds.isEmpty) selBounds.set(p.bounds) else selBounds.union(p.bounds)
        invalidate()
    }

    fun deleteSelection() {
        if (!hasSelection || mode == Mode.MOVE) return
        val idx = selPages.map { pages.indexOf(it) to it }.filter { it.first >= 0 }.sortedBy { it.first }
        // Deleting a page leaves the handwriting that was on it.
        val strokes = selStrokes.filter { s ->
            selPages.none { it.bounds.contains(s.bounds.centerX(), s.bounds.centerY()) }
        }
        val e = Edit.Delete(strokes, idx)
        clearSelection()
        apply(e, record = true)
    }

    /** Selection rectangle in screen coordinates, including any in-progress move. */
    private fun selectionScreenRect(out: RectF): RectF {
        out.set(
            (selBounds.left + moveDx) * zoom + tx, (selBounds.top + moveDy) * zoom + ty,
            (selBounds.right + moveDx) * zoom + tx, (selBounds.bottom + moveDy) * zoom + ty,
        )
        return out
    }

    private fun startMove(e: MotionEvent, i: Int) = beginMove(e.getPointerId(i), e.getX(i), e.getY(i))

    /** Ink whose center lies on one of [pgs]: annotations travel with their page. */
    private fun strokesOnPages(pgs: Collection<PdfPageItem>, exclude: Collection<Stroke>): List<Stroke> {
        val out = LinkedHashSet<Stroke>()
        val skip = exclude.toHashSet()
        for (p in pgs) tiles.forEachIn(p.bounds) { s ->
            if (s !in skip && p.bounds.contains(s.bounds.centerX(), s.bounds.centerY())) out.add(s)
        }
        return out.toList()
    }

    private fun beginMove(pointerId: Int, x: Float, y: Float) {
        activePointer = pointerId
        moveStartX = x; moveStartY = y
        moveDx = 0f; moveDy = 0f
        mode = Mode.MOVE
        // Lift the selected ink out of the tiles into one floating display list.
        for (s in selStrokes) tiles.remove(s)
        recordFloating()
        invalidate()
    }

    private fun recordFloating() {
        if (selStrokes.isNotEmpty()) {
            val b = RectF(selStrokes[0].bounds)
            for (s in selStrokes) b.union(s.bounds)
            val l = floor(b.left).toInt(); val t = floor(b.top).toInt()
            floatingNode.setPosition(l, t, max(l + 1, ceil(b.right).toInt()), max(t + 1, ceil(b.bottom).toInt()))
            val c = floatingNode.beginRecording()
            try {
                c.translate(-l.toFloat(), -t.toFloat())
                for (s in selStrokes) renderer.draw(c, s.tool, s.color, s.width, s.points, s.pointCount)
            } finally {
                floatingNode.endRecording()
            }
        }
        invalidate()
    }

    private fun updateMove(e: MotionEvent) {
        val i = e.findPointerIndex(activePointer)
        if (i < 0) return
        moveDx = (e.getX(i) - moveStartX) / zoom
        moveDy = (e.getY(i) - moveStartY) / zoom
        invalidate()
    }

    private fun commitMove(cancel: Boolean = false) {
        if (mode != Mode.MOVE) return
        mode = Mode.NONE
        activePointer = -1
        val dx = moveDx; val dy = moveDy
        moveDx = 0f; moveDy = 0f
        floatingNode.discardDisplayList()
        if (cancel || (abs(dx) * zoom < 1f && abs(dy) * zoom < 1f)) {
            for (s in selStrokes) tiles.add(s) // put back unchanged
            invalidate()
            return
        }
        val newStrokes = selStrokes.map { it.translated(dx, dy) }
        val newPages = selPages.map { it.translated(dx, dy) }
        // Old strokes are already out of the tiles; apply() removing them again is a no-op.
        apply(Edit.Move(ArrayList(selStrokes), newStrokes, ArrayList(selPages), newPages), record = true)
        setSelection(newStrokes, newPages)
        if (newPages.isNotEmpty()) postDelayed(patchRunnable, PATCH_DELAY_MS) // re-sharpen moved pages
    }

    private fun finishLasso() {
        mode = Mode.NONE
        activePointer = -1
        val n = lassoCount
        lassoCount = 0
        if (n < 3) { clearSelection(); return }
        val b = RectF(lassoPts[0], lassoPts[1], lassoPts[0], lassoPts[1])
        for (k in 1 until n) b.union(lassoPts[k * 2], lassoPts[k * 2 + 1])
        val strokes = ArrayList<Stroke>()
        tiles.forEachIn(b) { s ->
            if (s.fractionInside(lassoPts, n) >= LASSO_FRACTION) strokes.add(s)
        }
        val pgs = pages.filter { p ->
            RectF.intersects(p.bounds, b) &&
                Stroke.pointInPolygon(p.x, p.y, lassoPts, n) &&
                Stroke.pointInPolygon(p.x + p.w, p.y, lassoPts, n) &&
                Stroke.pointInPolygon(p.x, p.y + p.h, lassoPts, n) &&
                Stroke.pointInPolygon(p.x + p.w, p.y + p.h, lassoPts, n)
        }
        strokes.addAll(strokesOnPages(pgs, strokes))
        if (strokes.isEmpty() && pgs.isEmpty()) clearSelection() else setSelection(strokes, pgs)
    }

    private fun addLassoPoint(sx: Float, sy: Float) {
        if ((lassoCount + 1) * 2 > lassoPts.size) lassoPts = lassoPts.copyOf(lassoPts.size * 2)
        lassoPts[lassoCount * 2] = (sx - tx) / zoom
        lassoPts[lassoCount * 2 + 1] = (sy - ty) / zoom
        lassoCount++
    }

    private fun onLongPress() {
        if (mode != Mode.GESTURE && mode != Mode.DRAW && mode != Mode.LASSO) return
        if (hypot(lastX - downX, lastY - downY) > touchSlop) return
        val wx = (downX - tx) / zoom
        val wy = (downY - ty) / zoom
        val page = pages.lastOrNull { it.bounds.contains(wx, wy) } ?: return
        // It was a long-press, not ink or a lasso: discard what was started.
        liveCount = 0; lassoCount = 0
        setSelection(strokesOnPages(listOf(page), emptyList()), listOf(page))
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Pick it up right away so the same touch can drag it.
        beginMove(downPointer, lastX, lastY)
    }

    // ================================================================ view navigation

    fun zoomToFit() {
        val b = RectF()
        var any = false
        for (t in tiles.tilesSnapshot()) { if (!any) b.set(t.bounds) else b.union(t.bounds); any = true }
        for (p in pages) { if (!any) b.set(p.bounds) else b.union(p.bounds); any = true }
        if (!any || b.width() <= 0f || b.height() <= 0f) { resetZoom(); return }
        val availH = (height - topInset).toFloat()
        val z = min(width * 0.92f / b.width(), availH * 0.92f / b.height()).coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoomTo(z, b.centerX(), b.centerY(), width / 2f, topInset + availH / 2f)
    }

    fun resetZoom() {
        val v = visibleWorld()
        zoomTo(density, v.centerX(), v.centerY(), width / 2f, topInset + (height - topInset) / 2f)
    }

    /** Sets zoom so world point (wx, wy) lands on screen point (sx, sy). */
    private fun zoomTo(z: Float, wx: Float, wy: Float, sx: Float, sy: Float) {
        zoom = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        tx = sx - wx * zoom
        ty = sy - wy * zoom
        transformChanged()
    }

    private fun transformChanged() {
        pdfCache?.cancelPatchRequests()
        removeCallbacks(patchRunnable)
        postDelayed(patchRunnable, PATCH_DELAY_MS)
        invalidate()
    }

    private fun requestPatches() {
        val cache = pdfCache ?: return
        val v = visibleWorld()
        for (p in pages) {
            if (RectF.intersects(p.bounds, v) && cache.needsPatch(p, zoom)) cache.requestPatch(p, v, zoom)
        }
    }

    // ================================================================ input

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!loaded) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(longPress)
                val i = e.actionIndex
                when (mode) {
                    Mode.DRAW, Mode.ERASE, Mode.LASSO -> {
                        val activeIsPen = isPen(e, e.findPointerIndex(activePointer))
                        if (!activeIsPen && !isPen(e, i)) {
                            // Second finger while using a tool by finger: it was a pinch.
                            cancelInk(); startGesture(e)
                        }
                    }
                    Mode.MOVE -> {
                        val activeIsPen = isPen(e, e.findPointerIndex(activePointer))
                        if (!activeIsPen && !isPen(e, i)) { commitMove(); startGesture(e) }
                    }
                    Mode.GESTURE -> if (isPen(e, i)) { mode = Mode.NONE; startInk(e, i) } else startGesture(e)
                    Mode.NONE -> if (isPen(e, i)) startInk(e, i)
                    Mode.CHIP -> {}
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val ai = if (activePointer >= 0) e.findPointerIndex(activePointer) else 0
                if (ai >= 0) {
                    lastX = e.getX(ai); lastY = e.getY(ai)
                    if (hypot(lastX - downX, lastY - downY) > touchSlop) removeCallbacks(longPress)
                }
                when (mode) {
                    Mode.DRAW -> moveDraw(e)
                    Mode.ERASE -> moveErase(e)
                    Mode.LASSO -> moveLasso(e)
                    Mode.MOVE -> updateMove(e)
                    Mode.GESTURE -> moveGesture(e)
                    Mode.NONE, Mode.CHIP -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val canceled = e.flags and MotionEvent.FLAG_CANCELED != 0
                if (id == activePointer) {
                    when (mode) {
                        Mode.DRAW, Mode.ERASE -> if (canceled) cancelInk() else finishInk(e)
                        Mode.LASSO -> if (canceled) cancelInk() else finishLasso()
                        Mode.MOVE -> commitMove(cancel = canceled)
                        else -> {}
                    }
                } else if (mode == Mode.GESTURE) startGesture(e, excludeIndex = e.actionIndex)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val canceled = e.flags and MotionEvent.FLAG_CANCELED != 0
                when (mode) {
                    Mode.DRAW, Mode.ERASE -> if (canceled) cancelInk() else finishInk(e)
                    Mode.LASSO -> if (canceled) cancelInk() else finishLasso()
                    Mode.MOVE -> commitMove(cancel = canceled)
                    Mode.CHIP -> if (chipRect.contains(e.x, e.y)) deleteSelection()
                    Mode.GESTURE -> {
                        // A tap (no real movement) outside the selection deselects.
                        if (hasSelection && hypot(e.x - downX, e.y - downY) <= touchSlop) clearSelection()
                    }
                    Mode.NONE -> {}
                }
                mode = Mode.NONE
                activePointer = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (mode == Mode.MOVE) commitMove(cancel = true) else cancelInk()
                mode = Mode.NONE
            }
        }
        return true
    }

    private fun onDown(e: MotionEvent) {
        val x = e.x; val y = e.y
        downX = x; downY = y; lastX = x; lastY = y
        downPointer = e.getPointerId(0)
        panStarted = false
        val pen = isPen(e, 0)
        val eraserIntent = pen && wantsEraser(e, 0)

        if (hasSelection && !eraserIntent) {
            if (chipRect.contains(x, y)) { mode = Mode.CHIP; return }
            val r = selectionScreenRect(tmpRect)
            r.inset(-touchSlop, -touchSlop)
            if (r.contains(x, y)) { startMove(e, 0); return }
            if (pen || fingerDraws) clearSelection() // a finger outside may just be panning
        }

        if (pen || fingerDraws) startInk(e, 0) else startGesture(e)

        // Long-press picks up a PDF page: with a finger always; with the pen only in lasso mode.
        if (!pen || tool == Tool.LASSO) postDelayed(longPress, longPressMs)
    }

    private fun isPen(e: MotionEvent, i: Int): Boolean {
        if (i < 0) return false
        return when (e.getToolType(i)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER, MotionEvent.TOOL_TYPE_MOUSE -> true
            else -> false
        }
    }

    private fun wantsEraser(e: MotionEvent, i: Int): Boolean =
        e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0

    private fun startInk(e: MotionEvent, i: Int) {
        requestUnbufferedDispatch(e) // deliver stylus samples at full rate, not vsync-batched
        activePointer = e.getPointerId(i)
        val x = e.getX(i); val y = e.getY(i)
        if (tool == Tool.ERASER || wantsEraser(e, i)) {
            clearSelection()
            mode = Mode.ERASE
            erased.clear()
            lastEraseWX = Float.NaN
            eraseAt(x, y)
        } else if (tool == Tool.LASSO) {
            clearSelection()
            mode = Mode.LASSO
            lassoCount = 0
            addLassoPoint(x, y)
        } else {
            mode = Mode.DRAW
            liveTool = tool
            liveColor = if (tool == Tool.HIGHLIGHTER) highlighterColor else penColor
            liveWidth = if (tool == Tool.HIGHLIGHTER) highlighterWidth else penWidth
            liveCount = 0
            addPoint(x, y, pressureOf(e, i, -1))
        }
        invalidate()
    }

    private fun pressureOf(e: MotionEvent, i: Int, h: Int): Float {
        val t = e.getToolType(i)
        if (t != MotionEvent.TOOL_TYPE_STYLUS && t != MotionEvent.TOOL_TYPE_ERASER) return 0.75f
        return if (h < 0) e.getPressure(i) else e.getHistoricalPressure(i, h)
    }

    private fun moveDraw(e: MotionEvent) {
        val i = e.findPointerIndex(activePointer)
        if (i < 0) return
        for (h in 0 until e.historySize) {
            addPoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h), pressureOf(e, i, h))
        }
        addPoint(e.getX(i), e.getY(i), pressureOf(e, i, -1))
        invalidate()
    }

    private fun moveLasso(e: MotionEvent) {
        val i = e.findPointerIndex(activePointer)
        if (i < 0) return
        for (h in 0 until e.historySize) addLassoPoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
        addLassoPoint(e.getX(i), e.getY(i))
        invalidate()
    }

    private fun addPoint(sx: Float, sy: Float, p: Float) {
        val wx = (sx - tx) / zoom
        val wy = (sy - ty) / zoom
        if (liveCount > 0) {
            val lx = livePts[(liveCount - 1) * 3]; val ly = livePts[(liveCount - 1) * 3 + 1]
            val minD = MIN_POINT_DIST_PX / zoom
            if (hypot(wx - lx, wy - ly) < minD) {
                // Too close: just update pressure of the last sample.
                livePts[(liveCount - 1) * 3 + 2] = max(livePts[(liveCount - 1) * 3 + 2], p)
                return
            }
        }
        if ((liveCount + 1) * 3 > livePts.size) livePts = livePts.copyOf(livePts.size * 2)
        livePts[liveCount * 3] = wx
        livePts[liveCount * 3 + 1] = wy
        livePts[liveCount * 3 + 2] = p
        liveCount++
    }

    private fun moveErase(e: MotionEvent) {
        val i = e.findPointerIndex(activePointer)
        if (i < 0) return
        for (h in 0 until e.historySize) eraseAt(e.getHistoricalX(i, h), e.getHistoricalY(i, h))
        eraseAt(e.getX(i), e.getY(i))
        invalidate()
    }

    private fun eraseAt(sx: Float, sy: Float) {
        eraserX = sx; eraserY = sy
        val wx = (sx - tx) / zoom
        val wy = (sy - ty) / zoom
        val r = eraserRadiusPx / zoom
        // Sample along the path since the last event so fast swipes don't skip strokes.
        if (!lastEraseWX.isNaN()) {
            val d = hypot(wx - lastEraseWX, wy - lastEraseWY)
            val steps = (d / (r * 0.75f)).toInt()
            for (k in 1..steps) {
                val f = k.toFloat() / (steps + 1)
                eraseWorld(lastEraseWX + (wx - lastEraseWX) * f, lastEraseWY + (wy - lastEraseWY) * f, r)
            }
        }
        eraseWorld(wx, wy, r)
        lastEraseWX = wx; lastEraseWY = wy
    }

    private fun eraseWorld(wx: Float, wy: Float, r: Float) {
        tmpRect.set(wx - r, wy - r, wx + r, wy + r)
        tiles.forEachIn(tmpRect) { s ->
            if (s.hitTest(wx, wy, r)) { tiles.remove(s); erased.add(s) }
        }
    }

    private fun finishInk(e: MotionEvent) {
        when (mode) {
            Mode.DRAW -> {
                val i = e.findPointerIndex(activePointer)
                if (i >= 0) addPoint(e.getX(i), e.getY(i), pressureOf(e, i, -1))
                if (liveCount > 0) {
                    val s = Stroke(liveTool, liveColor, liveWidth, livePts.copyOf(liveCount * 3))
                    s.seq = nextSeq++
                    apply(Edit.AddStrokes(listOf(s)), record = true)
                }
                liveCount = 0
            }
            Mode.ERASE -> {
                if (erased.isNotEmpty()) apply(Edit.RemoveStrokes(ArrayList(erased)), record = true)
                erased.clear()
                eraserX = Float.NaN
            }
            else -> {}
        }
        mode = Mode.NONE
        activePointer = -1
        invalidate()
    }

    private fun cancelInk() {
        if (mode == Mode.ERASE) erased.forEach { tiles.add(it) } // put back what was erased
        erased.clear()
        liveCount = 0
        lassoCount = 0
        eraserX = Float.NaN
        if (mode == Mode.DRAW || mode == Mode.ERASE || mode == Mode.LASSO) mode = Mode.NONE
        activePointer = -1
        invalidate()
    }

    /** (Re)anchors the pan/zoom gesture on the current finger pointers. */
    private fun startGesture(e: MotionEvent, excludeIndex: Int = -1) {
        mode = Mode.GESTURE
        computeFingerCentroid(e, excludeIndex)
    }

    private fun computeFingerCentroid(e: MotionEvent, excludeIndex: Int): Boolean {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex || isPen(e, i)) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        if (n == 0) { gCount = 0; return false }
        val cx = sx / n; val cy = sy / n
        var span = 0f
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex || isPen(e, i)) continue
            span += hypot(e.getX(i) - cx, e.getY(i) - cy)
        }
        gCx = cx; gCy = cy; gSpan = span / n; gCount = n
        return true
    }

    private fun moveGesture(e: MotionEvent) {
        val pcx = gCx; val pcy = gCy; val pspan = gSpan; val pcount = gCount
        if (!computeFingerCentroid(e, -1) || gCount != pcount) return
        // Hold still for a possible long-press until the finger clearly moves.
        if (!panStarted) {
            if (gCount == 1 && hypot(gCx - downX, gCy - downY) <= touchSlop) { gCx = pcx; gCy = pcy; return }
            panStarted = true
        }
        var newZoom = zoom
        if (gCount >= 2 && pspan > 0f && gSpan > 0f) newZoom = (zoom * gSpan / pspan).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val wx = (pcx - tx) / zoom
        val wy = (pcy - ty) / zoom
        zoom = newZoom
        tx = gCx - wx * zoom
        ty = gCy - wy * zoom
        transformChanged()
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (loaded && e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (e.metaState and KeyEvent.META_CTRL_ON != 0) {
                val f = if (v > 0) 1.15f else 1f / 1.15f
                val wx = (e.x - tx) / zoom; val wy = (e.y - ty) / zoom
                zoomTo(zoom * f, wx, wy, e.x, e.y)
            } else {
                tx -= h * 80f * density
                ty += v * 80f * density
                transformChanged()
            }
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    // ================================================================ rendering

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(paperColor)
        if (!loaded) return
        visible.set((0 - tx) / zoom, (0 - ty) / zoom, (width - tx) / zoom, (height - ty) / zoom)

        if (showGrid) drawGrid(canvas)
        drawPages(canvas)

        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(zoom, zoom)
        tiles.draw(canvas, visible)
        if (mode == Mode.MOVE && selStrokes.isNotEmpty()) {
            canvas.translate(moveDx, moveDy)
            if (canvas.isHardwareAccelerated && floatingNode.hasDisplayList()) canvas.drawRenderNode(floatingNode)
            else for (s in selStrokes) renderer.draw(canvas, s.tool, s.color, s.width, s.points, s.pointCount)
            canvas.translate(-moveDx, -moveDy)
        }
        if (mode == Mode.DRAW && liveCount > 0) {
            renderer.draw(canvas, liveTool, liveColor, liveWidth, livePts, liveCount)
        }
        canvas.restore()

        if (mode == Mode.LASSO && lassoCount > 1) drawLasso(canvas)
        if (hasSelection) drawSelection(canvas)
        if (mode == Mode.ERASE && !eraserX.isNaN()) canvas.drawCircle(eraserX, eraserY, eraserRadiusPx, cursorPaint)
    }

    private fun drawLasso(canvas: Canvas) {
        lassoPath.rewind()
        lassoPath.moveTo(lassoPts[0] * zoom + tx, lassoPts[1] * zoom + ty)
        for (k in 1 until lassoCount) lassoPath.lineTo(lassoPts[k * 2] * zoom + tx, lassoPts[k * 2 + 1] * zoom + ty)
        canvas.drawPath(lassoPath, dashPaint)
    }

    private fun drawSelection(canvas: Canvas) {
        val r = selectionScreenRect(tmpRect)
        val pad = 6f * density
        r.inset(-pad, -pad)
        canvas.drawRect(r, selFill)
        canvas.drawRect(r, dashPaint)
        if (mode == Mode.MOVE) { chipRect.setEmpty(); return }
        // "Delete" chip above the selection (or below if that would be off screen).
        val w = 76f * density; val h = 32f * density; val gap = 8f * density
        var top = r.top - gap - h
        if (top < topInset + gap) top = r.bottom + gap
        val left = (r.right - w).coerceIn(gap, max(gap, width - w - gap))
        chipRect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(chipRect, h / 2, h / 2, chipPaint)
        val ty0 = chipRect.centerY() - (chipText.descent() + chipText.ascent()) / 2
        canvas.drawText(context.getString(com.avonotes.R.string.delete), chipRect.centerX(), ty0, chipText)
    }

    private fun drawPages(canvas: Canvas) {
        val cache = pdfCache ?: return
        cache.beginFrame()
        val moving = mode == Mode.MOVE && selPages.isNotEmpty()
        for (p in pages) {
            val lifted = moving && p in selPages
            val ox = if (lifted) moveDx else 0f
            val oy = if (lifted) moveDy else 0f
            val l = p.x + ox; val t = p.y + oy
            if (l > visible.right || l + p.w < visible.left || t > visible.bottom || t + p.h < visible.top) continue
            val pageRect = RectF(l * zoom + tx, t * zoom + ty, (l + p.w) * zoom + tx, (t + p.h) * zoom + ty)
            canvas.drawRect(pageRect, pageFill)
            val bmp = cache.bitmapFor(p, zoom)
            if (bmp != null) canvas.drawBitmap(bmp, null, pageRect, bmpPaint)
            if (!lifted) cache.patchFor(p, zoom)?.let { patch ->
                val r = patch.region
                tmpRect.set(r.left * zoom + tx, r.top * zoom + ty, r.right * zoom + tx, r.bottom * zoom + ty)
                canvas.drawBitmap(patch.bitmap, null, tmpRect, patchPaint)
            }
            canvas.drawRect(pageRect, pageBorder)
        }
        cache.endFrame()
    }

    private fun drawGrid(canvas: Canvas) {
        var spacing = GRID_SPACING * zoom
        while (spacing < 18f * density) spacing *= 4f
        val ox = ((tx % spacing) + spacing) % spacing
        val oy = ((ty % spacing) + spacing) % spacing
        val cols = (width / spacing).toInt() + 2
        val rows = (height / spacing).toInt() + 2
        val n = cols * rows * 2
        if (gridBuf.size < n) gridBuf = FloatArray(n)
        var k = 0
        for (r in 0 until rows) {
            val y = oy + r * spacing
            for (c in 0 until cols) {
                gridBuf[k++] = ox + c * spacing
                gridBuf[k++] = y
            }
        }
        gridPaint.strokeWidth = 2f * density
        canvas.drawPoints(gridBuf, 0, k, gridPaint)
    }

    companion object {
        const val MIN_ZOOM = 0.02f
        const val MAX_ZOOM = 40f
        private const val MAX_HISTORY = 300
        private const val MIN_POINT_DIST_PX = 0.75f
        private const val GRID_SPACING = 32f
        private const val PATCH_DELAY_MS = 180L
        private const val LASSO_FRACTION = 0.5f
        private const val ACCENT = 0xFF2E7D32.toInt()
    }
}
