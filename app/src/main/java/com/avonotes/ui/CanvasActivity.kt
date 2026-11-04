package com.avonotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsetsController
import com.avonotes.canvas.DarkInk
import android.window.OnBackInvokedDispatcher
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import com.avonotes.model.Pattern
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
    private lateinit var btnSave: ImageButton
    private var autoSave = true
    private lateinit var titleView: TextView
    private var noteTitle = ""

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
        titleView = title
        title.setOnClickListener { renameNote() }
        btnPen = findViewById(R.id.btn_pen)
        btnHighlighter = findViewById(R.id.btn_highlighter)
        btnEraser = findViewById(R.id.btn_eraser)
        btnLasso = findViewById(R.id.btn_lasso)
        btnUndo = findViewById(R.id.btn_undo)
        btnRedo = findViewById(R.id.btn_redo)
        btnSave = findViewById(R.id.btn_save)
        // btnPen / btnHighlighter use the untinted ToolButton.Ink style; updateToolUi() colors them.

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
        findViewById<View>(R.id.btn_back).setOnClickListener { attemptClose() }
        btnSave.setOnClickListener {
            save(withThumb = true) { ok -> if (ok) Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show() }
        }
        setDirty(false)
        if (Build.VERSION.SDK_INT >= 33) {
            // Apps targeting API 36 no longer get onBackPressed(); intercept back here instead.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                attemptClose()
            }
        }
        btnPen.setOnClickListener { selectTool(Tool.PEN, it) }
        btnHighlighter.setOnClickListener { selectTool(Tool.HIGHLIGHTER, it) }
        btnEraser.setOnClickListener { selectTool(Tool.ERASER, it) }
        btnLasso.setOnClickListener { selectTool(Tool.LASSO, it) }
        // Long-press opens color and width too (tapping the active tool again also does).
        btnPen.setOnLongClickListener { canvasView.tool = Tool.PEN; updateToolUi(); showPenPopup(it); true }
        btnHighlighter.setOnLongClickListener {
            canvasView.tool = Tool.HIGHLIGHTER; updateToolUi(); showPenPopup(it); true
        }
        btnUndo.setOnClickListener { canvasView.undo() }
        btnRedo.setOnClickListener { canvasView.redo() }
        findViewById<View>(R.id.btn_pdf).setOnClickListener { pickPdf() }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMore(it) }
        applyTheme()
        onHistoryChanged(false, false)

        io.execute {
            val doc = runCatching { store.load(dir) }.getOrNull()
            val pattern = store.readPattern(dir)
            main.post {
                canvasView.pattern = pattern
                if (isDestroyed) return@post
                if (doc == null) {
                    Toast.makeText(this, R.string.load_failed, Toast.LENGTH_LONG).show()
                    finish(); return@post
                }
                noteTitle = doc.title
                title.text = doc.title.ifEmpty { getString(R.string.untitled) }
                val cache = PdfPageCache(store.pdfDir(dir), { canvasView.docFileName(it) }) { canvasView.invalidate() }
                canvasView.setDocument(doc, cache)
                loaded = true
            }
        }
    }

    /**
     * This activity handles uiMode changes itself (so switching theme never drops unsaved ink or
     * undo history), so re-resolve every themed color here.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    private fun applyTheme() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        findViewById<View>(R.id.root).setBackgroundColor(getColor(R.color.paper))
        val toolbar = findViewById<LinearLayout>(R.id.toolbar)
        toolbar.setBackgroundColor(getColor(R.color.toolbar_bg))
        titleView.setTextColor(getColor(R.color.icon))
        val iconTint = getColorStateList(R.color.icon)
        for (i in 0 until toolbar.childCount) {
            when (val v = toolbar.getChildAt(i)) {
                titleView -> {}
                is ImageButton -> {
                    v.background = getDrawable(R.drawable.tool_bg)
                    if (v !== btnPen && v !== btnHighlighter) v.imageTintList = iconTint
                }
                else -> v.setBackgroundColor(getColor(R.color.toolbar_divider))
            }
        }
        val lightBars = if (night) 0 else
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(
            lightBars,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        canvasView.dark = night
        updateToolUi()
    }

    override fun onPause() {
        super.onPause()
        // With auto-save off, nothing is written until the user saves (or confirms on close).
        if (autoSave) save(withThumb = true)
        saveSettings()
    }

    override fun onDestroy() {
        main.removeCallbacks(autosave)
        if (::canvasView.isInitialized) canvasView.release()
        io.shutdown() // queued saves still finish
        super.onDestroy()
    }

    // ---------------------------------------------------------------- saving

    // Not onContentChanged(): that name belongs to Activity and is called by setContentView().
    override fun onNoteChanged() {
        setDirty(true)
        main.removeCallbacks(autosave)
        if (autoSave) main.postDelayed(autosave, AUTOSAVE_DELAY_MS)
    }

    override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
        btnUndo.isEnabled = canUndo; btnUndo.alpha = if (canUndo) 1f else 0.35f
        btnRedo.isEnabled = canRedo; btnRedo.alpha = if (canRedo) 1f else 0.35f
    }

    private fun setDirty(d: Boolean) {
        dirty = d
        btnSave.isEnabled = d
        btnSave.alpha = if (d) 1f else 0.35f
    }

    /**
     * Snapshots on the UI thread (cheap: strokes are immutable) and writes on the IO thread.
     * [then] runs on the UI thread once the write has finished (successfully or not).
     */
    private fun save(withThumb: Boolean = false, then: ((Boolean) -> Unit)? = null) {
        main.removeCallbacks(autosave)
        if (!loaded) { then?.invoke(true); return }
        val snap = NoteDocument()
        canvasView.snapshot(snap)
        setDirty(false)
        io.execute {
            val ok = runCatching { store.save(dir, snap) }.isSuccess
            // The preview is only needed in the library, so render it when leaving or saving by hand.
            if (ok && withThumb) runCatching { store.writeThumb(dir, snap) }
            main.post {
                if (!ok) {
                    setDirty(true)
                    if (!isFinishing) Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
                }
                then?.invoke(ok)
            }
        }
    }

    /** Back button, system back, Escape: ask first if auto-save is off and there are unsaved changes. */
    private fun attemptClose() {
        if (autoSave || !dirty) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(getString(R.string.unsaved_message, noteTitle.ifEmpty { getString(R.string.untitled) }))
            .setPositiveButton(R.string.save) { _, _ -> save(withThumb = true) { ok -> if (ok) finish() } }
            .setNegativeButton(R.string.discard) { _, _ -> setDirty(false); finish() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    @Deprecated("Used below API 33; newer versions use the OnBackInvokedCallback.")
    override fun onBackPressed() = attemptClose()

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
        // The pen is drawn in its ink color; the marker keeps its outline and gets an ink swatch.
        val penShown = DarkInk.display(canvasView.penColor, false, canvasView.dark)
        btnPen.setImageDrawable(getDrawable(R.drawable.ic_pen)!!.mutate().apply { setTint(penShown) })
        val body = getDrawable(R.drawable.ic_highlighter)!!.mutate().apply { setTint(getColor(R.color.icon)) }
        val ink = getDrawable(R.drawable.ic_highlighter_ink)!!.mutate().apply {
            setTint(canvasView.highlighterColor or 0xFF000000.toInt())
        }
        btnHighlighter.setImageDrawable(LayerDrawable(arrayOf(body, ink)))
    }

    private fun showPenPopup(anchor: View) {
        val hl = canvasView.tool == Tool.HIGHLIGHTER
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                setColor(getColor(R.color.popup_bg)); cornerRadius = 14 * d
                setStroke(d.toInt().coerceAtLeast(1), getColor(R.color.popup_border))
            }
            elevation = 8 * d
        }
        val popup = PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 8 * d

        // Brush picker (pen tool only): a live sample of each brush in the current color.
        val previews = ArrayList<BrushPreviewView>()
        if (!hl) {
            val brushRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, (10 * d).toInt())
            }
            fun styleChip(v: View, selected: Boolean) {
                v.background = GradientDrawable().apply {
                    cornerRadius = 10 * d
                    setColor(if (selected) getColor(R.color.tool_selected_bg) else Color.TRANSPARENT)
                    setStroke(d.toInt().coerceAtLeast(1), getColor(R.color.popup_border))
                }
            }
            val chips = ArrayList<View>()
            for (b in BRUSHES) {
                val chip = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
                    layoutParams = LinearLayout.LayoutParams((72 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = (6 * d).toInt()
                    }
                    contentDescription = getString(brushName(b))
                }
                val preview = BrushPreviewView(this).apply {
                    brush = b
                    dark = canvasView.dark
                    color = canvasView.penColor
                }
                previews.add(preview)
                chip.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (40 * d).toInt()))
                chip.addView(TextView(this).apply {
                    setText(brushName(b)); textSize = 12f; gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.icon))
                })
                styleChip(chip, b == canvasView.penBrush)
                chip.setOnClickListener {
                    canvasView.penBrush = b
                    for ((k, c) in chips.withIndex()) styleChip(c, BRUSHES[k] == b)
                    saveSettings()
                }
                chips.add(chip)
                brushRow.addView(chip)
            }
            box.addView(brushRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in if (hl) HIGHLIGHTER_COLORS else PEN_COLORS) {
            val size = (34 * d).toInt()
            val sw = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (8 * d).toInt() }
                val selected = c == (if (hl) canvasView.highlighterColor else canvasView.penColor)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    // Show the color as it will appear on this canvas (dark mode maps pen ink).
                    setColor(DarkInk.display(c, hl, canvasView.dark) or 0xFF000000.toInt())
                    setStroke(
                        ((if (selected) 3f else 1f) * d).toInt(),
                        if (selected) getColor(R.color.accent) else getColor(R.color.popup_border),
                    )
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
            setText(R.string.width); setPadding(0, (10 * d).toInt(), 0, 0); setTextColor(getColor(R.color.text_secondary))
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
        // Match the swatch row's width so no swatch gets clipped.
        box.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        popup.showAsDropDown(anchor, 0, (4 * d).toInt(), Gravity.START)
    }

    private fun showMore(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.finger_draws).apply { isCheckable = true; isChecked = canvasView.fingerDraws }
        m.menu.add(0, 2, 1, R.string.background)
        m.menu.add(0, 3, 2, R.string.fit_content)
        m.menu.add(0, 4, 3, R.string.reset_zoom)
        m.menu.add(0, 5, 4, R.string.rename_note)
        m.menu.add(0, 6, 5, R.string.autosave).apply { isCheckable = true; isChecked = autoSave }
        if (ThemePref.supported) m.menu.add(0, 7, 6, R.string.theme)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                5 -> { renameNote(); return@setOnMenuItemClickListener true }
                7 -> { ThemePref.showDialog(this); return@setOnMenuItemClickListener true }
                6 -> {
                    autoSave = !autoSave
                    if (autoSave && dirty) save()
                }
                1 -> canvasView.fingerDraws = !canvasView.fingerDraws
                2 -> { choosePattern(); return@setOnMenuItemClickListener true }
                3 -> canvasView.zoomToFit()
                4 -> canvasView.resetZoom()
            }
            saveSettings(); true
        }
        m.show()
    }

    /** The background is note metadata like the name: applied and written right away. */
    private fun choosePattern() {
        if (!loaded) return
        val options = arrayOf(Pattern.DOTS, Pattern.LINES, Pattern.GRID, Pattern.BLANK)
        val labels = arrayOf(
            getString(R.string.pattern_dots), getString(R.string.pattern_lines),
            getString(R.string.pattern_grid), getString(R.string.pattern_blank),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.background_title)
            .setSingleChoiceItems(labels, options.indexOf(canvasView.pattern)) { d, which ->
                d.dismiss()
                val p = options[which]
                canvasView.pattern = p
                io.execute { runCatching { store.writePattern(dir, p) } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The name is note metadata, so it is written right away (independent of saving the ink). */
    private fun renameNote() {
        if (!loaded) return
        showNameDialog(this, R.string.rename_note, noteTitle) { t ->
            noteTitle = t
            titleView.text = t.ifEmpty { getString(R.string.untitled) }
            io.execute { runCatching { store.writeTitle(dir, t) } }
        }
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
        canvasView.penBrush = Tool.fromCode(prefs.getInt("penBrush", Tool.PEN.code)).takeIf { it.isBrush } ?: Tool.PEN
        canvasView.penWidth = prefs.getFloat("penWidth", 2.5f)
        canvasView.highlighterColor = prefs.getInt("hlColor", HIGHLIGHTER_COLORS[0])
        canvasView.highlighterWidth = prefs.getFloat("hlWidth", 18f)
        canvasView.fingerDraws = prefs.getBoolean("fingerDraws", false)
        autoSave = prefs.getBoolean("autoSave", true)
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("tool", canvasView.tool.code)
            .putInt("penColor", canvasView.penColor)
            .putInt("penBrush", canvasView.penBrush.code)
            .putFloat("penWidth", canvasView.penWidth)
            .putInt("hlColor", canvasView.highlighterColor)
            .putFloat("hlWidth", canvasView.highlighterWidth)
            .putBoolean("fingerDraws", canvasView.fingerDraws)
            .putBoolean("autoSave", autoSave)
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
        private val BRUSHES = arrayOf(Tool.PEN, Tool.FOUNTAIN, Tool.PENCIL, Tool.MARKER)

        private fun brushName(t: Tool) = when (t) {
            Tool.FOUNTAIN -> R.string.brush_fountain
            Tool.PENCIL -> R.string.brush_pencil
            Tool.MARKER -> R.string.brush_marker
            else -> R.string.brush_pen
        }

        private val HIGHLIGHTER_COLORS = intArrayOf(
            0x66FFEB3B, 0x6676FF03, 0x66FF80AB, 0x6640C4FF, 0x66FFAB40,
        )
    }
}
