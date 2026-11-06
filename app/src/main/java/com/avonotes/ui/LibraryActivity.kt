package com.avonotes.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.avonotes.R
import com.avonotes.io.NoteStore
import com.avonotes.io.PdfExporter
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The note library: a header with counts, search and sort, then a "Groups" section and a
 * "Notes" section of cards. Tapping a group shows just its notes; back returns to everything.
 *
 * Laid out as a ListView of rows (section headers, and rows of N cards) so sections can span
 * the full width while rows are still recycled.
 */
class LibraryActivity : Activity() {
    private lateinit var store: NoteStore
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var exportDir: File? = null
    private var exportGroup: NoteStore.Group? = null
    private var exportPdfMode: PdfExporter.Mode? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private sealed class Item {
        class GroupItem(val group: NoteStore.Group, val notes: List<NoteStore.NoteInfo>) : Item()
        class NoteItem(val note: NoteStore.NoteInfo) : Item()
    }

    private sealed class Row {
        class Section(val title: String, val count: Int) : Row()
        class Cards(val items: List<Item>) : Row()
    }

    private var allNotes: List<NoteStore.NoteInfo> = emptyList()
    private var groups: List<NoteStore.Group> = emptyList()
    private var rows: List<Row> = emptyList()
    private var columns = 3
    private var sortByName = false
    private var query = ""

    /** The group being shown, or null for the top level. Kept across recreation (e.g. theme change). */
    private var currentGroupId: String? = null
    private val currentGroup get() = groups.firstOrNull { it.id == currentGroupId }

    private lateinit var list: ListView
    private lateinit var empty: View
    private lateinit var headerTitle: TextView
    private lateinit var headerSubtitle: TextView
    private lateinit var btnUp: View
    private lateinit var logo: View
    private lateinit var btnNewGroup: View
    private lateinit var search: EditText
    private lateinit var sortRecent: View
    private lateinit var sortName: View

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
        exportDir = savedInstanceState?.getString(STATE_EXPORT_DIR)?.let { File(it) }
        exportGroup = savedInstanceState?.getString(STATE_EXPORT_GROUP)?.let { id -> store.groups().firstOrNull { it.id == id } }
        exportPdfMode = savedInstanceState?.getString(STATE_EXPORT_PDF_MODE)?.let { PdfExporter.Mode.valueOf(it) }
        sortByName = prefs.getBoolean("librarySortByName", false)

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        headerTitle = findViewById(R.id.header_title)
        headerSubtitle = findViewById(R.id.header_subtitle)
        btnUp = findViewById(R.id.btn_up)
        logo = findViewById(R.id.logo)
        btnNewGroup = findViewById(R.id.btn_new_group)
        search = findViewById(R.id.search)
        sortRecent = findViewById(R.id.sort_recent)
        sortName = findViewById(R.id.sort_name)
        list.adapter = adapter

