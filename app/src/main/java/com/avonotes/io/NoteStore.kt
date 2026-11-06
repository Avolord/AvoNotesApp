package com.avonotes.io

import android.content.Context
import com.avonotes.model.NoteDocument
import com.avonotes.model.PdfDoc
import com.avonotes.model.Pattern
import com.avonotes.model.PdfPageItem
import com.avonotes.model.Stroke
import com.avonotes.model.Tool
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Notes live in app storage, one folder each:
 *
 *   notes/<uuid>/note.avn    binary content (see [writeNote])
 *   notes/<uuid>/title.txt   UTF-8 title (separate so renaming/listing is cheap)
 *   notes/<uuid>/thumb.webp  library preview (regenerated when stale; not exported)
 *   notes/<uuid>/pdf/<id>.pdf  embedded PDFs
 *   notes/<uuid>/group.txt   id of the group the note is in (absent = no group)
 *   notes/<uuid>/background.txt  paper pattern code (absent = dots)
 *   groups.tsv               one "id<TAB>name" line per group
 *
 * Groups are one level deep. A note only stores its group's id, so renaming a group
 * touches a single file. An exported ".avonote" file is a zip of the note folder
 * (without its group); an ".avogroup" file holds a group's name and all of its notes.
 */
class NoteStore(context: Context) {
    val root = File(context.filesDir, "notes").apply { mkdirs() }
    private val groupsFile = File(context.filesDir, GROUPS_FILE)

    class NoteInfo(val dir: File, val title: String, val modified: Long, val groupId: String?)
    data class Group(val id: String, val name: String)

    fun list(): List<NoteInfo> =
        (root.listFiles() ?: emptyArray())
            .filter { File(it, NOTE_FILE).exists() }
            .map { NoteInfo(it, readTitle(it), File(it, NOTE_FILE).lastModified(), readGroup(it)) }
            .sortedByDescending { it.modified }

    fun create(title: String, groupId: String? = null): File {
        val dir = File(root, UUID.randomUUID().toString())
        File(dir, PDF_DIR).mkdirs()
        writeTitle(dir, title)
        if (groupId != null) setGroup(dir, groupId)
        save(dir, NoteDocument().apply { viewZoom = 0f })
        return dir
    }

    // ---------------------------------------------------------------- groups

    fun groups(): List<Group> =
        runCatching { groupsFile.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else Group(line.substring(0, tab), line.substring(tab + 1))
            }
            .sortedBy { it.name.lowercase() }

    private fun writeGroups(groups: List<Group>) = atomicWrite(groupsFile) { out ->
        // Names are single-line (tabs/newlines replaced) so the file stays parseable.
        val text = groups.joinToString("\n") { "${it.id}\t${it.name.replace(Regex("[\\t\\r\\n]"), " ")}" }
        out.write(text.toByteArray(Charsets.UTF_8))
    }

    fun createGroup(name: String): Group {
        val g = Group(UUID.randomUUID().toString(), name)
        writeGroups(groups() + g)
        return g
    }

    fun renameGroup(id: String, name: String) =
        writeGroups(groups().map { if (it.id == id) it.copy(name = name) else it })

    /** Removes the group; its notes are kept and simply leave the group. */
    fun deleteGroup(id: String) {
        for (n in list()) if (n.groupId == id) setGroup(n.dir, null)
        writeGroups(groups().filter { it.id != id })
    }

    // ---------------------------------------------------------------- background pattern

    fun readPattern(dir: File): Pattern =
        Pattern.fromCode(runCatching { File(dir, PATTERN_FILE).readText().trim().toInt() }.getOrDefault(Pattern.DOTS.code))

    fun writePattern(dir: File, p: Pattern) = atomicWrite(File(dir, PATTERN_FILE)) {
        it.write(p.code.toString().toByteArray(Charsets.UTF_8))
    }

    fun readGroup(dir: File): String? =
        runCatching { File(dir, GROUP_FILE).readText().trim() }.getOrNull()?.ifEmpty { null }

    fun setGroup(dir: File, groupId: String?) {
        val f = File(dir, GROUP_FILE)
        if (groupId == null) f.delete() else atomicWrite(f) { it.write(groupId.toByteArray(Charsets.UTF_8)) }
    }

    fun readTitle(dir: File): String =
        runCatching { File(dir, TITLE_FILE).readText().trim() }.getOrDefault("")

    fun writeTitle(dir: File, title: String) = atomicWrite(File(dir, TITLE_FILE)) {
        it.write(title.toByteArray(Charsets.UTF_8))
    }

