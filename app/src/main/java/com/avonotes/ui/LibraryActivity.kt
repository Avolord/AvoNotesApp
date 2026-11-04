package com.avonotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.avonotes.R
import com.avonotes.io.NoteStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class LibraryActivity : Activity() {
    private lateinit var store: NoteStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var notes: List<NoteStore.NoteInfo> = emptyList()
    private var exportDir: File? = null

    private lateinit var grid: GridView
    private lateinit var empty: TextView

    // Previews are decoded (or regenerated when stale) on their own thread so
    // they never hold up opening, importing or exporting notes.
    private val thumbs = Executors.newSingleThreadExecutor()
    private val thumbCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val thumbPending = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        store = NoteStore(this)
        applySystemBarInsets(findViewById(R.id.root))

        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> open(notes[pos].dir) }
        grid.setOnItemLongClickListener { _, _, pos, _ -> showActions(notes[pos]); true }

        findViewById<View>(R.id.btn_new).setOnClickListener {
            val title = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
            io.execute {
                val dir = store.create(title)
                main.post { open(dir) }
            }
        }
        findViewById<View>(R.id.btn_import).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, REQ_IMPORT)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        io.shutdown()
        thumbs.shutdownNow()
        super.onDestroy()
    }

    private fun thumbKey(n: NoteStore.NoteInfo) = "${n.dir.name}@${n.modified}"

    private fun bindThumb(view: ImageView, n: NoteStore.NoteInfo) {
        val key = thumbKey(n)
        view.tag = key
        val cached = thumbCache.get(key)
        view.setImageBitmap(cached)
        if (cached != null || !thumbPending.add(key)) return
        thumbs.execute {
            val bmp = runCatching {
                if (store.thumbStale(n.dir)) store.writeThumb(n.dir, store.load(n.dir))
                else BitmapFactory.decodeFile(store.thumbFile(n.dir).path)
            }.getOrNull()
            main.post {
                thumbPending.remove(key)
                if (bmp == null || isDestroyed) return@post
                thumbCache.put(key, bmp)
                // Only if this view still shows the same note (views are recycled).
                for (i in 0 until grid.childCount) {
                    val iv = grid.getChildAt(i).findViewById<ImageView>(R.id.thumb)
                    if (iv.tag == key) iv.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun refresh() {
        io.execute {
            val l = store.list()
            main.post {
                notes = l
                adapter.notifyDataSetChanged()
                empty.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun open(dir: File) {
        startActivity(Intent(this, CanvasActivity::class.java).putExtra(CanvasActivity.EXTRA_DIR, dir.absolutePath))
    }

    private fun showActions(note: NoteStore.NoteInfo) {
        val items = arrayOf(getString(R.string.rename), getString(R.string.export), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(note.title.ifEmpty { getString(R.string.untitled) })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> rename(note)
                    1 -> export(note)
                    2 -> confirmDelete(note)
                }
            }
            .show()
    }

    private fun rename(note: NoteStore.NoteInfo) {
        val input = EditText(this).apply { setText(note.title); setSelectAllOnFocus(true) }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val frame = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(frame)
            .setPositiveButton(R.string.ok) { _, _ ->
                val t = input.text.toString().trim()
                io.execute { store.writeTitle(note.dir, t); main.post { refresh() } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun export(note: NoteStore.NoteInfo) {
        exportDir = note.dir
        val safe = note.title.ifEmpty { "note" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "$safe.avonote")
        }
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun confirmDelete(note: NoteStore.NoteInfo) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, note.title.ifEmpty { getString(R.string.untitled) }))
            .setPositiveButton(R.string.delete) { _, _ -> io.execute { store.delete(note.dir); main.post { refresh() } } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Deprecated("Platform Activity result API; no AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_IMPORT -> io.execute {
                val ok = runCatching { contentResolver.openInputStream(uri)!!.use { store.import(it) } }.isSuccess
                main.post {
                    if (!ok) Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
            REQ_EXPORT -> {
                val dir = exportDir ?: return
                io.execute {
                    val ok = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { store.export(dir, it) } }.isSuccess
                    main.post {
                        Toast.makeText(this, if (ok) R.string.exported else R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = notes.size
        override fun getItem(position: Int) = notes[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_note, parent, false)
            val n = notes[position]
            val thumb = v.findViewById<ImageView>(R.id.thumb)
            val colW = grid.columnWidth
            if (colW > 0 && thumb.layoutParams.height != colW * 3 / 4) {
                thumb.layoutParams = thumb.layoutParams.apply { height = colW * 3 / 4 } // keep 4:3
            }
            bindThumb(thumb, n)
            v.findViewById<TextView>(R.id.title).text = n.title.ifEmpty { getString(R.string.untitled) }
            v.findViewById<TextView>(R.id.subtitle).text = DateUtils.getRelativeTimeSpanString(
                n.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            )
            return v
        }
    }

    companion object {
        private const val REQ_IMPORT = 1
        private const val REQ_EXPORT = 2
    }
}
