package com.avonotes.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.avonotes.R
import com.avonotes.io.NoteStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * The note library: groups (as cards) followed by notes that aren't in a group.
 * Tapping a group shows just its notes; back returns to everything.
 */
class LibraryActivity : Activity() {
    private lateinit var store: NoteStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var exportDir: File? = null

    private sealed class Item {
        class GroupItem(val group: NoteStore.Group, val notes: List<NoteStore.NoteInfo>) : Item()
        class NoteItem(val note: NoteStore.NoteInfo) : Item()
    }

    private var allNotes: List<NoteStore.NoteInfo> = emptyList()
    private var groups: List<NoteStore.Group> = emptyList()
    private var items: List<Item> = emptyList()

    /** The group being shown, or null for the top level. Kept across recreation (e.g. theme change). */
    private var currentGroupId: String? = null
    private val currentGroup get() = groups.firstOrNull { it.id == currentGroupId }

    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var headerTitle: TextView
    private lateinit var btnUp: View
    private lateinit var btnNewGroup: View

    // Previews are decoded (or regenerated when stale) on their own thread so
    // they never hold up opening, importing or exporting notes.
    private val thumbs = Executors.newSingleThreadExecutor()
    private val thumbCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val thumbPending = HashSet<String>()

    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        store = NoteStore(this)
        applySystemBarInsets(findViewById(R.id.root))
        currentGroupId = savedInstanceState?.getString(STATE_GROUP)

        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        headerTitle = findViewById(R.id.header_title)
        btnUp = findViewById(R.id.btn_up)
        btnNewGroup = findViewById(R.id.btn_new_group)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ ->
            when (val it = items[pos]) {
                is Item.GroupItem -> showGroup(it.group.id)
                is Item.NoteItem -> open(it.note.dir)
            }
        }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            when (val it = items[pos]) {
                is Item.GroupItem -> showGroupActions(it.group)
                is Item.NoteItem -> showActions(it.note)
            }
            true
        }

        btnUp.setOnClickListener { showGroup(null) }
        btnNewGroup.setOnClickListener {
            showNameDialog(this, R.string.name_new_group, getString(R.string.group_default_name), R.string.create) { name ->
                io.execute { store.createGroup(name); main.post { refresh() } }
            }
        }
        findViewById<View>(R.id.btn_new).setOnClickListener {
            val suggested = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
            val group = currentGroupId
            showNameDialog(this, R.string.name_new_note, suggested, R.string.create) { title ->
                io.execute {
                    val dir = store.create(title, group)
                    main.post { open(dir) }
                }
            }
        }
        findViewById<View>(R.id.btn_theme).apply {
            visibility = if (ThemePref.supported) View.VISIBLE else View.GONE
            setOnClickListener { ThemePref.showDialog(this@LibraryActivity) }
        }
        findViewById<View>(R.id.btn_import).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, REQ_IMPORT)
        }
        updateHeader()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_GROUP, currentGroupId)
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

    // ---------------------------------------------------------------- navigation

    private fun showGroup(id: String?) {
        currentGroupId = id
        updateHeader()
        rebuildItems()
        grid.setSelection(0)
    }

    private fun updateHeader() {
        val g = currentGroup
        headerTitle.text = g?.name ?: getString(R.string.app_name)
        btnUp.visibility = if (currentGroupId != null) View.VISIBLE else View.GONE
        btnNewGroup.visibility = if (currentGroupId == null) View.VISIBLE else View.GONE
        // Inside a group, back goes up to all notes instead of leaving the app.
        if (Build.VERSION.SDK_INT >= 33) {
            val inGroup = currentGroupId != null
            if (inGroup && backCallback == null) {
                backCallback = OnBackInvokedCallback { showGroup(null) }.also {
                    onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
                }
            } else if (!inGroup) {
                backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
                backCallback = null
            }
        }
    }

    @Deprecated("Used below API 33; newer versions use the OnBackInvokedCallback.")
    override fun onBackPressed() {
        if (currentGroupId != null) showGroup(null) else super.onBackPressed()
    }

    private fun refresh() {
        io.execute {
            val l = store.list()
            val g = store.groups()
            main.post {
                allNotes = l
                groups = g
                if (currentGroupId != null && currentGroup == null) currentGroupId = null // group was deleted
                updateHeader()
                rebuildItems()
            }
        }
    }

    private fun rebuildItems() {
        val known = groups.map { it.id }.toHashSet()
        val group = currentGroupId
        items = if (group != null) {
            allNotes.filter { it.groupId == group }.map { Item.NoteItem(it) }
        } else {
            val byGroup = allNotes.filter { it.groupId in known }.groupBy { it.groupId }
            // Groups first (with their notes, newest first), then notes not in any (existing) group.
            groups.map { Item.GroupItem(it, byGroup[it.id].orEmpty()) } +
                allNotes.filter { it.groupId == null || it.groupId !in known }.map { Item.NoteItem(it) }
        }
        adapter.notifyDataSetChanged()
        empty.setText(if (group != null) R.string.empty_group else R.string.empty_library)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(dir: File) {
        startActivity(Intent(this, CanvasActivity::class.java).putExtra(CanvasActivity.EXTRA_DIR, dir.absolutePath))
    }

    // ---------------------------------------------------------------- note actions

    private fun showActions(note: NoteStore.NoteInfo) {
        val items = arrayOf(
            getString(R.string.rename), getString(R.string.move_to_group),
            getString(R.string.export), getString(R.string.delete),
        )
        AlertDialog.Builder(this)
            .setTitle(note.title.ifEmpty { getString(R.string.untitled) })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> rename(note)
                    1 -> moveToGroup(note)
                    2 -> export(note)
                    3 -> confirmDelete(note)
                }
            }
            .show()
    }

    private fun moveToGroup(note: NoteStore.NoteInfo) {
        val known = groups
        val labels = listOf(getString(R.string.no_group)) + known.map { it.name } + getString(R.string.new_group_ellipsis)
        val checked = known.indexOfFirst { it.id == note.groupId }.let { if (it < 0) 0 else it + 1 }
        AlertDialog.Builder(this)
            .setTitle(R.string.move_to_group)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> setGroup(note, null)
                    labels.size - 1 -> showNameDialog(
                        this, R.string.name_new_group, getString(R.string.group_default_name), R.string.create,
                    ) { name ->
                        io.execute {
                            val g = store.createGroup(name)
                            store.setGroup(note.dir, g.id)
                            main.post { refresh() }
                        }
                    }
                    else -> setGroup(note, known[which - 1].id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setGroup(note: NoteStore.NoteInfo, groupId: String?) {
        io.execute { store.setGroup(note.dir, groupId); main.post { refresh() } }
    }

    private fun rename(note: NoteStore.NoteInfo) {
        showNameDialog(this, R.string.rename_note, note.title) { t ->
            io.execute { store.writeTitle(note.dir, t); main.post { refresh() } }
        }
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

    // ---------------------------------------------------------------- group actions

    private fun showGroupActions(group: NoteStore.Group) {
        val items = arrayOf(getString(R.string.rename_group), getString(R.string.delete_group))
        AlertDialog.Builder(this)
            .setTitle(group.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showNameDialog(this, R.string.rename_group, group.name) { name ->
                        io.execute { store.renameGroup(group.id, name); main.post { refresh() } }
                    }
                    1 -> AlertDialog.Builder(this)
                        .setMessage(getString(R.string.delete_group_confirm, group.name))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            io.execute { store.deleteGroup(group.id); main.post { refresh() } }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- import / export results

    @Deprecated("Platform Activity result API; no AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_IMPORT -> {
                val group = currentGroupId // imported notes land in the group being viewed
                io.execute {
                    val ok = runCatching {
                        val dir = contentResolver.openInputStream(uri)!!.use { store.import(it) }
                        if (group != null) store.setGroup(dir, group)
                    }.isSuccess
                    main.post {
                        if (!ok) Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
                        refresh()
                    }
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

    // ---------------------------------------------------------------- grid

    private fun thumbKey(n: NoteStore.NoteInfo) = "${n.dir.name}@${n.modified}"

    private fun bindThumb(view: ImageView, n: NoteStore.NoteInfo?) {
        if (n == null) { view.tag = null; view.setImageBitmap(null); return }
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
                // Only views still showing this note (views are recycled; group mosaics hold several).
                setThumbWhereTagged(grid, key, bmp)
            }
        }
    }

    private fun setThumbWhereTagged(v: View, key: String, bmp: Bitmap) {
        if (v is ImageView && v.tag == key) v.setImageBitmap(bmp)
        if (v is ViewGroup) for (i in 0 until v.childCount) setThumbWhereTagged(v.getChildAt(i), key, bmp)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Item.GroupItem) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val colW = grid.columnWidth
            return when (val item = items[position]) {
                is Item.GroupItem -> {
                    val v = convertView ?: layoutInflater.inflate(R.layout.item_group, parent, false)
                    keepAspect(v.findViewById(R.id.mosaic), colW)
                    val tiles = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3)
                    for (k in tiles.indices) bindThumb(v.findViewById(tiles[k]), item.notes.getOrNull(k))
                    v.findViewById<TextView>(R.id.title).text = item.group.name
                    v.findViewById<TextView>(R.id.subtitle).text =
                        resources.getQuantityString(R.plurals.note_count, item.notes.size, item.notes.size)
                    v
                }
                is Item.NoteItem -> {
                    val v = convertView ?: layoutInflater.inflate(R.layout.item_note, parent, false)
                    val n = item.note
                    val thumb = v.findViewById<ImageView>(R.id.thumb)
                    keepAspect(thumb, colW)
                    bindThumb(thumb, n)
                    v.findViewById<TextView>(R.id.title).text = n.title.ifEmpty { getString(R.string.untitled) }
                    v.findViewById<TextView>(R.id.subtitle).text = DateUtils.getRelativeTimeSpanString(
                        n.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    )
                    v
                }
            }
        }

        /** Cards are 4:3 at whatever width the grid gives each column. */
        private fun keepAspect(v: View, colW: Int) {
            if (colW > 0 && v.layoutParams.height != colW * 3 / 4) {
                v.layoutParams = v.layoutParams.apply { height = colW * 3 / 4 }
            }
        }
    }

    companion object {
        private const val REQ_IMPORT = 1
        private const val REQ_EXPORT = 2
        private const val STATE_GROUP = "group"
    }
}