    fun delete(dir: File) {
        if (dir.parentFile == root) dir.deleteRecursively()
    }

    fun pdfDir(dir: File) = File(dir, PDF_DIR).apply { mkdirs() }

    // ---------------------------------------------------------------- thumbnails

    fun thumbFile(dir: File) = File(dir, THUMB_FILE)

    /** True if the preview is missing or older than the note content. */
    fun thumbStale(dir: File): Boolean {
        val t = thumbFile(dir)
        return !t.exists() || t.lastModified() < File(dir, NOTE_FILE).lastModified()
    }

    /** Renders and stores the preview for [doc] (call off the UI thread). */
    fun writeThumb(dir: File, doc: NoteDocument): android.graphics.Bitmap {
        val bmp = Thumbnailer.render(doc, pdfDir(dir))
        atomicWrite(thumbFile(dir)) { bmp.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
        return bmp
    }

    // ---------------------------------------------------------------- load / save

    fun load(dir: File): NoteDocument {
        val doc = DataInputStream(BufferedInputStream(File(dir, NOTE_FILE).inputStream(), 1 shl 16)).use { readNote(it) }
        doc.title = readTitle(dir)
        // Drop PDFs no longer referenced (e.g. imported, then undone, then closed).
        val used = doc.pdfDocs.map { it.fileName }.toSet()
        pdfDir(dir).listFiles()?.forEach { if (it.name !in used) it.delete() }
        return doc
    }

    fun save(dir: File, doc: NoteDocument) = atomicWrite(File(dir, NOTE_FILE)) { out ->
        DataOutputStream(BufferedOutputStream(out, 1 shl 16)).let { writeNote(it, doc); it.flush() }
    }

    private fun atomicWrite(target: File, write: (FileOutputStream) -> Unit) {
        // Unique temp name: the library and the editor may both write a preview at once.
        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        FileOutputStream(tmp).use { fos ->
            write(fos)
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) { tmp.delete(); throw IOException("rename failed: $target") }
    }

    // ---------------------------------------------------------------- duplicate

    /** Copies a note (ink, PDFs, background) into a new note. Returns the copy's folder. */
    fun duplicateNote(dir: File, title: String, groupId: String?): File {
        val copy = File(root, UUID.randomUUID().toString())
        try {
            File(copy, PDF_DIR).mkdirs()
            for (name in listOf(NOTE_FILE, PATTERN_FILE)) {
                val f = File(dir, name)
                if (f.exists()) f.copyTo(File(copy, name))
            }
            pdfDir(dir).listFiles()?.forEach { it.copyTo(File(File(copy, PDF_DIR), it.name)) }
            writeTitle(copy, title)
            if (groupId != null) setGroup(copy, groupId)
            load(copy) // validate
            return copy
        } catch (t: Throwable) {
            copy.deleteRecursively()
            throw t
        }
    }

    /** Copies a group and all its notes into a new group named [name]. */
    fun duplicateGroup(group: Group, name: String): Group {
        val g = createGroup(name)
        for (n in list().filter { it.groupId == group.id }) duplicateNote(n.dir, n.title, g.id)
        return g
    }

    // ---------------------------------------------------------------- backup / restore

    /**
     * Writes everything (all notes, their groups and PDFs) as one ".avobackup" zip:
     * [BACKUP_MANIFEST] first, then "groups.tsv", then each note under "notes/<id>/".
     */
    fun exportBackup(out: OutputStream): Int {
        val notes = list()
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_MANIFEST)); zip.write("$BACKUP_VERSION\n".toByteArray()); zip.closeEntry()
            if (groupsFile.exists()) {
                zip.putNextEntry(ZipEntry(GROUPS_FILE)); groupsFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            for (n in notes) {
                val prefix = "$GROUP_NOTES_DIR/${n.dir.name}/"
                writeNoteEntries(n.dir, zip, prefix)
                val g = File(n.dir, GROUP_FILE)
                if (g.exists()) { zip.putNextEntry(ZipEntry(prefix + GROUP_FILE)); g.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
        }
        return notes.size
    }

    /** What kind of AvoNotes file [input] is, judged by its first zip entry. */
    enum class FileKind { NOTE, GROUP, BACKUP }

    fun kindOf(input: InputStream): FileKind =
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            when (zip.nextEntry?.name) {
                BACKUP_MANIFEST -> FileKind.BACKUP
                GROUP_MANIFEST -> FileKind.GROUP
                else -> FileKind.NOTE
            }
        }

