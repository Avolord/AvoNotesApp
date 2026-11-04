package com.avonotes.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.avonotes.R
import com.avonotes.canvas.InfiniteCanvasView
import com.avonotes.io.NoteStore
import com.avonotes.model.NoteDocument
import com.avonotes.model.PdfDoc
import com.avonotes.model.Tool
import com.avonotes.pdf.PdfPageCache
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class CanvasActivity : Activity(), InfiniteCanvasView.Listener {
    private lateinit var store: NoteStore
    private lateinit var dir: File
    private lateinit var canvasView: InfiniteCanvasView
    private lateinit var btnPen: ImageButton
    private lateinit var btnHighlighter: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnLasso: ImageButton
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnColor: View

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var loaded = false
    private var dirty = false
    private val autosave = Runnable { save() }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_canvas)
        store = NoteStore(this)
        dir = File(intent.getStringExtra(EXTRA_DIR) ?: run { finish(); return })

        canvasView = findViewById(R.id.canvas)
        canvasView.listener = this
        val toolbar = findViewById<LinearLayout>(R.id.toolbar)
        val title = findViewById<TextView>(R.id.title)
        btnPen = findViewById(R.id.btn_pen)
        btnHighlighter = findViewById(R.id.btn_highlighter)
        btnEraser = findViewById(R.id.btn_eraser)
        btnLasso = findViewById(R.id.btn_lasso)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        btnColor = findViewById(R.id.btn_color)

        // Toolbar sits under the status bar; the canvas itself draws edge to edge.
        toolbar.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val p = (4 * resources.displayMetrics.density).toInt()
            v.setPadding(i.left + p, i.top, i.right + p, 0)
            insets
        }
        toolbar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> canvasView.topInset = v.height }
        if (resources.configuration.screenWidthDp < 560) title.visibility = View.GONE

        loadSettings()
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        btnPen.setOnClickListener { selectTool(Tool.PEN, it) }
        btnHighlighter.setOnClickListener { selectTool(Tool.HIGHLIGHTER, it) }
        btnEraser.setOnClickListener { selectTool(Tool.ERASER, it) }
        btnLasso.setOnClickListener { selectTool(Tool.LASSO, it) }
        btnColor.setOnClickListener { showPenPopup(it) }
        btnUndo.setOnClickListener { canvasView.undo() }
        btnRedo.setOnClickListener { canvasView.redo() }
        findViewById<View>(R.id.btn_pdf).setOnClickListener { pickPdf() }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMore(it) }
        updateToolUi()
        onHistoryChanged(false, false)

        io.execute {
            val doc = runCatching { store.load(dir) }.getOrNull()
            main.post {
                if (isDestroyed) return@post
                if (doc == null) {
                    Toast.makeText(this, R.string.load_failed, Toast.LENGTH_LONG).show()
                    finish(); return@post
                }
                title.text = doc.title.ifEmpty { getString(R.string.untitled) }
                val cache = PdfPageCache(store.pdfDir(dir), { canvasView.docFileName(it) }) { canvasView.invalidate() }
                canvasView.setDocument(doc, cache)
                loaded = true
            }
        }
    }

    override fun onPause() {
        super.onPause()
        save(withThumb = true)
        saveSettings()
    }

    override fun onDestroy() {
        main.removeCallbacks(autosave)
        if (::canvasView.isInitialized) canvasView.release()
        io.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- saving

    override fun onContentChanged() {
        dirty = true
        main.removeCallbacks(autosave)
        main.postDelayed(autosave, AUTOSAVE_DELAY_MS)
    }

    override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
        btnUndo.isEnabled = canUndo; btnUndo.alpha = if (canUndo) 1f else 0.35f
        btnRedo.isEnabled = canRedo; btnRedo.alpha = if (canRedo) 1f else 0.35f
    }

    /** Snapshots on the UI thread (cheap: strokes are immutable) and writes on the IO thread. */
    private fun save(withThumb: Boolean = false) {
        main.removeCallbacks(autosave)
        if (!loaded) return
        val snap = NoteDocument()
        canvasView.snapshot(snap)
        dirty = false
        io.execute {
            runCatching { store.save(dir, snap) }.onFailure { main.post { dirty = true } }
            // The preview is only needed in the library, so render it when leaving the note.
            if (withThumb) runCatching { store.writeThumb(dir, snap) }
        }
    }

    // ---------------------------------------------------------------- tools

    private fun selectTool(t: Tool, anchor: View) {
        if (canvasView.tool == t && (t == Tool.PEN || t == Tool.HIGHLIGHTER)) showPenPopup(anchor)
        canvasView.tool = t
        updateToolUi()
    }

    private fun updateToolUi() {
        btnPen.isSelected = canvasView.tool == Tool.PEN
        btnHighlighter.isSelected = canvasView.tool == Tool.HIGHLIGHTER
        btnEraser.isSelected = canvasView.tool == Tool.ERASER
        btnLasso.isSelected = canvasView.tool == Tool.LASSO
        val c = if (canvasView.tool == Tool.HIGHLIGHTER) canvasView.highlighterColor else canvasView.penColor
        btnColor.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c or 0xFF000000.toInt())
            setStroke((1.5f * resources.displayMetrics.density).toInt(), 0x33000000)
        }
    }

    private fun showPenPopup(anchor: View) {
        val hl = canvasView.tool == Tool.HIGHLIGHTER
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = 14 * d
                setStroke(d.toInt().coerceAtLeast(1), 0x22000000)
            }
            elevation = 8 * d
        }
        val popup = PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 8 * d

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in if (hl) HIGHLIGHTER_COLORS else PEN_COLORS) {
            val size = (34 * d).toInt()
            val sw = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (8 * d).toInt() }
                val selected = c == (if (hl) canvasView.highlighterColor else canvasView.penColor)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c or 0xFF000000.toInt())
                    setStroke(((if (selected) 3f else 1f) * d).toInt(), if (selected) 0xFF2E7D32.toInt() else 0x33000000)
                }
                setOnClickListener {
                    if (hl) canvasView.highlighterColor = c else canvasView.penColor = c
                    updateToolUi(); popup.dismiss()
                }
            }
            row.addView(sw)
        }
        box.addView(row)

        val label = TextView(this).apply {
            setText(R.string.width); setPadding(0, (10 * d).toInt(), 0, 0); setTextColor(0xFF6B6B66.toInt())
        }
        box.addView(label)
        val range = if (hl) 4f..60f else 0.5f..16f
        val seek = SeekBar(this).apply {
            max = 100
            val cur = if (hl) canvasView.highlighterWidth else canvasView.penWidth
            progress = (((cur - range.start) / (range.endInclusive - range.start)) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val w = range.start + (range.endInclusive - range.start) * p / 100f
                    if (hl) canvasView.highlighterWidth = w else canvasView.penWidth = w
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        box.addView(seek, LinearLayout.LayoutParams((260 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        popup.showAsDropDown(anchor, 0, (4 * d).toInt(), Gravity.START)
    }

    private fun showMore(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.finger_draws).apply { isCheckable = true; isChecked = canvasView.fingerDraws }
        m.menu.add(0, 2, 1, R.string.show_grid).apply { isCheckable = true; isChecked = canvasView.showGrid }
        m.menu.add(0, 3, 2, R.string.fit_content)
        m.menu.add(0, 4, 3, R.string.reset_zoom)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> canvasView.fingerDraws = !canvasView.fingerDraws
                2 -> canvasView.showGrid = !canvasView.showGrid
                3 -> canvasView.zoomToFit()
                4 -> canvasView.resetZoom()
            }
            saveSettings(); true
        }
        m.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && canvasView.hasSelection) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> { canvasView.deleteSelection(); return true }
                KeyEvent.KEYCODE_ESCAPE -> { canvasView.clearSelection(); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_Z -> { if (event.isShiftPressed) canvasView.redo() else canvasView.undo(); return true }
                KeyEvent.KEYCODE_Y -> { canvasView.redo(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- settings

    private fun loadSettings() {
        canvasView.tool = Tool.fromCode(prefs.getInt("tool", 0))
        canvasView.penColor = prefs.getInt("penColor", PEN_COLORS[0])
        canvasView.penWidth = prefs.getFloat("penWidth", 2.5f)
        canvasView.highlighterColor = prefs.getInt("hlColor", HIGHLIGHTER_COLORS[0])
        canvasView.highlighterWidth = prefs.getFloat("hlWidth", 18f)
        canvasView.fingerDraws = prefs.getBoolean("fingerDraws", false)
        canvasView.showGrid = prefs.getBoolean("showGrid", true)
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("tool", canvasView.tool.code)
            .putInt("penColor", canvasView.penColor)
            .putFloat("penWidth", canvasView.penWidth)
            .putInt("hlColor", canvasView.highlighterColor)
            .putFloat("hlWidth", canvasView.highlighterWidth)
            .putBoolean("fingerDraws", canvasView.fingerDraws)
            .putBoolean("showGrid", canvasView.showGrid)
            .apply()
    }

    // ---------------------------------------------------------------- PDF import

    private fun pickPdf() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(i, REQ_PDF)
    }

    @Deprecated("Platform Activity result API; no AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_PDF || resultCode != RESULT_OK || uri == null) return
        Toast.makeText(this, R.string.importing_pdf, Toast.LENGTH_SHORT).show()
        val name = displayName(uri) ?: "document.pdf"
        io.execute {
            val id = UUID.randomUUID().toString()
            val fileName = "$id.pdf"
            val target = File(store.pdfDir(dir), fileName)
            val sizes = runCatching {
                contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                PdfPageCache.readPageSizes(target)
            }.getOrNull()
            main.post {
                if (sizes.isNullOrEmpty()) {
                    target.delete()
                    Toast.makeText(this, R.string.pdf_failed, Toast.LENGTH_LONG).show()
                } else {
                    canvasView.addPdf(PdfDoc(id, fileName, name), sizes)
                }
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val EXTRA_DIR = "dir"
        private const val REQ_PDF = 10
        private const val AUTOSAVE_DELAY_MS = 1500L

        private val PEN_COLORS = intArrayOf(
            0xFF1A1A1A.toInt(), 0xFF5F6368.toInt(), 0xFF1565C0.toInt(), 0xFFC62828.toInt(),
            0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(),
        )
        private val HIGHLIGHTER_COLORS = intArrayOf(
            0x66FFEB3B, 0x6676FF03, 0x66FF80AB, 0x6640C4FF, 0x66FFAB40,
        )
    }
}
