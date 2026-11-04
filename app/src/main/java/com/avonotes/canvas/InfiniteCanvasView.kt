package com.avonotes.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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
import com.avonotes.model.Pattern
import com.avonotes.model.PdfDoc
import com.avonotes.model.PdfPageItem
import com.avonotes.model.Stroke
import com.avonotes.model.Tool
import com.avonotes.pdf.PdfPageCache
import kotlin.math.abs
import kotlin.math.atan2
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
        /** A short confirmation to show the user (e.g. "Copied"). */
        fun onMessage(textRes: Int) {}
    }

    var listener: Listener? = null

    // ---------------------------------------------------------------- settings
    var tool = Tool.PEN
        set(v) {
            field = v
            if (v != Tool.LASSO) clearSelection()
        }
    /** Which brush the pen tool draws with: PEN, FOUNTAIN, PENCIL or MARKER. */
    var penBrush = Tool.PEN
    var penColor = Color.BLACK
    var penWidth = 2.5f
    var highlighterColor = 0x66FFEB3B
    var highlighterWidth = 18f
    var fingerDraws = false
    /** Paper pattern of the open note (per note; dots by default). */
    var pattern = Pattern.DOTS
        set(v) { field = v; invalidate() }
    /** Partial eraser: cuts away just the ink under the eraser. Off: erases whole strokes. */
    var partialEraser = false
    /** Eraser radius in dp (screen size, independent of zoom). */
    var eraserSize = DEFAULT_ERASER_SIZE
    private val density = resources.displayMetrics.density
    private val eraserRadiusPx get() = eraserSize * density
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
        /** Partial erase: [old] strokes were cut into the remaining pieces [new]. */
        class Replace(val old: List<Stroke>, val new: List<Stroke>) : Edit()
        class AddPdf(val doc: PdfDoc, val pages: List<PdfPageItem>) : Edit()
        class Move(
            val oldStrokes: List<Stroke>, val newStrokes: List<Stroke>,
            val oldPages: List<PdfPageItem>, val newPages: List<PdfPageItem>,
        ) : Edit()
        /** [pages] are (original index, page), ascending by index. */
        class Delete(val strokes: List<Stroke>, val pages: List<Pair<Int, PdfPageItem>>) : Edit()
        /** Paste / duplicate: new ink and pages on top. */
        class AddItems(val strokes: List<Stroke>, val pages: List<PdfPageItem>) : Edit()
    }
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    // ---------------------------------------------------------------- selection
    private val selStrokes = ArrayList<Stroke>()
    private val selPages = ArrayList<PdfPageItem>()
    private val selBounds = RectF()
    val hasSelection get() = selStrokes.isNotEmpty() || selPages.isNotEmpty()

    /** What a drag on the selection does: move it, resize it from a corner, or rotate it. */
    private enum class Xform { MOVE, SCALE, ROTATE }
    private var xform = Xform.MOVE
    /** The in-progress transform of the lifted selection, in world coordinates. */
    private val liveMatrix = Matrix()
    private var moveStartX = 0f
    private var moveStartY = 0f
    private var pivotX = 0f // scale anchor (opposite corner) or rotation center, world
    private var pivotY = 0f
    private var startDist = 1f
    private var startAngle = 0f
    private var liveAngle = 0f
    private val floatingNode = RenderNode("selection").apply { setClipToBounds(false) }

    /** Action chips under the selection (screen coordinates), in [CHIP_ACTIONS] order. */
    private val chipRects = Array(3) { RectF() }
    private var pressedChip = -1
    /** Corner resize handles and the rotate handle (screen coordinates; empty when hidden). */
    private val cornerHandles = FloatArray(8)
    private val rotateHandle = PointF(Float.NaN, Float.NaN)
    private var handleCorner = -1

    /** "Paste" chip shown after a long-press on empty canvas, at [pasteX], [pasteY] (world). */
    private val pasteChip = RectF()
    private var pasteX = 0f
    private var pasteY = 0f

    // ---------------------------------------------------------------- input state
    private enum class Mode { NONE, DRAW, ERASE, LASSO, MOVE, GESTURE, CHIP }
    private var mode = Mode.NONE
    private var activePointer = -1
    private var livePts = FloatArray(3 * 256)
    private var liveCount = 0
    private var liveTool = Tool.PEN
    private var liveColor = 0
    private var liveWidth = 0f
    /** Strokes that existed before this erase gesture and were removed or cut by it. */
    private val erased = ArrayList<Stroke>()
    /** Pieces left by cutting during this gesture (partial eraser), currently in the tiles. */
    private val erasePieces = HashSet<Stroke>()
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
    private val linePaint = Paint().apply { style = Paint.Style.STROKE }
    private var lineColor = 0x1F000000
    private var majorLineColor = 0x30000000
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
    private val tmpRect2 = RectF()
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = ACCENT; strokeWidth = 2f * density
    }
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
            lineColor = if (v) 0x1FFFFFFF else 0x1F000000
            majorLineColor = if (v) 0x33FFFFFF else 0x30000000
            cursorPaint.color = if (v) 0x88FFFFFF.toInt() else 0x88000000.toInt()
            val accent = if (v) 0xFF81C784.toInt() else ACCENT
            dashPaint.color = accent
            handleStroke.color = accent
            handleFill.color = if (v) 0xFF2B2B29.toInt() else Color.WHITE
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
            is Edit.Replace -> { e.old.forEach { tiles.remove(it) }; e.new.forEach { tiles.add(it) } }
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
            is Edit.AddItems -> { e.strokes.forEach { tiles.add(it) }; pages.addAll(e.pages) }
        }
        if (record) record(e)
    }

    /** Pushes an edit that is already reflected in the content onto the undo history. */
    private fun record(e: Edit) {
        undoStack.addLast(e)
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        changed()
    }

    private fun revert(e: Edit) {
        when (e) {
            is Edit.AddStrokes -> e.strokes.forEach { tiles.remove(it) }
            is Edit.RemoveStrokes -> e.strokes.forEach { tiles.add(it) }
            is Edit.Replace -> { e.new.forEach { tiles.remove(it) }; e.old.forEach { tiles.add(it) } }
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
            is Edit.AddItems -> { e.strokes.forEach { tiles.remove(it) }; pages.removeAll(e.pages.toSet()) }
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

    // ---------------------------------------------------------------- copy / paste

    /**
     * Copied ink and pages, relative to the copied area's top-left corner. Shared by all open
     * notes (it lives as long as the app). Pages only paste into the note they came from, since
     * they refer to that note's PDF files.
     */
    private class Clip(val strokes: List<Stroke>, val pages: List<PdfPageItem>, val docIds: Set<String>, val w: Float, val h: Float)

    fun copySelection(): Boolean {
        if (!hasSelection || mode == Mode.MOVE) return false
        val m = Matrix().apply { setTranslate(-selBounds.left, -selBounds.top) }
        clip = Clip(
            selStrokes.map { it.transformed(m, 1f) },
            selPages.map { it.transformed(m) },
            selPages.map { it.docId }.toSet(),
            selBounds.width(), selBounds.height(),
        )
        listener?.onMessage(com.avonotes.R.string.copied)
        return true
    }

    val canPaste get() = clip != null

    /** Copies the selection and pastes it right next to itself (offset down and right). */
    fun duplicateSelection() {
        if (!hasSelection || mode == Mode.MOVE) return
        val c = Clip(
            selStrokes.map { it.transformed(Matrix(), 1f) }, ArrayList(selPages), emptySet(), 0f, 0f,
        )
        val off = DUPLICATE_OFFSET_DP * density / zoom
        val m = Matrix().apply { setTranslate(off, off) }
        addCopies(c.strokes, c.pages, m)
    }

    /** Pastes the clipboard centered on world point (wx, wy), or the middle of the screen. */
    fun paste(wx: Float = Float.NaN, wy: Float = Float.NaN) {
        val c = clip ?: return
        if (mode != Mode.NONE && mode != Mode.GESTURE) return
        val v = visibleWorld()
        val cx = if (wx.isNaN()) v.centerX() else wx
        val cy = if (wy.isNaN()) v.centerY() else wy
        val m = Matrix().apply { setTranslate(cx - c.w / 2f, cy - c.h / 2f) }
        // Pages refer to this note's PDFs; from another note only the ink comes along.
        val ownDocs = pdfDocs.map { it.id }.toSet()
        val pgs = c.pages.filter { it.docId in ownDocs }
        if (pgs.size < c.pages.size) listener?.onMessage(com.avonotes.R.string.paste_pages_skipped)
        addCopies(c.strokes, pgs, m)
    }

    private fun addCopies(strokes: List<Stroke>, pgs: List<PdfPageItem>, m: Matrix) {
        if (strokes.isEmpty() && pgs.isEmpty()) return
        val newStrokes = strokes.sortedBy { it.seq }.map { s -> s.transformed(m, 1f).also { it.seq = nextSeq++ } }
        val newPages = pgs.map { it.transformed(m) }
        clearSelection()
        apply(Edit.AddItems(newStrokes, newPages), record = true)
        setSelection(newStrokes, newPages)
        if (newPages.isNotEmpty()) postDelayed(patchRunnable, PATCH_DELAY_MS)
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

    /** Selection rectangle in screen coordinates (untransformed; see [drawSelection] while dragging). */
    private fun selectionScreenRect(out: RectF): RectF {
        out.set(
            selBounds.left * zoom + tx, selBounds.top * zoom + ty,
            selBounds.right * zoom + tx, selBounds.bottom * zoom + ty,
        )
        return out
    }

    private fun startMove(e: MotionEvent, i: Int) = beginMove(e.getPointerId(i), e.getX(i), e.getY(i))

    /** Rotating pages isn't supported (they stay axis-aligned), so the handle only shows for ink. */
    private val canRotate get() = selPages.isEmpty()

    /** Which handle (0-3 corner, 4 rotate) is under screen point (x, y), or -1. */
    private fun handleAt(x: Float, y: Float): Int {
        val r = HANDLE_HIT_DP * density
        if (!rotateHandle.x.isNaN() && hypot(x - rotateHandle.x, y - rotateHandle.y) <= r) return 4
        for (k in 0 until 4) if (hypot(x - cornerHandles[k * 2], y - cornerHandles[k * 2 + 1]) <= r) return k
        return -1
    }

    /** Starts resizing from corner [corner] (0 TL, 1 TR, 2 BR, 3 BL) or rotating ([corner] 4). */
    private fun beginHandleDrag(corner: Int, pointerId: Int, x: Float, y: Float) {
        val wx = (x - tx) / zoom; val wy = (y - ty) / zoom
        if (corner == 4) {
            xform = Xform.ROTATE
            pivotX = selBounds.centerX(); pivotY = selBounds.centerY()
            startAngle = Math.toDegrees(atan2((wy - pivotY).toDouble(), (wx - pivotX).toDouble())).toFloat()
            liveAngle = 0f
        } else {
            xform = Xform.SCALE
            // Anchor the opposite corner; the grabbed one follows the finger.
            pivotX = if (corner == 0 || corner == 3) selBounds.right else selBounds.left
            pivotY = if (corner == 0 || corner == 1) selBounds.bottom else selBounds.top
            startDist = max(1e-3f, hypot(wx - pivotX, wy - pivotY))
        }
        handleCorner = corner
        liftSelection(pointerId, x, y)
    }

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
        xform = Xform.MOVE
        handleCorner = -1
        liftSelection(pointerId, x, y)
    }

    private fun liftSelection(pointerId: Int, x: Float, y: Float) {
        activePointer = pointerId
        moveStartX = x; moveStartY = y
        liveMatrix.reset()
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
        val x = e.getX(i); val y = e.getY(i)
        val wx = (x - tx) / zoom; val wy = (y - ty) / zoom
        when (xform) {
            Xform.MOVE -> liveMatrix.setTranslate((x - moveStartX) / zoom, (y - moveStartY) / zoom)
            Xform.SCALE -> {
                val s = (hypot(wx - pivotX, wy - pivotY) / startDist).coerceIn(MIN_SCALE, MAX_SCALE)
                liveMatrix.setScale(s, s, pivotX, pivotY)
            }
            Xform.ROTATE -> {
                var a = Math.toDegrees(atan2((wy - pivotY).toDouble(), (wx - pivotX).toDouble())).toFloat() - startAngle
                a = ((a % 360f) + 540f) % 360f - 180f // -180..180
                // Snap to multiples of 45° when close, so straightening ink is easy.
                val snap = Math.round(a / 45f) * 45f
                if (abs(a - snap) < ROTATE_SNAP_DEG) a = snap
                liveAngle = a
                liveMatrix.setRotate(a, pivotX, pivotY)
            }
        }
        invalidate()
    }

    /** True if [liveMatrix] would change nothing visible (a tap, or a drag back to the start). */
    private fun liveMatrixIsNoop(): Boolean {
        val v = FloatArray(9)
        liveMatrix.getValues(v)
        val sizePx = max(selBounds.width(), selBounds.height()) * zoom
        val linear = abs(v[Matrix.MSCALE_X] - 1f) + abs(v[Matrix.MSKEW_X]) + abs(v[Matrix.MSKEW_Y]) + abs(v[Matrix.MSCALE_Y] - 1f)
        return linear * sizePx < 1f && abs(v[Matrix.MTRANS_X]) * zoom < 1f && abs(v[Matrix.MTRANS_Y]) * zoom < 1f
    }

    private fun commitMove(cancel: Boolean = false) {
        if (mode != Mode.MOVE) return
        mode = Mode.NONE
        activePointer = -1
        handleCorner = -1
        floatingNode.discardDisplayList()
        if (cancel || liveMatrixIsNoop()) {
            liveMatrix.reset()
            for (s in selStrokes) tiles.add(s) // put back unchanged
            invalidate()
            return
        }
        val m = Matrix(liveMatrix)
        liveMatrix.reset()
        val widthScale = m.mapRadius(1f)
        val newStrokes = selStrokes.map { it.transformed(m, widthScale) }
        val newPages = selPages.map { it.transformed(m) }
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
        val page = pages.lastOrNull { it.bounds.contains(wx, wy) }
        if (page == null) {
            // On empty canvas, a long-press offers to paste what was copied.
            if (clip == null || mode != Mode.GESTURE) return
            pasteX = wx; pasteY = wy
            val w = 84f * density; val h = 36f * density
            pasteChip.set(downX - w / 2, downY - h - 16f * density, downX + w / 2, downY - 16f * density)
            if (pasteChip.top < topInset) pasteChip.offset(0f, h + 32f * density)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            return
        }
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
                    Mode.CHIP -> { mode = Mode.NONE; onChipTapped(e.x, e.y) }
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

        if (!pasteChip.isEmpty) {
            if (pasteChip.contains(x, y)) { pressedChip = CHIP_PASTE; mode = Mode.CHIP; return }
            pasteChip.setEmpty(); invalidate()
        }

        if (hasSelection && !eraserIntent) {
            val chip = chipRects.indexOfFirst { it.contains(x, y) }
            if (chip >= 0) { pressedChip = chip; mode = Mode.CHIP; return }
            val handle = handleAt(x, y)
            if (handle >= 0) { beginHandleDrag(handle, e.getPointerId(0), x, y); return }
            val r = selectionScreenRect(tmpRect)
            r.inset(-touchSlop, -touchSlop)
            if (r.contains(x, y)) { startMove(e, 0); return }
            if (pen || fingerDraws) clearSelection() // a finger outside may just be panning
        }

        if (pen || fingerDraws) startInk(e, 0) else startGesture(e)

        // Long-press picks up a PDF page (or offers to paste): with a finger always; with the pen
        // only in lasso mode.
        if (!pen || tool == Tool.LASSO) postDelayed(longPress, longPressMs)
    }

    private fun onChipTapped(x: Float, y: Float) {
        val chip = pressedChip
        pressedChip = -1
        if (chip == CHIP_PASTE) {
            val hit = pasteChip.contains(x, y)
            pasteChip.setEmpty(); invalidate()
            if (hit) paste(pasteX, pasteY)
            return
        }
        if (chip < 0 || !chipRects[chip].contains(x, y)) return
        when (CHIP_ACTIONS[chip]) {
            com.avonotes.R.string.copy -> copySelection()
            com.avonotes.R.string.duplicate -> duplicateSelection()
            com.avonotes.R.string.delete -> deleteSelection()
        }
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
            liveTool = if (tool == Tool.HIGHLIGHTER) Tool.HIGHLIGHTER else penBrush
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
            // Partial erasing cuts along each circle, so sample densely enough to leave straight edges.
            val steps = (d / (r * if (partialEraser) 0.4f else 0.75f)).toInt()
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
        if (!partialEraser) {
            tiles.forEachIn(tmpRect) { s ->
                if (s.hitTest(wx, wy, r)) { tiles.remove(s); erased.add(s) }
            }
            return
        }
        // Collect first: cutting changes the tiles being iterated.
        eraseHits.clear()
        tiles.forEachIn(tmpRect) { s -> eraseHits.add(s) }
        for (s in eraseHits) {
            val pieces = s.erase(wx, wy, r) ?: continue
            tiles.remove(s)
            // A piece cut again in the same gesture was never part of the note: just drop it.
            if (!erasePieces.remove(s)) erased.add(s)
            for (p in pieces) { tiles.add(p); erasePieces.add(p) }
        }
    }

    private val eraseHits = ArrayList<Stroke>()

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
                // The tiles already show the result; just record it for undo.
                if (erasePieces.isNotEmpty()) record(Edit.Replace(ArrayList(erased), erasePieces.sortedBy { it.seq }))
                else if (erased.isNotEmpty()) record(Edit.RemoveStrokes(ArrayList(erased)))
                erased.clear()
                erasePieces.clear()
                eraserX = Float.NaN
            }
            else -> {}
        }
        mode = Mode.NONE
        activePointer = -1
        invalidate()
    }

    private fun cancelInk() {
        if (mode == Mode.ERASE) { // put back what was erased
            erasePieces.forEach { tiles.remove(it) }
            erased.forEach { tiles.add(it) }
        }
        erased.clear()
        erasePieces.clear()
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

        drawPattern(canvas)
        drawPages(canvas)

        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(zoom, zoom)
        tiles.draw(canvas, visible)
        if (mode == Mode.MOVE && selStrokes.isNotEmpty()) {
            canvas.save()
            canvas.concat(liveMatrix)
            if (canvas.isHardwareAccelerated && floatingNode.hasDisplayList()) canvas.drawRenderNode(floatingNode)
            else for (s in selStrokes) renderer.draw(canvas, s.tool, s.color, s.width, s.points, s.pointCount)
            canvas.restore()
        }
        if (mode == Mode.DRAW && liveCount > 0) {
            renderer.draw(canvas, liveTool, liveColor, liveWidth, livePts, liveCount)
        }
        canvas.restore()

        if (mode == Mode.LASSO && lassoCount > 1) drawLasso(canvas)
        if (hasSelection) drawSelection(canvas)
        if (!pasteChip.isEmpty) drawChip(canvas, pasteChip, context.getString(com.avonotes.R.string.paste))
        if (mode == Mode.ERASE && !eraserX.isNaN()) canvas.drawCircle(eraserX, eraserY, eraserRadiusPx, cursorPaint)
    }

    private fun drawChip(canvas: Canvas, r: RectF, label: String) {
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, chipPaint)
        val ty0 = r.centerY() - (chipText.descent() + chipText.ascent()) / 2
        canvas.drawText(label, r.centerX(), ty0, chipText)
    }

    private fun drawLasso(canvas: Canvas) {
        lassoPath.rewind()
        lassoPath.moveTo(lassoPts[0] * zoom + tx, lassoPts[1] * zoom + ty)
        for (k in 1 until lassoCount) lassoPath.lineTo(lassoPts[k * 2] * zoom + tx, lassoPts[k * 2 + 1] * zoom + ty)
        canvas.drawPath(lassoPath, dashPaint)
    }

    private fun drawSelection(canvas: Canvas) {
        val pad = 6f * density
        if (mode == Mode.MOVE) {
            // The box follows the live transform (it may be rotated).
            val b = RectF(selBounds)
            val p = pad / zoom
            b.inset(-p, -p)
            val pts = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)
            liveMatrix.mapPoints(pts)
            lassoPath.rewind()
            for (k in 0 until 4) {
                val sx = pts[k * 2] * zoom + tx; val sy = pts[k * 2 + 1] * zoom + ty
                if (k == 0) lassoPath.moveTo(sx, sy) else lassoPath.lineTo(sx, sy)
            }
            lassoPath.close()
            canvas.drawPath(lassoPath, selFill)
            canvas.drawPath(lassoPath, dashPaint)
            for (c in chipRects) c.setEmpty()
            if (xform == Xform.ROTATE) {
                // Angle readout next to the finger.
                val label = "${Math.round(liveAngle)}°"
                val w = chipText.measureText(label) + 24f * density
                val h = 30f * density
                tmpRect.set(lastX + 20f * density, lastY - h - 20f * density, lastX + 20f * density + w, lastY - 20f * density)
                drawChip(canvas, tmpRect, label)
            }
            return
        }

        val r = selectionScreenRect(tmpRect)
        r.inset(-pad, -pad)
        canvas.drawRect(r, selFill)
        canvas.drawRect(r, dashPaint)

        // Corner handles (resize) and, for ink, a rotate handle above the top edge.
        val corners = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)
        corners.copyInto(cornerHandles)
        val hr = HANDLE_DP * density
        if (canRotate) {
            val stem = 28f * density
            var hy = r.top - stem
            if (hy < topInset + hr) hy = r.bottom + stem // no room above: put it below
            rotateHandle.set(r.centerX(), hy)
            canvas.drawLine(r.centerX(), if (hy < r.top) r.top else r.bottom, r.centerX(), hy, dashPaint)
            canvas.drawCircle(rotateHandle.x, rotateHandle.y, hr * 1.15f, handleFill)
            canvas.drawCircle(rotateHandle.x, rotateHandle.y, hr * 1.15f, handleStroke)
            // A small curved arrow inside the rotate handle.
            val ar = hr * 0.55f
            tmpRect2.set(rotateHandle.x - ar, rotateHandle.y - ar, rotateHandle.x + ar, rotateHandle.y + ar)
            canvas.drawArc(tmpRect2, -60f, 270f, false, handleStroke)
        } else rotateHandle.set(Float.NaN, Float.NaN)
        for (k in 0 until 4) {
            canvas.drawCircle(corners[k * 2], corners[k * 2 + 1], hr, handleFill)
            canvas.drawCircle(corners[k * 2], corners[k * 2 + 1], hr, handleStroke)
        }

        // Action chips under the selection (or above it if there's no room below).
        val h = 36f * density; val gap = 8f * density; val space = 6f * density
        val labels = CHIP_ACTIONS.map { context.getString(it) }
        val widths = labels.map { chipText.measureText(it) + 28f * density }
        val total = widths.sum() + space * (labels.size - 1)
        // Keep clear of the rotate handle on whichever side it is.
        val rotBelow = !rotateHandle.y.isNaN() && rotateHandle.y > r.bottom
        val rotAbove = !rotateHandle.y.isNaN() && rotateHandle.y < r.top
        var top = if (rotBelow) rotateHandle.y + hr * 1.15f + gap else r.bottom + gap + hr
        if (top + h > height - gap) {
            top = if (rotAbove) rotateHandle.y - hr * 1.15f - gap - h else r.top - gap - hr - h
        }
        top = top.coerceIn(topInset + gap, max(topInset + gap, height - h - gap))
        var left = (r.centerX() - total / 2).coerceIn(gap, max(gap, width - total - gap))
        for (k in labels.indices) {
            chipRects[k].set(left, top, left + widths[k], top + h)
            drawChip(canvas, chipRects[k], labels[k])
            left += widths[k] + space
        }
    }

    private fun drawPages(canvas: Canvas) {
        val cache = pdfCache ?: return
        cache.beginFrame()
        val moving = mode == Mode.MOVE && selPages.isNotEmpty()
        val box = RectF()
        for (p in pages) {
            val lifted = moving && p in selPages
            box.set(p.bounds)
            if (lifted) liveMatrix.mapRect(box)
            if (!RectF.intersects(box, visible)) continue
            val pageRect = RectF(box.left * zoom + tx, box.top * zoom + ty, box.right * zoom + tx, box.bottom * zoom + ty)
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

    /**
     * Paper pattern, drawn in screen space but anchored to world coordinates, so it moves and
     * scales with the content. When zoomed out far enough that it would get dense, the spacing
     * jumps by 4x (multiples of the base spacing stay aligned, so nothing shifts).
     */
    private fun drawPattern(canvas: Canvas) {
        when (pattern) {
            Pattern.BLANK -> {}
            Pattern.DOTS -> {
                val spacing = adaptiveSpacing(18f)
                val ox = ((tx % spacing) + spacing) % spacing
                val oy = ((ty % spacing) + spacing) % spacing
                val cols = (width / spacing).toInt() + 2
                val rows = (height / spacing).toInt() + 2
                ensureGridBuf(cols * rows * 2)
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
            Pattern.LINES -> {
                linePaint.color = lineColor
                drawLineSet(canvas, adaptiveSpacing(18f), horizontal = true, vertical = false)
            }
            Pattern.GRID -> {
                val minor = adaptiveSpacing(12f)
                linePaint.color = lineColor
                drawLineSet(canvas, minor, horizontal = true, vertical = true)
                linePaint.color = majorLineColor
                drawLineSet(canvas, minor * 4f, horizontal = true, vertical = true)
            }
        }
    }

    private fun adaptiveSpacing(minDp: Float): Float {
        var spacing = GRID_SPACING * zoom
        while (spacing < minDp * density) spacing *= 4f
        return spacing
    }

    private fun drawLineSet(canvas: Canvas, spacing: Float, horizontal: Boolean, vertical: Boolean) {
        val ox = ((tx % spacing) + spacing) % spacing
        val oy = ((ty % spacing) + spacing) % spacing
        val rows = if (horizontal) (height / spacing).toInt() + 2 else 0
        val cols = if (vertical) (width / spacing).toInt() + 2 else 0
        ensureGridBuf((rows + cols) * 4)
        var k = 0
        val w = width.toFloat(); val h = height.toFloat()
        for (r in 0 until rows) {
            val y = oy + r * spacing
            gridBuf[k++] = 0f; gridBuf[k++] = y; gridBuf[k++] = w; gridBuf[k++] = y
        }
        for (c in 0 until cols) {
            val x = ox + c * spacing
            gridBuf[k++] = x; gridBuf[k++] = 0f; gridBuf[k++] = x; gridBuf[k++] = h
        }
        linePaint.strokeWidth = density
        canvas.drawLines(gridBuf, 0, k, linePaint)
    }

    private fun ensureGridBuf(n: Int) {
        if (gridBuf.size < n) gridBuf = FloatArray(n)
    }

    companion object {
        const val MIN_ZOOM = 0.02f
        const val MAX_ZOOM = 40f
        private const val MAX_HISTORY = 300
        private const val MIN_POINT_DIST_PX = 0.75f
        private const val GRID_SPACING = 32f
        private const val PATCH_DELAY_MS = 180L
        private const val LASSO_FRACTION = 0.5f
        const val DEFAULT_ERASER_SIZE = 14f

        // Selection handles and chips.
        private const val HANDLE_DP = 9f
        private const val HANDLE_HIT_DP = 24f
        private const val MIN_SCALE = 0.05f
        private const val MAX_SCALE = 20f
        private const val ROTATE_SNAP_DEG = 4f
        private const val DUPLICATE_OFFSET_DP = 24f
        private val CHIP_ACTIONS = intArrayOf(
            com.avonotes.R.string.copy, com.avonotes.R.string.duplicate, com.avonotes.R.string.delete,
        )
        private const val CHIP_PASTE = 100

        /** The in-app clipboard, shared by every note opened in this app session. */
        private var clip: Clip? = null
        private const val ACCENT = 0xFF2E7D32.toInt()
    }
}