    /** A backup unpacked and checked in a staging folder, not yet applied. */
    class StagedBackup(val dir: File, val noteCount: Int, val groupCount: Int)

    private val stagingRoot = File(root.parentFile, "restore-staging")

    /**
     * Unpacks a backup into a staging folder and validates every note. Nothing in the library
     * changes until [applyBackup]. Throws if the file isn't a valid backup.
     */
    fun stageBackup(input: InputStream): StagedBackup {
        stagingRoot.deleteRecursively()
        val dir = File(stagingRoot, UUID.randomUUID().toString())
        val notes = File(dir, "notes").apply { mkdirs() }
        try {
            var sawManifest = false
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    when {
                        e.isDirectory -> {}
                        name == BACKUP_MANIFEST -> {
                            val v = zip.readBytes().toString(Charsets.UTF_8).trim().toIntOrNull() ?: 0
                            if (v > BACKUP_VERSION) throw IOException("backup was made by a newer version")
                            sawManifest = true
                        }
                        name == GROUPS_FILE -> File(dir, GROUPS_FILE).outputStream().use { zip.copyTo(it) }
                        name.startsWith("$GROUP_NOTES_DIR/") -> {
                            val rest = name.removePrefix("$GROUP_NOTES_DIR/")
                            val slash = rest.indexOf('/')
                            val id = if (slash > 0) rest.substring(0, slash) else null
                            if (id != null && SAFE_NAME.matches(id)) {
                                val noteDir = File(notes, id).apply { File(this, PDF_DIR).mkdirs() }
                                val leaf = rest.substring(slash + 1)
                                val target = if (leaf == GROUP_FILE) File(noteDir, GROUP_FILE) else safeTarget(noteDir, leaf)
                                target?.outputStream()?.use { zip.copyTo(it) }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (!sawManifest) throw IOException("not a backup")
            val noteDirs = notes.listFiles()?.toList().orEmpty()
            noteDirs.forEach { load(it) } // validate all before anything is applied
            val groupCount = runCatching { File(dir, GROUPS_FILE).readLines() }.getOrDefault(emptyList()).count { '\t' in it }
            return StagedBackup(dir, noteDirs.size, groupCount)
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    fun discardStaged(staged: StagedBackup) { staged.dir.deleteRecursively() }

    /** Outcome of applying a backup: notes added, and notes skipped because they were already here. */
    class RestoreResult(val added: Int, val skipped: Int)

    /**
     * Applies a staged backup.
     *  - [replace] = true: the library becomes exactly the backup (current notes and groups are removed).
     *  - [replace] = false (merge): current notes stay. Backup groups join existing groups with the
     *    same id or name; backup notes are added, except ones already here unchanged. A note that
     *    exists here with different content is added as a separate copy, so nothing is overwritten.
     */
    fun applyBackup(staged: StagedBackup, replace: Boolean): RestoreResult {
        val stagedNotes = File(staged.dir, "notes").listFiles()?.toList().orEmpty()
        val stagedGroups = File(staged.dir, GROUPS_FILE).let { f ->
            if (!f.exists()) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else Group(line.substring(0, tab), line.substring(tab + 1))
            }
        }
        try {
            if (replace) {
                // Swap whole folders, so a failure can't leave a half-deleted library behind.
                val old = File(root.parentFile, "notes-replaced-${System.nanoTime()}")
                if (!root.renameTo(old)) throw IOException("could not move the current notes aside")
                if (!File(staged.dir, "notes").renameTo(root)) {
                    old.renameTo(root)
                    throw IOException("could not move the backup into place")
                }
                writeGroups(stagedGroups)
                old.deleteRecursively()
                return RestoreResult(stagedNotes.size, 0)
            }

            // Merge.
            val current = groups()
            val groupMap = HashMap<String, String>() // backup group id -> id to use here
            val added = ArrayList<Group>()
            for (g in stagedGroups) {
                val sameId = current.firstOrNull { it.id == g.id }
                val sameName = current.firstOrNull { it.name.equals(g.name, ignoreCase = true) }
                when {
                    sameId != null -> groupMap[g.id] = sameId.id
                    sameName != null -> groupMap[g.id] = sameName.id
                    else -> { added.add(g); groupMap[g.id] = g.id }
                }
            }
            if (added.isNotEmpty()) writeGroups(current + added)

            var addedNotes = 0; var skipped = 0
            for (src in stagedNotes) {
                val existing = File(root, src.name)
                val target = if (existing.exists()) {
                    if (sameNote(existing, src)) { skipped++; continue }
                    File(root, UUID.randomUUID().toString()) // changed since the backup: keep both
                } else existing
                if (!src.renameTo(target)) { src.copyRecursively(target, overwrite = false) }
                val g = readGroup(target)
                setGroup(target, g?.let { groupMap[it] ?: it.takeIf { id -> current.any { c -> c.id == id } } })
                addedNotes++
            }
            return RestoreResult(addedNotes, skipped)
        } finally {
            staged.dir.deleteRecursively()
        }
    }

    /** True if two note folders hold the same content and title. */
    private fun sameNote(a: File, b: File): Boolean =
        readTitle(a) == readTitle(b) &&
            runCatching { File(a, NOTE_FILE).readBytes().contentEquals(File(b, NOTE_FILE).readBytes()) }.getOrDefault(false)

    // ---------------------------------------------------------------- export / import

    /** Exports one note as an ".avonote" zip. Only that note, never its group. */
    fun export(dir: File, out: OutputStream) {
        ZipOutputStream(BufferedOutputStream(out)).use { zip -> writeNoteEntries(dir, zip, "") }
    }

    /**
     * Exports a group and all of its notes as one ".avogroup" zip: a [GROUP_MANIFEST] entry with
     * the group's name first, then each note's files under "notes/<n>/" (same layout as a note).
     */
    fun exportGroup(group: Group, out: OutputStream): Int {
        val notes = list().filter { it.groupId == group.id }
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(GROUP_MANIFEST))
            zip.write(group.name.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            notes.forEachIndexed { i, n -> writeNoteEntries(n.dir, zip, "$GROUP_NOTES_DIR/$i/") }
        }
        return notes.size
    }

    private fun writeNoteEntries(dir: File, zip: ZipOutputStream, prefix: String) {
        for (name in listOf(NOTE_FILE, TITLE_FILE, PATTERN_FILE)) {
            val f = File(dir, name)
            if (!f.exists()) continue
            zip.putNextEntry(ZipEntry(prefix + name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        }
        // Only PDFs the saved note references.
        val doc = load(dir)
        for (d in doc.pdfDocs) {
            val f = File(pdfDir(dir), d.fileName)
            if (!f.exists()) continue
            zip.putNextEntry(ZipEntry("$prefix$PDF_DIR/${d.fileName}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
        }
    }

    /** What an import produced: the new note folders, and the new group if it was a group file. */
    class ImportResult(val notes: List<File>, val group: Group?)

    /**
     * Imports an exported note (".avonote") as a new note, or an exported group (".avogroup") as a
     * new group with new copies of its notes. Nothing is left behind if the file is invalid.
     */
    fun import(input: InputStream): ImportResult {
        var groupName: String? = null
        var single: File? = null
        val groupDirs = sortedMapOf<Int, File>()
        fun newNoteDir() = File(root, UUID.randomUUID().toString()).apply { File(this, PDF_DIR).mkdirs() }
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    when {
                        e.isDirectory -> {}
                        name == GROUP_MANIFEST ->
                            groupName = zip.readBytes().toString(Charsets.UTF_8).trim().take(200)
                        name.startsWith("$GROUP_NOTES_DIR/") -> {
                            // notes/<n>/<file>
                            val rest = name.removePrefix("$GROUP_NOTES_DIR/")
                            val slash = rest.indexOf('/')
                            val n = if (slash > 0) rest.substring(0, slash).toIntOrNull() else null
                            if (n != null && n in 0 until MAX_GROUP_NOTES) {
                                val dir = groupDirs.getOrPut(n) { newNoteDir() }
                                safeTarget(dir, rest.substring(slash + 1))?.outputStream()?.use { zip.copyTo(it) }
                            }
                        }
                        else -> {
                            val dir = single ?: newNoteDir().also { single = it }
                            safeTarget(dir, name)?.outputStream()?.use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (groupName != null) {
                // A group file: its notes become a new group (never merged into an existing one).
                single?.deleteRecursively()
                val dirs = groupDirs.values.toList()
                dirs.forEach { load(it) } // validate all before creating anything visible
                val g = createGroup(groupName!!.ifEmpty { "Group" })
                dirs.forEach { setGroup(it, g.id) }
                return ImportResult(dirs, g)
            }
            groupDirs.values.forEach { it.deleteRecursively() }
            val dir = single ?: throw IOException("empty file")
            load(dir) // validate
            return ImportResult(listOf(dir), null)
        } catch (t: Throwable) {
            single?.deleteRecursively()
            groupDirs.values.forEach { it.deleteRecursively() }
            throw t
        }
    }

    /** Only accept the known file layout; rejects path traversal and anything unexpected. */
    private fun safeTarget(dir: File, name: String): File? {
        if (name == NOTE_FILE || name == TITLE_FILE || name == PATTERN_FILE) return File(dir, name)
        if (name.startsWith("$PDF_DIR/")) {
            val leaf = name.removePrefix("$PDF_DIR/")
            if (leaf.isNotEmpty() && SAFE_NAME.matches(leaf)) return File(File(dir, PDF_DIR), leaf)
        }
        return null
    }

    companion object {
        const val NOTE_FILE = "note.avn"
        const val TITLE_FILE = "title.txt"
        const val THUMB_FILE = "thumb.webp"
        const val GROUP_FILE = "group.txt"
        const val PATTERN_FILE = "background.txt"
        const val GROUPS_FILE = "groups.tsv"
        const val PDF_DIR = "pdf"
        /** First entry of an ".avogroup" file: the group's name. */
        private const val GROUP_MANIFEST = "avogroup.txt"
        private const val GROUP_NOTES_DIR = "notes"
        /** First entry of an ".avobackup" file: the backup format version. */
        private const val BACKUP_MANIFEST = "avobackup.txt"
        private const val BACKUP_VERSION = 1
        private const val MAX_GROUP_NOTES = 100_000
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+") // no slashes, so no traversal
        private const val MAGIC = 0x41564E31 // "AVN1"
        private const val VERSION = 1

        /*
         * note.avn, big-endian:
         *   int magic, int version
         *   float viewX, viewY, viewZoom
         *   int docCount   { utf id, utf fileName, utf displayName }
         *   int pageCount  { utf docId, int pageIndex, float x, y, w, h }
         *   int strokeCount{ byte tool, int color, float width, int n, float[n*3] (x, y, pressure) }
         */
        fun writeNote(out: DataOutputStream, doc: NoteDocument) {
            out.writeInt(MAGIC); out.writeInt(VERSION)
            out.writeFloat(doc.viewX); out.writeFloat(doc.viewY); out.writeFloat(doc.viewZoom)
            out.writeInt(doc.pdfDocs.size)
            for (d in doc.pdfDocs) { out.writeUTF(d.id); out.writeUTF(d.fileName); out.writeUTF(d.displayName) }
            out.writeInt(doc.pages.size)
            for (p in doc.pages) {
                out.writeUTF(p.docId); out.writeInt(p.pageIndex)
                out.writeFloat(p.x); out.writeFloat(p.y); out.writeFloat(p.w); out.writeFloat(p.h)
            }
            out.writeInt(doc.strokes.size)
            var buf = ByteBuffer.allocate(0)
            for (s in doc.strokes) {
                out.writeByte(s.tool.code); out.writeInt(s.color); out.writeFloat(s.width)
                out.writeInt(s.pointCount)
                val bytes = s.points.size * 4
                if (buf.capacity() < bytes) buf = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN)
                buf.clear()
                buf.asFloatBuffer().put(s.points)
                out.write(buf.array(), 0, bytes)
            }
        }

        fun readNote(input: DataInputStream): NoteDocument {
            if (input.readInt() != MAGIC) throw IOException("not an AvoNotes file")
            val version = input.readInt()
            if (version > VERSION) throw IOException("note was saved by a newer version")
            val doc = NoteDocument()
            doc.viewX = input.readFloat(); doc.viewY = input.readFloat(); doc.viewZoom = input.readFloat()
            repeat(input.readInt()) {
                doc.pdfDocs.add(PdfDoc(input.readUTF(), input.readUTF(), input.readUTF()))
            }
            repeat(input.readInt()) {
                doc.pages.add(
                    PdfPageItem(
                        input.readUTF(), input.readInt(),
                        input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat(),
                    )
                )
            }
            val strokeCount = input.readInt()
            doc.strokes.ensureCapacity(strokeCount)
            var bytes = ByteArray(0)
            repeat(strokeCount) {
                val tool = Tool.fromCode(input.readByte().toInt())
                val color = input.readInt()
                val width = input.readFloat()
                val n = input.readInt()
                if (n < 0 || n > 10_000_000) throw IOException("corrupt stroke")
                val len = n * 3 * 4
                if (bytes.size < len) bytes = ByteArray(len)
                input.readFully(bytes, 0, len)
                val pts = FloatArray(n * 3)
                ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.BIG_ENDIAN).asFloatBuffer().get(pts)
                doc.strokes.add(Stroke(tool, color, width, pts))
            }
            return doc
        }
    }
}