        // Column count follows the available width (cards are at least ~200dp wide).
        list.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ ->
            if (r - l != or_ - ol) {
                val dp = resources.displayMetrics.density
                val cols = max(2, ((v.width - v.paddingLeft - v.paddingRight) / dp / 210f).toInt())
                if (cols != columns) { columns = cols; rebuildRows() } else adapter.notifyDataSetChanged()
            }
        }

        btnUp.setOnClickListener { showGroup(null) }
        btnNewGroup.setOnClickListener {
            newGroupDialog { }
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
        findViewById<View>(R.id.btn_library_more).setOnClickListener { showLibraryMenu() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                rebuildRows()
            }
        })
        sortRecent.setOnClickListener { setSort(false) }
        sortName.setOnClickListener { setSort(true) }
        updateSortChips()
        updateHeader()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_GROUP, currentGroupId)
        // The save dialog may outlive this activity instance (e.g. a rotation meanwhile).
        outState.putString(STATE_EXPORT_DIR, exportDir?.path)
        outState.putString(STATE_EXPORT_GROUP, exportGroup?.id)
        outState.putString(STATE_EXPORT_PDF_MODE, exportPdfMode?.name)
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

    // ---------------------------------------------------------------- navigation & state

    private fun setSort(byName: Boolean) {
        sortByName = byName
        prefs.edit().putBoolean("librarySortByName", byName).apply()
        updateSortChips()
        rebuildRows()
    }

    private fun updateSortChips() {
        sortRecent.isSelected = !sortByName
        sortName.isSelected = sortByName
    }

    private fun showGroup(id: String?) {
        currentGroupId = id
        updateHeader()
        rebuildRows()
        list.setSelection(0)
    }

    private fun updateHeader() {
        val g = currentGroup
        val inGroup = currentGroupId != null
        headerTitle.text = g?.name ?: getString(R.string.app_name)
        val noteCount = if (inGroup) allNotes.count { it.groupId == currentGroupId } else allNotes.size
        val notesText = resources.getQuantityString(R.plurals.note_count, noteCount, noteCount)
        headerSubtitle.text = if (inGroup) notesText else
            getString(R.string.stats, notesText, resources.getQuantityString(R.plurals.group_count, groups.size, groups.size))
        btnUp.visibility = if (inGroup) View.VISIBLE else View.GONE
        logo.visibility = if (inGroup) View.GONE else View.VISIBLE
        btnNewGroup.visibility = if (inGroup) View.GONE else View.VISIBLE
        // Inside a group, back goes up to all notes instead of leaving the app.
        if (Build.VERSION.SDK_INT >= 33) {
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

    @SuppressLint("GestureBackNavigation") // API 33+ uses the OnBackInvokedCallback above
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
                rebuildRows()
            }
        }
    }

    private fun sortNotes(notes: List<NoteStore.NoteInfo>) =
        if (sortByName) notes.sortedBy { it.title.lowercase() } else notes.sortedByDescending { it.modified }

    private fun rebuildRows() {
        val known = groups.map { it.id }.toHashSet()
        val group = currentGroupId
        val q = query.lowercase()
        val out = ArrayList<Row>()

        fun addSection(titleRes: Int, items: List<Item>) {
            if (items.isEmpty()) return
            out.add(Row.Section(getString(titleRes), items.size))
            items.chunked(columns).forEach { out.add(Row.Cards(it)) }
        }

        when {
            q.isNotEmpty() -> {
                // Search everything, wherever it lives.
                val gs = groups.filter { it.name.lowercase().contains(q) }
                    .map { g -> Item.GroupItem(g, sortNotes(allNotes.filter { it.groupId == g.id })) }
                val ns = sortNotes(allNotes.filter { it.title.lowercase().contains(q) }).map { Item.NoteItem(it) }
                addSection(R.string.section_groups, gs)
                addSection(R.string.section_notes, ns)
            }
            group != null -> {
                addSection(R.string.section_notes, sortNotes(allNotes.filter { it.groupId == group }).map { Item.NoteItem(it) })
            }
            else -> {
                val byGroup = allNotes.filter { it.groupId in known }.groupBy { it.groupId }
                var gs = groups.map { Item.GroupItem(it, sortNotes(byGroup[it.id].orEmpty())) }
                if (!sortByName) gs = gs.sortedByDescending { gi -> gi.notes.maxOfOrNull { it.modified } ?: 0L }
                addSection(R.string.section_groups, gs)
                addSection(
                    R.string.section_notes,
                    sortNotes(allNotes.filter { it.groupId == null || it.groupId !in known }).map { Item.NoteItem(it) },
                )
            }
        }
        rows = out
        adapter.notifyDataSetChanged()
        updateEmptyState(group, q)
    }

    private fun updateEmptyState(group: String?, q: String) {
        val isEmpty = rows.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (!isEmpty) return
        val title = findViewById<TextView>(R.id.empty_title)
        val text = findViewById<TextView>(R.id.empty_text)
        when {
            q.isNotEmpty() -> { title.setText(R.string.no_results_title); text.text = getString(R.string.no_results, query) }
            group != null -> { title.setText(R.string.empty_group_title); text.setText(R.string.empty_group) }
            else -> { title.setText(R.string.empty_title); text.setText(R.string.empty_library) }
        }
    }

    private fun open(dir: File) {
        startActivity(Intent(this, CanvasActivity::class.java).putExtra(CanvasActivity.EXTRA_DIR, dir.absolutePath))
    }

    // ---------------------------------------------------------------- note actions

    private fun showActions(note: NoteStore.NoteInfo) {
        val title = note.title.ifEmpty { getString(R.string.untitled) }
        val groupName = groups.firstOrNull { it.id == note.groupId }?.name
        val edited = getString(R.string.edited, relativeTime(note.modified))
        val subtitle = if (groupName != null) getString(R.string.stats, edited, groupName) else edited
        ActionSheet(this)
            .header(title, subtitle, preview = thumbCache.get(thumbKey(note)), iconRes = R.drawable.ic_pen)
            .item(R.drawable.ic_rename, R.string.rename) { rename(note) }
            .item(R.drawable.ic_copy, R.string.duplicate_note) { duplicateNote(note) }
            .item(R.drawable.ic_move, R.string.move_to_group) { moveToGroup(note) }
            .divider()
            .item(R.drawable.ic_export, R.string.export) { export(note) }
            .item(R.drawable.ic_pdf_file, R.string.export_pdf) { exportPdf(note) }
            .divider()
            .item(R.drawable.ic_delete, R.string.delete, destructive = true) { confirmDelete(note) }
            .show()
    }

    /** Copies the note next to the original (same group), named "<title> (copy)". */
    private fun duplicateNote(note: NoteStore.NoteInfo) {
        val title = getString(R.string.copy_name, note.title.ifEmpty { getString(R.string.untitled) })
        io.execute {
            val ok = runCatching { store.duplicateNote(note.dir, title, note.groupId) }.isSuccess
            main.post {
                if (!ok) Toast.makeText(this, R.string.duplicate_failed, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun moveToGroup(note: NoteStore.NoteInfo) {
        // Each group is described well enough to tell same-looking ones apart: its latest note's
        // preview and title, how many notes it has, and when it last changed.
        val sameName = groups.groupingBy { it.name.trim().lowercase() }.eachCount()
        val entries = groups.map { g ->
            val notes = allNotes.filter { it.groupId == g.id && it.dir != note.dir }.sortedByDescending { it.modified }
            val latest = notes.firstOrNull()
            val count = when {
                g.id == note.groupId && notes.isEmpty() -> getString(R.string.group_picker_current_only)
                g.id == note.groupId -> getString(R.string.group_picker_current_others, notes.size)
                else -> resources.getQuantityString(R.plurals.note_count, notes.size, notes.size)
            }
            var detail = if (latest == null) count else getString(
                R.string.group_picker_detail, count, latest.title.ifEmpty { getString(R.string.untitled) }, relativeTime(latest.modified),
            )
            // Groups that share a name are only told apart by their notes; say so, as a hint to rename.
            val twins = sameName[g.name.trim().lowercase()] ?: 1
            if (twins > 1) detail = getString(R.string.group_picker_same_name, detail, twins)
            GroupPicker.Entry(g.id, g.name, detail, latest?.let { thumbCache.get(thumbKey(it)) }, latest?.modified ?: 0L)
        }
        GroupPicker(this, note.title.ifEmpty { getString(R.string.untitled) }, entries, note.groupId) { choice ->
            when (choice) {
                GroupPicker.Choice.None -> setGroup(note, null)
                GroupPicker.Choice.New -> newGroupDialog { g -> setGroup(note, g.id) }
                is GroupPicker.Choice.Group -> setGroup(note, choice.id)
            }
        }.show()
    }

    /** Asks for a new group's name (it must not already exist), creates it, then calls [then]. */
    private fun newGroupDialog(then: (NoteStore.Group) -> Unit) {
        showNameDialog(
            this, R.string.name_new_group, uniqueName(getString(R.string.group_default_name)), R.string.create,
            validate = { groupNameError(it, null) },
        ) { name ->
            io.execute {
                val g = store.createGroup(name)
                main.post { then(g); refresh() }
            }
        }
    }

    /** An error message if another group already has [name], else null. */
    private fun groupNameError(name: String, exceptId: String?): String? =
        if (groups.any { it.id != exceptId && it.name.trim().equals(name.trim(), ignoreCase = true) })
            getString(R.string.group_name_taken) else null

    private fun uniqueName(base: String): String {
        val taken = groups.map { it.name.trim().lowercase() }.toHashSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base ($n)".lowercase() in taken) n++
        return "$base ($n)"
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

    /** Exports the saved note as a PDF (asks for the layout if it has PDF pages). */
    private fun exportPdf(note: NoteStore.NoteInfo) {
        io.execute {
            val doc = runCatching { store.load(note.dir) }.getOrNull()
            main.post {
                if (doc == null) { Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show(); return@post }
                if (doc.contentBounds() == null) { Toast.makeText(this, R.string.pdf_nothing, Toast.LENGTH_LONG).show(); return@post }
                choosePdfExportMode(this, doc.pages.isNotEmpty()) { mode ->
                    exportDir = note.dir
                    exportGroup = null
                    exportPdfMode = mode
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_TITLE, safeFileName(note.title, "note") + ".pdf")
                    }
                    startActivityForResult(i, REQ_EXPORT_PDF)
                }
            }
        }
    }

    /** Exports the whole group (its name and every note in it) as one ".avogroup" file. */
    private fun exportGroup(group: NoteStore.Group) {
        exportDir = null
        exportGroup = group
        val safe = group.name.ifEmpty { "group" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "$safe.avogroup")
        }
        startActivityForResult(i, REQ_EXPORT_GROUP)
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
        val notes = allNotes.filter { it.groupId == group.id }
        val count = resources.getQuantityString(R.plurals.note_count, notes.size, notes.size)
        val latest = notes.maxOfOrNull { it.modified }
        val subtitle = if (latest == null) count else getString(R.string.stats, count, relativeTime(latest))
        ActionSheet(this)
            .header(group.name, subtitle, iconRes = R.drawable.ic_folder)
            .item(R.drawable.ic_rename, R.string.rename_group) {
                showNameDialog(this, R.string.rename_group, group.name, validate = { groupNameError(it, group.id) }) { name ->
                    io.execute { store.renameGroup(group.id, name); main.post { refresh() } }
                }
            }
            .item(R.drawable.ic_copy, R.string.duplicate_group) { duplicateGroup(group) }
            .item(R.drawable.ic_export, R.string.export_group) { exportGroup(group) }
            .divider()
            .item(R.drawable.ic_delete, R.string.delete_group, destructive = true) {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.delete_group_confirm, group.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        io.execute { store.deleteGroup(group.id); main.post { refresh() } }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .show()
    }

    // ---------------------------------------------------------------- backup / restore

    private fun showLibraryMenu() {
        ActionSheet(this)
            .item(R.drawable.ic_backup, getString(R.string.backup_export), getString(R.string.backup_export_desc)) {
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(Date())
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, "AvoNotes backup $date.avobackup")
                }
                startActivityForResult(i, REQ_BACKUP)
            }
            .item(R.drawable.ic_restore, getString(R.string.backup_restore), getString(R.string.backup_restore_desc)) {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(i, REQ_RESTORE)
            }
            .show()
    }

    /** Unpacks and checks the backup first; nothing changes until the user picks how to load it. */
    private fun restoreBackup(uri: android.net.Uri) {
        io.execute {
            val staged = runCatching { contentResolver.openInputStream(uri)!!.use { store.stageBackup(it) } }
                .onFailure { android.util.Log.w("AvoNotes", "backup unreadable", it) }.getOrNull()
            main.post {
                if (staged == null) { Toast.makeText(this, R.string.backup_invalid, Toast.LENGTH_LONG).show(); return@post }
                chooseRestoreMode(staged)
            }
        }
    }

    private fun chooseRestoreMode(staged: NoteStore.StagedBackup) {
        val notes = resources.getQuantityString(R.plurals.note_count, staged.noteCount, staged.noteCount)
        val gs = resources.getQuantityString(R.plurals.group_count, staged.groupCount, staged.groupCount)
        var chosen = false
        val sheet = ActionSheet(this)
            .header(getString(R.string.backup_title), getString(R.string.backup_summary, notes, gs), iconRes = R.drawable.ic_restore)
            .item(R.drawable.ic_merge, getString(R.string.backup_merge), getString(R.string.backup_merge_desc)) {
                chosen = true; applyRestore(staged, replace = false)
            }
            .divider()
            .item(R.drawable.ic_delete, getString(R.string.backup_replace), getString(R.string.backup_replace_desc), destructive = true) {
                chosen = true
                val curNotes = resources.getQuantityString(R.plurals.note_count, allNotes.size, allNotes.size)
                val curGroups = resources.getQuantityString(R.plurals.group_count, groups.size, groups.size)
                AlertDialog.Builder(this)
                    .setTitle(R.string.backup_replace)
                    .setMessage(getString(R.string.backup_replace_confirm, curNotes, curGroups))
                    .setPositiveButton(R.string.backup_replace_button) { _, _ -> applyRestore(staged, replace = true) }
                    .setNegativeButton(R.string.cancel) { _, _ -> io.execute { store.discardStaged(staged) } }
                    .setOnCancelListener { io.execute { store.discardStaged(staged) } }
                    .show()
            }
        sheet.onDismiss { if (!chosen) io.execute { store.discardStaged(staged) } }
        sheet.show()
    }

    private fun applyRestore(staged: NoteStore.StagedBackup, replace: Boolean) {
        io.execute {
            val result = runCatching { store.applyBackup(staged, replace) }
                .onFailure { android.util.Log.w("AvoNotes", "restore failed", it) }.getOrNull()
            main.post {
                if (result == null) Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show()
                else {
                    thumbCache.evictAll()
                    val msg = if (replace) getString(R.string.backup_replaced, resources.getQuantityString(R.plurals.note_count, result.added, result.added))
                    else getString(R.string.backup_restored, result.added, result.skipped)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                showGroup(null)
                refresh()
            }
        }
    }

    private fun duplicateGroup(group: NoteStore.Group) {
        val name = getString(R.string.copy_name, group.name)
        io.execute {
            val ok = runCatching { store.duplicateGroup(group, name) }.isSuccess
            main.post {
                if (!ok) Toast.makeText(this, R.string.duplicate_failed, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun relativeTime(t: Long): String =
        DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    // ---------------------------------------------------------------- import / export results

    @Deprecated("Platform Activity result API; no AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_RESTORE -> restoreBackup(uri)
            REQ_BACKUP -> {
                io.execute {
                    val n = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { store.exportBackup(it) } }
                        .onFailure { android.util.Log.w("AvoNotes", "backup failed", it) }.getOrNull()
                    main.post {
                        val msg = if (n == null) getString(R.string.export_failed)
                        else getString(R.string.backup_done, resources.getQuantityString(R.plurals.note_count, n, n))
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            REQ_IMPORT -> {
                val group = currentGroupId // an imported note lands in the group being viewed
                io.execute {
                    // A full backup picked here gets the restore choice instead of a plain import.
                    val kind = runCatching { contentResolver.openInputStream(uri)!!.use { store.kindOf(it) } }.getOrNull()
                    if (kind == NoteStore.FileKind.BACKUP) { main.post { restoreBackup(uri) }; return@execute }
                    val result = runCatching {
                        contentResolver.openInputStream(uri)!!.use { store.import(it) }.also { r ->
                            // A group file brings its own (new) group; a single note joins this one.
                            if (r.group == null && group != null) r.notes.forEach { store.setGroup(it, group) }
                        }
                    }.getOrNull()
                    main.post {
                        when {
                            result == null -> Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
                            result.group != null -> {
                                Toast.makeText(
                                    this,
                                    resources.getQuantityString(R.plurals.imported_group, result.notes.size, result.group.name, result.notes.size),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                showGroup(null) // the new group is a top-level group
                            }
                        }
                        refresh()
                    }
                }
            }
            REQ_EXPORT_PDF -> {
                val dir = exportDir ?: return
                val mode = exportPdfMode ?: return
                Toast.makeText(this, R.string.exporting_pdf, Toast.LENGTH_SHORT).show()
                io.execute {
                    val ok = runCatching {
                        val doc = store.load(dir)
                        contentResolver.openOutputStream(uri, "wt")!!.use { PdfExporter.export(doc, store.pdfDir(dir), mode, it) }
                    }.onFailure { android.util.Log.w("AvoNotes", "PDF export failed", it) }.isSuccess
                    main.post {
                        Toast.makeText(this, if (ok) R.string.exported else R.string.export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            REQ_EXPORT_GROUP -> {
                val g = exportGroup ?: return
                io.execute {
                    val count = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { store.exportGroup(g, it) } }
                        .onFailure { android.util.Log.w("AvoNotes", "group export failed", it) }.getOrNull()
                    main.post {
                        val msg = if (count == null) getString(R.string.export_failed)
                        else resources.getQuantityString(R.plurals.exported_group, count, count)
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

    // ---------------------------------------------------------------- previews

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
                setThumbWhereTagged(list, key, bmp)
            }
        }
    }

    private fun setThumbWhereTagged(v: View, key: String, bmp: Bitmap) {
        if (v is ImageView && v.tag == key) v.setImageBitmap(bmp)
        if (v is ViewGroup) for (i in 0 until v.childCount) setThumbWhereTagged(v.getChildAt(i), key, bmp)
    }

    // ---------------------------------------------------------------- rows

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Section) 0 else 1
        override fun isEnabled(position: Int) = false // cards handle their own clicks

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val row = rows[position]) {
                is Row.Section -> {
                    val v = convertView ?: layoutInflater.inflate(R.layout.item_section, parent, false)
                    v.findViewById<TextView>(R.id.section_title).text = row.title
                    v.findViewById<TextView>(R.id.section_count).text = row.count.toString()
                    v
                }
                is Row.Cards -> bindCardRow(convertView as? LinearLayout, parent, row.items)
            }
    }

    /** A row of [columns] equal slots; each slot holds a note or group card (reused when the type matches). */
    private fun bindCardRow(existing: LinearLayout?, parent: ViewGroup, items: List<Item>): View {
        val d = resources.displayMetrics.density
        val gap = (6 * d).toInt()
        val row = existing ?: LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        while (row.childCount < columns) {
            row.addView(FrameLayout(this).apply { setPadding(gap, gap, gap, gap) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        while (row.childCount > columns) row.removeViewAt(row.childCount - 1)

        val listInner = list.width - list.paddingLeft - list.paddingRight
        val slotW = if (listInner > 0) listInner / columns - 2 * gap else 0
        val previewH = if (slotW > 0) slotW * 3 / 4 else (150 * d).toInt()

        for (k in 0 until columns) {
            val slot = row.getChildAt(k) as FrameLayout
            val item = items.getOrNull(k)
            if (item == null) { slot.visibility = View.INVISIBLE; continue }
            slot.visibility = View.VISIBLE
            val wantLayout = if (item is Item.GroupItem) R.layout.item_group else R.layout.item_note
            var card = slot.getChildAt(0)
            if (card == null || card.tag != wantLayout) {
                slot.removeAllViews()
                card = LayoutInflater.from(this).inflate(wantLayout, slot, false)
                card.tag = wantLayout
                // Rounded corners clip the previews (the XML attribute needs API 31; the method doesn't).
                card.clipToOutline = true
                for (id in intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3)) card.findViewById<View>(id)?.clipToOutline = true
                slot.addView(card)
            }
            bindCard(card, item, previewH)
        }
        return row
    }

    private fun bindCard(card: View, item: Item, previewH: Int) {
        when (item) {
            is Item.GroupItem -> {
                val mosaic = card.findViewById<View>(R.id.mosaic)
                if (mosaic.layoutParams.height != previewH) mosaic.layoutParams = mosaic.layoutParams.apply { height = previewH }
                val tiles = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3)
                for (k in tiles.indices) bindThumb(card.findViewById(tiles[k]), item.notes.getOrNull(k))
                card.findViewById<TextView>(R.id.title).text = item.group.name
                val count = resources.getQuantityString(R.plurals.note_count, item.notes.size, item.notes.size)
                val newest = item.notes.maxOfOrNull { it.modified }
                card.findViewById<TextView>(R.id.subtitle).text =
                    if (newest == null) count else "$count · ${relative(newest)}"
                card.setOnClickListener { search.text.clear(); showGroup(item.group.id) }
                card.setOnLongClickListener { showGroupActions(item.group); true }
            }
            is Item.NoteItem -> {
                val n = item.note
                val thumb = card.findViewById<ImageView>(R.id.thumb)
                if (thumb.layoutParams.height != previewH) thumb.layoutParams = thumb.layoutParams.apply { height = previewH }
                bindThumb(thumb, n)
                card.findViewById<TextView>(R.id.title).text = n.title.ifEmpty { getString(R.string.untitled) }
                // In search results, say which group a note is in.
                val groupName = if (query.isNotEmpty()) groups.firstOrNull { it.id == n.groupId }?.name else null
                card.findViewById<TextView>(R.id.subtitle).text =
                    if (groupName != null) "$groupName · ${relative(n.modified)}" else relative(n.modified)
                card.setOnClickListener { open(n.dir) }
                card.setOnLongClickListener { showActions(n); true }
            }
        }
    }

    private fun relative(t: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

    companion object {
        private const val REQ_IMPORT = 1
        private const val REQ_EXPORT = 2
        private const val REQ_EXPORT_GROUP = 3
        private const val REQ_EXPORT_PDF = 4
        private const val REQ_BACKUP = 5
        private const val REQ_RESTORE = 6
        private const val STATE_EXPORT_PDF_MODE = "exportPdfMode"
        private const val STATE_GROUP = "group"
        private const val STATE_EXPORT_DIR = "exportDir"
        private const val STATE_EXPORT_GROUP = "exportGroup"
    }
}
